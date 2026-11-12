package com.vj.spellbee.data

import android.content.Context
import org.json.JSONArray

data class Word(
    val word: String,
    val level: Int,
    val partOfSpeech: String,
    val definition: String,
    val sentence: String,
    val origin: String,
    val sayAs: String?,
    val alternates: List<String>,
) {
    /** What the text-to-speech engine should say for this word. */
    val spoken: String get() = sayAs ?: word

    /** Letters only, upper case — what the speller's answer is checked against. */
    val answerKey: String get() = word.filter { it.isLetter() }.uppercase()

    /** Sentence with the word swapped for its spoken form (for TTS). */
    val spokenSentence: String
        get() = if (sayAs == null) sentence else sentence.replace(Regex(Regex.escape(word), RegexOption.IGNORE_CASE), sayAs)

    /** Sentence with the word hidden (for the screen). */
    val blankedSentence: String
        get() = sentence.replace(Regex("\\b" + Regex.escape(word) + "\\w*", RegexOption.IGNORE_CASE), "_____")
}

data class Level(
    val id: Int,
    val name: String,
    val subtitle: String,
    val wordsPerRound: Int,
    val secondsPerWord: Int,
    val emoji: String,
)

object WordBank {
    val levels = listOf(
        Level(1, "Classroom Bee", "One Bee words · ages 6–8", 10, 120, "🏫"),
        Level(2, "School Bee", "Two Bee words · ages 8–11", 10, 120, "🎒"),
        Level(3, "Regional Bee", "Three Bee words · ages 11–14", 12, 90, "🏟️"),
        Level(4, "National Bee – Prelims", "Tough words from many languages", 12, 90, "🇺🇸"),
        Level(5, "National Bee – Finals", "Championship words", 15, 90, "🏆"),
    )

    private var cache: List<Word>? = null
    private var byLevel: Map<Int, List<Word>> = emptyMap()

    /** Hand-written words first, then the big Wiktionary list (skipping duplicates). Thread-safe. */
    @Synchronized
    fun all(context: Context): List<Word> = cache ?: run {
        val hand = load(context)
        val seen = hand.mapTo(HashSet()) { it.word.lowercase() }
        val wiki = loadWiktionary(context).filter { seen.add(it.word.lowercase()) }
        (hand + wiki).also { list ->
            cache = list
            byLevel = list.groupBy { it.level }
        }
    }

    fun forLevel(context: Context, level: Int): List<Word> {
        all(context)
        return byLevel[level].orEmpty()
    }

    fun find(context: Context, text: String): Word? =
        all(context).firstOrNull { it.word.equals(text.trim(), ignoreCase = true) }

    /** Compact rows: [word, level, pos, definition, sentence, origin]. Data © Wiktionary, CC BY-SA 4.0. */
    private fun loadWiktionary(context: Context): List<Word> {
        val text = try {
            context.assets.open("words_wiktionary.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONArray(i)
            Word(
                word = r.getString(0),
                level = r.getInt(1),
                partOfSpeech = r.getString(2),
                definition = r.getString(3),
                sentence = r.getString(4),
                origin = r.getString(5),
                sayAs = null,
                alternates = emptyList(),
            )
        }
    }

    private fun load(context: Context): List<Word> {
        val text = context.assets.open("words.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val alts = o.optJSONArray("alt")
            Word(
                word = o.getString("w"),
                level = o.getInt("level"),
                partOfSpeech = o.getString("pos"),
                definition = o.getString("def"),
                sentence = o.getString("sent"),
                origin = o.getString("origin"),
                sayAs = o.optString("say").takeIf { it.isNotBlank() },
                alternates = if (alts == null) emptyList() else (0 until alts.length()).map { alts.getString(it) },
            )
        }
    }
}
