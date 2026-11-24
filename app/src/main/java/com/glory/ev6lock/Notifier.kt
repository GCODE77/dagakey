package com.glory.ev6lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Notifier {
    private const val CH = "ev6"            // 꼭 눌러야 하는 알림(설치 확인·문 열기 묻기) — 팝업
    private const val CH_LOW = "ev6_bar"    // 상단 고정 바(조용)
    private const val CH_Q = "ev6_quiet"    // 처리 결과·안내 — 소리·팝업 없이 조용히 (곧 사라짐)
    private const val CH_UPD = "ev6_upd"    // 새 버전 — 소리 없이 상단에만

    private fun nm(ctx: Context): NotificationManager {
        val m = ctx.getSystemService(NotificationManager::class.java)
        // ★ 실측: 모든 알림이 '팝업(헤드업)'으로 계속 떠서 화면을 가림 → 결과·안내는 조용한 채널로
        m.createNotificationChannel(NotificationChannel(CH, "확인이 필요한 알림", NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel(CH_LOW, "빠른 제어 바", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(CH_Q, "처리 결과·안내 (조용히)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel(CH_UPD, "중요 안내 (소리 없음)", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        return m
    }

    private fun action(ctx: Context, act: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE
    )

    /** 알림 본문을 누르면 다가키 앱 화면 열기 */
    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 40, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun base(ctx: Context, text: String, ch: String = CH_Q) = Notification.Builder(ctx, ch)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("다가키")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(openApp(ctx))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)

    /** 처리 결과 알림: 조용히 뜨고 10초 뒤 저절로 사라짐 */
    fun info(ctx: Context, text: String) = nm(ctx).notify(1, base(ctx, text).setTimeoutAfter(10_000).build())

    /** 새 버전 알림: 누르거나 '업데이트'를 누르면 앱이 열리며 바로 다운로드 시작 */
    fun updateAvailable(ctx: Context, tag: String) {
        val open = PendingIntent.getActivity(
            ctx, 41, Intent(ctx, MainActivity::class.java).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(ctx).notify(
            5, Notification.Builder(ctx, CH_UPD)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("다가키 새 버전 $tag")
                .setContentText("눌러서 업데이트 (다운로드 후 바로 설치)")
                .setContentIntent(open).setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "업데이트", open).build())
                .build()
        )
    }

    /** 앱이 화면에 없을 때 설치 확인 화면을 알림으로 이어줌 */
    fun installConfirm(ctx: Context, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, 42, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm(ctx).notify(
            6, Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("다가키 업데이트 — 설치 확인")
                .setContentText("눌러서 설치를 계속하세요")
                .setContentIntent(pi).setAutoCancel(true).build()
        )
    }

    fun pendingLock(ctx: Context, min: Int) = nm(ctx).notify(
        1, base(ctx, "차에서 걸어서 떠나면 ${min}분 뒤 자동으로 잠급니다")
            .addAction(Notification.Action.Builder(null, "취소", action(ctx, "CANCEL", 11)).build())
            .build()
    )

    fun askUnlock(ctx: Context) = nm(ctx).notify(
        2, base(ctx, "차 근처에 도착했습니다. 문을 열까요?", CH)
            .addAction(Notification.Action.Builder(null, "문 열기", action(ctx, "UNLOCK", 12)).build())
            .build()
    )

    /** 폰이 잠겨 있어 기아 앱을 못 띄울 때: 잠금만 풀면 바로 이어서 처리 */
    fun askUnlockPhone(ctx: Context, what: String) = nm(ctx).notify(
        4, base(ctx, "폰 잠금을 풀면 바로 $what 합니다 (지문/얼굴)")
            .setContentTitle("다가키 — $what 대기 중")
            .setContentIntent(PendingIntent.getActivity(ctx, 43,
                Intent(ctx, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setTimeoutAfter(60_000)
            .build()
    )

    /** 자동 잠금이 끝내 실패: 차 문이 열려 있을 수 있음 → 팝업으로 알리고 '지금 잠금' 버튼 */
    fun lockFailed(ctx: Context) = nm(ctx).notify(
        9, Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("⚠ 자동 잠금에 실패했어요")
            .setContentText("차 문이 열려 있을 수 있어요. 눌러서 확인하세요")
            .setStyle(Notification.BigTextStyle().bigText("기아 앱에서 잠금 버튼을 못 찾았어요. 차 문이 열려 있을 수 있으니 확인하거나 '지금 잠금'을 눌러 주세요."))
            .setContentIntent(openApp(ctx)).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "지금 잠금", action(ctx, "LOCK", 10)).build())
            .build()
    )

    /** 접근성이 꺼졌거나(또는 켜져 있는데 기기가 멈춰 둠) 자동 동작이 안 될 때: 눌러서 접근성 설정 열기 */
    fun accProblem(ctx: Context, enabledInSettings: Boolean) {
        val pi = PendingIntent.getActivity(ctx, 45,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val t = if (enabledInSettings)
            "접근성이 켜져 있지만 기기가 멈춰 둔 상태예요. 설정 › 접근성 › 설치된 앱 › 다가키를 끄고 다시 켜면 돌아와요. (눌러서 설정 열기)"
        else "접근성이 꺼져 있어서 자동 열기·잠금이 안 돼요. 눌러서 다가키를 켜세요."
        nm(ctx).notify(8, Notification.Builder(ctx, CH_UPD)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(if (enabledInSettings) "접근성이 멈췄어요" else "접근성이 꺼져 있어요")
            .setContentText("눌러서 접근성 설정 열기")
            .setStyle(Notification.BigTextStyle().bigText(t))
            .setContentIntent(pi).setAutoCancel(true).build())
    }
    fun accOk(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(8)

    /** 주머니 속에서 폰 잠금 때문에 못 열었을 때: '잠금 해제 유지' 켜는 방법 안내 → 누르면 잠금화면 설정 */
    fun pocketTip(ctx: Context) {
        val pi = PendingIntent.getActivity(ctx, 44,
            Intent("android.settings.LOCK_SCREEN_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val t = "폰이 잠겨 있어 주머니 속에서 열지 못했어요.\n설정 → 잠금화면 및 AOD → 잠금 해제 유지 → '몸에 지니고 있을 때'를 켜면 주머니 속에서도 열려요. (눌러서 설정 열기)"
        nm(ctx).notify(7, Notification.Builder(ctx, CH_Q)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("주머니 속에서도 열리게 하기")
            .setContentText("눌러서 '잠금 해제 유지' 켜기")
            .setStyle(Notification.BigTextStyle().bigText(t))
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    /** 상단에 항상 떠 있는 빠른 제어 바 — 차 근처에서 한 번 눌러 디지털키로 열기/잠금 */
    fun persistent(ctx: Context) = nm(ctx).notify(
        3, Notification.Builder(ctx, CH_LOW)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(if (Prefs(ctx).masterOn) "다가키 · 자동 켜짐" else "다가키 · 자동 꺼짐")
            .setContentText("차 근처에서 눌러 디지털키로 제어 · 누르면 앱 열기")
            .setContentIntent(openApp(ctx))
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
