package com.glory.ev6lock

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
import kotlinx.coroutines.launch

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
        p.autoLock = true; p.approachUnlock = true; p.autoUnlock = true; p.useAppAutomation = true
        Notifier.persistent(this)
        setContent {
            MaterialTheme(colorScheme = EvScheme) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Screen(p) }
            }
        }
    }

    /** 배터리 '제한 없음' 화면으로 이동 (삼성은 요청창이 막히는 경우가 있어 단계별로 시도) */
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
        val bat = remember(tick) { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }
        var demo by remember { mutableStateOf(p.demoMode) }
        val lastLine = remember(tick, status) { p.lastLog.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "아직 기록 없음" }
        val sig = remember(tick) { p.bleAddress.isNotBlank() }   // 차 신호 등록 여부 (처음 설치 시 꼭 필요)
        val allReady = acc && bat && !demo && sig

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
                IconPill("⚙") { showSettings = true }
            }

            // ── 히어로: 지금 차 상태를 한눈에 ──
            val inCar = remember(tick) { p.inCar }
            val (heroTitle, heroSub, heroColor) = when {
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
                        Column {
                            Text(heroTitle, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Txt)
                            Text(heroSub, fontSize = 13.sp, color = Sub)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("자동 잠금", allReady && !inCar)
                        Chip("자동 열기", allReady && !inCar)
                        Chip(if (inCar) "주행 감지" else "차 감시 중", allReady)
                    }
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
                BigButton("열기", R.drawable.ic_btn_unlock, if (on("열기")) Blue else Card2, if (on("열기")) Color.White else Txt, Modifier.weight(1f), !busy) {
                    mark("열기"); run("열기") { it.unlock(); "열기 요청을 보냈어요" }
                }
                BigButton("잠금", R.drawable.ic_btn_lock, if (on("잠금")) Blue else Card2, if (on("잠금")) Color.White else Txt, Modifier.weight(1f), !busy) {
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
            if (!allReady) Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card1)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (!acc) CheckRow("접근성 권한", false, "눌러서 켜기") {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (!bat) CheckRow("배터리 제한 없음", false, "눌러서 해제") { openBattery() }
                    if (!sig) CheckRow("차 신호 등록 (차 옆에서)", false, "눌러서 등록") { showSettings = true }
                    if (demo) CheckRow("실제 차량 제어", false, "테스트 모드 · 눌러서 끄기") { demo = false; p.demoMode = false }
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
        if (showLog) ModalBottomSheet(onDismissRequest = { showLog = false }, containerColor = Card1) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("활동 기록", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Txt)
                    Spacer(Modifier.weight(1f))
                    TextButton({ p.lastLog = ""; tick++; showLog = false }) { Text("지우기", color = Red) }
                }
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    p.lastLog.lineSequence().filter { it.isNotBlank() }.forEach {
                        Text(it, fontSize = 13.sp, color = Txt, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        }

        // ── 설정 시트 ──
        if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Card1) {
            Column(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Txt)
                Advanced(p)
            }
        }
    }

    @Composable
    private fun Chip(label: String, on: Boolean) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (on) Green.copy(alpha = 0.14f) else Card2)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Green else Sub))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = if (on) Txt else Sub, fontWeight = FontWeight.SemiBold)
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

    @Composable
    private fun Advanced(p: Prefs) {
        var showBt by remember { mutableStateOf(false) }
        var showBle by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ KiaAutoService.instance?.selfTest() ?: p.log("자가 진단: 접근성이 꺼져 있음") },
                Modifier.fillMaxWidth()) { Text("자가 진단 (기록에 결과 표시)") }
            OutlinedButton({ KiaAutoService.instance?.testLeaveWatch() ?: p.log("접근성이 꺼져 있음") },
                Modifier.fillMaxWidth()) { Text("멀어짐 잠금 테스트 (실제로 잠금 요청됨)") }
            Text("다가감 감지", fontSize = 14.sp, color = Sub)
            var bleList by remember { mutableStateOf(listOf<String>()) }
            var bleSel by remember { mutableStateOf(p.bleAddress) }
            Button({
                showBle = true; bleList = listOf("10초간 주변 신호 찾는 중… (차 바로 옆에서)")
                BleNear.discover(this@MainActivity) { r ->
                    bleList = r.ifEmpty { listOf("잡힌 신호 없음 — 블루투스/위치 켜졌는지 확인") }
                    p.log("신호 찾기 결과 ${r.size}개"); r.take(15).forEach { p.log("  $it") }
                }
            }, Modifier.fillMaxWidth()) { Text("차 옆에서 신호 찾기 (10초)") }
            Text("선택된 접근 신호: ${bleSel.ifBlank { "자동(표준 디지털키)" }}", fontSize = 13.sp, color = Txt)
            if (showBle) bleList.forEach { line ->
                val mac = line.substringBefore(" ")
                TextButton({ if (mac.count { it == ':' } == 5) { p.bleAddress = mac; bleSel = mac; showBle = false } }) {
                    Text(line, fontSize = 12.sp)
                }
            }

            Text("차량 블루투스(오디오): ${p.carBtName.ifBlank { "자동(이름에 EV6)" }}", fontSize = 13.sp, color = Txt)
            OutlinedButton({ showBt = !showBt }, Modifier.fillMaxWidth()) { Text("차량 블루투스 직접 선택") }
            if (showBt) bondedDevices().forEach { (n, a) ->
                TextButton({ p.carBtName = n; p.carBtAddress = a; showBt = false }) { Text("$n ($a)") }
            }

            // ── 배터리 절약 ──
            Text("배터리 절약", fontSize = 14.sp, color = Sub)
            var ms by remember { mutableStateOf(p.motionSave) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(ms, { ms = it; p.motionSave = it; if (!it) { p.bleActive = true; BleNear.start(this@MainActivity, quiet = true) } })
                Spacer(Modifier.width(10.dp))
                Text("5분간 가만히 있으면 절전 감시 (걸으면 바로 빠른 감시)", fontSize = 14.sp, color = Txt)
            }
            Text("이 와이파이에 연결돼 있으면 차 감시 쉼 (집·회사·교회 등)", fontSize = 14.sp, color = Txt)
            Text("연결됐던 와이파이 중 체크하세요 (여러 개 가능)", fontSize = 12.sp, color = Sub)
            var homes by remember { mutableStateOf(PowerSaver.homeList(p)) }
            val cur = PowerSaver.currentSsid
            // 기록 + 이미 등록한 것 + 지금 연결된 것 → 한 목록에 체크박스로
            val all = (listOfNotNull(cur.ifEmpty { null }) + homes + PowerSaver.history(p)).distinct()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card2)) {
                if (all.isEmpty()) Text("아직 기록 없음 — 와이파이에 연결되면 여기 나타나요",
                    fontSize = 13.sp, color = Sub, modifier = Modifier.padding(14.dp))
                all.forEach { s ->
                    val on = s in homes
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (on) PowerSaver.removeHome(p, s) else PowerSaver.addHome(p, s)
                            homes = PowerSaver.homeList(p)
                        }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, {
                            if (on) PowerSaver.removeHome(p, s) else PowerSaver.addHome(p, s)
                            homes = PowerSaver.homeList(p)
                        })
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
                OutlinedButton({ PowerSaver.addHome(p, manual); manual = ""; homes = PowerSaver.homeList(p) }) { Text("추가") }
            }
            Text("※ 주차장까지 잡히는 와이파이(예: 회사 주차장)는 넣지 마세요 — 연결된 동안 다가가도 안 열려요",
                fontSize = 12.sp, color = Sub)

            Text("자동 잠금", fontSize = 14.sp, color = Sub)
            var steps by remember { mutableStateOf(p.stepThreshold.toString()) }
            OutlinedTextField(steps, { steps = it; it.toIntOrNull()?.let { v -> p.stepThreshold = v } },
                label = { Text("이만큼 걸어서 멀어지면 바로 잠금 (걸음)") }, modifier = Modifier.fillMaxWidth())
            var delay by remember { mutableStateOf(p.lockDelayMin.toString()) }
            OutlinedTextField(delay, { delay = it; it.toIntOrNull()?.let { v -> p.lockDelayMin = v } },
                label = { Text("예비 확인(분) — 실시간 감지를 놓쳤을 때만") }, modifier = Modifier.fillMaxWidth())

            Text("기아 앱 조작", fontSize = 14.sp, color = Sub)
            Button({
                if (!KiaAutoService.isConnected) p.log("접근성부터 켜세요")
                else {
                    KiaAutoService.arm("learn", "")
                    packageManager.getLaunchIntentForPackage("com.kia.oneapp.kr")?.let { startActivity(it) }
                }
            }, Modifier.fillMaxWidth()) { Text("기아 앱 화면 학습 (90초)") }
            var kpin by remember { mutableStateOf(p.kiaPin) }
            OutlinedTextField(kpin, { kpin = it; p.kiaPin = it }, label = { Text("원격제어 PIN (필요할 때만)") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
    }
}
