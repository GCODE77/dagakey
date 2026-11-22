package com.glory.ev6lock

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid

/**
 * 차량 디지털키 BLE 신호 감지 (기아 앱이 "디지털키"를 띄우는 그 신호).
 * CCC Digital Key 표준 차량은 서비스 UUID 0xFFF5로 광고한다.
 * 백그라운드 PendingIntent 스캔 → 앱이 꺼져 있어도 신호를 받으면 깨어남.
 */
object BleNear {
    val CCC_DK: ParcelUuid = ParcelUuid.fromString("0000fff5-0000-1000-8000-00805f9b34fb")

    /**
     * 신호 세기 → 대략 거리(m). 전파 감쇠 공식: 거리 = 10^((1m 세기 - 세기) / (10·n)).
     * n=2.5 (주차장·사람 몸·주머니를 고려한 실내 값). 오차 큼(±50%) — 표시·설정용 '대략' 값.
     */
    private const val N = 2.5
    fun meters(rssi: Int, ref1m: Int): Double = Math.pow(10.0, (ref1m - rssi) / (10 * N))
    fun rssiAt(m: Double, ref1m: Int): Int = (ref1m - 10 * N * Math.log10(m.coerceAtLeast(0.5))).toInt()
    fun dist(p: Prefs, rssi: Int): String {
        val m = meters(rssi, p.bleRef1m)
        return "세기 $rssi, 약 ${if (m < 10) "%.1f".format(m) else m.toInt().toString()}m"
    }

    /** 차 신호를 받을 때마다 호출: 최근 세기·20초 최대값 기록 */
    fun remember(p: Prefs, rssi: Int) {
        val now = System.currentTimeMillis()
        if (now - p.lastRssiAt > 1500 || rssi != p.lastRssi) { p.lastRssi = rssi; p.lastRssiAt = now }
        if (now - p.nearMaxAt > 20_000 || rssi >= p.nearMax) { p.nearMax = rssi; p.nearMaxAt = now }
    }

    /**
     * 실제 사건으로 거리 자동 보정.
     *  - 탑승(차 오디오 연결): 직전 20초 최대 세기 = 차 문 앞 ≈ 1m
     *  - 차 옆에서 직접 열기/잠금 누름: 최근 15초 세기 ≈ 2m
     * 각 기록을 '1m 환산 세기'로 바꿔 최근 7개의 중간값을 기준으로 씀 (튀는 값 무시).
     */
    fun calibrate(p: Prefs, why: String) {
        val now = System.currentTimeMillis()
        val (rssi, m) = when (why) {
            "탑승" -> if (now - p.nearMaxAt < 25_000) p.nearMax to 1.0 else return
            else -> if (now - p.lastRssiAt < 15_000) p.lastRssi to 2.0 else return
        }
        // 멀리서 누른 것(현재 기준으로 5m 넘음)은 '차 옆'이 아니므로 보정에 안 씀
        if (why != "탑승" && meters(rssi, p.bleRef1m) > 5.0) return
        if (rssi < -95 || rssi > -30) return
        val ref = (rssi + 10 * N * Math.log10(m)).toInt()
        val list = (p.calSamples.split(",").mapNotNull { it.toIntOrNull() } + ref).takeLast(7)
        p.calSamples = list.joinToString(",")
        val med = list.sorted()[list.size / 2].coerceIn(-80, -40)
        val old = p.bleRef1m
        p.bleRef1m = med
        p.log("📏 거리 기록 ($why, 세기 $rssi ≈ ${m.toInt()}m) → 1m 기준 $old → $med (기록 ${list.size}개)")
    }

