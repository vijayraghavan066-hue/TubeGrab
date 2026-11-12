package com.vj.spellbee

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vj.spellbee.data.Level
import com.vj.spellbee.data.Rules
import com.vj.spellbee.data.Word
import com.vj.spellbee.data.WordBank
import com.vj.spellbee.speech.Ask
import com.vj.spellbee.speech.LetterParser
import com.vj.spellbee.speech.Listener
import com.vj.spellbee.speech.Pronouncer
import com.vj.spellbee.speech.VoiceIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { HOME, HOW, LEVELS, ROUND, RESULT }
enum class Mode { COMPETITION, PRACTICE }

data class Outcome(val word: Word, val given: String, val correct: Boolean, val timedOut: Boolean = false)

data class UiState(
    val screen: Screen = Screen.HOME,
    val mode: Mode = Mode.COMPETITION,
    val timerOn: Boolean = true,
    val level: Level? = null,
    val words: List<Word> = emptyList(),
    val index: Int = 0,
    val typed: String = "",
    val heard: String? = null,
    val listening: Boolean = false,
    val partial: String = "",
    val secondsLeft: Int = 0,
    val outcome: Outcome? = null,
    val results: List<Outcome> = emptyList(),
    val bubble: String = "Hi! I'm your pronouncer. Tap the mic and ask me anything — like \"How does the spelling bee work?\"",
    val unlocked: Int = 1,
    val best: Map<Int, Int> = emptyMap(),
    val homeInput: String = "",
    val newlyUnlocked: Boolean = false,
) {
    val current: Word? get() = words.getOrNull(index)
    val score: Int get() = results.count { it.correct }
    val isOut: Boolean get() = mode == Mode.COMPETITION && outcome?.correct == false
    val isLastWord: Boolean get() = index >= words.size - 1
}

class BeeViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val voice = Pronouncer(ctx)
    private val ears = Listener(ctx)
    private val prefs = ctx.getSharedPreferences("bee", Context.MODE_PRIVATE)
    private val bell: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }
    private var timerJob: Job? = null

    init {
        // Load the big word list in the background so the first round starts instantly.
        viewModelScope.launch(Dispatchers.IO) { WordBank.all(ctx) }
    }

    var ui by mutableStateOf(
        UiState(
            unlocked = prefs.getInt("unlocked", 1),
            best = WordBank.levels.associate { it.id to prefs.getInt("best_${it.id}", -1) },
        )
    )
        private set

    // ---------- navigation ----------
    fun go(screen: Screen) {
        voice.stop(); ears.stop()
        ui = ui.copy(screen = screen, listening = false, partial = "")
    }

    fun back() {
        when (ui.screen) {
            Screen.ROUND -> quitRound()
            Screen.RESULT -> go(Screen.LEVELS)
            else -> go(Screen.HOME)
        }
    }

    fun setMode(mode: Mode) { ui = ui.copy(mode = mode) }
    fun toggleTimer() { ui = ui.copy(timerOn = !ui.timerOn) }

    fun unlockAll() {
        prefs.edit().putInt("unlocked", WordBank.levels.size).apply()
        ui = ui.copy(unlocked = WordBank.levels.size)
    }

    // ---------- home "ask the pronouncer" ----------
    fun setHomeInput(text: String) { ui = ui.copy(homeInput = text) }

    fun readRules() {
        ui = ui.copy(bubble = "📖 Reading the rules…")
        voice.say(Rules.spokenSummary)
    }

    fun sayLevels() {
        val text = "There are five levels: " + WordBank.levels.joinToString(", ") { it.name } +
            ". Score eighty percent or better to unlock the next one."
        ui = ui.copy(bubble = "🏁 " + WordBank.levels.joinToString(" → ") { it.name })
        voice.say(text)
    }

    /** Pronounce any word the parent/kid types or asks about. */
    fun pronounceWord(raw: String, alternates: Boolean, slow: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty()) {
            ui = ui.copy(bubble = "Type a word first, then tap a button.")
            return
        }
        val w = WordBank.find(ctx, text)
        val spoken = w?.spoken ?: text
        when {
            slow -> { voice.say(spoken, 0.45f); ui = ui.copy(bubble = "🐢 Slowly: $text") }
            !alternates -> { voice.say(spoken); ui = ui.copy(bubble = "🔊 $text") }
            w != null && w.alternates.isNotEmpty() -> {
                ui = ui.copy(bubble = "🔀 $text can be said: ${(listOf(spoken) + w.alternates).joinToString("  •  ")}")
                voice.say("$spoken. It may also be pronounced: " + w.alternates.joinToString(". Or: ") + ".")
            }
            else -> {
                ui = ui.copy(bubble = "🔊 $text — I don't have any other pronunciations listed for this word.")
                voice.say("$spoken. I don't have any other pronunciations listed for this word.")
            }
        }
    }

    private fun handleHomeSpeech(alts: List<String>) {
        val heard = alts.firstOrNull().orEmpty()
        if (heard.isBlank()) { ui = ui.copy(bubble = "I didn't catch that. Tap the mic and try again."); return }
        val word = alts.firstNotNullOfOrNull { VoiceIntent.extractWord(it) }
        val ask = alts.map { VoiceIntent.classify(it) }.firstOrNull { it != Ask.NONE } ?: Ask.NONE
        when {
            word != null -> {
                ui = ui.copy(homeInput = word)
                pronounceWord(word, alternates = ask == Ask.ALTERNATES, slow = ask == Ask.SLOW)
            }
            ask == Ask.HOW_IT_WORKS -> { readRules(); ui = ui.copy(screen = Screen.HOW) }
            ask == Ask.LEVELS -> sayLevels()
            else -> ui = ui.copy(
                bubble = "I heard \"$heard\". Try: \"How does the spelling bee work?\" or \"Other ways to pronounce tomato?\""
            )
        }
    }

    // ---------- listening ----------
    fun listen(forRound: Boolean) {
        voice.stop()
        ui = ui.copy(listening = true, partial = "")
        ears.start(
            onPartialCb = { ui = ui.copy(partial = it) },
            onResultCb = { alts ->
                ui = ui.copy(listening = false, partial = "")
                if (forRound) handleRoundSpeech(alts) else handleHomeSpeech(alts)
            },
            onErrorCb = { msg -> ui = ui.copy(listening = false, partial = "", bubble = msg) },
        )
    }

    fun stopListening() {
        ears.stop()
        ui = ui.copy(listening = false, partial = "")
    }

    fun micDenied() {
        ui = ui.copy(bubble = "No microphone permission — you can still type. (Enable it in Settings › Apps › Spell Bee Champ.)")
    }

    // ---------- the round ----------
    fun startLevel(level: Level) {
        val pool = WordBank.forLevel(ctx, level.id).shuffled()
        ui = ui.copy(
            screen = Screen.ROUND, level = level, words = pool.take(level.wordsPerRound),
            index = 0, results = emptyList(), newlyUnlocked = false,
        )
        presentWord()
    }

    fun retry() { ui.level?.let { startLevel(it) } }

    private fun presentWord() {
        val w = ui.current ?: return
        ui = ui.copy(outcome = null, heard = null, typed = "", partial = "", bubble = "🔊 Listen for your word…")
        voice.say("Your word is: ${w.spoken}.")
        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        val level = ui.level ?: return
        if (!ui.timerOn) { ui = ui.copy(secondsLeft = 0); return }
        ui = ui.copy(secondsLeft = level.secondsPerWord)
        timerJob = viewModelScope.launch {
            while (ui.secondsLeft > 0) {
                delay(1000)
                ui = ui.copy(secondsLeft = ui.secondsLeft - 1)
                if (ui.secondsLeft == 30 && !voice.isSpeaking && !ui.listening) voice.say("Thirty seconds.")
            }
            if (ui.outcome == null) judge("", timedOut = true)
        }
    }

    fun ask(a: Ask) {
        val w = ui.current ?: return
        when (a) {
            Ask.REPEAT -> { voice.say(w.spoken); ui = ui.copy(bubble = "🔊 Here's your word again.") }
            Ask.SLOW -> { voice.say(w.spoken, 0.45f); ui = ui.copy(bubble = "🐢 Saying it slowly.") }
            Ask.DEFINITION -> {
                voice.say("The definition is: ${w.definition}.")
                ui = ui.copy(bubble = "📘 Definition: ${w.definition}")
            }
            Ask.SENTENCE -> if (w.sentence.isBlank()) {
                voice.say("I'm sorry, I don't have a sentence for this word. The definition is: ${w.definition}.")
                ui = ui.copy(bubble = "💬 No sentence for this word. Definition: ${w.definition}")
            } else {
                voice.say(w.spokenSentence)
                ui = ui.copy(bubble = "💬 ${w.blankedSentence}")
            }
            Ask.ORIGIN -> if (w.origin.isBlank()) {
                voice.say("I'm sorry, the language of origin isn't listed for this word.")
                ui = ui.copy(bubble = "🌍 Language of origin isn't listed for this word.")
            } else {
                voice.say("The language of origin is ${w.origin}.")
                ui = ui.copy(bubble = "🌍 Language of origin: ${w.origin}")
            }
            Ask.PART_OF_SPEECH -> {
                val article = if (w.partOfSpeech.first().lowercaseChar() in "aeiou") "an" else "a"
                voice.say("It is $article ${w.partOfSpeech}.")
                ui = ui.copy(bubble = "🏷 Part of speech: ${w.partOfSpeech}")
            }
            Ask.ALTERNATES -> if (w.alternates.isEmpty()) {
                voice.say("There are no other pronunciations for this word. ${w.spoken}.")
                ui = ui.copy(bubble = "🔀 No other pronunciations for this word.")
            } else {
                voice.say("This word may also be pronounced: " + w.alternates.joinToString(". Or: ") + ".")
                ui = ui.copy(bubble = "🔀 Listen — there ${if (w.alternates.size == 1) "is 1 other way" else "are ${w.alternates.size} other ways"} to say it.")
            }
            Ask.HOW_IT_WORKS -> {
                voice.say(Rules.spokenSummary)
                ui = ui.copy(bubble = "📖 Ask questions, then spell letter by letter. One miss and the bell rings!")
            }
            Ask.LEVELS -> sayLevels()
            Ask.NONE -> Unit
        }
    }

    private fun handleRoundSpeech(alts: List<String>) {
        val w = ui.current ?: return
        if (ui.outcome != null) return
        if (alts.isEmpty()) { ui = ui.copy(bubble = "I didn't catch that. Tap the mic and try again."); return }
        val question = alts.map { VoiceIntent.classify(it) }.firstOrNull { it != Ask.NONE }
        if (question != null) { ask(question); return }

        val parsed = LetterParser.best(alts, w.word)
        ui = if (parsed.letters.isEmpty()) {
            ui.copy(bubble = "I heard \"${alts.first()}\". Please say the letters one at a time, like \"C… A… T\".")
        } else {
            val note = if (parsed.unknown.isNotEmpty()) " (I wasn't sure about: ${parsed.unknown.joinToString()})" else ""
            ui.copy(heard = parsed.letters, bubble = "👂 Is that right? Lock it in or say it again.$note")
        }
    }

    fun setTyped(text: String) { ui = ui.copy(typed = text.filter { it.isLetter() || it == ' ' }) }
    fun clearHeard() { ui = ui.copy(heard = null) }

    fun lockIn(answer: String) {
        if (ui.outcome != null) return
        val letters = answer.filter { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return
        judge(letters)
    }

    private fun judge(given: String, timedOut: Boolean = false) {
        val w = ui.current ?: return
        timerJob?.cancel()
        ears.stop()
        val correct = !timedOut && given == w.answerKey
        val outcome = Outcome(w, given, correct, timedOut)
        ui = ui.copy(outcome = outcome, results = ui.results + outcome, heard = null, listening = false, secondsLeft = 0,
            bubble = if (correct) "🎉 That is correct!" else if (timedOut) "⏰ Time's up!" else "🔔 Ding! That's not quite right.")
        if (correct) {
            voice.say("That is correct!")
        } else {
            bell?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            viewModelScope.launch {
                delay(450)
                val lead = if (timedOut) "Time is up." else "I'm sorry, that is incorrect."
                voice.say("$lead The correct spelling is: ${Pronouncer.spellOut(w.word)}. ${w.spoken}.", 0.8f)
            }
        }
    }

    fun next() {
        if (ui.isOut || ui.isLastWord) finish()
        else { ui = ui.copy(index = ui.index + 1); presentWord() }
    }

    private fun finish() {
        timerJob?.cancel()
        val level = ui.level ?: return
        val pct = if (ui.words.isEmpty()) 0 else ui.score * 100 / ui.words.size
        val best = ui.best.toMutableMap()
        if (pct > (best[level.id] ?: -1)) {
            best[level.id] = pct
            prefs.edit().putInt("best_${level.id}", pct).apply()
        }
        var unlocked = ui.unlocked
        var newly = false
        if (pct >= 80 && level.id == unlocked && unlocked < WordBank.levels.size) {
            unlocked++
            newly = true
            prefs.edit().putInt("unlocked", unlocked).apply()
        }
        ui = ui.copy(screen = Screen.RESULT, best = best, unlocked = unlocked, newlyUnlocked = newly)
        val extra = when {
            pct == 100 -> " Perfect round. You are a champion!"
            newly -> " You unlocked the next level!"
            else -> " Keep practicing!"
        }
        voice.say("You spelled ${ui.score} out of ${ui.words.size} words correctly.$extra")
    }

    private fun quitRound() {
        timerJob?.cancel()
        go(Screen.LEVELS)
    }

    override fun onCleared() {
        timerJob?.cancel()
        ears.stop()
        voice.shutdown()
        bell?.release()
        super.onCleared()
    }
}
