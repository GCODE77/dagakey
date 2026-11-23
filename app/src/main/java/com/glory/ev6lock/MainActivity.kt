package com.glory.ev6lock

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B111B)
private val Card1 = Color(0xFF161F2C)
private val Card2 = Color(0xFF1E2A3A)
private val Blue = Color(0xFF4C8DFF)
private val Green = Color(0xFF3DDC97)
private val Red = Color(0xFFFF6B6B)
private val Sub = Color(0xFF8C98A8)
private val Txt = Color(0xFFEAF0F7)

private val EvScheme = darkColorScheme(
    primary = Blue, onPrimary = Color.White, secondary = Sub,
    background = Bg, surface = Card1, onSurface = Txt, onBackground = Txt,
    surfaceVariant = Card2, error = Red,
)

class MainActivity : ComponentActivity() {
    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        permReq.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        )
        val p = Prefs(this)
        p.autoUnlock = true; p.useAppAutomation = true   // (자동 잠금/열기 켜기·끄기는 사용자 설정을 그대로 둠)
        Notifier.persistent(this)
        Updater.schedule(this)                                  // 12시간마다 새 버전 확인
        BleRefreshReceiver.schedule(this)                       // 20분마다 차 감시 새로고침 + 접근성 점검
        wantUpdate = intent?.getBooleanExtra("update", false) == true   // 알림을 눌러 들어온 경우
        handleLink(intent)                                              // 가족이 보낸 차 신호 링크
        setContent {
            MaterialTheme(colorScheme = EvScheme) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Screen(p) }
            }
        }
    }

    /** 새 버전 알림을 눌러 들어오면 true → 바로 다운로드 시작 */
    private var wantUpdate by mutableStateOf(false)

    override fun onResume() { super.onResume(); Updater.appForeground = true }
    override fun onPause() { Updater.appForeground = false; super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("update", false)) wantUpdate = true
        handleLink(intent)
    }

    /** 배터리 '제한 없음' 화면으로 이동 (삼성은 요청창이 막히는 경우가 있어 단계별로 시도) */
    // ── 차 신호 가족 공유 ──
    /** 공유 문구: 사람이 읽을 설명 + 누르면 바로 등록되는 링크 + 붙여넣기용 코드 */
    private fun shareCar(p: Prefs) {
        val link = "dagakey://car?mac=${p.bleAddress}&ref=${p.bleRef1m}" +
            (if (p.carBtName.isNotBlank()) "&audio=" + Uri.encode(p.carBtName) else "")
        val text = "다가키 차 신호 공유 🚗\n" +
            "1) 다가키 설치: https://github.com/GCODE77/dagakey/releases/latest\n" +
            "2) 이 링크를 누르거나, 이 글을 복사해 다가키 › 설정 › 차 신호 등록 › '받은 정보 붙여넣기'\n" +
            "$link\n다가키코드: ${p.bleAddress}|${p.bleRef1m}|${p.carBtName}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "차 신호 공유"))
    }

    /** 받은 글/링크에서 차 신호 읽어 등록. 성공하면 true */
    fun importCar(p: Prefs, text: String, from: String): Boolean {
        val mac = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").find(text)?.value?.uppercase() ?: return false
        p.bleAddress = mac
        val ref = Regex("ref=(-\\d{2})").find(text)?.groupValues?.get(1)
            ?: Regex("다가키코드:\\s*[0-9A-Fa-f:]{17}\\|(-\\d{2})").find(text)?.groupValues?.get(1)
        ref?.toIntOrNull()?.let { p.bleRef1m = it.coerceIn(-80, -40); p.calSamples = "${p.bleRef1m}" }
        val audio = Regex("audio=([^&\\s]+)").find(text)?.groupValues?.get(1)?.let { Uri.decode(it) }
            ?: Regex("다가키코드:\\s*[0-9A-Fa-f:]{17}\\|-?\\d*\\|(.+)").find(text)?.groupValues?.get(1)?.trim()
        if (!audio.isNullOrBlank()) { p.carBtName = audio; p.carBtAddress = "" }
        p.log("차 신호 등록 ($from): $mac" + (ref?.let { " · 기준 $it" } ?: ""))
        BleNear.start(this, quiet = true)
        Toast.makeText(this, "✓ 차 신호를 등록했어요 ($mac)", Toast.LENGTH_LONG).show()
        return true
    }

    /** 공유 링크(dagakey://car?...)로 열렸으면 바로 등록 */
    private fun handleLink(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "dagakey" && d.host == "car") importCar(Prefs(this), d.toString(), "공유 링크")
    }

    /** 잠금화면 설정 (여기 '잠금 해제 유지 → 몸에 지니고 있을 때') */
    private fun openLockScreenSettings() {
        Toast.makeText(this, "'잠금 해제 유지' → '몸에 지니고 있을 때'를 켜세요", Toast.LENGTH_LONG).show()
        try { startActivity(Intent("android.settings.LOCK_SCREEN_SETTINGS")) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    private fun openBattery() {
        val pkg = Uri.parse("package:$packageName")
        val tries = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (i in tries) {
            if (i.resolveActivity(packageManager) != null) {
                try {
                    startActivity(i)
                    if (i.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        Toast.makeText(this, "배터리 → '제한 없음'을 선택하세요", Toast.LENGTH_LONG).show()
                    return
                } catch (_: Exception) {}
            }
        }
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<Pair<String, String>> = try {
        getSystemService(BluetoothManager::class.java).adapter.bondedDevices.map { (it.name ?: "?") to it.address }
    } catch (e: SecurityException) { emptyList() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen(p: Prefs) {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("대기 중") }
        var busy by remember { mutableStateOf(false) }
        // 누른 표시: 저장된 값에서 읽음 (기아 앱 갔다 와도 유지, 위젯에서 누른 것도 표시) — 2분간
        var tapKick by remember { mutableStateOf(0) }
        var showLog by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }

        // 설정 화면에서 돌아올 때마다 상태 새로고침
        var tick by remember { mutableStateOf(0) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
            owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
        }
        val acc = remember(tick) { KiaAutoService.isConnected }
        // ★ 설정엔 '켜짐'인데 기기가 서비스를 멈춰 둔(비정상 종료) 경우가 있음 → '꺼짐'과 구분해서 안내
        val accEnabled = remember(tick) { accEnabledInSettings() }
        val graceUntil = remember { System.currentTimeMillis() + 15_000 }
        val accConnecting = accEnabled && !acc && System.currentTimeMillis() < graceUntil
        val accStuck = accEnabled && !acc && !accConnecting
        val bat = remember(tick) { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }
        var demo by remember { mutableStateOf(p.demoMode) }
        val lastLine = remember(tick, status) { p.lastLog.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "아직 기록 없음" }
        val sig = remember(tick) { p.bleAddress.isNotBlank() }   // 차 신호 등록 여부 (처음 설치 시 꼭 필요)
        val allReady = acc && bat && !demo && sig

        // ── 업데이트 ──
        val upd by Updater.state.collectAsState()
        val hasUpd = remember(tick, upd) { Updater.hasUpdate(this@MainActivity) }
        // 앱을 열 때 1시간 넘게 확인 안 했으면 확인 (설정에서 끈 경우 제외)
        LaunchedEffect(Unit) {
            if (p.autoUpdate && System.currentTimeMillis() - p.lastUpdCheck > 3_600_000) {
                withContext(Dispatchers.IO) { Updater.fetch(this@MainActivity) }; tick++
            }
        }
        // 설치 허용을 켜고 돌아오면 다시 시도할 수 있게
        LaunchedEffect(tick) { if (Updater.state.value is Updater.St.NeedPermission && Updater.canInstall(this@MainActivity)) Updater.state.value = Updater.St.Idle }
        // 알림을 눌러 들어온 경우: 바로 다운로드 시작
        LaunchedEffect(wantUpdate) {
            if (wantUpdate) {
                wantUpdate = false
                if (!Updater.hasUpdate(this@MainActivity)) withContext(Dispatchers.IO) { Updater.fetch(this@MainActivity) }
                tick++
                if (Updater.hasUpdate(this@MainActivity)) Updater.start(this@MainActivity)
            }
        }

        fun run(label: String, f: suspend (KiaApi) -> String) = scope.launch {
            busy = true; status = "$label 요청 중…"
            status = try { f(KiaApi.get(p)) } catch (e: Exception) { "오류: ${e.message}" }
            p.log("[수동] $label → $status"); busy = false; tick++
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 헤더 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("다가키", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Txt)
                Spacer(Modifier.width(8.dp))
                Text("EV6", fontSize = 14.sp, color = Blue, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconPill("⚙") { settingsPage = null; showSettings = true }
            }

            // ── 새 버전 배너 ──
            if (hasUpd) Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Blue.copy(alpha = 0.16f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("새 버전 ${p.updTag}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Txt)
                            Text("현재 v${Updater.installedVersion(this@MainActivity)}", fontSize = 12.sp, color = Sub)
                        }
                        when (val u = upd) {
                            is Updater.St.Idle, is Updater.St.Failed ->
                                Button({ Updater.start(this@MainActivity) }, shape = RoundedCornerShape(14.dp)) {
                                    Text(if (u is Updater.St.Failed) "다시 시도" else "업데이트", fontWeight = FontWeight.Bold)
                                }
                            is Updater.St.NeedPermission ->
                                Button({ Updater.openInstallPermission(this@MainActivity) }, shape = RoundedCornerShape(14.dp)) { Text("허용하러 가기") }
                            else -> {}
                        }
                    }
                    when (val u = upd) {
                        is Updater.St.Downloading -> {
                            LinearProgressIndicator({ u.pct / 100f }, Modifier.fillMaxWidth())
                            Text("내려받는 중 ${u.pct}%", fontSize = 13.sp, color = Txt)
                        }
                        is Updater.St.Installing -> Text("설치 화면이 뜨면 '설치(업데이트)'를 눌러주세요", fontSize = 13.sp, color = Txt)
                        is Updater.St.NeedPermission -> Text("먼저 '이 앱에서 설치 허용'을 켜야 해요. 켠 뒤 돌아오면 다시 누르세요", fontSize = 13.sp, color = Txt)
                        is Updater.St.Failed -> Text(u.msg, fontSize = 13.sp, color = Red)
                        else -> Text("누르면 내려받아 바로 설치해요", fontSize = 13.sp, color = Sub)
                    }
                }
            }

            // ── 히어로: 지금 차 상태를 한눈에 ──
            val inCar = remember(tick) { p.inCar }
            var master by remember { mutableStateOf(p.masterOn) }
            var autoLockOn by remember { mutableStateOf(p.autoLock) }
            var approachOn by remember { mutableStateOf(p.approachUnlock) }
            val braked = remember(tick) { p.braked }
            fun applyAuto() {
                Notifier.persistent(this@MainActivity); DagaWidget.refresh(this@MainActivity, null)
                if (master && approachOn) BleNear.start(this@MainActivity, quiet = true) else BleNear.stop(this@MainActivity)
                if (!master || !autoLockOn) { KiaAutoService.instance?.stopLeaveWatch(); Jobs.cancelLock(this@MainActivity) }
            }
            val (heroTitle, heroSub, heroColor) = when {
                !master -> Triple("자동 꺼짐", "자동 잠금·열기를 쉬는 중 · 버튼·위젯은 동작해요", Sub)
                braked -> Triple("잠시 멈춤", "자동 동작이 반복돼 30분간 쉬어요", Red)
                accConnecting -> Triple("접근성 연결 중…", "잠시만 기다려 주세요", Blue)
                accStuck -> Triple("접근성이 멈췄어요", "껐다가 다시 켜면 돌아와요 (아래 빨간 항목)", Red)
                !allReady -> Triple("설정이 필요해요", "아래 빨간 항목을 눌러 켜주세요", Red)
                inCar -> Triple("주행 중", "운전 중에는 자동 잠금·열기를 멈춰요", Blue)
                else -> Triple("자동으로 지키는 중", "멀어지면 잠그고, 다가가면 열어요", Green)
            }
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Card1)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(heroColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painterResource(if (inCar) R.drawable.ic_btn_trunk else if (allReady) R.drawable.ic_btn_lock else R.drawable.ic_btn_unlock),
                                contentDescription = null, modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(heroTitle, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Txt)
                            Text(heroSub, fontSize = 13.sp, color = Sub)
                        }
                        // 전체 켜기/끄기
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            OnOffSwitch(master, {
                                master = it; p.masterOn = it
                                if (it) p.brakeUntil = 0
                                p.log(if (it) "다가키 자동 켜짐" else "다가키 자동 꺼짐 (수동 버튼·위젯만 동작)")
                                applyAuto(); tick++
                            })
                            Text(if (master) "자동 켜짐" else "자동 꺼짐", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (master) Green else Sub)
                        }
                    }
                    // 따로 켜고 끄기: 줄을 누르거나 오른쪽 스위치
                    val uM = remember(tick) { p.unlockMeters }
                    val lM = remember(tick) { p.lockMeters }
                    AutoRow(R.drawable.ic_btn_unlock, Blue.copy(alpha = 0.25f), "다가가면 열기",
                        if (approachOn) "약 ${uM}m 안으로 걸어오면 자동으로 열어요" else "다가가도 자동으로 열지 않아요",
                        approachOn, master) {
                        approachOn = it; p.approachUnlock = it
                        p.log("다가가면 열기 ${if (it) "켬" else "끔"}"); applyAuto()
                    }
                    AutoRow(R.drawable.ic_btn_lock, Green.copy(alpha = 0.22f), "멀어지면 잠금",
                        if (autoLockOn) "약 ${lM}m 이상 멀어지면 자동으로 잠가요" else "멀어져도 자동으로 잠그지 않아요",
                        autoLockOn, master) {
                        autoLockOn = it; p.autoLock = it
                        p.log("멀어지면 잠금 ${if (it) "켬" else "끔"}"); applyAuto()
                    }
                    if (inCar) Text("주행 중 — 자동 동작을 멈춰요", fontSize = 12.sp, color = Blue)
                }
            }

            // ── 빠른 제어: 3개 타일 ──
            // 방금 누른 버튼: 파란색으로 표시 + 위에 '✓ ○○ 요청함 · 시:분' (위젯과 동일한 방식)
            // tick(3초마다)으로 저장값을 다시 읽어 표시 갱신
            val (pressedBtn, pressedText) = remember(tick, tapKick) {
                val age = System.currentTimeMillis() - p.lastTapAt
                if (p.lastTap.isNotBlank() && age < 120_000) {
                    val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(p.lastTapAt))
                    p.lastTap to "✓ ${p.lastTap} 요청함 · $t"
                } else null to null
            }
            fun mark(what: String) {
                DagaWidget.tapped(this@MainActivity, what)   // 저장 + 위젯 표시
                tapKick++
            }
            Text(pressedText ?: status, fontSize = 14.sp, color = if (pressedText != null) Blue else Sub,
                fontWeight = if (pressedText != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val on = { w: String -> pressedBtn == w }
                BigButton("열기", R.drawable.ic_btn_unlock, if (on("열기")) Blue else Color(0xFF1B2B47), if (on("열기")) Color.White else Txt, Modifier.weight(1f), !busy) {
                    mark("열기"); run("열기") { it.unlock(); "열기 요청을 보냈어요" }
                }
                BigButton("잠금", R.drawable.ic_btn_lock, if (on("잠금")) Blue else Color(0xFF17312B), if (on("잠금")) Color.White else Txt, Modifier.weight(1f), !busy) {
                    mark("잠금"); run("잠금") { it.lock(); "잠금 요청을 보냈어요" }
                }
                BigButton("트렁크", R.drawable.ic_btn_trunk, if (on("트렁크")) Blue else Card2, if (on("트렁크")) Color.White else Txt, Modifier.weight(1f), !busy) {
                    if (!KiaAutoService.isConnected) { status = "접근성 권한을 먼저 켜주세요" }
                    else {
                        mark("트렁크")
                        status = "트렁크 요청 중… (차 근처에서만 가능)"
                        p.log("[수동] 트렁크 열기/닫기")
                        KiaAutoService.arm("trunk", "")
                        packageManager.getLaunchIntentForPackage("com.kia.oneapp.kr")?.let { startActivity(it) }
                    }
                }
            }
            Text("차 근처에선 디지털키, 멀리선 원격으로 동작 · 트렁크는 차 근처에서만",
                fontSize = 12.sp, color = Sub, modifier = Modifier.padding(start = 4.dp))

            // ── 준비 체크: 문제 있을 때만 표시 (모두 정상이면 숨김) ──
            if (!allReady && !accConnecting) Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card1)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (!acc && accStuck) {
                        CheckRow("접근성이 멈췄어요", false, "껐다 켜기") {
                            Toast.makeText(this@MainActivity, "다가키를 끄고 → 다시 켜세요", Toast.LENGTH_LONG).show()
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        Text("설정에는 켜져 있지만 기기가 서비스를 멈춰 둔 상태예요(앱이 갑자기 종료됐을 때 생겨요). 설정 › 접근성 › 설치된 앱 › 다가키를 한 번 끄고 다시 켜면 바로 돌아와요.",
                            fontSize = 12.sp, color = Sub, modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp))
                    } else if (!acc && !accConnecting) CheckRow("접근성 권한", false, "눌러서 켜기") {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (!bat) CheckRow("배터리 제한 없음", false, "눌러서 해제") { openBattery() }
                    if (!sig) CheckRow("차 신호 등록 (차 옆에서)", false, "눌러서 등록") { settingsPage = "차 신호 등록"; showSettings = true }
                    if (demo) CheckRow("실제 차량 제어", false, "테스트 모드 · 눌러서 끄기") { demo = false; p.demoMode = false }
                }
            }
            // ── 주머니 속 실패가 최근 2주 안에 있었으면: '잠금 해제 유지' 안내 카드 ──
            // '이미 켰어요'를 누른 뒤에 또 주머니 속 실패가 생겼을 때만 다시 보여줌 (켰는데도 계속 떠 있던 문제 해결)
            val pocketFail = remember(tick) {
                p.lastPocketFailAt > p.pocketDoneAt && System.currentTimeMillis() - p.lastPocketFailAt < 14L * 24 * 3600_000
            }
            if (pocketFail) Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card1)) {
                Column(Modifier.fillMaxWidth()) {
                    CheckRow("주머니 속에서도 열리게 (잠금 해제 유지)", false, "설정 열기") { openLockScreenSettings() }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                        TextButton({ p.pocketDoneAt = System.currentTimeMillis(); tick++ }) { Text("이미 켰어요 (안내 숨기기)", color = Sub) }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 최근 활동 ──
            val recent = remember(tick, status) {
                p.lastLog.lineSequence().filter { it.isNotBlank() && !it.contains("대기중") && !it.contains("기다리는 중") }
                    .take(2).map { it.drop(6) }.toList()   // 날짜 떼고 '시:분:초 내용'
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card1)
                    .clickable { showLog = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 활동", fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
                    Text("전체 보기 ›", fontSize = 12.sp, color = Blue, fontWeight = FontWeight.SemiBold)
                }
                if (recent.isEmpty()) Text("아직 기록 없음", fontSize = 13.sp, color = Txt)
                recent.forEach { Text(it, fontSize = 13.sp, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        // 화면이 켜져 있는 동안 3초마다 상태 갱신 (주행/주차 표시 등)
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); tick++ } }

        // ── 기록 시트 ──
        if (showLog) ModalBottomSheet(onDismissRequest = { showLog = false }, containerColor = Card1,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("활동 기록", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Txt)
                    Spacer(Modifier.weight(1f))
                    TextButton({
                        // 문제 분석용: 상세 기록 파일 뒷부분을 공유 (카톡·메일 등)
                        val f = java.io.File(filesDir, "app.log")
                        val body = if (f.exists()) f.readText().takeLast(60_000) else p.lastLog
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "다가키 기록 v${Updater.installedVersion(this@MainActivity)}")
                            .putExtra(Intent.EXTRA_TEXT, body), "기록 보내기"))
                    }) { Text("공유", color = Blue) }
                    TextButton({ p.lastLog = ""; tick++; showLog = false }) { Text("지우기", color = Red) }
                }
                Column(Modifier.fillMaxHeight(0.85f).verticalScroll(rememberScrollState())) {
                    p.lastLog.lineSequence().filter { it.isNotBlank() }.forEach {
                        Text(it, fontSize = 13.sp, color = Txt, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        }

        // ── 설정 시트 ──
        // ── 설정: 전체 화면 (목록 → 항목 화면) ──
        if (showSettings) {
            val back = { if (settingsPage != null) settingsPage = null else { showSettings = false; tick++ } }
            androidx.activity.compose.BackHandler { back() }
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    // 상단 바: ← + 제목
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable { back() }, contentAlignment = Alignment.Center) {
                            Text("←", fontSize = 24.sp, color = Txt)
                        }
                        Text(if (settingsPage == null) "설정" else "설정 › ${settingsPage}", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = if (settingsPage == null) Txt else Sub)
                    }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) { Advanced(p) }
                }
            }
        }
    }

    @Composable
    private fun Chip(label: String, on: Boolean, enabled: Boolean = true, icon: Int = 0, onClick: (() -> Unit)? = null) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (on) Green.copy(alpha = 0.14f) else Card2)
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != 0) Image(painterResource(icon), null, Modifier.size(18.dp),
                alpha = if (on) 1f else 0.4f)
            else Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Green else Sub))
            Spacer(Modifier.width(6.dp))
            Text(if (onClick != null) "$label ${if (on) "ON" else "OFF"}" else label,
                fontSize = 12.sp, color = if (on) Txt else Sub, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun BigButton(label: String, icon: Int, bg: Color, fg: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
        Button(
            onClick, modifier.height(110.dp), enabled = enabled, shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(icon), contentDescription = label, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun CheckRow(title: String, ok: Boolean, action: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (ok) Green else Red),
                contentAlignment = Alignment.Center
            ) { Text(if (ok) "✓" else "!", fontSize = 13.sp, color = Bg, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 15.sp, color = Txt, modifier = Modifier.weight(1f))
            Text(action + " ›", fontSize = 13.sp, color = if (ok) Sub else Red, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun IconPill(t: String, onClick: () -> Unit) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Card1).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(t, fontSize = 18.sp, color = Txt) }
    }


    /** 설정에서 접근성이 '켜짐'으로 되어 있는지 (서비스가 실제로 붙어 있는지와는 별개) */
    private fun accEnabledInSettings(): Boolean =
        (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.startsWith("$packageName/") }

    /** 켜짐=초록 트랙+흰 손잡이 / 꺼짐=어두운 트랙+테두리 — 한눈에 구분 */
    @Composable
    private fun OnOffSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Green, checkedBorderColor = Green,
            uncheckedThumbColor = Color(0xFFB8C2D0), uncheckedTrackColor = Card1, uncheckedBorderColor = Sub,
            disabledCheckedTrackColor = Green.copy(alpha = 0.35f), disabledUncheckedTrackColor = Card1,
            disabledUncheckedBorderColor = Sub.copy(alpha = 0.4f)))
    }

    /** 자동 기능 한 줄: [아이콘] 제목 / 설명 …… [스위치 + 켜짐·꺼짐] — 줄 전체를 눌러도 전환 */
    @Composable
    private fun AutoRow(icon: Int, iconBg: Color, title: String, sub: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        val active = on && enabled
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card2)
            .clickable(enabled = enabled) { onChange(!on) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                Image(painterResource(icon), null, Modifier.size(22.dp), alpha = if (active) 1f else 0.4f)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (enabled) Txt else Sub)
                Text(sub, fontSize = 12.sp, color = if (active) Green else Sub)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OnOffSwitch(on, onChange, enabled)
                Text(if (on) "켜짐" else "꺼짐", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (active) Green else Sub)
            }
        }
    }

    // 설정 화면: 목록(한 화면) → 항목을 누르면 그 항목만 전체 화면으로
    private var settingsPage by mutableStateOf<String?>(null)

    @Composable
    private fun Section(title: String, sub: String, open0: Boolean = false, icon: Int = 0, iconBg: Color = Card1,
                        emoji: String = "", content: @Composable ColumnScope.() -> Unit) {
        val cur = settingsPage
        if (cur == title) {   // 상세 화면
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != 0) Box(Modifier.size(48.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                        Image(painterResource(icon), null, Modifier.size(28.dp))
                    } else Text(emoji, fontSize = 30.sp)
                    Spacer(Modifier.width(14.dp))
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Txt)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card2).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
            return
        }
        if (cur != null) return
        // 목록 줄
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card2)) {
            Row(Modifier.fillMaxWidth().clickable { settingsPage = title }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (icon == 0 && emoji.isNotEmpty()) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
                    Spacer(Modifier.width(12.dp))
                }
                // 열기·잠금은 앱 버튼과 같은 그림(열린 자물쇠 / 닫힌 자물쇠) + 다른 바탕색으로 한눈에 구분
                if (icon != 0) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                        Image(painterResource(icon), null, Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Txt)
                    Text(sub, fontSize = 12.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›", fontSize = 26.sp, color = Sub)
            }
        }
    }

    /**
     * 숫자 설정 공통 방식 (거리·걸음·시간 모두 똑같이):
     *  − / + 로 1씩 · 가운데 숫자를 누르면 '자주 쓰는 값' 목록 + 직접 입력 → 바로 저장 + '✓ 저장됨'
     */
    @Composable
    private fun Stepper(label: String, value: Int, unit: String, min: Int, max: Int, presets: List<Int>, onSave: (Int) -> Unit) {
        var saved by remember { mutableStateOf(value) }   // 저장된 값
        var v by remember { mutableStateOf(value) }       // 고르는 중인 값 (저장 전)
        var savedAt by remember { mutableStateOf(0L) }
        var pick by remember { mutableStateOf(false) }
        LaunchedEffect(savedAt) { if (savedAt > 0) { kotlinx.coroutines.delay(2500); savedAt = 0 } }
        fun set(n: Int) { v = n.coerceIn(min, max) }      // 값만 바꿈 — '저장'을 눌러야 적용
        fun save() { onSave(v); saved = v; savedAt = System.currentTimeMillis() }
        val dirty = v != saved
        Column {
            Text(label, fontSize = 14.sp, color = Txt)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton({ set(v - 1) }, enabled = v > min, shape = CircleShape,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("−", fontSize = 22.sp) }
                // 숫자 칸: 누르면 목록/직접 입력
                Row(Modifier.padding(horizontal = 8.dp).width(112.dp).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Card1).clickable { pick = true },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("$v$unit", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Txt)
                    Text(" ▾", fontSize = 13.sp, color = Sub)
                }
                FilledTonalButton({ set(v + 1) }, enabled = v < max, shape = CircleShape,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("+", fontSize = 22.sp) }
                Spacer(Modifier.width(10.dp))
                if (dirty) Button({ save() }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("저장") }
                else if (savedAt > 0) Text("✓ 저장됨", fontSize = 13.sp, color = Green, fontWeight = FontWeight.SemiBold)
            }
            if (dirty) Text("아직 저장 안 됨 (지금 적용값: $saved$unit)", fontSize = 12.sp, color = Red,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (pick) {
            var typed by remember { mutableStateOf(v.toString()) }
            val tv = typed.toIntOrNull()
            val ok = tv != null && tv in min..max
            AlertDialog(
                onDismissRequest = { pick = false },
                containerColor = Card1,
                title = { Text(label, fontSize = 17.sp, color = Txt) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("자주 쓰는 값", fontSize = 13.sp, color = Sub)
                        // 목록: 3개씩 줄
                        presets.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { n ->
                                    val sel = n == v
                                    Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) Blue else Card2).clickable { set(n); typed = n.toString() },
                                        contentAlignment = Alignment.Center) {
                                        Text("$n$unit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                            color = if (sel) Color.White else Txt)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        Text("직접 입력 ($min~$max)", fontSize = 13.sp, color = Sub)
                        OutlinedTextField(typed, { typed = it.filter(Char::isDigit).take(3) },
                            singleLine = true, suffix = { Text(unit) }, isError = typed.isNotEmpty() && !ok,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { Button({ tv?.let { set(it); save() }; pick = false }, enabled = ok) { Text("저장") } },
                dismissButton = { TextButton({ pick = false }) { Text("취소") } }
            )
        }
    }

    @Composable
    private fun Advanced(p: Prefs) {
        val sc = rememberCoroutineScope()
        val ctx = this@MainActivity
        // 실시간 차 신호 (2초마다 갱신)
        var live by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); live++ } }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── 1. 다가가면 열기 ──
            var metersNow by remember { mutableStateOf(p.unlockMeters) }
            var stepsNow by remember { mutableStateOf(p.stepThreshold) }
            Section("다가가면 열기", "약 ${metersNow}m 안으로 걸어오면 디지털키로 열기", open0 = true,
                icon = R.drawable.ic_btn_unlock, iconBg = Blue.copy(alpha = 0.22f)) {
                val (nowTxt, nowColor) = remember(live) {
                    val age = (System.currentTimeMillis() - p.lastRssiAt) / 1000
                    when {
                        p.lastRssiAt == 0L || age > 60 -> "지금 차 신호: 안 잡힘 (멀거나 감시 쉼)" to Sub
                        else -> "지금 차 신호: ${BleNear.dist(p, p.lastRssi)} · ${age}초 전" to
                            (if (p.lastRssi >= p.bleRssi) Green else Txt)
                    }
                }
                Text(nowTxt, fontSize = 14.sp, color = nowColor, fontWeight = FontWeight.SemiBold)
                Stepper("이 거리 안으로 걸어오면 열기 (약)", p.unlockMeters, "m", 2, 30, listOf(3, 5, 8, 10, 12, 15, 20, 25, 30)) {
                    p.unlockMeters = it; metersNow = it; p.log("열기 거리 저장: 약 ${it}m (세기 ${p.bleRssi} 이상)")
                }
                val n = p.calSamples.split(",").count { it.isNotBlank() }
                Text("거리 자동 보정: 기록 ${n}개 · 차 옆(1m) 세기 ${p.bleRef1m}\n" +
                    "차에 타거나 차 옆에서 열기/잠금을 누를 때마다 그때 신호로 스스로 맞춰요. 쓸수록 정확해져요.",
                    fontSize = 12.sp, color = Sub)
                // 주머니 속(화면 꺼짐·잠김)에서도 열리게
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card1).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📱 주머니 속에서도 열리게", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Txt)
                    Text("화면이 꺼져 있어도 다가키가 화면을 켜요. 하지만 지문·PIN 잠금은 어떤 앱도 대신 풀 수 없어서, " +
                        "폰이 잠겨 있으면 기아 앱을 못 띄워요.\n→ 잠금 해제 유지 → '몸에 지니고 있을 때'를 켜면, 폰을 한 번 풀고 주머니에 넣은 뒤로는 " +
                        "몸에 지니는 동안 잠기지 않아 주머니 속에서도 열려요. (내려놓으면 다시 잠김)", fontSize = 12.sp, color = Sub)
                    OutlinedButton({ openLockScreenSettings() }, Modifier.fillMaxWidth()) { Text("잠금 해제 유지 설정 열기") }
                }
                var remoteOn by remember { mutableStateOf(p.approachRemote) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("멀리서 감지됐을 때 원격으로도 열기", fontSize = 14.sp, color = Txt)
                        Text("끔(권장): 처음 감지(약 ${metersNow}m)에서는 디지털키로만 열어요 — 멀리서 열리는 일 없음", fontSize = 12.sp, color = Sub)
                    }
                    OnOffSwitch(remoteOn, { remoteOn = it; p.approachRemote = it; p.log("멀리서 원격 열기 ${if (it) "허용" else "끔"}") })
                }
                var arrRemote by remember { mutableStateOf(p.arrivalRemote) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("차 바로 옆(약 2m)에서 안 열렸으면 원격으로도", fontSize = 14.sp, color = Txt)
                        Text("켬(권장): 다가오다가 차 바로 옆에 왔는데 디지털키로 아직 못 열었으면, 그때 원격으로도 한 번 열어요", fontSize = 12.sp, color = Sub)
                    }
                    OnOffSwitch(arrRemote, { arrRemote = it; p.arrivalRemote = it; p.log("도착 시 원격 보조 ${if (it) "켬" else "끔"}") })
                }
            }

            // ── 2. 멀어지면 잠금 ──
            Section("멀어지면 잠금", "내린 뒤 ${stepsNow}걸음 걸어서 멀어지면 잠금",
                icon = R.drawable.ic_btn_lock, iconBg = Green.copy(alpha = 0.18f)) {
                Stepper("이만큼 걸어서 멀어지면 잠금", p.stepThreshold, "걸음", 5, 200, listOf(5, 10, 15, 20, 25, 30, 40, 50, 70)) {
                    p.stepThreshold = it; stepsNow = it; p.log("설정 저장: 잠금 걸음 ${it}걸음")
                }
                Stepper("예비 확인 (실시간 감지를 놓쳤을 때)", p.lockDelayMin, "분", 1, 30, listOf(1, 2, 3, 5, 10, 15)) {
                    p.lockDelayMin = it; p.log("설정 저장: 예비 확인 ${it}분")
                }
                Stepper("이 거리 이상 멀어져야 잠금 (약)", p.lockMeters, "m", 3, 50, listOf(5, 8, 10, 15, 20, 30)) {
                    p.lockMeters = it; p.log("설정 저장: 잠금 거리 약 ${it}m (세기 ${BleNear.rssiAt(it.toDouble(), p.bleRef1m)} 이하)")
                }
                val liveLock = remember(live) {
                    val age = (System.currentTimeMillis() - p.lastRssiAt) / 1000
                    if (p.lastRssiAt == 0L || age > 60) "지금 차 신호: 안 잡힘 (멀거나 감시 쉼)" else "지금 차 신호: ${BleNear.dist(p, p.lastRssi)} · ${age}초 전"
                }
                Text(liveLock, fontSize = 13.sp, color = Sub)
                Text("걸음 수와 거리가 둘 다 넘어야 잠겨요. 차 주변(트렁크 등)을 돌아다녀도 안 잠겨요. " +
                    "가만히 있을 때 조금씩 움직인 걸음은 세지 않아요.", fontSize = 12.sp, color = Sub)
                var holdNow by remember { mutableStateOf(p.trunkHoldMin) }
                Stepper("트렁크를 쓴 뒤 자동 잠금을 쉬는 시간", p.trunkHoldMin, "분", 0, 60, listOf(0, 3, 5, 10, 15, 30)) {
                    p.trunkHoldMin = it; holdNow = it; p.log("설정 저장: 트렁크 후 자동 잠금 보류 ${it}분")
                }
                Text(if (holdNow == 0) "0분: 트렁크를 써도 멀어지면(걸음+거리) 바로 잠겨요"
                     else "트렁크 버튼을 누르면 ${holdNow}분 동안은 멀어져도 잠그지 않아요", fontSize = 12.sp, color = Sub)
                var lockRemote by remember { mutableStateOf(p.leaveRemote) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("디지털키가 끊겨도 원격으로 잠금", fontSize = 14.sp, color = Txt)
                        Text("켬(권장): 멀어지면 디지털키가 끊기므로 원격으로 확실히 잠가요", fontSize = 12.sp, color = Sub)
                    }
                    OnOffSwitch(lockRemote, { lockRemote = it; p.leaveRemote = it; p.log("자동 잠금 원격 ${if (it) "허용" else "끔"}") })
                }
            }

            // ── 3. 차 연결 ──
            Section("차 신호 등록", if (p.bleAddress.isBlank()) "아직 등록 안 됨 — 차 옆에서 눌러 등록" else "등록됨 · ${p.carBtName.ifBlank { "오디오: EV6" }}", emoji = "🚗",
                open0 = p.bleAddress.isBlank()) {
                var bleList by remember { mutableStateOf(listOf<String>()) }
                var bleSel by remember { mutableStateOf(p.bleAddress) }
                Button({
                    bleList = listOf("10초간 주변 신호 찾는 중… (차 바로 옆에서)")
                    BleNear.discover(ctx) { r ->
                        bleList = r.ifEmpty { listOf("잡힌 신호 없음 — 블루투스/위치 켜졌는지 확인") }
                        p.log("신호 찾기 결과 ${r.size}개"); r.take(15).forEach { p.log("  $it") }
                    }
                }, Modifier.fillMaxWidth()) { Text("차 옆에서 신호 찾기 (10초)") }
                Text("선택된 신호: ${bleSel.ifBlank { "없음" }}", fontSize = 13.sp, color = Txt)
                // 주소를 이미 알면 직접 입력 (재설치·가족 폰에서 차에 안 가도 등록)
                var manualMac by remember { mutableStateOf("") }
                val macOk = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(manualMac)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(manualMac, { manualMac = it.uppercase().filter { c -> c.isLetterOrDigit() || c == ':' }.take(17) },
                        label = { Text("신호 주소 직접 입력 (예: AA:BB:CC:DD:EE:FF)") }, singleLine = true,
                        isError = manualMac.isNotEmpty() && !macOk, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button({
                        p.bleAddress = manualMac; bleSel = manualMac; p.log("차 신호 직접 등록: $manualMac"); manualMac = ""
                        Toast.makeText(ctx, "✓ 차 신호를 등록했어요", Toast.LENGTH_SHORT).show()
                        BleNear.start(ctx, quiet = true)
                    }, enabled = macOk) { Text("저장") }
                }
                // 가족과 공유: 보내기 / 받은 정보 붙여넣기
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ shareCar(p) }, Modifier.weight(1f), enabled = bleSel.isNotBlank()) { Text("가족에게 공유") }
                    Button({
                        val clip = (getSystemService(android.content.ClipboardManager::class.java))
                            ?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                        if (importCar(p, clip, "붙여넣기")) bleSel = p.bleAddress
                        else Toast.makeText(ctx, "복사한 글에서 차 신호를 못 찾았어요. 받은 메시지를 먼저 복사하세요", Toast.LENGTH_LONG).show()
                    }, Modifier.weight(1f)) { Text("받은 정보 붙여넣기") }
                }
                Text("가족 폰: 받은 메시지의 링크를 누르거나, 메시지를 복사한 뒤 '받은 정보 붙여넣기' → 차에 안 가도 바로 등록돼요",
                    fontSize = 12.sp, color = Sub)
                if (bleList.isNotEmpty()) Text("맨 위(가장 센 신호)를 누르세요", fontSize = 12.sp, color = Sub)
                bleList.forEach { line ->
                    val mac = line.substringBefore(" ")
                    TextButton({ if (mac.count { it == ':' } == 5) {
                        p.bleAddress = mac; bleSel = mac; bleList = emptyList()
                        Regex("세기=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                            p.bleRef1m = it.coerceIn(-80, -40); p.calSamples = "${p.bleRef1m}"
                            p.log("거리 기준 저장: 차 옆 세기 ${p.bleRef1m}")
                        }
                        Toast.makeText(ctx, "✓ 차 신호를 등록했어요", Toast.LENGTH_SHORT).show()
                        BleNear.start(ctx, quiet = true)
                    } }) { Text(line, fontSize = 12.sp) }
                }
                var showBt by remember { mutableStateOf(false) }
                var btName by remember { mutableStateOf(p.carBtName) }
                Text("차량 블루투스(오디오): ${btName.ifBlank { "자동 (이름에 EV6)" }}", fontSize = 13.sp, color = Txt)
                OutlinedButton({ showBt = !showBt }, Modifier.fillMaxWidth()) { Text("차량 블루투스 직접 선택") }
                if (showBt) bondedDevices().forEach { (nm, a) ->
                    TextButton({ p.carBtName = nm; p.carBtAddress = a; btName = nm; showBt = false }) { Text("$nm ($a)") }
                }
            }

            // ── 4. 배터리 절약 ──
            Section("배터리 절약", "집·회사 와이파이 ${PowerSaver.homeList(p).size}곳에서 감시 쉼", emoji = "🔋") {
                var ms by remember { mutableStateOf(p.motionSave) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("5분간 가만히 있으면 절전 감시", fontSize = 14.sp, color = Txt, modifier = Modifier.weight(1f))
                    OnOffSwitch(ms, { ms = it; p.motionSave = it; if (!it) { p.bleActive = true; BleNear.start(ctx, quiet = true) } })
                }
                Text("이 와이파이에 연결돼 있으면 차 감시 쉼 (여러 개 체크)", fontSize = 14.sp, color = Txt)
                var homes by remember { mutableStateOf(PowerSaver.homeList(p)) }
                val cur = PowerSaver.currentSsid
                val all = (listOfNotNull(cur.ifEmpty { null }) + homes + PowerSaver.history(p)).distinct()
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card1)) {
                    if (all.isEmpty()) Text("아직 기록 없음 — 와이파이에 연결되면 여기 나타나요",
                        fontSize = 13.sp, color = Sub, modifier = Modifier.padding(14.dp))
                    all.forEach { s ->
                        val on = s in homes
                        val toggle = { if (on) PowerSaver.removeHome(p, s) else PowerSaver.addHome(p, s); homes = PowerSaver.homeList(p) }
                        Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { toggle() })
                            Text(s, fontSize = 14.sp, color = Txt, modifier = Modifier.weight(1f))
                            if (s == cur) Text("연결됨", fontSize = 12.sp, color = Green, modifier = Modifier.padding(end = 10.dp))
                        }
                    }
                }
                var manual by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(manual, { manual = it }, label = { Text("와이파이 이름 직접 입력") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton({ PowerSaver.addHome(p, manual); manual = ""; homes = PowerSaver.homeList(p) }, enabled = manual.isNotBlank()) { Text("추가") }
                }
                Text("※ 주차장까지 잡히는 와이파이는 넣지 마세요 — 연결된 동안 다가가도 안 열려요", fontSize = 12.sp, color = Sub)
            }

            // ── 5. 업데이트 ──
            // ── 위젯: 버튼을 끌어서 칸에 넣기 (1~3개 자유) ──
            var wSaved by remember { mutableStateOf(DagaWidget.order(p)) }
            fun wDesc(o: List<String>) = o.filter { it.isNotEmpty() }.joinToString(" · ") { DagaWidget.label(it) }.ifEmpty { "비어 있음" }
            Section("위젯", "버튼: " + wDesc(wSaved), emoji = "🧩") {
                var slots by remember { mutableStateOf(wSaved) }
                val slotRects = remember { mutableStateMapOf<Int, Rect>() }
                var dragKey by remember { mutableStateOf<String?>(null) }
                var dragStart by remember { mutableStateOf(Offset.Zero) }
                var dragDelta by remember { mutableStateOf(Offset.Zero) }
                var hover by remember { mutableStateOf(-1) }
                fun put(k: String, i: Int) { slots = slots.map { if (it == k) "" else it }.toMutableList().also { it[i] = k } }

                Text("아래 버튼을 끌어서 위젯 칸에 놓으세요", fontSize = 14.sp, color = Txt)
                // 위젯 미리보기 (실제 위젯과 같은 모양)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Bg).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_btn_unlock), null, Modifier.size(16.dp))   // (적응형 앱 아이콘은 그릴 수 없음)
                        Text(" 다가키", fontSize = 12.sp, color = Txt, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("앱 열기 ›", fontSize = 11.sp, color = Sub)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        slots.forEachIndexed { i, k ->
                            val hot = hover == i
                            Box(Modifier.weight(1f).height(60.dp)
                                .onGloballyPositioned { slotRects[i] = it.boundsInRoot() }
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (hot) Blue.copy(alpha = 0.35f) else if (k.isEmpty()) Card1 else Card2)
                                .border(2.dp, if (hot) Blue else if (k.isEmpty()) Sub.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable(enabled = k.isNotEmpty()) { slots = slots.toMutableList().also { it[i] = "" } },
                                contentAlignment = Alignment.Center) {
                                if (k.isEmpty()) Text("빈 칸", fontSize = 12.sp, color = Sub)
                                else Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(painterResource(DagaWidget.icon(k)), null, Modifier.size(20.dp))
                                    Text(" " + DagaWidget.label(k), fontSize = 13.sp, color = Txt, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Text("칸을 누르면 비워져요 · 빈 칸은 위젯에서 사라지고 남은 버튼이 넓어져요", fontSize = 12.sp, color = Sub)

                // 끌어다 놓을 버튼들 (눌러도 첫 빈 칸에 들어감)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DagaWidget.KEYS.forEach { k ->
                        var origin by remember { mutableStateOf(Offset.Zero) }
                        val dragging = dragKey == k
                        Box(Modifier.weight(1f).height(56.dp)
                            .zIndex(if (dragging) 1f else 0f)
                            .onGloballyPositioned { origin = it.boundsInRoot().center }
                            .graphicsLayer { if (dragging) { translationX = dragDelta.x; translationY = dragDelta.y; scaleX = 1.08f; scaleY = 1.08f } }
                            .clip(RoundedCornerShape(14.dp)).background(if (dragging) Blue else Card1)
                            .pointerInput(k) {
                                detectDragGestures(
                                    onDragStart = { dragKey = k; dragStart = origin; dragDelta = Offset.Zero },
                                    onDrag = { ch, amt ->
                                        ch.consume(); dragDelta += amt
                                        val at = dragStart + dragDelta
                                        hover = slotRects.entries.firstOrNull { it.value.contains(at) }?.key ?: -1
                                    },
                                    onDragEnd = { if (hover >= 0) put(k, hover); dragKey = null; hover = -1; dragDelta = Offset.Zero },
                                    onDragCancel = { dragKey = null; hover = -1; dragDelta = Offset.Zero })
                            }
                            .clickable { val e = slots.indexOfFirst { it.isEmpty() }; if (e >= 0) put(k, e) },
                            contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(DagaWidget.icon(k)), null, Modifier.size(20.dp))
                                Text(" " + DagaWidget.label(k), fontSize = 13.sp, color = Txt, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        p.widgetOrder = slots.joinToString(","); wSaved = slots
                        DagaWidget.refresh(ctx, null); p.log("위젯 저장: " + wDesc(slots))
                        Toast.makeText(ctx, "✓ 위젯을 저장했어요", Toast.LENGTH_SHORT).show()
                    }, enabled = slots != wSaved) { Text("저장") }
                    Spacer(Modifier.width(12.dp))
                    Text(if (slots != wSaved) "아직 저장 안 됨" else "✓ 저장됨", fontSize = 13.sp, color = if (slots != wSaved) Red else Green)
                    Spacer(Modifier.weight(1f))
                    TextButton({ slots = DagaWidget.KEYS }) { Text("기본값") }
                }
                Text("위젯 추가: 홈 화면 빈 곳을 길게 누르기 → 위젯 → 다가키 (3×1). 예전 위젯은 지우고 다시 추가하세요.",
                    fontSize = 12.sp, color = Sub)
            }

            Section("업데이트", "현재 v${Updater.installedVersion(ctx)}", emoji = "⬆️") {
                var au by remember { mutableStateOf(p.autoUpdate) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("새 버전 자동 확인 (12시간마다)", fontSize = 14.sp, color = Txt)
                        Text("인터넷은 GitHub에서 새 버전 확인·다운로드에만 써요", fontSize = 12.sp, color = Sub)
                    }
                    OnOffSwitch(au, { au = it; p.autoUpdate = it; Updater.schedule(ctx) })
                }
                var chk by remember { mutableStateOf("") }
                OutlinedButton({
                    chk = "확인 중…"
                    sc.launch {
                        val ok = withContext(Dispatchers.IO) { Updater.fetch(ctx) }
                        chk = when {
                            !ok -> "확인 실패 — 인터넷 연결을 확인하세요"
                            Updater.hasUpdate(ctx) -> "새 버전 ${p.updTag} 이 있어요 — 첫 화면 위쪽 '업데이트'를 누르세요"
                            else -> "✓ 최신 버전이에요"
                        }
                    }
                }, Modifier.fillMaxWidth()) { Text("지금 새 버전 확인") }
                if (chk.isNotEmpty()) Text(chk, fontSize = 13.sp, color = Txt)
            }

            // ── 6. 문제 해결 ──
            Section("문제 해결", "자가 진단 · 테스트 · 기아 앱 학습", emoji = "🛠️") {
                Button({ KiaAutoService.instance?.selfTest() ?: p.log("자가 진단: 접근성이 꺼져 있음"); Toast.makeText(ctx, "진단 시작 — 결과는 '최근 활동'에", Toast.LENGTH_SHORT).show() },
                    Modifier.fillMaxWidth()) { Text("자가 진단 (결과는 활동 기록에)") }
                OutlinedButton({ KiaAutoService.instance?.testLeaveWatch() ?: p.log("접근성이 꺼져 있음") },
                    Modifier.fillMaxWidth()) { Text("멀어짐 잠금 테스트 (실제로 잠금 요청됨)") }
                OutlinedButton({
                    if (!KiaAutoService.isConnected) p.log("접근성부터 켜세요")
                    else { KiaAutoService.arm("learn", ""); packageManager.getLaunchIntentForPackage("com.kia.oneapp.kr")?.let { startActivity(it) } }
                }, Modifier.fillMaxWidth()) { Text("기아 앱 화면 학습 (90초)") }
                var kpin by remember { mutableStateOf(p.kiaPin) }
                OutlinedTextField(kpin, { kpin = it; p.kiaPin = it }, label = { Text("원격제어 PIN (필요할 때만)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
