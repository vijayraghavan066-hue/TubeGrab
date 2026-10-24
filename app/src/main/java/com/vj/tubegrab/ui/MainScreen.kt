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
import com.vj.tubegrab.VideoItem
import com.vj.tubegrab.formatDuration
import com.vj.tubegrab.formatViews

@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
                        placeholder = { Text("Search YouTube or paste a link") },
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
                            tab = 0
                            vm.search()
                        }),
                    )
                    EngineBanner(engine, onRetry = vm::updateEngine)
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Search") })
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1; vm.clearSuggestions() },
                            text = { Text(if (active > 0) "Downloads ($active)" else "Downloads") },
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
            if (tab == 0) {
                SearchTab(vm, onPick = { s ->
                    focus.clearFocus()
                    vm.search(s)
                })
            } else {
                DownloadsTab(engine, onUpdateEngine = vm::updateEngine)
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
private fun SearchTab(vm: MainViewModel, onPick: (String) -> Unit) {
    val context = LocalContext.current

    // Live suggestions while typing (like YouTube's own search box)
    if (vm.suggestions.isNotEmpty()) {
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
        if (vm.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        when {
            !vm.hasSearched -> CenterHint(
                "Search for any video or song.\n\nTip: in the YouTube app tap Share → TubeGrab to download that video directly."
            )
            vm.searching && vm.results.isEmpty() -> CenterHint("Searching…")
            vm.error != null && vm.results.isEmpty() -> CenterHint(vm.error ?: "", isError = true)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(vm.results) { _, item ->
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
            val meta = listOf(item.channel, formatViews(item.views)).filter { it.isNotBlank() }.joinToString(" • ")
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
private fun DownloadsTab(engine: EngineStatus, onUpdateEngine: () -> Unit) {
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
                "Engine: yt-dlp ${v ?: "…"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUpdateEngine, enabled = engine is EngineStatus.Ready || engine is EngineStatus.Error) {
                Text("Update engine")
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
