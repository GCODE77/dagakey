package com.glory.ev6lock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.launch

/**
 * 기아 공식 앱(com.kia.oneapp.kr)을 사람 대신 조작하는 접근성 서비스.
 *
 * 실제 테스트에서 드러난 문제와 대응:
 *  1) 폰이 주머니에서 잠겨 있으면 기아 앱 화면이 잠금화면 뒤에 숨어 아무것도 못 누름
 *     → 화면을 켜고, 잠겨 있으면 "잠금 해제하면 바로 처리" 알림을 띄운 뒤
 *       사용자가 잠금을 푸는 순간(USER_PRESENT) 자동으로 이어서 처리
 *  2) 삼성 '오동작 방지 필터' 같은 덮개 창이 맨 위에 오면 활성창이 기아 앱이 아님
 *     → 모든 창 중에서 기아 앱 창을 직접 찾아 조작
 *  3) 디지털키 버튼 줄이 2페이지(경적/트렁크)로 넘어가 있으면 잠금 버튼이 없음
 *     → 1페이지로 되돌리는 스와이프 후 다시 찾기
 *  4) '열기/닫기'(트렁크) 같은 버튼을 잘못 누를 위험 → 정확히 '잠금'/'잠금 해제'만 누름
 */
class KiaAutoService : AccessibilityService() {

