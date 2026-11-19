package com.glory.ev6lock

enum class DoorState { LOCKED, UNLOCKED, UNKNOWN }

interface KiaApi {
    suspend fun login()
    suspend fun doorState(): DoorState
    suspend fun lock()
    suspend fun unlock()

    companion object {
        fun get(p: Prefs): KiaApi = when {
            p.demoMode -> DemoKiaApi
            else -> KiaAppApi(p)                 // 기아 앱 자동 조작(접근성)
        }
    }
}

/**
 * 기아 공식 앱을 접근성 서비스로 조작해서 잠그고 여는 구현.
 * 상태 확인은 서비스가 동작 중에 스스로 하므로 doorState는 UNKNOWN을 돌려주고,
 * lock/unlock은 서비스에 동작을 요청만 한다(비동기로 처리됨).
 */
class KiaAppApi(private val p: Prefs) : KiaApi {
    override suspend fun login() {}
    override suspend fun doorState() = DoorState.UNKNOWN  // 서비스가 자체 판단
    override suspend fun lock() = send("lock")
    override suspend fun unlock() = send("unlock")
    private fun send(action: String) {
        if (!KiaAutoService.isConnected) {
            p.log("접근성 권한이 꺼져 있음 → 설정에서 '다가키'를 켜세요")
            return
        }
        KiaAutoService.request(action, p.kiaPin)
    }
}

/** 실제 차 없이 전체 흐름을 테스트하기 위한 가짜 구현 */
object DemoKiaApi : KiaApi {
    var state = DoorState.UNLOCKED
    override suspend fun login() {}
    override suspend fun doorState() = state
    override suspend fun lock() { state = DoorState.LOCKED }
    override suspend fun unlock() { state = DoorState.UNLOCKED }
}
