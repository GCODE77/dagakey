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
        if (p.inCar) return   // 차 안에서는 감시 안 함
        // 배터리 절약 ①: 집 와이파이에 연결돼 있으면 쉼 (집 안에 있으니 차에 다가갈 일 없음)
        if (p.atHome) { stop(ctx); if (!quiet) p.log("집 와이파이 연결 중 → 차 감시 쉼"); return }
        stop(ctx)
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
    fun stop(ctx: Context) { try { scanner(ctx)?.stopScan(pi(ctx)) } catch (_: Exception) {} }

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
        if (p.approachUnlock && !p.inCar) BleNear.start(ctx, quiet = true)
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
        // '멀리 갔었음' 판단: 신호가 20초 넘게 안 들렸거나(범위 밖) 아주 약하면(-95 미만)
        val prevSeen = p.lastSeenAt
        if (!p.farSinceLeave && (now - prevSeen > 20_000 || best < -95)) {
            p.farSinceLeave = true
            p.log("차에서 충분히 멀어짐 확인 (다가오면 열기 준비)")
        }
        if (now - prevSeen > 5_000) p.lastSeenAt = now   // 기록은 5초에 한 번만
        if (best < p.bleRssi) return   // 아직 멀다
        // ★ 실측: 주행 중 차 신호가 세게 잡혀 '다가옴'으로 열기 실행 → 차 안이면 무시
        if (p.inCar) return
        // ★ 실측: 떠난 지 2.5분 만에 돌아왔는데 '3분 무시' 규칙 때문에 안 열림
        //   → 시간 대신 '실제로 멀리 갔다가 돌아오는지'로 판단 (내리자마자 다시 열리는 것도 방지)
        if (!p.farSinceLeave && now - p.lastLeftAt < 15 * 60_000) return
        val sinceUnlock = now - p.lastUnlockAt

        // ② 도착 단계: 멀리서(-90) 원격으로 연 뒤 30초 지나면 차가 스스로 다시 잠김
        //   → 차 바로 옆(-65 이상)에 도착하면 한 번 더 열기 (첫 감지 후 5분 안, 1회)
        if (sinceUnlock in 25_000..5 * 60_000) {
            if (best >= -65 && !p.arrivedDone) {
                p.arrivedDone = true
                p.log("차 바로 옆 도착 (세기 $best) → 30초 자동잠금 대비 다시 열기")
                BleNear.stop(ctx)
                if (KiaAutoService.pendingAction != "unlock") Jobs.unlockNow(ctx)
            }
            return
        }
        if (sinceUnlock < 25_000) return
        // 방금 연 지 10분 안이면(도착 단계 끝난 뒤) 무시
        if (sinceUnlock < 10 * 60_000) return

        // ① 첫 감지: 멀리서(-90) 다가옴 → 원격 먼저 열기 시작
        p.log("차 신호 감지 (세기 $best) → 차에 다가옴, 열기 시작")
        KiaAutoService.instance?.stopLeaveWatch()   // 다가오는 걸음을 멀어짐으로 오인 방지
        p.lastUnlockAt = now
        p.arrivedDone = false
        // 감시는 끄지 않음: 바로 옆 도착(②)을 한 번 더 잡기 위해 계속 들음 (시동 켜면 자동 중지)
        Jobs.cancelLock(ctx)
        if (p.approachUnlock) {
            if (p.autoUnlock) Jobs.unlockNow(ctx) else Notifier.askUnlock(ctx)
        }
    }
}
