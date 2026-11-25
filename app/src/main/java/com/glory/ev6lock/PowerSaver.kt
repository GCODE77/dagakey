package com.glory.ev6lock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 배터리 절약:
 *  ① 집 와이파이에 연결돼 있으면 차 감시를 쉼 → 와이파이가 끊기는 순간(집을 나서면) 다시 감시
 *  ② 5분 넘게 가만히 있으면 절전 감시 → 걷기 시작하면 바로 빠른 감시
 *     (차에 다가갈 때는 항상 '걷는 중'이라 반응 속도에는 영향 없음)
 */
object PowerSaver {
    @Volatile var currentSsid: String = ""
    private val h = Handler(Looper.getMainLooper())
    private const val IDLE_MS = 5 * 60_000L
    private const val DEEP_MS = 30 * 60_000L
    private lateinit var app: Context

    fun start(ctx: Context) {
        app = ctx.applicationContext
        watchMotion()
        watchWifi()
        h.postDelayed(idleCheck, 2 * 60_000)
    }

    // ── ② 움직임 ──
    private val sm by lazy { app.getSystemService(SensorManager::class.java) }

    // ── '지금 걷는 중인지' (최근 걸음 시각 기록) ──
    // ★ 실측: 교회 실내에서 가만히 있는데 차 신호가 들락날락 → 열기/잠금 반복.
    //   자동 열기·잠금은 '지금 실제로 걷고 있을 때'만 하도록 판단 근거로 씀.
    private val stepTimes = java.util.ArrayDeque<Long>()
    @Volatile private var stepSensorOk = false
    /** 최근 ms 동안 걸음 수. 걸음 센서를 못 쓰는 폰이면 -1 (판단 불가 → 막지 않음) */
    fun stepsInLast(ms: Long): Int {
        if (!stepSensorOk) return -1
        val now = System.currentTimeMillis()
        synchronized(stepTimes) {
            while (stepTimes.isNotEmpty() && now - stepTimes.peekFirst()!! > 5 * 60_000) stepTimes.pollFirst()
            return stepTimes.count { now - it <= ms }
        }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            synchronized(stepTimes) { stepTimes.addLast(System.currentTimeMillis()); if (stepTimes.size > 600) stepTimes.pollFirst() }
            moving("걸음")
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    /** '움직이기 시작함'을 폰이 잠든 상태에서도 깨워서 알려주는 센서 (한 번 울리면 다시 걸어야 함) */
    private val sigMotion = object : TriggerEventListener() {
        override fun onTrigger(e: TriggerEvent?) { moving("움직임"); armSigMotion() }
    }
    private fun armSigMotion() {
        try { sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sm.requestTriggerSensor(sigMotion, it) } } catch (_: Exception) {}
    }

