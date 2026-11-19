package com.vj.tidy

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

private val Bg = Color(0xFF111619)
private val CardBg = Color(0xFF1B2328)
private val Accent = Color(0xFF4FD1C5)
private val Danger = Color(0xFFE5534B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateLog.load(this)
        AutoUpdate.schedule(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Color(0xFFFFC857))) {
                Surface(Modifier.fillMaxSize(), color = Bg) { TidyApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AutoUpdate.appVisible = true
    }

    override fun onPause() {
        AutoUpdate.appVisible = false
        super.onPause()
    }
}

/* ----------------------------- State ----------------------------- */

sealed class Phase {
    data object Idle : Phase()
    data class Scanning(val text: String, val fraction: Float) : Phase()
    data object Done : Phase()
    data class Error(val message: String) : Phase()
}

class TidyVm(app: Application) : AndroidViewModel(app) {
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase = _phase.asStateFlow()
    private val _result = MutableStateFlow<ScanResult?>(null)
    val result = _result.asStateFlow()
    private val _blur = MutableStateFlow(1)
    val blurLevel = _blur.asStateFlow()
    private var job: Job? = null

    fun scan() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                _phase.value = Phase.Scanning("Starting…", 0f)
                _result.value = Scanner.scan(getApplication()) { text, f -> _phase.value = Phase.Scanning(text, f) }
                _phase.value = Phase.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = Phase.Error("Scan failed: ${e.message}")
            }
        }
    }

    fun setBlur(level: Int) { _blur.value = level }

    fun removed(ids: Set<Long>) {
        _result.value = _result.value?.without(ids)
    }
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/* ----------------------------- App ----------------------------- */

@Composable
fun TidyApp(vm: TidyVm = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    RefreshOnResume { refresh++ }
    val granted = remember(refresh) { mediaPermissions().all { Perms.has(ctx, it) } }
    var asked by remember { mutableStateOf(false) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        refresh++
    }
    var open by remember { mutableStateOf<Category?>(null) }

    LaunchedEffect(Unit) { Updater.check(ctx, silent = true) }
    LaunchedEffect(granted) { if (granted && vm.result.value == null) vm.scan() }

    val cat = open
    if (cat != null) {
        BackHandler { open = null }
        ReviewScreen(vm, cat, onBack = { open = null })
        return
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🧹 Phone Tidy", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        UpdateBar(onUpdate = { r -> scope.launch { Updater.download(ctx, r) } })

        if (!granted) {
            PermissionCard(asked) {
                if (asked) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                } else askMedia.launch(mediaPermissions())
            }
        } else {
            HomeContent(vm) { open = it }
        }

        HorizontalDivider()
        AutoUpdateSection()
        TextButton(onClick = { scope.launch { Updater.check(ctx, silent = false) } }) {
            Text("Check app update · ${Updater.currentVersionName(ctx)}")
        }
    }
}

@Composable
private fun PermissionCard(askedBefore: Boolean, onAllow: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Let Phone Tidy look at your photos & videos", fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "It finds duplicates, blurry and dark shots, and big files. Everything — including the AI — runs on this phone. Nothing is uploaded.",
                color = Color.LightGray, fontSize = 14.sp
            )
            if (askedBefore) Text(
                "Choose \"Allow all\" (not \"Select photos\"), or the scan only sees the few photos you pick.",
                color = Color(0xFFFFC857), fontSize = 13.sp
            )
            Button(onClick = onAllow) { Text(if (askedBefore) "Open settings" else "Allow access") }
        }
    }
}

