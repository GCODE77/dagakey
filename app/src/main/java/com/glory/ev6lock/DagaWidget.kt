package com.glory.ev6lock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 홈 화면 위젯(3×1): 위 제목 줄 = 앱 열기 / 아래 버튼 3개 (순서는 설정에서) */
class DagaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, null)

    companion object {
        /** 버튼 종류: 동작 이름, 글자, 아이콘, 요청 코드 */
        private data class Btn(val act: String, val label: String, val icon: Int, val code: Int)
        private val ALL = mapOf(
            "UNLOCK" to Btn("UNLOCK", "열기", R.drawable.ic_btn_unlock, 21),
            "LOCK" to Btn("LOCK", "잠금", R.drawable.ic_btn_lock, 22),
            "TRUNK" to Btn("TRUNK", "트렁크", R.drawable.ic_btn_trunk, 23),
        )
        fun label(act: String) = ALL[act]?.label ?: act
        val KEYS = listOf("UNLOCK", "LOCK", "TRUNK")
        fun icon(act: String) = ALL[act]?.icon ?: 0
        /** 칸 3개에 넣은 버튼 ("" = 빈 칸). 처음엔 기본 순서 */
        fun order(p: Prefs): List<String> {
            if (p.widgetOrder.isBlank()) return KEYS
            val o = p.widgetOrder.split(",").map { if (it in ALL) it else "" }
            return (o + listOf("", "", "")).take(3)
        }

        /** 제목 줄 오른쪽에 '방금 누른 것' 표시 (예: "✓ 열기 18:15") */
        fun refresh(ctx: Context, status: String?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DagaWidget::class.java))
            if (ids.isEmpty()) return
            val p = Prefs(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_title, status ?: if (p.masterOn) "앱 열기 ›" else "자동 꺼짐 ›")
            v.setTextColor(R.id.w_title, if (status != null) 0xFF4C8DFF.toInt() else 0xFF8C98A8.toInt())
            val slots = listOf(Triple(R.id.w_b1, R.id.w_i1, R.id.w_t1), Triple(R.id.w_b2, R.id.w_i2, R.id.w_t2), Triple(R.id.w_b3, R.id.w_i3, R.id.w_t3))
            val o = order(p)
            // 빈 칸은 숨김 → 남은 버튼이 넓어짐. 마지막으로 보이는 칸은 오른쪽 여백 없음
            val lastVisible = o.indexOfLast { it.isNotEmpty() }
            o.zip(slots).forEachIndexed { i, (key, s) ->
                if (key.isEmpty()) { v.setViewVisibility(s.first, android.view.View.GONE); return@forEachIndexed }
                v.setViewVisibility(s.first, android.view.View.VISIBLE)
                if (android.os.Build.VERSION.SDK_INT >= 31) v.setViewLayoutMargin(s.first, RemoteViews.MARGIN_END, if (i == lastVisible) 0f else 4f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                val b = ALL.getValue(key)
                v.setImageViewResource(s.second, b.icon)
                v.setTextViewText(s.third, b.label)
                v.setOnClickPendingIntent(s.first, pi(ctx, b.act, b.code))
            }
            // 위 제목 줄 전체를 누르면 앱 열기
            v.setOnClickPendingIntent(R.id.w_app, PendingIntent.getActivity(
                ctx, 24, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            ids.forEach { mgr.updateAppWidget(it, v) }
        }

        fun tapped(ctx: Context, what: String) {
            Prefs(ctx).apply {
                lastTap = what; lastTapAt = System.currentTimeMillis()
                // ★ 실측: 트렁크를 쓰러 갔는데 자동으로 잠겨버림 → 트렁크를 누르면 10분간 자동 잠금 보류
                if (what == "트렁크") {
                    if (trunkHoldMin > 0) { lockHoldUntil = System.currentTimeMillis() + trunkHoldMin * 60_000L; log("트렁크 사용 → ${trunkHoldMin}분간 자동 잠금 보류") }
                    else lockHoldUntil = 0
                }
                BleNear.calibrate(this, "직접 $what")   // 차 옆에서 누른 거라면 그때 세기로 거리 보정
            }
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date())
            refresh(ctx, "✓ $what 요청 $t")
        }

        private fun pi(ctx: Context, act: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE
        )
    }
}
