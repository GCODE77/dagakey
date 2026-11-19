package com.glory.ev6lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Notifier {
    private const val CH = "ev6"          // 알림(소리)
    private const val CH_LOW = "ev6_bar"  // 상단 고정 바(조용)

    private fun nm(ctx: Context): NotificationManager {
        val m = ctx.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CH, "차량 알림", NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel(CH_LOW, "빠른 제어 바", NotificationManager.IMPORTANCE_LOW))
        return m
    }

    private fun action(ctx: Context, act: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE
    )

    private fun base(ctx: Context, text: String) = Notification.Builder(ctx, CH)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("EV6")
        .setContentText(text)
        .setAutoCancel(true)

    fun info(ctx: Context, text: String) = nm(ctx).notify(1, base(ctx, text).build())

    fun pendingLock(ctx: Context, min: Int) = nm(ctx).notify(
        1, base(ctx, "차에서 걸어서 떠나면 ${min}분 뒤 자동으로 잠급니다")
            .addAction(Notification.Action.Builder(null, "취소", action(ctx, "CANCEL", 11)).build())
            .build()
    )

    fun askUnlock(ctx: Context) = nm(ctx).notify(
        2, base(ctx, "차 근처에 도착했습니다. 문을 열까요?")
            .addAction(Notification.Action.Builder(null, "문 열기", action(ctx, "UNLOCK", 12)).build())
            .build()
    )

    /** 폰이 잠겨 있어 기아 앱을 못 띄울 때: 잠금만 풀면 바로 이어서 처리 */
    fun askUnlockPhone(ctx: Context, what: String) = nm(ctx).notify(
        4, base(ctx, "폰 잠금을 풀면 바로 $what 합니다 (지문/얼굴)")
            .setContentTitle("다가키 — $what 대기 중")
            .setTimeoutAfter(120_000)
            .build()
    )

    /** 상단에 항상 떠 있는 빠른 제어 바 — 차 근처에서 한 번 눌러 디지털키로 열기/잠금 */
    fun persistent(ctx: Context) = nm(ctx).notify(
        3, Notification.Builder(ctx, CH_LOW)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("다가키")
            .setContentText("차 근처에서 눌러 디지털키로 제어")
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "열기", action(ctx, "UNLOCK", 12)).build())
            .addAction(Notification.Action.Builder(null, "잠금", action(ctx, "LOCK", 10)).build())
            .addAction(Notification.Action.Builder(null, "트렁크", action(ctx, "TRUNK", 13)).build())
            .build()
    )

    fun cancelAll(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java)
        m.cancel(1); m.cancel(2)   // 고정 바(3)는 남겨둠
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = Prefs(ctx)
        when (i.action) {
            "LOCK" -> { p.log("[위젯/알림] 잠금 누름"); DagaWidget.tapped(ctx, "잠금"); Jobs.scheduleLock(ctx, 0, force = true) }
            "CANCEL" -> { Jobs.cancelLock(ctx); p.log("자동 잠금 취소") }
            "UNLOCK" -> { p.log("[위젯/알림] 열기 누름"); DagaWidget.tapped(ctx, "열기"); Jobs.unlockNow(ctx) }
            "TRUNK" -> {
                p.log("[위젯/알림] 트렁크 누름"); DagaWidget.tapped(ctx, "트렁크")
                if (KiaAutoService.isConnected) KiaAutoService.request("trunk", "")
                else Notifier.info(ctx, "접근성 권한이 꺼져 있어요 — 다가키 앱에서 켜주세요")
            }
        }
    }
}