    companion object {
        private const val KIA = "com.kia.oneapp.kr"
        @Volatile var instance: KiaAutoService? = null
        /** 지금 수행할 동작: "", "lock", "unlock", "learn" */
        @Volatile var pendingAction: String = ""
        @Volatile var pin: String = ""
        /** 자동 열기: 디지털키가 붙을 때만 열고 원격은 보내지 않음 (멀리서 열리는 사고 방지) */
        @Volatile var dkOnly: Boolean = false

        /** 백그라운드 경로(자동 잠금/열기): 서비스가 화면을 켜고 기아 앱을 띄움 */
        fun request(action: String, pin: String) {
            this.pin = pin
            if (action == "unlock") instance?.stopLeaveWatch()
            instance?.ctrlRetry = 0
            pendingAction = action
            instance?.kick()
        }
        /** 앱 화면에서 누른 경우: 실행은 호출자가, 처리는 서비스가 */
        fun arm(action: String, pin: String) {
            this.pin = pin
            pendingAction = action
            instance?.resetTries()
        }
        val isConnected get() = instance != null
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var p: Prefs
    private var learnStart = 0L
    private var lastSig = ""
    private var actionStart = 0L
    private var ticking = false
    private var waitingUnlock = false
    private var swipedBack = 0
    private var pressed = false
    private var remoteSent = false

    private val controlTick = object : Runnable {
        override fun run() {
            if (isControl()) {
                process()
                if (isControl()) h.postDelayed(this, 1500) else ticking = false
            } else ticking = false
        }
    }
    private fun ensureTicker() { if (!ticking) { ticking = true; h.postDelayed(controlTick, 1500) } }

    /** 사용자가 폰 잠금을 푸는 순간 → 미뤄둔 동작 이어서 */
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (deferred) { resumeDeferred(); return }
            if (waitingUnlock && (isControl())) {
                waitingUnlock = false
                p.log("폰 잠금 해제됨 → ${actionName()} 이어서 진행")
                launchKia()
            }
        }
    }

    private fun learnFile(line: String) {
        for (dir in listOfNotNull(filesDir, try { getExternalFilesDir(null) } catch (_: Exception) { null })) {
            try { java.io.File(dir, "learn.txt").appendText(line + "\n") } catch (_: Exception) {}
        }
    }

    /** 화면의 모든 요소(글자·그림 설명·상태·눌림·위치)를 한 줄씩 — 문 잠김 같은 '그림'이 어떤 이름표를 갖는지 확인용 */
    private fun dumpNodes(n: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (n == null || out.size >= 260) return
        val r = android.graphics.Rect(); n.getBoundsInScreen(r)
        val txt = n.text?.toString()?.take(30)
        val d = n.contentDescription?.toString()?.take(40)
        val id = n.viewIdResourceName?.substringAfterLast('/')
        val st = if (android.os.Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString() else null
        val flags = buildString {
            if (n.isClickable) append('C'); if (n.isChecked) append('K'); if (n.isSelected) append('S')
            if (!n.isEnabled) append('D'); if (n.isVisibleToUser) append('V')
        }
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        // 이름표가 있거나 눌리는 것, 그림(Image*)은 이름표가 없어도 기록
        if (txt != null || d != null || id != null || st != null || n.isClickable || cls.contains("Image"))
            out.add("${"  ".repeat(minOf(depth, 8))}$cls t=$txt d=$d id=$id st=$st f=$flags [${r.left},${r.top},${r.right},${r.bottom}]")
        for (i in 0 until n.childCount) dumpNodes(n.getChild(i), depth + 1, out)
    }

    override fun onServiceConnected() {
        p = Prefs(applicationContext)
        instance = this
        registerReceiver(userPresent, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
        })
        p.log("접근성 서비스 연결됨")
        // 폰 재부팅/앱 업데이트 후에도 차 접근 감시가 항상 켜져 있도록
        if (p.approachUnlock) BleNear.start(applicationContext)
        // ★ 실측: 안드로이드는 30분 넘게 켜진 블루투스 검색을 '수동 모드'로 낮춤 → 3시간 뒤 다가가도 감지 못함
        //   → 20분마다 검색을 새로 시작해 항상 '능동 모드' 유지 (차 안에서는 쉼)
        BleRefreshReceiver.schedule(applicationContext)
        PowerSaver.start(applicationContext)   // 배터리 절약 (집 와이파이 / 가만히 있을 때)
    }

    override fun onDestroy() {
        try { unregisterReceiver(userPresent) } catch (_: Exception) {}
        instance = null
    }

    override fun onInterrupt() {}

    fun resetTries() {
        learnStart = 0L; lastSig = ""; actionStart = 0L; swipedBack = 0; pressed = false; remoteSent = false
        backDone = false; homeTries = 0; launchedAt = System.currentTimeMillis(); dkTaps = 0; dkTapAt = 0L
        h.postDelayed({ process() }, 2500)
        if (isControl()) ensureTicker()
    }

    private fun done() { pendingAction = ""; dkOnly = false; relaunch = 0; ctrlRetry = 0; hintCount = 0; lastHint = ""; verifyStart = 0L; doorSeen = null; actionStart = 0L; ticking = false; waitingUnlock = false; pressed = false; launchedAt = 0L; homeTries = 0; remoteSent = false; backDone = false; deferred = false; diagDone = false; dkTaps = 0; dkTapAt = 0L }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "dagakey:wake"
            ).acquire(40_000)   // 기아 앱 조작 끝날 때까지 화면 유지
        } catch (_: Exception) {}
    }

    /** 자동 동작 시작: 화면을 켜고(징검다리 화면) 잠겨 있으면 해제를 요청 → 풀리면 기아 앱 */
    fun kick() {
        if (pendingAction.isBlank()) return
        actionStart = 0L; swipedBack = 0; pressed = false; homeTries = 0; remoteSent = false; dkTaps = 0; dkTapAt = 0L; relaunch = 0; verifyStart = 0L; doorSeen = null
        wakeScreen()
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            waitingUnlock = true
            p.log("폰이 잠겨 있음 → 화면 켜고 잠금 해제 요청 (얼굴/지문)")
            Notifier.askUnlockPhone(applicationContext, actionName())
            h.postDelayed({ if (waitingUnlock) { p.log("폰 잠금 해제 안 됨 — ${actionName()} 취소"); done() } }, 120_000)
        } else p.log("화면 켜고 기아 앱 실행 → ${actionName()}")
        try {
            startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            p.log("징검다리 화면 실패(${e.message}) → 기아 앱 직접 실행")
            if (!km.isKeyguardLocked) launchKia()
        }
    }

    /** WakeActivity가 기아 앱을 띄운 직후 호출 */
    fun onKiaLaunched() {
        waitingUnlock = false
        actionStart = 0L; launchedAt = System.currentTimeMillis()
        h.postDelayed({ process() }, 2000)
        ensureTicker()
    }
    private var launchedAt = 0L
    private var homeTries = 0
    private var relaunch = 0
    private var ctrlRetry = 0
    private var hintCount = 0
    private var learnDumps = 0
    // ── 문 상태 읽기(제어탭) ──
    private var verifyStart = 0L              // 제어탭 버튼을 누른 시각 (상태가 바뀌는지 확인 중)
    private var doorSeen: DoorState? = null   // 마지막으로 본 문 상태
    private var doorSeenAt = 0L               // 그 상태를 처음 본 시각 (3초 이상 같아야 믿음)

    private fun firstNode(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) firstNode(n.getChild(i), pred)?.let { return it }
        return null
    }

    /**
     * 제어탭 '문' 타일에서 문 상태 읽기 (실측: 11-24 23:04 화면 학습)
     *  - 버튼 글자가 '잠금 해제' → 지금 잠김 / '잠금' → 지금 열림 (버튼은 "누르면 할 동작"을 보여줌)
     * 제어탭이 아니면 null. 반환: (상태, 그 버튼 노드)
     */
    private fun readDoorTile(root: AccessibilityNodeInfo?): Pair<DoorState, AccessibilityNodeInfo>? {
        val label = firstNode(root) { it.isVisibleToUser && it.text?.toString()?.replace(" ", "") == "문" } ?: return null
        var c: AccessibilityNodeInfo? = label.parent
        var depth = 0
        while (c != null && depth < 3) {
            firstNode(c) { it.isVisibleToUser && it.text?.toString()?.replace(" ", "") == "잠금해제" }?.let { return DoorState.LOCKED to it }
            firstNode(c) { it.isVisibleToUser && it.text?.toString()?.replace(" ", "") == "잠금" }?.let { return DoorState.UNLOCKED to it }
            c = c.parent; depth++
        }
        return null
    }

    /** 원하는 상태가 이미 되어 있어 버튼을 누를 필요가 없을 때 */
    private fun finishAlready(what: String) {
        val act = pendingAction
        p.log("문이 이미 $what (제어탭 확인) → ${actionName()} 불필요")
        if (act == "lock") { p.lastLockAt = System.currentTimeMillis(); stopLeaveWatch() }
        else if (act == "unlock") p.lastDkUnlockAt = System.currentTimeMillis()   // 이미 열려 있으니 도착 단계 원격 보조 불필요
        Notifier.info(applicationContext, "문이 이미 $what")
        done()
    }
    /** 제어탭 버튼을 누른 뒤 상태가 실제로 바뀐 것을 확인 */
    private fun finishVerified() {
        if (pendingAction == "lock") { p.lastLockAt = System.currentTimeMillis(); stopLeaveWatch() }
        p.log("${actionName()} 완료 (제어탭에서 상태 확인됨)")
        Notifier.info(applicationContext, "${actionName()} 했습니다 [원격·상태 확인됨]")
        done()
    }
    private fun failFinal(act: String) {
        done()
        if (act == "lock") { p.log("⚠ 잠금 실패 — 알림으로 알림"); Notifier.lockFailed(applicationContext) }
        else if (act == "unlock") Notifier.info(applicationContext, "열기에 실패했어요 — 직접 확인해 주세요")
    }
    private var lastHint = ""

    private fun launchKia() {
        p.log("기아 앱 실행 → ${actionName()}")
        val i = packageManager.getLaunchIntentForPackage(KIA)
        if (i == null) { p.log("기아 앱을 찾을 수 없음"); done(); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(i)
        onKiaLaunched()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingAction.isBlank()) return
        if (event?.packageName != KIA) return
        h.removeCallbacks(processRunnable)
        h.postDelayed(processRunnable, 700)
    }

    private val processRunnable = Runnable { process() }

    private fun isControl() = pendingAction == "lock" || pendingAction == "unlock" || pendingAction == "trunk"

    private fun actionName() = when (pendingAction) {
        "lock" -> "잠금"; "unlock" -> "열기"; "trunk" -> "트렁크 열기/닫기"; "learn" -> "학습"; else -> pendingAction
    }

    /** 덮개 창(오동작 방지 필터 등)이 있어도 기아 앱 창을 직접 찾음 */
    private fun kiaRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName == KIA) return it }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName == KIA) return r
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * ★ 실측(캡처): 디지털키가 저절로 안 붙으면 홈에 '디지털 키 연결하기 >' 버튼이 뜨고 눌러야 연결됨.
     * → 보이면 자동으로 누름 (8초 간격, 최대 3번)
     */
    private var dkTaps = 0
    private var dkTapAt = 0L
    private fun tryConnectDk(root: AccessibilityNodeInfo) {
        if (dkTaps >= 3 || System.currentTimeMillis() - dkTapAt < 8000) return
        val btn = findExact(root, setOf("디지털키연결하기"), visibleOnly = true) ?: return
        dkTaps++; dkTapAt = System.currentTimeMillis()
        val ok = clickOrTap(btn)
        p.log("'디지털 키 연결하기' 누름 (${if (ok) "성공" else "실패"}, ${dkTaps}번째)")
    }

    /** 진단: 기아 앱이 안 보일 때 지금 화면 상태를 기록 (주머니 속 화면 꺼짐인지, 다른 앱이 덮었는지 등) */
    private fun diagnoseScreen() {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        val pk = try { windows.mapNotNull { it.root?.packageName?.toString() }.distinct().joinToString(", ") } catch (_: Exception) { "?" }
        p.log("진단: 화면 ${if (pm.isInteractive) "켜짐" else "꺼짐"} · 잠금 ${if (km.isKeyguardLocked) "걸림" else "풀림"} · 보이는 앱 [$pk]")
    }

    /**
     * ★ 실측: 주머니 속에서는 화면이 바로 꺼져 기아 앱이 안 보임 → 예전엔 30초 뒤 포기.
     * 이제는 포기하지 않고 '폰 화면이 켜지는 순간'(엘리베이터에서 폰 꺼낼 때 등) 이어서 마무리.
     */
    private var deferred = false
    private fun deferUntilScreen() {
        if (deferred) return
        deferred = true; launchedAt = 0L; actionStart = 0L; ticking = false
        val what = actionName()
        p.log("주머니 속이라 기아 앱이 안 보임 → 폰 화면을 켜면 바로 $what 마무리 (15분간 대기)")
        Notifier.askUnlockPhone(applicationContext, what)
        // 폰 잠금(지문·PIN)은 다른 앱이 풀 수 없음 → '잠금 해제 유지(몸에 지니고 있을 때)' 안내 (하루 1번)
        val now = System.currentTimeMillis()
        if (now - p.lastPocketFailAt > 24 * 3600_000L) Notifier.pocketTip(applicationContext)
        p.lastPocketFailAt = now
        h.postDelayed({ if (deferred) { p.log("15분 지나 $what 대기 종료"); done() } }, 15 * 60_000)
    }
    private fun resumeDeferred() {
        if (!deferred || !isControl()) return
        deferred = false
        p.log("화면 켜짐 → ${actionName()} 이어서 진행")
        pressed = false; homeTries = 0; backDone = false; remoteSent = false
        try { startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { launchKia() }
    }

    private fun process() {
        if (pendingAction.isBlank() || waitingUnlock || deferred) return
        val root = kiaRoot() ?: run {
            if (isControl()) {
                if (launchedAt == 0L) launchedAt = System.currentTimeMillis()
                val waited = System.currentTimeMillis() - launchedAt
                if (waited in 5_000..6_500) diagnoseScreen()
                // ★ 실측: 화면이 켜져 있는데 기아 앱이 안 뜨고 30초 뒤 포기(주머니 대기)로 넘어감
                //   → 10초 지나도 안 보이면 두 번까지 다시 실행해 봄
                if (waited >= 10_000 && relaunch < 2 && pendingAction != "trunk") {
                    relaunch++
                    p.log("기아 앱이 안 떠서 다시 실행 ($relaunch/2)")
                    try { startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: Exception) { launchKia() }
                    return
                }
                if (waited > 30_000) {
                    if (pendingAction == "trunk") {
                        p.log("기아 앱 화면이 30초간 안 보임 — 트렁크 중단")
                        Notifier.info(applicationContext, "트렁크 실패 — 폰 화면을 켜고 다시 시도해 주세요")
                        done(); launchedAt = 0L
                    } else deferUntilScreen()
                } else if (waited / 1000 % 10 == 0L) p.log("기아 앱 화면 기다리는 중… (${waited / 1000}초)")
            }
            return
        }
        val allText = collectText(root)

        // ★ 문이 잠겼는지/열렸는지 기아 앱이 글자로 보여주는지 알아보기 위해, 문·잠금 관련 글자를 기록 (화면이 바뀔 때마다, 한 동작에 최대 4번)
        if (isControl() && hintCount < 4) {
            val hint = allText.map { it.trim() }.filter { s ->
                s.length in 2..40 && (s.contains("잠김") || s.contains("잠금") || s.contains("열림") || s.contains("닫힘") || s.contains("도어") ||
                    s.contains("문 ") || s.contains("트렁크") || s.contains("디지털") || s.contains("원격"))
            }.distinct().take(16).joinToString(" | ")
            if (hint.isNotEmpty() && hint != lastHint) { lastHint = hint; hintCount++; p.log("기아 화면 문·잠금 글자: $hint") }
        }

        if (pendingAction != "learn" && allText.any { it.contains("USB") && it.contains("디버깅") }) {
            p.log("기아 앱이 USB 디버깅 차단 화면을 띄움 → USB 디버깅을 끄세요"); done(); return
        }

        when (pendingAction) {
            "learn" -> learn(root, allText)
            "lock", "unlock" -> { ensureTicker(); doControl(root, allText) }
            "trunk" -> { ensureTicker(); doTrunk(root, allText) }
        }
    }

    private fun learn(root: AccessibilityNodeInfo, allText: List<String>) {
        if (learnStart == 0L) {
            learnStart = System.currentTimeMillis(); lastSig = ""; learnDumps = 0
            learnFile("================ 학습 시작 ${java.util.Date()} ================")
            p.log("학습 중… 기아 앱에서 차량 화면까지 이동하세요 (90초간 기록)")
            h.postDelayed({
                if (pendingAction == "learn") {
                    pendingAction = ""; learnStart = 0L
                    p.log("학습 끝 (learn.txt 저장됨)")
                    learnFile("================ 학습 끝 ================")
                }
            }, 90_000)
        }
        val texts = allText.distinct()
        val nodes = mutableListOf<String>(); dumpNodes(root, 0, nodes)
        val sig = texts.take(10).joinToString("¶") + "#" + nodes.joinToString("").hashCode()
        if (sig != lastSig && learnDumps < 25) {
            lastSig = sig; learnDumps++
            learnFile("[구조 ${nodes.size}개]"); nodes.forEach { learnFile(it) }
            p.log("[학습] 화면 구조 ${nodes.size}개 기록 (${learnDumps}/25)")
            learnFile("\n--- 화면 ---")
            learnFile("[글자] " + texts.take(80).joinToString(" | "))
            p.log("[학습] 화면 글자: " + texts.take(40).joinToString(" | "))   // 앱 기록에도 남겨 USB로 바로 읽을 수 있게
            learnFile("[버튼] " + collectClickable(root).distinct().take(50).joinToString(" | "))
        }
    }

    private fun doControl(root: AccessibilityNodeInfo, allText: List<String>) {
        if (pressed) return   // 이미 한 번 눌렀음 — 연타 방지 (완료 처리 대기)
        if (actionStart == 0L) actionStart = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - actionStart
        // ── 제어탭 버튼을 누른 뒤: 문 상태가 실제로 바뀌는지 확인 (최대 15초, 안 바뀌면 다시 누름 2번까지) ──
        if (verifyStart > 0L) {
            val st = readDoorTile(root)?.first
            if (st == (if (pendingAction == "lock") DoorState.LOCKED else DoorState.UNLOCKED)) { finishVerified(); return }
            if (System.currentTimeMillis() - verifyStart > 15_000) {
                verifyStart = 0L
                if (ctrlRetry < 2) { ctrlRetry++; p.log("상태가 안 바뀜 — ${actionName()} 다시 누름 ($ctrlRetry/2)") }
                else { failFinal(pendingAction); return }
            }
            return
        }
        // 원격을 먼저 보낸 열기는 걸어오는 시간을 고려해 오래 기다림
        if (elapsed > (if (remoteSent) 100_000 else if (dkOnly) 70_000 else 40_000)) {
            // ★ 실패하기 전에 현재 문 상태부터 확인: 이미 원하는 상태면 실패가 아님 (11-24 17:36은 이미 잠겨 있었던 것으로 보임)
            readDoorTile(root)?.let { (st, _) ->
                if (pendingAction == "lock" && st == DoorState.LOCKED) { finishAlready("잠겨 있음"); return }
                if (pendingAction == "unlock" && st == DoorState.UNLOCKED) { finishAlready("열려 있음"); return }
            }
            // ★ 실측(11-24 17:36): 잠금이 '버튼을 못 찾음'으로 조용히 끝나 차가 열린 채 남음
            //   → 그때 화면 글자를 기록(원인 분석), 기아 앱을 새로 띄워 2번까지 다시 시도, 끝내 실패하면 알림
            val vis = collectVisibleText(root).distinct().take(25).joinToString(" | ")
            p.log("시간 초과 — ${actionName()} (버튼을 못 찾음) · 화면: $vis")
            val act = pendingAction
            if (ctrlRetry < 2) {
                ctrlRetry++
                p.log("${actionName()} 다시 시도 ($ctrlRetry/2) — 기아 앱을 새로 띄움")
                val keepDk = dkOnly
                kick()
                dkOnly = keepDk
                return
            }
            done()
            if (act == "lock") { p.log("⚠ 잠금 실패 — 알림으로 알림"); Notifier.lockFailed(applicationContext) }
            else if (act == "unlock") Notifier.info(applicationContext, "열기에 실패했어요 — 직접 확인해 주세요")
            return
        }

        val wantLock = pendingAction == "lock"
        val norm = allText.map { it.replace(" ", "") }
        val dkConnected = norm.any { it.contains("디지털키연결됨") }

        // ── 제어탭의 '문' 타일이 보이면 문 상태를 읽어서 판단 ──
        //   ★ 실측(11-24 17:36): 차가 이미 잠겨 있으면 제어탭엔 '잠금 해제'만 보여 '잠금' 버튼을 못 찾고 실패로 끝났음
        val door = readDoorTile(root)
        if (door != null) {
            val (st, btn) = door
            // 오래된 화면 값에 속지 않도록 같은 상태가 3초 이상 보일 때만 '이미 됐음'으로 판단
            if (st != doorSeen) { doorSeen = st; doorSeenAt = System.currentTimeMillis(); p.log("문 상태(제어탭): ${if (st == DoorState.LOCKED) "잠김" else "열림"}") }
            val stable = System.currentTimeMillis() - doorSeenAt >= 3_000
            if (wantLock && st == DoorState.LOCKED) { if (stable) finishAlready("잠겨 있음"); return }
            if (!wantLock && st == DoorState.UNLOCKED) { if (stable) finishAlready("열려 있음"); return }
            if (wantLock && !dkOnly) {   // 열려 있고 잠가야 함 + 원격 허용 → 제어탭 버튼을 바로 누르고 상태 확인
                if (clickOrTap(btn)) { verifyStart = System.currentTimeMillis(); p.log("잠금 버튼 누름 (제어탭, 원격) — 상태 확인 중") }
                return
            }
            goHome(root); return    // 디지털키로 열기·디지털키 전용 잠금은 홈 탭에서
        }

        // ★ 기아 앱은 '마지막에 보던 화면'(예: 제어 > EV 충전)으로 열림 → 잠금 버튼과 디지털키는 '홈' 탭에만 있음
        val onHome = findExact(root, setOf("잠금해제", "잠금", "디지털키", "디지털키연결됨", "디지털키연결하기"), visibleOnly = true) != null
        if (!onHome) { goHome(root); return }

        // 이미 원하는 동작이 요청되어 있으면 끝
        if (wantLock && norm.any { it.contains("잠금을요청") }) { p.log("잠금 요청 확인됨"); done(); return }

        // ── 열기: ① 원격으로 먼저 열기 요청(느리니까 미리) → ② 디지털키가 붙으면 한 번 더 '잠금 해제' ──
        if (!wantLock) {
            if (!dkConnected && dkOnly) {
                // 자동 열기(디지털키 전용): 원격은 보내지 않고 디지털키가 붙을 때까지만 기다림
                if (elapsed / 1000 % 5 == 0L) p.log("디지털키 연결 대기중… (${elapsed / 1000}초, 원격 안 보냄)")
                tryConnectDk(root)
                if (elapsed >= 60_000) {
                    p.log("디지털키 연결 안 됨 — 차가 아직 멀어서 열지 않음 (자동 열기는 디지털키로만)")
                    done()
                }
                return
            }
            if (!dkConnected) {
                if (!remoteSent) {
                    val n = findExact(root, setOf("잠금해제"), visibleOnly = true)
                    if (n != null && clickOrTap(n)) {
                        remoteSent = true
                        p.log("① 원격 열기 먼저 보냄 → 디지털키 연결되면 한 번 더 누름")
                    }
                } else if (elapsed / 1000 % 5 == 0L) p.log("디지털키 연결 대기중… (${elapsed / 1000}초)")
                tryConnectDk(root)
                // ★ 원격 열기는 30초 안 만지면 차가 다시 잠금 → 디지털키가 붙을 때까지 최대 90초 기다렸다 한 번 더 열기
                if (elapsed >= (if (remoteSent) 90_000 else 30_000)) {
                    p.log(if (remoteSent) "디지털키 연결 안 됨 — 원격 열기만 보냄" else "열기 버튼을 못 찾음")
                    if (remoteSent) Notifier.info(applicationContext, "열기 요청 보냄 [원격]")
                    done()
                }
                return
            }
            // 디지털키 연결됨 → 디지털키로 '잠금 해제'
            val n = findExact(root, setOf("잠금해제"), visibleOnly = true) ?: run {
                if (swipedBack < 3 && norm.any { it == "HOLD" || it.contains("열기/닫기") || it == "끄기" }) {
                    swipedBack++; p.log("버튼줄 2페이지 → 1페이지로 넘김"); swipeRow(root)
                }
                return
            }
            val ok = clickOrTap(n)
            if (ok) { pressed = true; p.lastDkUnlockAt = System.currentTimeMillis() }   // 도착 단계에서 원격 보조 여부 판단용
            p.log("② 디지털키로 열기 누름 (${if (ok) "성공" else "실패"})${if (remoteSent) " — 원격도 이미 보냄" else ""}")
            if (ok) h.postDelayed({
                if (pendingAction == "unlock") { p.log("열기 완료"); Notifier.info(applicationContext, "문을 열었습니다 [디지털키]"); done() }
            }, 2500)
            return
        }

        // 자동 잠금(디지털키 전용 설정): 디지털키가 붙을 때만 잠금
        if (wantLock && dkOnly && !dkConnected) {
            tryConnectDk(root)
            if (elapsed >= 40_000) { p.log("디지털키 연결 안 됨 — 원격 잠금 꺼짐 설정이라 잠그지 않음"); Notifier.info(applicationContext, "디지털키가 연결 안 돼 잠그지 못했어요"); done() }
            return
        }
        val node = if (wantLock) findExact(root, setOf("잠금"), visibleOnly = true)
                   else findExact(root, setOf("잠금해제"), visibleOnly = true)
        if (node == null) {
            // 디지털키 버튼줄이 2페이지(경적/트렁크)에 있으면 1페이지로 되돌림
            if (swipedBack < 3 && norm.any { it == "HOLD" || it.contains("열기/닫기") || it == "끄기" }) {
                swipedBack++
                p.log("버튼줄 2페이지 → 1페이지로 넘김")
                swipeRow(root)
            }
            return
        }
        val ok = clickOrTap(node)
        if (ok) pressed = true
        val via = when { wantLock -> if (dkConnected) "디지털키" else "원격"; dkConnected -> "디지털키"; else -> "원격(디지털키 연결 안 됨)" }
        p.log("${actionName()} 버튼 누름 (${if (ok) "성공" else "실패"}) [$via]")
        if (ok) h.postDelayed({
            if (pendingAction == (if (wantLock) "lock" else "unlock")) {
                if (wantLock) { p.lastLockAt = System.currentTimeMillis(); stopLeaveWatch() }
                p.log("${actionName()} 완료"); Notifier.info(applicationContext, "${actionName()} 했습니다 [$via]"); done()
            }
        }, 2500)
    }

    /**
     * 트렁크 열기/닫기: 홈 탭 → 디지털키 연결 대기 → 버튼줄 2페이지로 넘김 → 'HOLD 열기/닫기'를 3초간 꾹 누름.
     * (기아 앱에서 이 버튼은 눌린 채로 유지해야 동작 / 디지털키가 연결돼야 보이는 버튼)
     */
    private fun doTrunk(root: AccessibilityNodeInfo, allText: List<String>) {
        if (pressed) return
        if (actionStart == 0L) actionStart = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - actionStart
        if (elapsed > 45_000) {
            p.log("트렁크: 시간 초과 (디지털키 연결 안 됨 또는 버튼 못 찾음)")
            Notifier.info(applicationContext, "트렁크 실패 — 차 가까이에서 다시 시도해 주세요"); done(); return
        }
        val norm = allText.map { it.replace(" ", "") }
        val onHome = findExact(root, setOf("잠금해제", "잠금", "디지털키", "디지털키연결됨", "디지털키연결하기", "열기/닫기"), visibleOnly = true) != null
        if (!onHome) { goHome(root); return }
        if (!norm.any { it.contains("디지털키연결됨") }) {
            if (elapsed / 1000 % 5 == 0L) p.log("트렁크: 디지털키 연결 대기중… (${elapsed / 1000}초)")
            tryConnectDk(root)
            return
        }
        val btn = findExact(root, setOf("열기/닫기"), visibleOnly = true)
        if (btn == null) {
            if (swipedBack < 3) { swipedBack++; p.log("트렁크: 버튼줄 2페이지로 넘김"); swipeRow(root, forward = true) }
            return
        }
        // 버튼(아이콘) 위치를 3초간 꾹 누르기
        val r = Rect(); btn.getBoundsInScreen(r)
        var target: AccessibilityNodeInfo = btn; var c: AccessibilityNodeInfo? = btn
        val half = resources.displayMetrics.widthPixels / 2
        while (c != null) {
            val cr = Rect(); c.getBoundsInScreen(cr)
            if ((c.isClickable || c.isLongClickable) && cr.width() < half) { target = c; break }
            c = c.parent
        }
        val tr = Rect(); target.getBoundsInScreen(tr)
        val x = tr.exactCenterX(); val y = if (target === btn) r.top - r.height() * 0.8f else tr.exactCenterY()
        val path = Path().apply { moveTo(x, y) }
        pressed = true
        // ★ 실측: 이전 버전은 '터치 제스처 권한'이 없어서 꾹 누르기가 조용히 실패했음 (capabilities=1)
        //   → 서비스 설정에 canPerformGestures 추가. 결과를 반드시 기록.
        val started = dispatchGesture(
            // ★ 실측: 3.5초면 트렁크가 닫히다가 멈춤 → 6초간 누름
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 6000)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { p.log("트렁크: 6초 꾹 누르기 끝남") }
                override fun onCancelled(g: GestureDescription?) { p.log("트렁크: 꾹 누르기가 중간에 끊김 (화면이 바뀌었거나 다른 터치)") }
            }, null
        )
        p.log("트렁크: '열기/닫기' 6초 꾹 누름 시작 (${if (started) "성공" else "실패 — 터치 권한 없음"})")
        if (!started) {
            // 보조: 길게 누르기 동작
            var lc: AccessibilityNodeInfo? = btn; var ok = false
            while (lc != null && !ok) { if (lc.isLongClickable) ok = lc.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK); lc = lc.parent }
            p.log("트렁크: 길게 누르기 대체 ${if (ok) "성공" else "실패"}")
        }
        h.postDelayed({
            if (pendingAction == "trunk") {
                Notifier.info(applicationContext, if (started) "트렁크 열기/닫기 요청했습니다" else "트렁크 누르기 실패 — 기록을 확인해 주세요")
                done()
            }
        }, 7000)
    }

    /**
     * 홈 탭으로 이동. ★ 실측: 기아 앱이 뜨는 중(스플래시)에 뒤로가기를 4번 눌러버림
     * → 앱이 뜬 뒤 2.5초는 기다리고, '홈' 탭을 누르는 게 우선, 뒤로가기는 6초 지나서 딱 1번만
     */
    private var backDone = false
    private var diagDone = false
    private fun goHome(root: AccessibilityNodeInfo) {
        val since = System.currentTimeMillis() - (if (launchedAt > 0) launchedAt else actionStart)
        if (since < 2500) return
        if (!diagDone) {
            diagDone = true
            val vis = collectVisibleText(root).distinct().take(15).joinToString(" | ")
            p.log("진단: 홈이 아님 — 보이는 글자: $vis")
        }
        // 기아 앱 공지/광고 팝업이 홈을 가리는 경우 닫기 (트렁크·충전구 '닫기'와 헷갈리지 않게 문구로만)
        val popup = findExact(root, setOf("오늘하루보지않기", "다시보지않기", "오늘그만보기", "오늘하루그만보기", "다음에"), visibleOnly = true)
        if (popup != null) { p.log("기아 앱 팝업 닫기"); clickOrTap(popup); return }
        if (homeTries >= 6) return
        val home = findExact(root, setOf("홈"), visibleOnly = true)
        if (home != null) {
            homeTries++
            p.log("기아 앱이 다른 화면에 있음 → '홈' 탭으로 이동")
            clickOrTap(home)
        } else if (since > 6000 && !backDone) {
            backDone = true
            p.log("홈 탭이 안 보임 → 뒤로가기 1번")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * 하차 후 '멀어짐' 실시간 감시: 4초마다 걸음 수 확인 → 기준 걸음 넘는 순간 바로 잠금.
     * (예전: 무조건 2분 기다린 뒤 확인 → 늦게 잠김)
     */
    private var leaveWatching = false
    @Volatile private var testNoDist = false
    fun startLeaveWatch() {
        if (leaveWatching) return
        if (!p.masterOn || !p.autoLock) return
        if (p.braked) { p.log("자동 동작 잠시 멈춤 중 — 멀어짐 감시 생략"); return }
        // ★ 실측: 이미 잠근 뒤 디지털키 모듈이 끊기면서 감시가 또 시작 → 1시간 뒤 차로 '다가가는' 걸음을
        //   '멀어짐'으로 보고 잠가버림. 최근 15분 안에 잠갔으면 시작하지 않음.
        if (System.currentTimeMillis() - p.lastLockAt < 15 * 60_000) { p.log("이미 잠금 완료 — 멀어짐 감시 생략"); return }
        leaveWatching = true
        val startAt = System.currentTimeMillis()
        p.log("하차 감지 → 걸어서 ${p.lockMeters}m 이상 멀어지면 바로 잠금 (실시간 감시)")
        // ★ 거리 확인을 위해 차 신호 검색을 바로 켬 (걸음 수만으로 잠그면 트렁크 쪽을 돌 때도 잠김)
        BleNear.start(applicationContext, quiet = true, forLock = true)
        var lastWaitLog = 0L
        val loop = object : Runnable {
            override fun run() {
                val self = this
                if (!leaveWatching) return
                if (p.inCar) { p.log("다시 탑승 → 잠금 감시 중단"); leaveWatching = false; return }
                // 10분 안에 안 떠났으면 감시 종료 (오래 남아 있다가 나중에 '다가오는 걸음'을 오인하는 것 방지)
                if (System.currentTimeMillis() - startAt > p.leaveWatchMin * 60_000L) { p.log("${p.leaveWatchMin}분간 안 멀어짐 → 멀어짐 감시 종료 (예비 잠금이 이어서 확인)"); leaveWatching = false; return }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    val now = Steps.current(applicationContext)
                    val walked = if (now != null && p.stepsAtPark >= 0) now - p.stepsAtPark else 0f
                    h.post {
                        if (!leaveWatching) return@post
                        // ★ 실측: 실내에서 조금씩 움직인 걸음이 10분 동안 쌓여 '멀어짐'으로 잠김
                        //   → 누적 걸음 + '지금 걷는 중'(최근 30초에 6걸음 이상)일 때만
                        val recent = PowerSaver.stepsInLast(30_000)
                        val walkingNow = recent < 0 || recent >= minOf(p.walkSteps, p.stepThreshold)
                        if (walked >= p.stepThreshold && walkingNow) {
                            // ★ 트렁크를 쓰는 중이면 잠그지 않고 계속 기다림
                            val nowMs = System.currentTimeMillis()
                            if (nowMs < p.lockHoldUntil) {
                                if (nowMs - lastWaitLog > 60_000) { lastWaitLog = nowMs; p.log("트렁크 사용 중 — 자동 잠금 보류") }
                                h.postDelayed(self, 4000); return@post
                            }
                            // ★ 걸음 수만으로는 차 주변을 돌아도 '멀어짐'이 됨 → 신호 세기로 정말 멀어졌는지 확인
                            val (far, why) = if (testNoDist) true to "테스트" else BleNear.farForLock(p)
                            if (!far) {
                                if (nowMs - lastWaitLog > 30_000) { lastWaitLog = nowMs; p.log("${walked.toInt()}걸음 걸었지만 아직 차 가까이 ($why) → ${p.lockMeters}m 이상 멀어질 때까지 기다림") }
                                h.postDelayed(self, 4000); return@post
                            }
                            testNoDist = false
                            leaveWatching = false
                            if (p.noteAutoAndCheckBrake()) {
                                p.log("⚠ 30분 안에 자동 동작이 너무 많음 → 30분간 자동 잠금·열기 멈춤 (오작동 방지)")
                                Notifier.info(applicationContext, "자동 잠금·열기가 반복돼서 30분간 멈췄어요")
                                return@post
                            }
                            p.log("${walked.toInt()}걸음 · 거리 확인 완료 → 바로 잠금")
                            Jobs.cancelLock(applicationContext)          // 예비 예약 취소
                            p.lastLeftAt = System.currentTimeMillis()
                            dkOnly = !p.leaveRemote
                            request("lock", pin)
                            h.postDelayed({ BleNear.start(applicationContext) }, 3000)
                        } else h.postDelayed(self, 4000)
                    }
                }
            }
        }
        h.postDelayed(loop, 3000)
    }
    fun stopLeaveWatch() { leaveWatching = false }

    /** 테스트: 이미 기준 걸음만큼 걸은 것처럼 만들어 '멀어지면 바로 잠금' 동작 확인 */
    fun testLeaveWatch() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val now = Steps.current(applicationContext) ?: run { p.log("테스트 실패: 걸음 센서 응답 없음"); return@launch }
            p.stepsAtPark = now - p.stepThreshold
            testNoDist = true   // 테스트는 거리 확인 건너뜀
            p.log("── 멀어짐 잠금 테스트 (가상으로 ${p.stepThreshold}걸음 걸은 상태) ──")
            h.post { startLeaveWatch() }
        }
    }

    /** 자가 진단: 터치(꾹 누르기) 권한, 걸음 센서, 차 안 여부를 실제로 확인해 기록 */
    fun selfTest() {
        p.log("── 자가 진단 시작 ──")
        p.log("차 안/주행 중 표시: ${if (p.inCar) "예" else "아니오"}")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val s = Steps.current(applicationContext)
            p.log("걸음 센서: ${s?.let { "정상 (누적 ${it.toInt()}걸음)" } ?: "응답 없음"}")
        }
        val w = resources.displayMetrics.widthPixels.toFloat()
        val path = Path().apply { moveTo(w * 0.5f, resources.displayMetrics.heightPixels * 0.06f) }
        val t0 = System.currentTimeMillis()
        val started = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 6000)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    p.log("꾹 누르기 테스트: 통과 (${System.currentTimeMillis() - t0}ms 동안 누름)")
                }
                override fun onCancelled(g: GestureDescription?) { p.log("꾹 누르기 테스트: 중간에 끊김") }
            }, null
        )
        p.log("터치 제스처 권한: ${if (started) "있음" else "없음 ✕ (트렁크 불가)"}")
    }

    /** 공백 무시하고 글자가 정확히 일치하는 노드만 ('열기/닫기' 같은 다른 버튼 방지) */
    private fun findExact(n: AccessibilityNodeInfo?, targets: Set<String>, visibleOnly: Boolean = false): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = (n.text ?: n.contentDescription)?.toString()?.replace(" ", "")?.trim() ?: ""
        if (t in targets && (!visibleOnly || n.isVisibleToUser)) return n
        for (i in 0 until n.childCount) { val r = findExact(n.getChild(i), targets, visibleOnly); if (r != null) return r }
        return null
    }

    /** 디지털키 버튼줄을 넘김: forward=false → 1페이지(잠금), true → 2페이지(트렁크) */
    private fun swipeRow(root: AccessibilityNodeInfo, forward: Boolean = false) {
        val anchor = findExact(root, setOf("켜기", "끄기", "열기/닫기", "잠금", "잠금해제"), visibleOnly = true) ?: return
        var c: AccessibilityNodeInfo? = anchor
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        while (c != null) {
            if (c.isScrollable && c.performAction(act)) return
            c = c.parent
        }
        val r = Rect(); anchor.getBoundsInScreen(r)
        val y = r.exactCenterY()
        val w = resources.displayMetrics.widthPixels.toFloat()
        val path = if (forward) Path().apply { moveTo(w * 0.85f, y); lineTo(w * 0.15f, y) }
                   else Path().apply { moveTo(w * 0.15f, y); lineTo(w * 0.85f, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
    }

    private fun clickOrTap(node: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = node
        while (c != null) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
        }
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        // 글자 바로 위(아이콘)를 탭
        val path = Path().apply { moveTo(r.exactCenterX(), r.top - r.height() * 0.8f) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    // ── 노드 탐색 도우미 ──
    private fun collectVisibleText(n: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        if (n == null) return out
        if (n.isVisibleToUser) (n.text ?: n.contentDescription)?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
        for (i in 0 until n.childCount) collectVisibleText(n.getChild(i), out)
        return out
    }
    private fun collectText(n: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        if (n == null) return out
        n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
        n.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
        for (i in 0 until n.childCount) collectText(n.getChild(i), out)
        return out
    }

    private fun collectClickable(n: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        if (n == null) return out
        if (n.isClickable) (n.text ?: n.contentDescription)?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
        for (i in 0 until n.childCount) collectClickable(n.getChild(i), out)
        return out
    }
}
