package com.glory.ev6lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle

/**
 * 투명 징검다리 화면: 꺼진 화면을 켜고 잠금화면 위에 잠깐 뜬 뒤,
 * 잠금 해제(얼굴/지문/스와이프)를 요청하고 → 풀리면 기아 앱을 띄운다.
 * (폰이 주머니에서 꺼져 있을 때 기아 앱이 뒤에 숨어 아무것도 못 누르던 문제 해결)
 */
class WakeActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { go(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { go() }
            override fun onDismissCancelled() { Prefs(this@WakeActivity).log("잠금 해제 취소됨"); finish() }
            override fun onDismissError() { Prefs(this@WakeActivity).log("잠금 해제 요청 실패"); finish() }
        })
    }

    private fun go() {
        Prefs(this).log("화면 켜짐·잠금 풀림 → 기아 앱 실행")
        packageManager.getLaunchIntentForPackage("com.kia.oneapp.kr")?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
        KiaAutoService.instance?.onKiaLaunched()
        finish()
    }
}