@Composable
private fun HomeContent(vm: TidyVm, onOpen: (Category) -> Unit) {
    val phase by vm.phase.collectAsState()
    val result by vm.result.collectAsState()
    val blur by vm.blurLevel.collectAsState()

    StorageCard(result)

    when (val p = phase) {
        is Phase.Scanning -> Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.text, color = Color.White)
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Text("First scan takes a few minutes. Later scans only check new photos.", color = Color.Gray, fontSize = 12.sp)
            }
        }
        is Phase.Error -> Text(p.message, color = Danger)
        else -> Unit
    }

    val r = result
    if (r != null) {
        val total = Category.entries.filter { it.preselect }.sumOf { r.savings(it, blur) }
        if (phase is Phase.Done) {
            Text(
                if (total > 0) "✨ About ${total.prettySize()} of junk found" else "✨ Looking tidy!",
                color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
        }
        Category.entries.forEach { cat ->
            val count = if (cat == Category.DUPLICATES) r.groups.size else r.items(cat, blur).size
            val size = r.savings(cat, blur)
            val sub = when {
                count == 0 -> "Nothing found"
                cat == Category.DUPLICATES -> "$count groups · ${size.prettySize()} can be freed"
                else -> "${num(count)} items · ${size.prettySize()}"
            }
            CategoryCard(cat, sub, enabled = count > 0) { onOpen(cat) }
        }
    }

    if (phase !is Phase.Scanning) {
        OutlinedButton(onClick = { vm.scan() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (result == null) "Scan my phone" else "Rescan")
        }
    }
}

@Composable
private fun StorageCard(r: ScanResult?) {
    val stat = remember(r) { StatFs(Environment.getDataDirectory().path) }
    val total = stat.totalBytes
    val used = total - stat.availableBytes
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Storage: ${used.prettySize()} of ${total.prettySize()} used", color = Color.White, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { if (total > 0) used.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (used > total * 0.9) Danger else Accent,
            )
            if (r != null) {
                Text(
                    "Photos ${r.images.sumOf { it.size }.prettySize()} (${num(r.images.size)}) · " +
                        "Videos ${r.videos.sumOf { it.size }.prettySize()} (${num(r.videos.size)})",
                    color = Color.LightGray, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(cat: Category, sub: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cat.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(sub, color = if (enabled) Color.LightGray else Color.Gray, fontSize = 13.sp)
            }
            if (enabled) Text("›", color = Accent, fontSize = 28.sp)
        }
    }
}

/* ----------------------------- Review ----------------------------- */

