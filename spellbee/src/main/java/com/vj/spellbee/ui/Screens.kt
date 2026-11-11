@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.vj.spellbee.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vj.spellbee.BeeViewModel
import com.vj.spellbee.Mode
import com.vj.spellbee.Outcome
import com.vj.spellbee.Screen
import com.vj.spellbee.UiState
import com.vj.spellbee.data.Rules
import com.vj.spellbee.data.WordBank
import com.vj.spellbee.speech.Ask

private val Ink = Color(0xFF1F1B16)
private val Good = Color(0xFF1E7B34)
private val Bad = Color(0xFFB3261E)

@Composable
fun BeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF8A5A00),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE08A),
            onPrimaryContainer = Ink,
            secondary = Color(0xFF2F4858),
            secondaryContainer = Color(0xFFDDE8EF),
            background = Color(0xFFFFFBF0),
            surface = Color(0xFFFFFBF0),
            surfaceVariant = Color(0xFFF3EAD3),
        ),
        content = content,
    )
}

@Composable
fun BeeApp(vm: BeeViewModel) {
    val ui = vm.ui
    val context = LocalContext.current
    var pendingForRound by remember { mutableStateOf<Boolean?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val forRound = pendingForRound
        pendingForRound = null
        if (granted && forRound != null) vm.listen(forRound) else if (!granted) vm.micDenied()
    }
    val mic: (Boolean) -> Unit = { forRound ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.listen(forRound)
        } else {
            pendingForRound = forRound
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(enabled = ui.screen != Screen.HOME) { vm.back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when (ui.screen) {
                Screen.HOME -> HomeScreen(ui, vm, mic)
                Screen.HOW -> HowScreen(vm)
                Screen.LEVELS -> LevelsScreen(ui, vm)
                Screen.ROUND -> RoundScreen(ui, vm, mic)
                Screen.RESULT -> ResultScreen(ui, vm)
            }
        }
    }
}

// ---------------- shared bits ----------------

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Back", fontSize = 16.sp) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Bubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Text("🐝", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, container: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (container != null) ButtonDefaults.buttonColors(containerColor = container) else ButtonDefaults.buttonColors(),
    ) { Text(text, fontSize = 18.sp, textAlign = TextAlign.Center) }
}

private fun spaced(letters: String) = letters.toList().joinToString(" - ")

// ---------------- Home ----------------

@Composable
private fun HomeScreen(ui: UiState, vm: BeeViewModel, mic: (Boolean) -> Unit) = Page {
    Text("🐝 Spell Bee Champ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Practice just like the real spelling bee.", style = MaterialTheme.typography.bodyMedium)
    Bubble(if (ui.listening) "🎙 Listening… ${ui.partial}" else ui.bubble)

    BigButton(if (ui.listening) "🎙 Listening… tap to stop" else "🎤 Ask the pronouncer",
        container = MaterialTheme.colorScheme.secondary) {
        if (ui.listening) vm.stopListening() else mic(false)
    }
    Text(
        "Try: \"How does the spelling bee work?\" · \"What are the levels?\" · \"Other ways to pronounce tomato?\"",
        style = MaterialTheme.typography.bodySmall,
    )

    BigButton("🏁 Start the Bee") { vm.go(Screen.LEVELS) }
    OutlinedButton(onClick = { vm.go(Screen.HOW) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("📖 How the spelling bee works", fontSize = 16.sp)
    }

    HorizontalDivider()
    Text("Hear any word", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = ui.homeInput,
        onValueChange = vm::setHomeInput,
        label = { Text("Type a word") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.pronounceWord(ui.homeInput, alternates = false) }),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { vm.pronounceWord(ui.homeInput, alternates = false) }, label = { Text("🔊 Say it") })
        AssistChip(onClick = { vm.pronounceWord(ui.homeInput, alternates = true) }, label = { Text("🔀 Other pronunciations") })
        AssistChip(onClick = { vm.pronounceWord(ui.homeInput, alternates = false, slow = true) }, label = { Text("🐢 Slowly") })
    }
}

// ---------------- How it works ----------------

@Composable
private fun HowScreen(vm: BeeViewModel) = Page {
    TopBar("How it works") { vm.back() }
    BigButton("🔊 Read it to me") { vm.readRules() }
    Rules.steps.forEach { (title, body) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(body)
            }
        }
    }
    BigButton("🏁 I'm ready — start the Bee") { vm.go(Screen.LEVELS) }
}

// ---------------- Levels ----------------

