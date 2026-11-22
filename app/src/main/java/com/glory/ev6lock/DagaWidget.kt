package com.glory.ev6lock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 홈 화면 위젯: 열기 / 잠금 / 트렁크 — 앱을 열지 않고 바로 실행 */
class DagaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, null)

    companion object {
        /** 위젯 맨 위 글자에 '방금 누른 것'을 표시 (예: "✓ 열기 요청 · 18:15") */
        fun refresh(ctx: Context, status: String?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DagaWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_title, status ?: if (Prefs(ctx).masterOn) "다가키" else "자동 꺼짐")
            v.setTextColor(R.id.w_title, if (status != null) 0xFF4C8DFF.toInt() else 0xFF8C98A8.toInt())
            v.setOnClickPendingIntent(R.id.w_unlock, pi(ctx, "UNLOCK", 21))
            v.setOnClickPendingIntent(R.id.w_lock, pi(ctx, "LOCK", 22))
            v.setOnClickPendingIntent(R.id.w_trunk, pi(ctx, "TRUNK", 23))
            // 왼쪽 '다가키' 칸 전체를 누르면 앱 열기 (넓게)
            v.setOnClickPendingIntent(R.id.w_app, PendingIntent.getActivity(
                ctx, 24, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            ids.forEach { mgr.updateAppWidget(it, v) }
        }

        fun tapped(ctx: Context, what: String) {
            Prefs(ctx).apply {
                lastTap = what; lastTapAt = System.currentTimeMillis()
                BleNear.calibrate(this, "직접 $what")   // 차 옆에서 누른 거라면 그때 세기로 거리 보정
            }
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date())
            refresh(ctx, "✓$what $t")   // 좁은 칸용 짧은 표시
        }

        private fun pi(ctx: Context, act: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE
        )
    }
}
