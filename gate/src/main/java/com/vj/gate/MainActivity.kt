package com.vj.gate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Gate asked for by a home-screen shortcut (long-press the app icon). */
    private val shortcutGate = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        History.load(this)
        shortcutGate.value = intent?.getStringExtra("gate")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7FD1FF), secondary = Color(0xFF4CD964))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF12161C)) {
                    GateApp(shortcutGate)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shortcutGate.value = intent.getStringExtra("gate")
    }
}

@Composable
fun GateApp(shortcutGate: MutableStateFlow<String?>) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Prefs.mode(ctx)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updater.check(ctx, silent = true) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🚪 Gate Remote", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        UpdateBar(onUpdate = { r -> scope.launch { Updater.download(ctx, r) } })

        when (mode) {
            Prefs.SENDER -> SenderScreen(shortcutGate)
            Prefs.RECEIVER -> ReceiverScreen()
            else -> ModePicker { Prefs.setMode(ctx, it); mode = it }
        }

        if (mode.isNotEmpty()) {
            HorizontalDivider()
            HistoryList()
            TextButton(onClick = { Prefs.setMode(ctx, ""); mode = "" }) {
                Text("Change what this phone does (currently: ${if (mode == Prefs.SENDER) "sender" else "gate phone"})")
            }
            TextButton(onClick = { scope.launch { Updater.check(ctx, silent = false) } }) {
                Text("Check app update · ${Updater.currentVersionName(ctx)}")
            }
        }
    }
}

@Composable
private fun ModePicker(onPick: (String) -> Unit) {
    Text("What does this phone do?", color = Color.White, fontSize = 18.sp)
    BigCard(
        "📱 Sends the open request",
        "Your phone. Tap a gate button here and it texts the gate phone.",
    ) { onPick(Prefs.SENDER) }
    BigCard(
        "🏠 Has the Gatewise app",
        "The gate phone. It reads the text, opens Gatewise and taps the gate, then texts back the result.",
    ) { onPick(Prefs.RECEIVER) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BigCard(title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(body, fontSize = 14.sp)
        }
    }
}

/* ----------------------------- Sender ----------------------------- */