@Composable
private fun LevelsScreen(ui: UiState, vm: BeeViewModel) = Page {
    TopBar("Choose your level") { vm.back() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = ui.mode == Mode.COMPETITION, onClick = { vm.setMode(Mode.COMPETITION) },
            label = { Text("🔔 Competition") })
        FilterChip(selected = ui.mode == Mode.PRACTICE, onClick = { vm.setMode(Mode.PRACTICE) },
            label = { Text("📝 Practice") })
    }
    Text(
        if (ui.mode == Mode.COMPETITION) "One misspelling and you're out — just like the real bee."
        else "Keep going after mistakes and learn the right spelling.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = ui.timerOn, onCheckedChange = { vm.toggleTimer() })
        Spacer(Modifier.width(8.dp))
        Text("⏱ Timer (2 min / 90 sec per word)")
    }

    WordBank.levels.forEach { level ->
        val locked = level.id > ui.unlocked
        val best = ui.best[level.id] ?: -1
        Card(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { vm.startLevel(level) },
            colors = CardDefaults.cardColors(
                containerColor = if (locked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
            ),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (locked) "🔒" else level.emoji, fontSize = 30.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Level ${level.id}: ${level.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(level.subtitle, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            locked -> "Score 80% on the level before to unlock"
                            best >= 0 -> "${level.wordsPerRound} words · Best: $best%"
                            else -> "${level.wordsPerRound} words · Not played yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (ui.unlocked < WordBank.levels.size) {
        TextButton(onClick = { vm.unlockAll() }) { Text("Parent: unlock all levels") }
    }
}

// ---------------- Round ----------------

@Composable
private fun RoundScreen(ui: UiState, vm: BeeViewModel, mic: (Boolean) -> Unit) {
    val level = ui.level ?: return
    ui.current ?: return
    Page {
        TopBar("${level.emoji} ${level.name}") { vm.back() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Word ${ui.index + 1} of ${ui.words.size}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("⭐ ${ui.score}", fontWeight = FontWeight.SemiBold)
            if (ui.timerOn && ui.outcome == null) {
                Spacer(Modifier.width(14.dp))
                Text(
                    "⏱ %d:%02d".format(ui.secondsLeft / 60, ui.secondsLeft % 60),
                    fontWeight = FontWeight.Bold,
                    color = if (ui.secondsLeft <= 30) Bad else Color.Unspecified,
                )
            }
        }
        Text(
            if (ui.mode == Mode.COMPETITION) "🔔 Competition — one miss and you're out" else "📝 Practice",
            style = MaterialTheme.typography.bodySmall,
        )
        Bubble(if (ui.listening) "🎙 Listening… ${ui.partial}" else ui.bubble)

        val outcome = ui.outcome
        if (outcome == null) {
            BigButton("🔊 Hear my word") { vm.ask(Ask.REPEAT) }
            Text("Ask the pronouncer", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "📘 Definition" to Ask.DEFINITION,
                    "💬 Sentence" to Ask.SENTENCE,
                    "🏷 Part of speech" to Ask.PART_OF_SPEECH,
                    "🌍 Origin" to Ask.ORIGIN,
                    "🔀 Other pronunciations" to Ask.ALTERNATES,
                    "🐢 Say it slowly" to Ask.SLOW,
                ).forEach { (label, ask) ->
                    AssistChip(onClick = { vm.ask(ask) }, label = { Text(label) })
                }
            }
            HorizontalDivider()

            val heard = ui.heard
            if (heard != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("I heard:", style = MaterialTheme.typography.bodyMedium)
                        Text(spaced(heard), fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.lockIn(heard) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Good)) { Text("✅ Lock it in") }
                    OutlinedButton(onClick = { vm.clearHeard(); mic(true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text("🔁 Say again")
                    }
                }
            } else {
                BigButton(
                    if (ui.listening) "🎙 Listening… tap to stop" else "🎤 Spell it out loud (or ask a question)",
                    container = MaterialTheme.colorScheme.secondary,
                ) { if (ui.listening) vm.stopListening() else mic(true) }
                Text("…or type it:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = ui.typed,
                    onValueChange = vm::setTyped,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    placeholder = { Text("Type your spelling") },
                    // Password keyboard = no autocorrect or suggestions (no peeking!). Text stays visible.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { vm.lockIn(ui.typed) }),
                )
                BigButton("✅ Lock it in", enabled = ui.typed.isNotBlank(), container = Good) { vm.lockIn(ui.typed) }
            }
        } else {
            OutcomeCard(outcome)
            BigButton(if (ui.isOut || ui.isLastWord) "🏁 See results" else "Next word ➜") { vm.next() }
        }
    }
}

@Composable
private fun OutcomeCard(o: Outcome) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (o.correct) Color(0xFFDFF5E3) else Color(0xFFFCE3E1)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    o.correct -> "🎉 Correct!"
                    o.timedOut -> "⏰ Time's up"
                    else -> "🔔 Not quite"
                },
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (o.correct) Good else Bad,
            )
            if (!o.correct && o.given.isNotEmpty()) Text("You spelled: ${o.given.lowercase()}", fontSize = 18.sp)
            Text("The word: ${o.word.word}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${o.word.partOfSpeech} · ${o.word.definition}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------- Results ----------------

@Composable
private fun ResultScreen(ui: UiState, vm: BeeViewModel) = Page {
    val level = ui.level
    val total = ui.words.size
    val pct = if (total == 0) 0 else ui.score * 100 / total
    TopBar("Results") { vm.back() }
    Text(
        when {
            pct == 100 -> "🏆"
            pct >= 80 -> "🥇"
            pct >= 50 -> "🐝"
            else -> "💪"
        },
        fontSize = 64.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
    Text("${ui.score} of $total correct ($pct%)", style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (level != null) Text(level.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (ui.mode == Mode.COMPETITION && ui.results.lastOrNull()?.correct == false) {
        Text("The bell rang on word ${ui.results.size}. Practice and try again!", textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
    if (ui.newlyUnlocked) Bubble("🔓 You unlocked the next level!")

    val missed = ui.results.filter { !it.correct }
    if (missed.isNotEmpty()) {
        Text("Words to study", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        missed.forEach { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(o.word.word, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (o.given.isNotEmpty()) Text("You spelled: ${o.given.lowercase()}", color = Bad)
                    Text(o.word.definition, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    BigButton("🔁 Try this level again") { vm.retry() }
    OutlinedButton(onClick = { vm.go(Screen.LEVELS) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("Back to levels")
    }
}
