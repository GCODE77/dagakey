package com.glory.ev6lock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 블루투스 연결/해제 감지. (ACL 이벤트는 앱이 꺼져 있어도 수신됨)
 * - 모든 연결/해제를 기록에 남겨 '어떤 신호가 차 근처에서 뜨는지' 나중에 분석 가능하게 함
 * - 차량 오디오("EV6"): 연결=탑승(잠금예약 취소), 해제=하차(걸어서 멀어지면 잠금)
 * - 접근용 장치(사용자 지정 or 알려진 차량 BLE): 연결=접근(열기), 해제=멀어짐(잠금)
 */
class CarBtReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        val dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val p = Prefs(ctx)
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        val name = try { dev.name } catch (e: SecurityException) { null } ?: "(이름없음)"
        val addr = dev.address

        // 모든 연결/해제 기록 (분석용)
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        p.btSeen = ("$t ${if (connected) "연결" else "끊김"} $name $addr\n" + p.btSeen).take(4000)

        Notifier.persistent(ctx)  // 빠른 제어 바 유지

        val isAudio = name.contains("EV6", true)
        // 접근 신호 = 설정에서 '차 옆에서 신호 찾기'로 고른 차의 디지털키 모듈
        val isApproach = !isAudio &&
                ((addr == p.bleAddress && p.bleAddress.isNotBlank()) || (addr == p.dkAddress && p.dkAddress.isNotBlank()))
        val isCarAudioSel = addr == p.carBtAddress && p.carBtAddress.isNotBlank()

        if (!p.masterOn) {   // 전체 꺼짐: 기록만 하고 자동 동작 안 함
            if (isApproach || isAudio || isCarAudioSel) p.log("블루투스 ${if (connected) "연결" else "끊김"}: $name — 다가키 꺼짐이라 무시")
            if (isAudio || isCarAudioSel) p.inCar = connected
            return
        }
        when {
            isApproach -> onApproachDevice(ctx, p, name, connected)
            isAudio || isCarAudioSel -> onCarAudio(ctx, p, name, connected)
            else -> p.log("블루투스 ${if (connected) "연결" else "끊김"}: $name (차량 아님)")
        }
    }

    /**
     * 차 디지털키 모듈: 실측 결과 '기아 앱이 디지털키를 연결할 때' 연결됨.
     * → 연결은 '다가옴' 신호로 쓰지 않음 (차 안에서 기아 앱만 열어도 열려버리므로).
     *   다가옴은 BLE 광고 신호 세기(BleNear)로 판단.
     * → 끊김은 '멀어지는 중일 수 있음': 걸음 수로 실제로 걸어서 떠났는지 확인 후 잠금.
     */
    private fun onApproachDevice(ctx: Context, p: Prefs, name: String, connected: Boolean) {
        if (connected) {
            val age = System.currentTimeMillis() - p.lastRssiAt
            p.log("차 디지털키 모듈 연결 ($name)" + if (age < 15_000) " · ${BleNear.dist(p, p.lastRssi)}" else "")
            return
        }
        // ★ 실측: 주행 중에도 이 모듈이 끊겼다 붙었다 함 → 차 안(오디오 연결)이면 무시
        if (p.inCar) { p.log("차 디지털키 모듈 끊김 — 주행/차 안이라 무시"); return }
        // ★ 실측: 잠근 직후 모듈이 끊기며 '하차'를 다시 기록 → 예비 잠금이 1시간 반복. 최근에 잠갔으면 무시
        if (System.currentTimeMillis() - p.lastLockAt < 15 * 60_000) { p.log("차 디지털키 모듈 끊김 — 이미 잠금 완료라 무시"); return }
        p.log("차 디지털키 모듈 끊김 ($name) → 걸어서 멀어지면 잠금")
        p.parkedAt = System.currentTimeMillis()
        p.lastLeftAt = System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            p.stepsAtPark = Steps.current(ctx) ?: -1f
            if (p.autoLock) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { KiaAutoService.instance?.startLeaveWatch() }
                Jobs.scheduleLock(ctx, p.lockDelayMin)
            }
            pending.finish()
        }
    }

    /** 차량 오디오: 연결=탑승, 해제=하차 */
    private fun onCarAudio(ctx: Context, p: Prefs, name: String, connected: Boolean) {
        if (connected) {
            p.log("차 오디오 연결 ($name) = 탑승/주행 → 자동 잠금·열기 멈춤")
            p.inCar = true
            BleNear.calibrate(p, "탑승")   // 방금 차 문 앞에서 잡힌 세기로 거리 보정
            Jobs.cancelLock(ctx)
            KiaAutoService.instance?.stopLeaveWatch()
            BleNear.stop(ctx)
            return
        }
        p.inCar = false
        p.log("차 오디오 끊김 ($name) = 하차 → 걸어서 멀어지면 잠금")
        p.parkedAt = System.currentTimeMillis()
        p.lastLeftAt = System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            p.stepsAtPark = Steps.current(ctx) ?: -1f
            if (p.autoLock) {
                // 걸어서 멀어지는 순간 바로 잠금 (실시간 감시) + 만약을 위한 예비 예약
                android.os.Handler(android.os.Looper.getMainLooper()).post { KiaAutoService.instance?.startLeaveWatch() }
                Jobs.scheduleLock(ctx, p.lockDelayMin)
            }
            pending.finish()
        }
        // BLE 접근 감시는 '걸어서 멀어진 뒤'(잠금 처리 후) 시작 — 내리자마자 시작하면 신호가 세서 바로 열려버림
    }
}