    private fun scanner(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, BleNearReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** 사용자가 설정에서 고른 차 신호가 있으면 그것, 없으면 표준 디지털키 신호(미등록 상태의 임시 대비) */
    private fun filters(p: Prefs): List<ScanFilter> =
        if (p.bleAddress.isNotBlank()) listOf(ScanFilter.Builder().setDeviceAddress(p.bleAddress).build())
        else listOf(ScanFilter.Builder().setServiceUuid(CCC_DK).build())

    /** 주차 후 감시 시작 (차에 다시 다가오는지) */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, quiet: Boolean = false) {
        val p = Prefs(ctx)
        if (!p.masterOn || !p.approachUnlock) { stop(ctx); return }   // 전체 꺼짐 / '다가가면 열기' 꺼짐
        if (p.inCar) { stop(ctx); return }   // 차 안에서는 감시 안 함
        // 배터리 절약 ①: 집 와이파이에 연결돼 있으면 쉼 (집 안에 있으니 차에 다가갈 일 없음)
        if (p.atHome) { stop(ctx); if (!quiet) p.log("집 와이파이 연결 중 → 차 감시 쉼"); return }
        // ★ 감시가 꺼져 있다가 새로 켜지는 경우만 시작 시각 기록 (20분 재시작·절전 전환은 이어지는 감시)
        if (!p.scanOn) { p.scanStartAt = System.currentTimeMillis(); p.scanOn = true }
        stopScan(ctx)
        // 배터리 절약 ②: 걷는 중이면 빠른 감시(균형), 오래 가만히 있으면 절전 감시
        val fast = !p.motionSave || p.bleActive
        val settings = ScanSettings.Builder()
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        try { scanner(ctx)?.startScan(filters(p), settings, pi(ctx)); if (!quiet) p.log("차 접근 감시 시작 (${if (fast) "빠른" else "절전"} 감시)") }
        catch (e: Exception) { Prefs(ctx).log("BLE 감시 실패: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) { stopScan(ctx); Prefs(ctx).scanOn = false }
    @SuppressLint("MissingPermission")
    private fun stopScan(ctx: Context) { try { scanner(ctx)?.stopScan(pi(ctx)) } catch (_: Exception) {} }

    /** 설정 화면용: 10초간 주변 신호 목록 수집 */
    @SuppressLint("MissingPermission")
    fun discover(ctx: Context, onResult: (List<String>) -> Unit) {
        val found = linkedMapOf<String, String>()
        val rssiOf = hashMapOf<String, Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val uuids = r.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().substring(4, 8) } ?: ""
                val mark = if (r.scanRecord?.serviceUuids?.contains(CCC_DK) == true) " ★디지털키" else ""
                rssiOf[r.device.address] = r.rssi
                found[r.device.address] = "${r.device.address} ${r.scanRecord?.deviceName ?: ""} 세기=${r.rssi} [$uuids]$mark"
            }
        }
        val s = scanner(ctx) ?: return onResult(listOf("블루투스 꺼짐"))
        s.startScan(cb)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            s.stopScan(cb)
            onResult(found.entries.sortedByDescending { rssiOf[it.key] ?: -200 }.map { it.value })
        }, 10_000)
    }
}

/**
 * 20분마다 차 감시(블루투스 검색)를 새로 시작.
 * 안드로이드가 오래 켜진 검색을 '수동 모드'로 낮춰 다가가도 못 듣는 문제 방지.
 * 폰이 깊은 절전 중이어도 울리는 시스템 알람 사용.
 */
class BleRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        PowerSaver.checkIdle()
        BleNear.start(ctx, quiet = true)
        schedule(ctx)
    }
    companion object {
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 31, Intent(ctx, BleRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 20 * 60_000, pi)
        }
    }
}

class BleNearReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results: List<ScanResult> =
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java) ?: return
        val p = Prefs(ctx)
        val best = results.maxOfOrNull { it.rssi } ?: return
        val now = System.currentTimeMillis()
        BleNear.remember(p, best)
        if (!p.masterOn || !p.approachUnlock) { BleNear.stop(ctx); return }
        // '멀리 갔었음' 판단: 감시가 켜진 상태에서 60초 넘게 신호가 안 들렸거나, 아주 약하면(약 25m+)
        // ★ 실측 버그: 잠근 직후 감시를 새로 켜면 '마지막으로 본 시각'이 오래전이라 바로 '멀어짐' → 2초 뒤 열림.
        //   → 감시 시작 이후의 공백만 '멀리 갔음'으로 인정
        val prevSeen = maxOf(p.lastSeenAt, p.scanStartAt)
        val farRssi = BleNear.rssiAt(25.0, p.bleRef1m)
        if (!p.farSinceLeave && (now - prevSeen > 60_000 || best < farRssi)) {
            p.farSinceLeave = true
            p.log("차에서 충분히 멀어짐 확인 (${BleNear.dist(p, best)}) → 다가오면 열기 준비")
        }
        if (now - p.lastSeenAt > 5_000) p.lastSeenAt = now   // 기록은 5초에 한 번만
        if (best < p.bleRssi) return   // 아직 멀다
        // ★ 실측: 주행 중 차 신호가 세게 잡혀 '다가옴'으로 열기 실행 → 차 안이면 무시
        if (p.inCar) return
        if (p.braked) return
        // ★ 실측: 떠난 지 2.5분 만에 돌아왔는데 '3분 무시' 규칙 때문에 안 열림
        //   → 시간 대신 '실제로 멀리 갔다가 돌아오는지'로 판단 (내리자마자 다시 열리는 것도 방지)
        if (!p.farSinceLeave && now - p.lastLeftAt < 15 * 60_000) return
        val sinceUnlock = now - p.lastUnlockAt

        // ② 도착 단계: 멀리서(-90) 원격으로 연 뒤 30초 지나면 차가 스스로 다시 잠김
        //   → 차 바로 옆(-65 이상)에 도착하면 한 번 더 열기 (첫 감지 후 5분 안, 1회)
        if (sinceUnlock in 25_000..5 * 60_000) {
            if (best >= BleNear.rssiAt(2.0, p.bleRef1m) && !p.arrivedDone) {
                p.arrivedDone = true
                p.log("차 바로 옆 도착 (${BleNear.dist(p, best)}) → 30초 자동잠금 대비 다시 열기")
                BleNear.stop(ctx)
                if (KiaAutoService.pendingAction != "unlock") { KiaAutoService.dkOnly = !p.approachRemote; Jobs.unlockNow(ctx) }
            }
            return
        }
        if (sinceUnlock < 25_000) return
        // 방금 연 지 10분 안이면(도착 단계 끝난 뒤) 무시
        if (sinceUnlock < 10 * 60_000) return

        // ★ 실측: 교회 실내에서 가만히 있는데 신호가 들락날락해 열림/잠김 반복
        //   → '지금 걸어서 다가오는 중'일 때만 열기 (최근 30초에 8걸음 이상)
        val walking = PowerSaver.stepsInLast(30_000)
        if (walking in 0..7) {
            if (now - p.lastIgnoreLogAt > 5 * 60_000) {
                p.lastIgnoreLogAt = now
                p.log("차 신호 (${BleNear.dist(p, best)}) — 걷는 중이 아니라 열지 않음")
            }
            return
        }

        // ① 첫 감지: 설정 거리 안으로 걸어서 다가옴 → 열기 시작
        p.log("차 신호 감지 (${BleNear.dist(p, best)}) · 걷는 중 → 차에 다가옴, 열기 시작")
        if (p.noteAutoAndCheckBrake()) {
            p.log("⚠ 30분 안에 자동 동작이 너무 많음 → 30분간 자동 잠금·열기 멈춤 (오작동 방지)")
            Notifier.info(ctx, "자동 잠금·열기가 반복돼서 30분간 멈췄어요")
            return
        }
        KiaAutoService.instance?.stopLeaveWatch()   // 다가오는 걸음을 멀어짐으로 오인 방지
        p.lastUnlockAt = now
        p.arrivedDone = false
        // 감시는 끄지 않음: 바로 옆 도착(②)을 한 번 더 잡기 위해 계속 들음 (시동 켜면 자동 중지)
        Jobs.cancelLock(ctx)
        if (p.autoUnlock) { KiaAutoService.dkOnly = !p.approachRemote; Jobs.unlockNow(ctx) } else Notifier.askUnlock(ctx)
    }
}
