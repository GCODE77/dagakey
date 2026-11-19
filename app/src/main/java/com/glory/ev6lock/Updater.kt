package com.glory.ev6lock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 앱 안 업데이트: GitHub 릴리스(최신)를 확인 → 알림 → 다운로드(진행률) → 검증 → 설치 화면.
 *
 * 안전장치
 *  - 정해진 저장소(REPO)의 https 릴리스 주소만 허용
 *  - 크기 + SHA-256(GitHub가 알려줄 때) 확인
 *  - 안드로이드는 '같은 서명'이 아닌 APK는 업데이트로 설치하지 않음 → 계정이 뚫려도 가짜 앱은 설치 불가
 *  - 인터넷은 이 기능에서만 사용 (설정에서 끌 수 있음)
 */
object Updater {
    const val REPO = "GCODE77/dagakey"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DL_PREFIX = "https://github.com/$REPO/releases/download/"

    sealed class St {
        object Idle : St()
        data class Downloading(val pct: Int) : St()
        object Installing : St()
        object NeedPermission : St()
        data class Failed(val msg: String) : St()
    }

    val state = MutableStateFlow<St>(St.Idle)
    /** 설치 확인 화면을 앱이 화면에 있을 때 바로 띄우기 위한 표시 */
    @Volatile var appForeground = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 버전 비교 ──
    fun installedVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }

    private fun parse(v: String): List<Int> {
        val n = v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+')
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }.take(3)
        return n + List(3 - n.size) { 0 }
    }
    fun isNewer(tag: String, current: String): Boolean {
        val a = parse(tag); val b = parse(current)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
    fun hasUpdate(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return p.updTag.isNotBlank() && p.updUrl.isNotBlank() && isNewer(p.updTag, installedVersion(ctx))
    }

    // ── 최신 릴리스 확인 ── (성공하면 true)
    fun fetch(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Dagakey/${installedVersion(ctx)}")
            if (c.responseCode != 200) { p.log("업데이트 확인 실패 (HTTP ${c.responseCode})"); return false }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name")
            val assets = j.getJSONArray("assets")
            var url = ""; var size = 0L; var sha = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", true)) {
                    url = a.getString("browser_download_url"); size = a.optLong("size", 0)
                    sha = a.optString("digest", "").removePrefix("sha256:").lowercase()
                    break
                }
            }
            p.lastUpdCheck = System.currentTimeMillis()
            if (!url.startsWith(DL_PREFIX)) { p.log("업데이트 확인: 릴리스에 올바른 APK가 없음"); return true }
            p.updTag = tag; p.updUrl = url; p.updSize = size; p.updSha = sha
            p.updNotes = j.optString("body", "").take(1500)
            true
        } catch (e: Exception) {
            p.log("업데이트 확인 실패: ${e.javaClass.simpleName}"); false
        }
    }

    // ── 정기 확인 예약 (12시간마다, 인터넷 연결 시) ──
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs(ctx).autoUpdate) { wm.cancelUniqueWork("update-check"); return }
        wm.enqueueUniquePeriodicWork(
            "update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
    }

    // ── 다운로드 + 설치 ──
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (state.value is St.Downloading || state.value is St.Installing) return
        val p = Prefs(app)
        if (!hasUpdate(app)) { state.value = St.Failed("설치할 새 버전이 없어요"); return }
        if (!canInstall(app)) { state.value = St.NeedPermission; return }
        val tag = p.updTag; val url = p.updUrl; val sha = p.updSha; val size = p.updSize
        state.value = St.Downloading(0)
        scope.launch {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "Dagakey-$tag.apk")
                p.log("업데이트 다운로드 시작 ($tag)")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "Dagakey/${installedVersion(app)}")
                if (c.responseCode != 200) throw IllegalStateException("다운로드 실패 (HTTP ${c.responseCode})")
                val total = if (size > 0) size else c.contentLengthLong
                val md = MessageDigest.getInstance("SHA-256")
                var read = 0L; var last = -1
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); read += n
                            val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 99) else 0
                            if (pct != last) { last = pct; state.value = St.Downloading(pct) }
                        }
                    }
                }
                if (total > 0 && f.length() != total) throw IllegalStateException("파일 크기가 달라요 (받는 중 끊김)")
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (sha.isNotBlank() && got != sha) { f.delete(); throw IllegalStateException("파일 검증 실패 (SHA-256 불일치)") }
                p.log("다운로드 완료·검증 통과 → 설치 시작")
                install(app, f)
            } catch (e: Exception) {
                p.log("업데이트 실패: ${e.message}")
                state.value = St.Failed(e.message ?: "알 수 없는 오류")
            }
        }
    }

    private fun install(ctx: Context, apk: File) {
        state.value = St.Installing
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (android.os.Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins -> s.openWrite("dagakey", 0, apk.length()).use { out -> ins.copyTo(out); s.fsync(out) } }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val pend = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pend.intentSender)
        }
    }
}

/** 12시간마다 새 버전 확인 → 있으면 알림 (같은 버전은 한 번만) */
class UpdateWorker(ctx: Context, ps: WorkerParameters) : CoroutineWorker(ctx, ps) {
    override suspend fun doWork(): Result {
        val p = Prefs(applicationContext)
        if (!p.autoUpdate) return Result.success()
        if (!Updater.fetch(applicationContext)) return Result.retry()
        if (Updater.hasUpdate(applicationContext) && p.updNotified != p.updTag) {
            p.updNotified = p.updTag
            Notifier.updateAvailable(applicationContext, p.updTag)
            p.log("새 버전 ${p.updTag} 알림")
        }
        return Result.success()
    }
}

/** 시스템 설치기가 결과를 돌려주는 곳 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Updater.appForeground) {
                    try { ctx.startActivity(confirm) } catch (_: Exception) { Notifier.installConfirm(ctx, confirm) }
                } else Notifier.installConfirm(ctx, confirm)   // 앱이 화면에 없으면 알림으로
            }
            PackageInstaller.STATUS_SUCCESS -> p.log("업데이트 설치 성공")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "코드 $st"
                p.log("업데이트 설치 실패/취소: $msg")
                Updater.state.value = if (st == PackageInstaller.STATUS_FAILURE_ABORTED) Updater.St.Idle
                    else Updater.St.Failed("설치 실패: $msg")
            }
        }
    }
}

/** 업데이트로 앱이 교체된 직후: 접근성이 유지됐는지 확인해서 알려줌 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        val p = Prefs(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            File(app.cacheDir, "update").listFiles()?.forEach { it.delete() }
            delay(8_000)   // 접근성 서비스가 다시 연결될 시간
            val v = Updater.installedVersion(app)
            p.log("업데이트 완료 → v$v")
            Notifier.info(app, if (KiaAutoService.isConnected) "다가키 v$v 로 업데이트됐어요" else "다가키 v$v 로 업데이트됐어요 — 접근성을 다시 켜주세요")
            pending.finish()
        }
    }
}