@Composable
private fun ReviewScreen(vm: TidyVm, cat: Category, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val result by vm.result.collectAsState()
    val blur by vm.blurLevel.collectAsState()
    val r = result ?: return
    val list = remember(r, cat, blur) { r.items(cat, blur) }
    val bestIds = remember(r) { r.groups.map { it.bestId }.toSet() }

    // Default selection: duplicates = everything except the AI's pick; blurry/dark = all; others = none
    val selected = remember(r, cat, blur) {
        mutableStateMapOf<Long, Boolean>().apply {
            list.forEach { m -> put(m.id, cat.preselect && m.id !in bestIds) }
        }
    }
    val chosen = list.filter { selected[it.id] == true }
    var pending by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            Toast.makeText(ctx, "Moved ${pending.size} to trash", Toast.LENGTH_SHORT).show()
            vm.removed(pending)
        }
        pending = emptySet()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", fontSize = 16.sp) }
            Text("${cat.emoji} ${cat.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(cat.help, color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))

        if (cat == Category.BLURRY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sensitivity", color = Color.Gray, fontSize = 13.sp)
                listOf("Low", "Medium", "High").forEachIndexed { i, label ->
                    FilterChip(selected = blur == i, onClick = { vm.setBlur(i) }, label = { Text(label) })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap to select · long-press to view", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { list.forEach { m -> selected[m.id] = m.id !in bestIds || cat != Category.DUPLICATES } }) { Text("All") }
            TextButton(onClick = { list.forEach { selected[it.id] = false } }) { Text("None") }
        }

        Box(Modifier.weight(1f)) {
            if (cat == Category.DUPLICATES) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.groups, key = { it.bestId }) { g ->
                        Column {
                            Text(
                                "${g.items.size} similar · ★ = AI's pick to keep",
                                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                g.items.forEach { m ->
                                    Tile(m, selected[m.id] == true, best = m.id == g.bestId, Modifier.size(118.dp)) {
                                        selected[m.id] = selected[m.id] != true
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list, key = { it.id }) { m ->
                        Tile(m, selected[m.id] == true, best = false, Modifier.aspectRatio(1f), showSize = !cat.preselect) {
                            selected[m.id] = selected[m.id] != true
                        }
                    }
                }
            }
        }

        Button(
            enabled = chosen.isNotEmpty(),
            onClick = {
                pending = chosen.map { it.id }.toSet()
                // Android's trash: shows its own confirm dialog; items can be restored for 30 days
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, chosen.map { it.uri }, true)
                trash.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(
                if (chosen.isEmpty()) "Select items to clean up"
                else "Move ${num(chosen.size)} to trash · ${chosen.sumOf { it.size }.prettySize()}"
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(m: Media, selected: Boolean, best: Boolean, modifier: Modifier, showSize: Boolean = false, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(if (selected) 3.dp else 0.dp, if (selected) Danger else Color.Transparent, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onToggle,
                onLongClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(m.uri, if (m.video) "video/*" else "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                }
            )
    ) {
        Thumb(m, Modifier.fillMaxSize())
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x55000000)))
        if (selected) Badge("🗑", Modifier.align(Alignment.TopEnd), Danger)
        if (best) Badge("★ Best", Modifier.align(Alignment.TopStart), Color(0xFF2E7D32))
        val label = buildString {
            if (m.video) append("▶ ${m.durationMs.prettyDuration()}")
            if (showSize || m.video) { if (isNotEmpty()) append(" · "); append(m.size.prettySize()) }
        }
        if (label.isNotEmpty()) Badge(label, Modifier.align(Alignment.BottomStart), Color(0xAA000000))
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, bg: Color) {
    Text(
        text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(4.dp).background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private object ThumbCache {
    val lru = object : LruCache<Long, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }
}

@Composable
private fun Thumb(m: Media, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(ThumbCache.lru.get(m.id), m.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(300, 300), null).asImageBitmap() }.getOrNull()
            }?.also { ThumbCache.lru.put(m.id, it) }
        }
    }
    Box(modifier.background(Color(0xFF263038))) {
        bmp?.let { Image(it, contentDescription = m.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/* ----------------------------- Updates ----------------------------- */

@Composable
private fun AutoUpdateSection() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    RefreshOnResume { refresh++ }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val installOk = remember(refresh) { ctx.packageManager.canRequestPackageInstalls() }
    val notifOk = remember(refresh) { Build.VERSION.SDK_INT < 33 || Perms.has(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val log by UpdateLog.items.collectAsState()

    Text("Auto-update", fontWeight = FontWeight.Bold, color = Color.White)
    Check("Allow Phone Tidy to install its own updates", installOk) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
    }
    Check("Notifications (for \"update ready\" if Android wants a tap)", notifOk) {
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Text(
        "Checks for new versions every 6 hours in the background and installs them. The first update needs one tap on Update; after that they install by themselves (Android 12+).",
        fontSize = 13.sp, color = Color.Gray
    )
    log.take(3).forEach { Text(it, color = Color.LightGray, fontSize = 12.sp) }
}

@Composable
private fun Check(text: String, ok: Boolean, fix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
        if (!ok) TextButton(onClick = fix) { Text("Fix") }
    }
}

@Composable
private fun UpdateBar(onUpdate: (Updater.Release) -> Unit) {
    val ctx = LocalContext.current
    val s by Updater.state.collectAsState()
    if (s is Updater.State.Idle) return
    Surface(color = Color(0xFF1D3B3E), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { UpdateLog.load(ctx); latest() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

private fun num(n: Int): String = NumberFormat.getIntegerInstance().format(n)