@Composable
private fun SenderScreen(shortcutGate: MutableStateFlow<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(Prefs.target(ctx)) }
    var code by remember { mutableStateOf(Prefs.code(ctx)) }
    var sendLoc by remember { mutableStateOf(Prefs.sendLocation(ctx)) }
    var status by remember { mutableStateOf("") }
    val reply by SmsReceiver.lastReply.collectAsState()
    var perms by remember { mutableIntStateOf(0) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms++ }
    RefreshOnResume { perms++ }

    val needed = buildList {
        add(Manifest.permission.SEND_SMS); add(Manifest.permission.RECEIVE_SMS)
        if (sendLoc) add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val missing = remember(perms, sendLoc) { needed.filterNot { Phone.has(ctx, it) } }
    val ready = target.isNotBlank() && code.isNotBlank() && Manifest.permission.SEND_SMS !in missing

    fun send(gate: Gate?) {
        if (!ready) { status = "Fill in the gate phone number and code first, and allow SMS."; return }
        scope.launch {
            SmsReceiver.lastReply.value = null
            status = if (sendLoc) "📍 Getting your location…" else "Sending…"
            val loc = if (sendLoc) Phone.location(ctx)?.let { it.latitude to it.longitude } else null
            val text = Command.build(gate, code, loc)
            val ok = Phone.sendSms(ctx, target, text)
            val what = gate?.title ?: "Status check"
            status = if (ok) "📤 Sent: $what${if (sendLoc && loc == null) " (no location fix)" else ""}. Waiting for reply…" else "❌ Couldn't send the text."
            History.add(ctx, "📤 $what${if (loc != null) " (with location)" else ""}")
        }
    }

    // Home-screen shortcut → send right away
    val pending by shortcutGate.collectAsState()
    LaunchedEffect(pending, ready) {
        val k = pending ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        shortcutGate.value = null
        send(if (k == "STATUS") null else Gate.fromKey(k))
    }

    if (missing.isNotEmpty()) {
        Button(onClick = { askPerms.launch(missing.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Allow SMS${if (sendLoc) " + location" else ""}")
        }
        RestrictedHint()
    }

    Gate.entries.forEach { g ->
        Button(
            onClick = { send(g) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (g == Gate.ENTRANCE) Color(0xFF5B3A8C) else Color(0xFF1F4B5A), contentColor = Color.White)
        ) { Text("${if (g == Gate.ENTRANCE) "🚗" else "🚶"}  Open ${g.title}", fontSize = 20.sp) }
    }
    OutlinedButton(onClick = { send(null) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
        Text("Is the gate phone ready? (status)")
    }

    if (status.isNotEmpty()) Text(status, color = Color.White)
    reply?.let {
        Surface(color = if (it.startsWith("✅") || it.startsWith("🧪")) Color(0xFF1E4D2B) else Color(0xFF4D1E1E), shape = RoundedCornerShape(12.dp)) {
            Text(it, Modifier.padding(12.dp), color = Color.White)
        }
    }

    HorizontalDivider()
    Text("Settings", fontWeight = FontWeight.Bold, color = Color.White)
    OutlinedTextField(target, { target = it; Prefs.setTarget(ctx, it) }, label = { Text("Gate phone number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { v -> code = v.filter { !it.isWhitespace() }; Prefs.setCode(ctx, code) }, label = { Text("Secret code (same on both phones)") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    SwitchRow("Send my location (for the 250 m rule)", sendLoc) { sendLoc = it; Prefs.setSendLocation(ctx, it) }
    Text("Tip: long-press the Gate Remote icon to drag one-tap gate shortcuts to your home screen.", fontSize = 13.sp, color = Color.Gray)
}

/* ----------------------------- Receiver (gate phone) ----------------------------- */

@Composable
private fun ReceiverScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    RefreshOnResume { refresh++ }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    var allowed by remember { mutableStateOf(Prefs.allowed(ctx)) }
    var code by remember { mutableStateOf(Prefs.code(ctx)) }
    var pkg by remember { mutableStateOf(Prefs.gatewisePkg(ctx)) }
    var nearby by remember { mutableStateOf(Prefs.requireNearby(ctx)) }
    var radius by remember { mutableStateOf(Prefs.radius(ctx).toString()) }
    var home by remember { mutableStateOf(Prefs.gateLocation(ctx)) }
    var locMsg by remember { mutableStateOf("") }

    val smsOk = remember(refresh) { Phone.has(ctx, Manifest.permission.RECEIVE_SMS) && Phone.has(ctx, Manifest.permission.SEND_SMS) }
    val locOk = remember(refresh) { Phone.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val a11yOk = remember(refresh) { GateService.isEnabled(ctx) }
    val gw = remember(refresh, pkg) { Phone.gatewisePackage(ctx) }
    val battOk = remember(refresh) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    Text("Setup checklist", fontWeight = FontWeight.Bold, color = Color.White)
    Check("SMS permission (read gate texts, text back)", smsOk) {
        askPerms.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    Check("Accessibility: Gate Remote turned on", a11yOk) {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    if (!smsOk || !a11yOk) RestrictedHint()
    Check("Gatewise app found${gw?.let { " ($it)" } ?: ""}", gw != null, null)
    Check("Battery: don't put Gate Remote to sleep", battOk) { openBatterySetting(ctx) }
    Check("Smart Lock → Trusted place = home (so it works while locked)", null) {
        ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    HorizontalDivider()
    Text("Who can open the gate", fontWeight = FontWeight.Bold, color = Color.White)
    OutlinedTextField(allowed, { allowed = it; Prefs.setAllowed(ctx, it) }, label = { Text("Allowed numbers (comma separated)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { v -> code = v.filter { !it.isWhitespace() }; Prefs.setCode(ctx, code) }, label = { Text("Secret code (same as sender)") },
        singleLine = true, modifier = Modifier.fillMaxWidth())

    HorizontalDivider()
    Text("250 m rule", fontWeight = FontWeight.Bold, color = Color.White)
    SwitchRow("Only open when the sender is near the gate", nearby) { nearby = it; Prefs.setRequireNearby(ctx, it) }
    OutlinedTextField(radius, { v -> radius = v.filter { it.isDigit() }.take(5); radius.toIntOrNull()?.let { Prefs.setRadius(ctx, it) } },
        label = { Text("Radius (meters)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(
        home?.let { "Gate location: %.5f, %.5f".format(it.first, it.second) } ?: "Gate location not set yet (rule is off until you set it).",
        color = Color.White, fontSize = 14.sp
    )
    OutlinedButton(onClick = {
        if (!locOk) { askPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)); return@OutlinedButton }
        scope.launch {
            locMsg = "📍 Getting location…"
            val l = Phone.location(ctx)
            if (l == null) locMsg = "❌ No location fix. Turn on location and try outside/near a window."
            else {
                Prefs.setGateLocation(ctx, l.latitude, l.longitude); home = l.latitude to l.longitude
                locMsg = "✅ Saved (±${l.accuracy.toInt()} m). Do this standing at the gate."
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Set gate location = where I'm standing now") }
    if (locMsg.isNotEmpty()) Text(locMsg, color = Color.White, fontSize = 14.sp)

    HorizontalDivider()
    Text("Gatewise buttons (as written in Gatewise)", fontWeight = FontWeight.Bold, color = Color.White)
    Gate.entries.forEach { g ->
        var v by remember { mutableStateOf(Prefs.label(ctx, g)) }
        OutlinedTextField(v, { v = it; Prefs.setLabel(ctx, g, it) }, label = { Text(g.title) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    OutlinedTextField(pkg, { pkg = it; Prefs.setGatewisePkg(ctx, it) }, label = { Text("Gatewise package (blank = auto-detect)") },
        singleLine = true, modifier = Modifier.fillMaxWidth())

    HorizontalDivider()
    Text("Test (doesn't open anything)", fontWeight = FontWeight.Bold, color = Color.White)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Gate.entries.forEach { g ->
            OutlinedButton(onClick = {
                val s = GateService.instance
                if (s == null) Toast.makeText(ctx, "Turn on Accessibility for Gate Remote first", Toast.LENGTH_LONG).show()
                else s.openGate(g, replyTo = null, dryRun = true)
            }, modifier = Modifier.weight(1f)) { Text(g.key, fontSize = 12.sp) }
        }
    }
    Text("Finds the button in Gatewise without tapping it. Result shows in the log below.", fontSize = 13.sp, color = Color.Gray)
}

@SuppressLint("BatteryLife")
private fun openBatterySetting(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

/* ----------------------------- Shared bits ----------------------------- */

@Composable
private fun RestrictedHint() {
    val ctx = LocalContext.current
    Text(
        "Android 13+: if a switch is greyed out (\"Restricted setting\"), open Settings → Apps → Gate Remote → ⋮ (top right) → Allow restricted settings, then come back.",
        fontSize = 13.sp, color = Color(0xFFFFC107)
    )
    TextButton(onClick = {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }) { Text("Open app info") }
}

@Composable
private fun Check(text: String, ok: Boolean?, fix: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(when (ok) { true -> "✅"; false -> "⚠️"; null -> "👉" }, fontSize = 18.sp)
        Spacer(Modifier.padding(4.dp))
        Text(text, Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
        if (ok != true && fix != null) TextButton(onClick = fix) { Text(if (ok == null) "Open" else "Fix") }
    }
}

@Composable
private fun SwitchRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = Color.White)
        Switch(value, onChange)
    }
}

@Composable
private fun HistoryList() {
    val ctx = LocalContext.current
    val items by History.items.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Log", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
        if (items.isNotEmpty()) TextButton(onClick = { History.clear(ctx) }) { Text("Clear") }
    }
    if (items.isEmpty()) Text("Nothing yet.", color = Color.Gray, fontSize = 13.sp)
    items.take(20).forEach { Text(it, color = Color.LightGray, fontSize = 13.sp) }
}

@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { History.load(ctx); latest() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
private fun UpdateBar(onUpdate: (Updater.Release) -> Unit) {
    val ctx = LocalContext.current
    val s by Updater.state.collectAsState()
    if (s is Updater.State.Idle) return
    Surface(color = Color(0xFF1F3A4D), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val st = s) {
                    is Updater.State.Checking -> "Checking for updates…"
                    is Updater.State.UpToDate -> "You're on the latest version"
                    is Updater.State.Available -> "⬆️ Update available · build ${st.release.build}"
                    is Updater.State.Downloading -> "Downloading update… ${st.percent}%"
                    is Updater.State.ReadyToInstall -> "Update downloaded · tap Install"
                    is Updater.State.Error -> st.message
                    is Updater.State.Idle -> ""
                },
                modifier = Modifier.weight(1f), color = Color.White, fontSize = 14.sp
            )
            when (val st = s) {
                is Updater.State.Available -> Button(onClick = { onUpdate(st.release) }) { Text("Update") }
                is Updater.State.ReadyToInstall -> Button(onClick = { Updater.install(ctx, st.file) }) { Text("Install") }
                is Updater.State.Downloading, is Updater.State.Checking -> Unit
                else -> TextButton(onClick = { Updater.dismiss() }) { Text("OK") }
            }
        }
    }
}
