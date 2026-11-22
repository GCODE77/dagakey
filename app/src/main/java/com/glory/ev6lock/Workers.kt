package com.glory.ev6lock

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * 잠금 작업.
 * - 블루투스 끊긴 뒤 걸음 수가 기준 미만이면 "아직 차 안/옆" → 1분 뒤 다시 확인 (최대 60분)
 * - 걸어서 떠났으면 문 상태 확인 → 열려 있을 때만 잠금
 * - force=true(재잠금, 수동)면 걸음 확인 생략
 */
class LockWorker(ctx: Context, ps: WorkerParameters) : CoroutineWorker(ctx, ps) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val p = Prefs(ctx)
        val force = inputData.getBoolean("force", false)

        // ★ 실측: 주행 중(차 오디오 연결)에 자동 잠금이 실행된 사례 → 차 안이면 자동 잠금 안 함
        if (!force && (!p.masterOn || !p.autoLock || p.braked)) { p.log("자동 잠금 꺼짐 → 예비 잠금 취소"); return Result.success() }
        if (!force && p.inCar) { p.log("차 안/주행 중 → 자동 잠금 취소"); return Result.success() }
        // ★ 실측: 이미 잠갔는데 예비 잠금이 1시간 동안 '아직 차 근처'를 반복하다 또 잠금 → 하차 이후 잠갔으면 종료
        if (!force && p.lastLockAt >= p.parkedAt) { p.log("이미 잠금 완료 — 예비 잠금 종료"); return Result.success() }
        // 다가와서 연 뒤라면 자동 잠금 하지 않음
        if (!force && p.lastUnlockAt > p.parkedAt) { p.log("하차 후 다시 열었음 — 예비 잠금 종료"); return Result.success() }

        if (!force && p.stepsAtPark >= 0) {
            val now = Steps.current(ctx)
            // ★ 실측: 걸음 센서가 응답을 못 주면(null) '걸어서 떠남'으로 오판해 잠갔음 → 0걸음으로 취급
            val walked = if (now != null) now - p.stepsAtPark else 0f
            val elapsedMin = (System.currentTimeMillis() - p.parkedAt) / 60000
            if (walked < p.stepThreshold && elapsedMin < 60) {
                p.log("아직 차 근처 (걸음 ${walked.toInt()}) -> 1분 뒤 재확인")
                Jobs.scheduleLock(ctx, 1)
                return Result.success()
            }
        }

        return try {
            val api = KiaApi.get(p)
            if (api.doorState() == DoorState.LOCKED) {
                p.log("이미 잠겨 있음 -> 아무것도 안 함")
            } else {
                api.lock()
                p.log("열려 있어서 잠금")
                // 완료 알림은 기아 앱에서 실제로 버튼을 누른 뒤 서비스가 띄움 (여기서 띄우면 실패해도 '잠갔다'고 나옴)
            }
            // 걸어서 떠난 상태 → 이제부터 디지털키 신호로 "다시 다가오는지" 감시
            if (p.approachUnlock) { p.lastLeftAt = System.currentTimeMillis(); BleNear.start(ctx) }
            Result.success()
        } catch (e: Exception) {
            p.log("잠금 실패: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else {
                Notifier.info(ctx, "자동 잠금 실패 - 직접 확인하세요")
                Result.failure()
            }
        }
    }
}

class UnlockWorker(ctx: Context, ps: WorkerParameters) : CoroutineWorker(ctx, ps) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val p = Prefs(ctx)
        return try {
            val api = KiaApi.get(p)
            if (api.doorState() != DoorState.UNLOCKED) api.unlock()
            p.lastUnlockAt = System.currentTimeMillis()
            p.log("열기 요청 보냄")
            // 완료 알림은 실제로 누른 뒤 서비스가 띄움
            // 재잠금은 기본 꺼짐. 켜두면 N분 안에 탑승 안 할 때만 다시 잠금
            if (p.autoRelock) Jobs.scheduleLock(ctx, p.relockMin, force = true)
            Result.success()
        } catch (e: Exception) {
            p.log("열기 실패: ${e.message}")
            Notifier.info(ctx, "문 열기 실패")
            Result.failure()
        }
    }
}

object Jobs {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleLock(ctx: Context, delayMin: Int, force: Boolean = false) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "lock", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<LockWorker>()
            .setInitialDelay(delayMin.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf("force" to force))
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
    )

    fun cancelLock(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork("lock")

    fun unlockNow(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "unlock", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<UnlockWorker>().setConstraints(net).build()
    )
}
