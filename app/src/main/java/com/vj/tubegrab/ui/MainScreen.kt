package com.vj.tubegrab.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vj.tubegrab.DlFormat
import com.vj.tubegrab.DlState
import com.vj.tubegrab.DlStatus
import com.vj.tubegrab.DownloadCenter
import com.vj.tubegrab.EngineStatus
import com.vj.tubegrab.MainViewModel
import com.vj.tubegrab.SOURCE_ARCHIVE
import com.vj.tubegrab.SOURCE_YOUTUBE
import com.vj.tubegrab.SearchPane
import com.vj.tubegrab.Updater
import com.vj.tubegrab.VideoItem
import com.vj.tubegrab.formatDuration
import com.vj.tubegrab.formatViews

@Composable
fun MainScreen(vm: MainViewModel) {
    val tab = vm.tab
    val engine by vm.engineStatus.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val active = DownloadCenter.items.count { it.status == DlStatus.Running || it.status == DlStatus.Queued }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        placeholder = {
                            Text(
                                if (tab == MainViewModel.TAB_OTHER) "Search Internet Archive + Dailymotion"
                                else "Search YouTube or paste a link"
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) {
                                IconButton(onClick = { vm.onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focus.clearFocus()
                            vm.search()
                        }),
                    )
                    EngineBanner(engine, onRetry = vm::updateEngine)
                    UpdateBanner(vm)
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == MainViewModel.TAB_YOUTUBE,
                            onClick = { vm.selectTab(MainViewModel.TAB_YOUTUBE) },
                            text = { Text("YouTube") },
                        )
                        Tab(
                            selected = tab == MainViewModel.TAB_OTHER,
                            onClick = { focus.clearFocus(); vm.selectTab(MainViewModel.TAB_OTHER) },
                            text = { Text("Other sites", maxLines = 1) },
                        )
                        Tab(
                            selected = tab == MainViewModel.TAB_DOWNLOADS,
                            onClick = { vm.selectTab(MainViewModel.TAB_DOWNLOADS) },
                            text = { Text(if (active > 0) "Downloads ($active)" else "Downloads", maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val onPick: (String) -> Unit = { s ->
                focus.clearFocus()
                vm.search(s)
            }
            when (tab) {
                MainViewModel.TAB_YOUTUBE -> SearchTab(
                    vm, vm.youtube, onPick,
                    emptyHint = "Search for any video or song.\n\nTip: in the YouTube app tap Share → TubeGrab to download that video directly.",
                )
                MainViewModel.TAB_OTHER -> SearchTab(
                    vm, vm.other, onPick,
                    emptyHint = "Searches Internet Archive (free public-domain movies, classic films, old TV, documentaries) and Dailymotion at the same time.\n\nType above and press search.",
                )
                else -> DownloadsTab(engine, onUpdateEngine = vm::updateEngine, onCheckAppUpdate = vm::checkForUpdate)
            }
        }
    }
}

/** Play-Store-style "Update available" strip shown at the top when a newer build exists. */
@Composable
private fun UpdateBanner(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.updateState.collectAsStateWithLifecycle()
    val s = state
    if (s is Updater.State.Idle) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val text = when (s) {
                    is Updater.State.Checking -> "Checking for updates…"
                    is Updater.State.UpToDate -> "You're on the latest version"
                    is Updater.State.Available -> "Update available · build ${s.release.build}" +
                        (if (s.release.sizeBytes > 0) " · ${s.release.sizeBytes / 1_048_576} MB" else "")
                    is Updater.State.Downloading -> "Downloading update… ${s.percent}%"
                    is Updater.State.ReadyToInstall -> "Update downloaded · tap Install"
                    is Updater.State.Error -> s.message
                    is Updater.State.Idle -> ""
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when (s) {
                    is Updater.State.Available ->
                        TextButton(onClick = { vm.downloadUpdate(s.release) }) { Text("Update") }
                    is Updater.State.ReadyToInstall ->
                        TextButton(onClick = { Updater.install(context, s.file) }) { Text("Install") }
                    else -> {}
                }
                if (s !is Updater.State.Downloading && s !is Updater.State.Checking) {
                    IconButton(onClick = { Updater.dismiss() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
            when (s) {
                is Updater.State.Downloading -> LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                is Updater.State.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                is Updater.State.ReadyToInstall -> Text(
                    "First time only: Android may ask you to allow TubeGrab to install apps. Turn it on, come back, and tap Install again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                else -> {}
            }
        }
    }
}

@Composable
private fun EngineBanner(status: EngineStatus, onRetry: () -> Unit) {
    val text = when (status) {
        EngineStatus.Starting -> "Setting up the download engine (first launch can take ~15s)…"
        EngineStatus.Updating -> "Updating the download engine…"
        is EngineStatus.Error -> status.message
        is EngineStatus.Ready -> return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (status is EngineStatus.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (status is EngineStatus.Error) TextButton(onClick = onRetry) { Text("Retry") }
        }
        if (status !is EngineStatus.Error) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------- Search tab

@Composable
private fun SearchTab(
    vm: MainViewModel,
    pane: SearchPane,
    onPick: (String) -> Unit,
    emptyHint: String,
) {
    val context = LocalContext.current

    // Live suggestions while typing (like YouTube's own search box)
    if (pane === vm.youtube && vm.suggestions.isNotEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.suggestions) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(s) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (pane.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        when {
            !pane.hasSearched -> CenterHint(emptyHint)
            pane.searching && pane.results.isEmpty() -> CenterHint("Searching…")
            pane.error != null && pane.results.isEmpty() -> CenterHint(pane.error ?: "", isError = true)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(pane.results) { _, item ->
                    ResultRow(
                        item = item,
                        state = DownloadCenter.latestFor(item.id),
                        onDownload = { f -> vm.download(item, f) },
                        onOpenVideo = { openUrl(context, item.url) },
                    )
                    HorizontalDivider(Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    item: VideoItem,
    state: DlState?,
    onDownload: (DlFormat) -> Unit,
    onOpenVideo: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Thumbnail(item, width = 150, onClick = onOpenVideo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val countText = if (item.source == SOURCE_ARCHIVE)
                formatViews(item.views).replace("views", "downloads") else formatViews(item.views)
            val sourceTag = if (item.source != SOURCE_YOUTUBE) item.source else ""
            val meta = listOf(sourceTag, item.channel, countText).filter { it.isNotBlank() }.joinToString(" • ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            DownloadControls(state, onDownload)
        }
    }
}

@Composable
private fun Thumbnail(item: VideoItem, width: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val d = formatDuration(item.durationSec)
        if (d.isNotEmpty()) {
            Text(
                d,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Download button, or live progress, or "Saved" — whatever fits the item's state. */
@Composable
private fun DownloadControls(state: DlState?, onDownload: (DlFormat) -> Unit) {
    val context = LocalContext.current
    if (state == null) {
        DownloadButton(onDownload)
        return
    }
    when (state.status) {
        DlStatus.Queued, DlStatus.Running -> ProgressBlock(state)
        DlStatus.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓ Saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { openSaved(context, state.savedUri) }) { Text("Play") }
            DownloadButton(onDownload, label = "Again")
        }
        DlStatus.Failed, DlStatus.Canceled -> Column {
            if (state.status == DlStatus.Failed) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DownloadButton(onDownload, label = "Try again")
        }
    }
}

@Composable
private fun ProgressBlock(state: DlState) {
    Column {
        if (state.progress in 0f..100f) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.format.label} · ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { DownloadCenter.cancel(state.key) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DownloadButton(onDownload: (DlFormat) -> Unit, label: String = "Download") {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DlFormat.all.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label) },
                    onClick = {
                        open = false
                        onDownload(f)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads tab

@Composable
private fun DownloadsTab(engine: EngineStatus, onUpdateEngine: () -> Unit, onCheckAppUpdate: () -> Unit) {
    val context = LocalContext.current
    val list = DownloadCenter.items

    Column(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Box(Modifier.weight(1f)) { CenterHint("No downloads yet.\nFiles are saved to Music/TubeGrab and Movies/TubeGrab.") }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { DownloadCenter.clearFinished() }) { Text("Clear finished") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.key }) { s ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Thumbnail(s.item, width = 110, onClick = { openUrl(context, s.item.url) })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            when (s.status) {
                                DlStatus.Queued, DlStatus.Running -> ProgressBlock(s)
                                DlStatus.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${s.format.label} · ${s.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { openSaved(context, s.savedUri) }) { Text("Play") }
                                }
                                DlStatus.Failed, DlStatus.Canceled -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (s.status == DlStatus.Failed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { DownloadCenter.retry(context, s) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Engine info + manual update (use this if YouTube downloads start failing)
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val v = (engine as? EngineStatus.Ready)?.version
            Text(
                "App ${Updater.currentVersionName(context)}\nEngine: yt-dlp ${v ?: "…"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onCheckAppUpdate) { Text("Check app update") }
                TextButton(onClick = onUpdateEngine, enabled = engine is EngineStatus.Ready || engine is EngineStatus.Error) {
                    Text("Update engine")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun CenterHint(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

private fun openSaved(context: Context, uri: Uri?) {
    if (uri == null) {
        Toast.makeText(context, "Saved — check Music/TubeGrab or Movies/TubeGrab", Toast.LENGTH_LONG).show()
        return
    }
    val type = context.contentResolver.getType(uri)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No player app found", Toast.LENGTH_SHORT).show()
    }
}