    private fun watchMotion() {
        try { sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { stepSensorOk = sm.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL) } } catch (_: Exception) {}
        armSigMotion()
    }

    private fun moving(why: String) {
        val p = Prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.lastMoveAt > 10_000) p.lastMoveAt = now   // 기록은 10초에 한 번
        val wasDeep = p.deepIdle
        if (wasDeep) p.deepIdle = false                        // 움직이면 깊은 절전 해제 → 아래에서 검색 재개
        if (p.motionSave && (!p.bleActive || wasDeep)) {
            p.bleActive = true
            p.log("$why 감지 → 빠른 차 감시로 전환" + if (wasDeep) " (깊은 절전 해제)" else "")
            if (p.approachUnlock && !p.inCar) BleNear.start(app, quiet = true)
        }
    }

    /** 5분간 움직임 없으면 절전 감시로 (폰이 깨어 있을 때 2분마다 + 20분 알람 때 확인) */
    private val idleCheck = object : Runnable {
        override fun run() { checkIdle(); h.postDelayed(this, 2 * 60_000) }
    }
    fun checkIdle() {
        if (!::app.isInitialized) return
        val p = Prefs(app)
        if (p.motionSave && p.bleActive && System.currentTimeMillis() - p.lastMoveAt > p.idleMin * 60_000L) {
            p.bleActive = false
            p.log("${p.idleMin}분간 움직임 없음 → 절전 차 감시로 전환")
            if (p.approachUnlock && !p.inCar) BleNear.start(app, quiet = true)
            armSigMotion()
        }
        // ★ 30분 넘게 가만히 있으면 검색을 완전히 쉼 (잠자는 동안·책상 앞 등). 걸음/움직임 센서는 계속 켜져 있어 움직이면 바로 재개
        if (p.deepSave && p.motionSave && !p.bleActive && !p.deepIdle && !p.inCar && p.scanOn &&
            System.currentTimeMillis() - p.lastMoveAt > p.deepMin * 60_000L) {
            p.deepIdle = true
            BleNear.stop(app)
            p.log("${p.deepMin}분 넘게 가만히 있음 → 차 검색 완전히 쉼 (움직이면 바로 재개)")
        }
    }

    // ── ① 집 와이파이 (여러 개 등록 가능, 줄바꿈으로 저장) ──
    /** 연결됐던 와이파이 기록 (최근 것이 위, 최대 30개) */
    fun history(p: Prefs) = p.wifiHistory.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    private fun remember(p: Prefs, ssid: String) {
        val h = history(p)
        if (h.firstOrNull() == ssid) return
        p.wifiHistory = (listOf(ssid) + h.filter { it != ssid }).take(30).joinToString("\n")
    }
    fun homeList(p: Prefs) = p.homeSsid.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    fun addHome(p: Prefs, ssid: String) {
        val s = ssid.trim(); if (s.isEmpty()) return
        val l = homeList(p); if (s !in l) p.homeSsid = (l + s).joinToString("\n")
        refreshHome(p)
    }
    fun removeHome(p: Prefs, ssid: String) {
        p.homeSsid = homeList(p).filter { it != ssid }.joinToString("\n")
        refreshHome(p)
    }
    /** 목록이 바뀌면 지금 연결된 와이파이 기준으로 바로 다시 판단 */
    private fun refreshHome(p: Prefs) {
        if (!::app.isInitialized) return
        val home = currentSsid.isNotEmpty() && currentSsid in homeList(p)
        if (home != p.atHome) {
            p.atHome = home
            if (home) { p.log("집 와이파이($currentSsid) 연결 → 차 감시 쉼"); BleNear.stop(app) }
            else if (p.approachUnlock && !p.inCar) BleNear.start(app, quiet = true)
        }
    }

    private fun watchWifi() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onWifi(c)
            override fun onLost(n: Network) = onWifiLost()
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onWifi(c)
            override fun onLost(n: Network) = onWifiLost()
        }
        try { cm.registerNetworkCallback(req, cb) } catch (_: Exception) {}
    }

    // ── 와이파이별 누적 연결 시간 (집 와이파이 추천용) ──
    private fun secsMap(p: Prefs): LinkedHashMap<String, Long> {
        val m = LinkedHashMap<String, Long>()
        p.wifiSecs.split("\n").forEach { l -> val i = l.lastIndexOf('='); if (i > 0) l.substring(i + 1).toLongOrNull()?.let { m[l.substring(0, i)] = it } }
        return m
    }
    /** 지금까지 연결해 있던 와이파이의 시간을 누적에 반영 */
    private fun closeCurrent(p: Prefs, now: Long) {
        if (p.wifiCur.isNotEmpty() && p.wifiSince > 0) {
            val m = secsMap(p)
            m[p.wifiCur] = (m[p.wifiCur] ?: 0L) + ((now - p.wifiSince) / 1000).coerceAtLeast(0)
            p.wifiSecs = m.entries.sortedByDescending { it.value }.take(20).joinToString("\n") { "${it.key}=${it.value}" }
        }
    }
    /** 와이파이별 누적 시간(초) 내림차순 — 지금 연결 중인 것은 현재까지 포함 */
    fun wifiTotals(p: Prefs): List<Pair<String, Long>> {
        val m = secsMap(p)
        if (p.wifiCur.isNotEmpty() && p.wifiSince > 0) m[p.wifiCur] = (m[p.wifiCur] ?: 0L) + ((System.currentTimeMillis() - p.wifiSince) / 1000)
        return m.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    /** 아직 집으로 등록 안 했고, 설정한 시간(기본 30분) 이상 연결해 있던 가장 긴 와이파이 */
    fun suggestHome(p: Prefs): Pair<String, Long>? {
        val homes = homeList(p)
        return wifiTotals(p).firstOrNull { it.first !in homes && it.second >= p.suggestMin * 60L }
    }

    private fun onWifi(c: NetworkCapabilities) {
        val ssid = (c.transportInfo as? WifiInfo)?.ssid?.trim('"') ?: return
        if (ssid.isBlank() || ssid == "<unknown ssid>") return
        currentSsid = ssid
        val p = Prefs(app)
        if (p.wifiCur != ssid) { val now = System.currentTimeMillis(); closeCurrent(p, now); p.wifiCur = ssid; p.wifiSince = now }
        remember(p, ssid)
        val home = ssid in homeList(p)
        if (home != p.atHome) {
            p.atHome = home
            if (home) { p.log("집 와이파이($ssid) 연결 → 차 감시 쉼"); BleNear.stop(app) }
            else if (p.approachUnlock && !p.inCar) BleNear.start(app, quiet = true)
        }
    }

    private fun onWifiLost() {
        currentSsid = ""
        val p = Prefs(app)
        closeCurrent(p, System.currentTimeMillis()); p.wifiCur = ""; p.wifiSince = 0
        // ★ 실측: 집 안에서 공유기 사이를 오가며 1~4초 끊겼다 붙는 일이 밤새 반복 → 15초 안에 다시 붙으면 외출로 보지 않음
        if (p.atHome) h.postDelayed({
            val q = Prefs(app)
            if (currentSsid.isEmpty() && q.atHome) {
                q.atHome = false
                q.bleActive = true; q.lastMoveAt = System.currentTimeMillis()   // 집을 나서는 중 → 바로 빠른 감시
                q.log("집 와이파이 끊김(외출) → 차 감시 다시 시작")
                if (q.approachUnlock && !q.inCar) BleNear.start(app, quiet = true)
            }
        }, 15_000)
    }
}
