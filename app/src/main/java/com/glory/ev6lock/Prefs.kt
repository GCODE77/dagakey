package com.glory.ev6lock

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** 설정 저장 (계정 정보는 암호화 저장) */
class Prefs(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val sp = EncryptedSharedPreferences.create(
        ctx, "ev6prefs",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var carBtAddress by str("carBt")
    var carBtName by str("carBtName")
    /** 디지털키(차량 BLE) 장치 - 차에 다가가면 연결되는 장치. 연결=열기, 끊김=잠금 */
    var dkAddress by str("dkAddr")
    var dkName by str("dkName")
    /** 모든 블루투스 연결/해제 기록 (디지털키 장치 찾기용) */
    var btSeen by str("btSeen")
    /** 디지털키 BLE 신호 - 특정 주소 고정(비우면 표준 UUID로 감지) */
    var bleAddress by str("bleAddr")
    /** 마지막으로 차를 떠난(잠근) 시각 / 마지막으로 연 시각 — 반복 동작 방지용 */
    var lastLeftAt: Long
        get() = sp.getLong("lastLeft", 0)
        set(v) = sp.edit().putLong("lastLeft", v).putBoolean("farSince", false).apply()   // 떠날 때마다 '멀리 갔었음' 초기화
    /** 떠난 뒤 실제로 차 신호가 끊기거나 약해질 만큼 멀리 갔었는지 — 이걸 거쳐야 '다가옴'으로 인정 */
    var farSinceLeave: Boolean
        get() = sp.getBoolean("farSince", true)
        set(v) = sp.edit().putBoolean("farSince", v).apply()
    var lastSeenAt: Long
        get() = sp.getLong("lastSeen", 0)
        set(v) = sp.edit().putLong("lastSeen", v).apply()
    var lastUnlockAt: Long
        get() = sp.getLong("lastUnlock", 0)
        set(v) = sp.edit().putLong("lastUnlock", v).apply()

    /** 이 세기 이상이면 "가까이 왔다" (차 옆 ≈ -50~-60, -80 ≈ 10m, -90 ≈ 20~30m) — 미리 열리도록 멀리서 감지 */
    var bleRssi: Int
        get() = sp.getInt("bleRssi3", -90)
        set(v) = sp.edit().putInt("bleRssi3", v).apply()
    // ── 업데이트 (GitHub 릴리스 확인) ──
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()
    var updTag by str("updTag")          // 발견한 최신 버전 태그 (예: v0.1.1)
    var updUrl by str("updUrl")          // APK 다운로드 주소
    var updSha by str("updSha")          // APK sha256 (있을 때만)
    var updNotes by str("updNotes")      // 릴리스 설명
    var updNotified by str("updNotified")  // 알림을 이미 보낸 버전
    var updSize: Long
        get() = sp.getLong("updSize", 0)
        set(v) = sp.edit().putLong("updSize", v).apply()
    var lastUpdCheck: Long
        get() = sp.getLong("lastUpdCheck", 0)
        set(v) = sp.edit().putLong("lastUpdCheck", v).apply()

    // ── 배터리 절약 ──
    /** 집 와이파이 이름 (연결돼 있으면 차 감시 쉼) */
    var homeSsid by str("homeSsid")
    /** 연결됐던 와이파이 기록 (최근 순, 줄바꿈) — 체크해서 고를 수 있게 */
    var wifiHistory by str("wifiHist")
    var atHome: Boolean
        get() = sp.getBoolean("atHome", false)
        set(v) = sp.edit().putBoolean("atHome", v).apply()
    /** 가만히 있으면 절전 감시로 */
    var motionSave: Boolean
        get() = sp.getBoolean("motionSave", true)
        set(v) = sp.edit().putBoolean("motionSave", v).apply()
    var lastMoveAt: Long
        get() = sp.getLong("lastMove", 0)
        set(v) = sp.edit().putLong("lastMove", v).apply()
    /** 지금 감시 모드: true=빠른 감시, false=절전 감시 */
    var bleActive: Boolean
        get() = sp.getBoolean("bleActive", true)
        set(v) = sp.edit().putBoolean("bleActive", v).apply()

    /** 다가감 2단계(차 바로 옆 도착 재열기)를 이미 했는지 */
    var arrivedDone: Boolean
        get() = sp.getBoolean("arrivedDone", false)
        set(v) = sp.edit().putBoolean("arrivedDone", v).apply()
    /** 마지막으로 실제 잠금 버튼을 누른 시각 — 이미 잠근 뒤 중복 잠금·오작동 방지 */
    var lastLockAt: Long
        get() = sp.getLong("lastLockAt", 0)
        set(v) = sp.edit().putLong("lastLockAt", v).apply()
    /** 마지막으로 누른 버튼(앱·위젯 공통) — 화면을 다시 열어도 '어느 걸 눌렀는지' 보이게 */
    var lastTap by str("lastTap")
    var lastTapAt: Long
        get() = sp.getLong("lastTapAt", 0)
        set(v) = sp.edit().putLong("lastTapAt", v).apply()
    /** 차 오디오(EV6)가 연결돼 있음 = 차 안/주행 중 → 자동 잠금·열기 금지 */
    var inCar: Boolean
        get() = sp.getBoolean("inCar", false)
        set(v) = sp.edit().putBoolean("inCar", v).apply()
    var kiaId by str("kiaId")
    var kiaPw by str("kiaPw")
    var kiaPin by str("kiaPin")
    var accessToken by str("token")
    var vehicleId by str("vehicleId")
    var lastLog by str("log")
    /** 제어 방식: true=기아 앱 자동 조작(접근성), false=서버 직접(미완성) */
    var useAppAutomation: Boolean
        get() = sp.getBoolean("useAuto", true)
        set(v) = sp.edit().putBoolean("useAuto", v).apply()
    /** 잠금/열기 버튼으로 인식할 키워드 (학습 후 조정). 쉼표 구분 */
    var lockKeywords: String
        get() = sp.getString("lockKw", "잠금,도어 잠금,문 잠금,LOCK") ?: ""
        set(v) = sp.edit().putString("lockKw", v).apply()
    var unlockKeywords: String
        get() = sp.getString("unlockKw", "열기,도어 열림,문 열기,잠금 해제,UNLOCK") ?: ""
        set(v) = sp.edit().putString("unlockKw", v).apply()

    var lockDelayMin: Int
        get() = sp.getInt("lockDelay", 2)
        set(v) = sp.edit().putInt("lockDelay", v).apply()
    var autoLock: Boolean
        get() = sp.getBoolean("autoLock", true)
        set(v) = sp.edit().putBoolean("autoLock", v).apply()
    /** 하차 판정: 블루투스 끊긴 뒤 이 걸음 수 이상 걸어야 잠금 (차 안에 있으면 안 잠금) */
    var stepThreshold: Int
        get() = sp.getInt("stepTh", 15)
        set(v) = sp.edit().putInt("stepTh", v).apply()
    var stepsAtPark: Float
        get() = sp.getFloat("stepsPark", -1f)
        set(v) = sp.edit().putFloat("stepsPark", v).apply()
    var parkedAt: Long
        get() = sp.getLong("parkedAt", 0)
        set(v) = sp.edit().putLong("parkedAt", v).apply()
    /** 자동으로 열었는데 이 시간 안에 탑승(블루투스 연결) 안 하면 다시 잠금 */
    var relockMin: Int
        get() = sp.getInt("relock", 3)
        set(v) = sp.edit().putInt("relock", v).apply()
    /** 열고 나서 자동 재잠금 사용 여부 (기본 꺼짐 — 열면 그대로 둠) */
    var autoRelock: Boolean
        get() = sp.getBoolean("autoRelock", false)
        set(v) = sp.edit().putBoolean("autoRelock", v).apply()

    /** 접근 시 열기 */
    var approachUnlock: Boolean
        get() = sp.getBoolean("approach", true)
        set(v) = sp.edit().putBoolean("approach", v).apply()
    /** false = 접근 시 알림 버튼, true = 완전 자동 열림 */
    var autoUnlock: Boolean
        get() = sp.getBoolean("autoUnlock", true)
        set(v) = sp.edit().putBoolean("autoUnlock", v).apply()
    var unlockRadiusM: Int
        get() = sp.getInt("radius", 40)
        set(v) = sp.edit().putInt("radius", v).apply()
    /** 테스트 모드: 실제 차량 대신 가짜 API 사용 */
    var demoMode: Boolean
        get() = sp.getBoolean("demo", false)
        set(v) = sp.edit().putBoolean("demo", v).apply()

    fun log(msg: String) {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        lastLog = ("$t $msg\n" + lastLog).take(4000)
        // 진단용: 파일에도 남김 (USB로 통째로 꺼내 분석)
        try {
            val f = java.io.File(appCtx.filesDir, "app.log")
            if (f.length() > 300_000) f.writeText("")
            f.appendText("$t $msg\n")
        } catch (_: Exception) {}
        android.util.Log.d("Dagakey", msg)
    }

    private fun str(key: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, "") ?: ""
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }
}
