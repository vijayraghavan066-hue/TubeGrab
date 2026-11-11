package com.vj.spellbee.speech

/** Things a speller (or parent) can ask the pronouncer. */
enum class Ask { REPEAT, DEFINITION, SENTENCE, ORIGIN, PART_OF_SPEECH, ALTERNATES, SLOW, HOW_IT_WORKS, LEVELS, NONE }

object VoiceIntent {
    fun classify(text: String): Ask {
        val t = " " + text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ") + " "
        fun has(vararg p: String) = p.any { t.contains(it) }
        return when {
            has("how does", "how do i", "how do you play", "how do we play", "rules", "how it works",
                "how this works", "how spelling bee", "how the spelling bee", "how a spelling bee") -> Ask.HOW_IT_WORKS
            has("alternate", "alternative", "another pronunciation", "other pronunciation", "other way",
                "another way", "different way", "ways to pronounce", "ways to say") -> Ask.ALTERNATES
            has("definition", "define", " mean", "meaning") -> Ask.DEFINITION
            has("sentence", "use it", "use the word") -> Ask.SENTENCE
            has("origin", "language", "come from", "comes from", "where is it from") -> Ask.ORIGIN
            has("part of speech", "what kind of word", "type of word") -> Ask.PART_OF_SPEECH
            has(" slow", "slower") -> Ask.SLOW
            has("repeat", "again", "pardon", "one more time", "say the word", "pronounce") -> Ask.REPEAT
            has(" levels ", "what level", "which level") -> Ask.LEVELS
            else -> Ask.NONE
        }
    }

    /** "how do you pronounce tomato" / "other ways to say either" -> "tomato" / "either". */
    fun extractWord(text: String): String? {
        val m = Regex("(?:pronounce|say|spell)\\s+(?:the\\s+word\\s+)?([a-z]+)").find(text.lowercase())
        return m?.groupValues?.get(1)?.takeIf { it !in setOf("it", "the", "this", "that", "a", "words", "word") }
    }
}

/** Turns spoken letters ("see ay tee", "C-A-T", "double L") into "CAT". */
object LetterParser {
    private val sounds: Map<String, Char> = mapOf(
        "a" to 'A', "ay" to 'A', "eh" to 'A', "hey" to 'A',
        "b" to 'B', "be" to 'B', "bee" to 'B',
        "c" to 'C', "see" to 'C', "sea" to 'C', "si" to 'C',
        "d" to 'D', "dee" to 'D',
        "e" to 'E', "ee" to 'E',
        "f" to 'F', "ef" to 'F', "eff" to 'F',
        "g" to 'G', "gee" to 'G', "ji" to 'G',
        "h" to 'H', "aitch" to 'H', "haitch" to 'H', "each" to 'H',
        "i" to 'I', "eye" to 'I', "aye" to 'I',
        "j" to 'J', "jay" to 'J',
        "k" to 'K', "kay" to 'K', "cay" to 'K',
        "l" to 'L', "el" to 'L', "ell" to 'L', "elle" to 'L',
        "m" to 'M', "em" to 'M',
        "n" to 'N', "en" to 'N', "an" to 'N',
        "o" to 'O', "oh" to 'O', "owe" to 'O',
        "p" to 'P', "pee" to 'P', "pea" to 'P',
        "q" to 'Q', "queue" to 'Q', "cue" to 'Q', "kew" to 'Q',
        "r" to 'R', "are" to 'R', "ar" to 'R', "our" to 'R',
        "s" to 'S', "es" to 'S', "ess" to 'S',
        "t" to 'T', "tee" to 'T', "tea" to 'T',
        "u" to 'U', "you" to 'U', "yu" to 'U', "ewe" to 'U',
        "v" to 'V', "vee" to 'V',
        "w" to 'W', "doubleu" to 'W',
        "x" to 'X', "ex" to 'X',
        "y" to 'Y', "why" to 'Y', "wye" to 'Y',
        "z" to 'Z', "zee" to 'Z', "zed" to 'Z',
    )
    private val filler = setOf("letter", "capital", "the", "um", "uh", "umm", "okay", "ok", "and", "then", "is")

    data class Result(val letters: String, val unknown: List<String>)

    fun parse(raw: String, target: String): Result {
        val tokens = raw.lowercase().replace(Regex("[^a-z ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        val tgt = target.lowercase().filter { it.isLetter() }
        // Spellers often say the word before and after spelling it — drop that.
        if (tokens.size > 1 && tokens.first() == tgt) tokens.removeAt(0)
        if (tokens.size > 1 && tokens.last() == tgt) tokens.removeAt(tokens.lastIndex)

        val out = StringBuilder()
        val unknown = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok in filler -> Unit
                (tok == "double" || tok == "triple") && i + 1 < tokens.size -> {
                    val next = tokens[i + 1]
                    if (tok == "double" && (next == "u" || next == "you" || next == "yu")) {
                        out.append('W')
                    } else {
                        val c = sounds[next]
                        if (c != null) repeat(if (tok == "double") 2 else 3) { out.append(c) } else unknown += "$tok $next"
                    }
                    i++
                }
                sounds.containsKey(tok) -> out.append(sounds.getValue(tok))
                else -> unknown += tok
            }
            i++
        }
        return Result(out.toString(), unknown)
    }

    /** Pick the recognizer alternative that parses cleanly. */
    fun best(alternatives: List<String>, target: String): Result {
        val parsed = alternatives.map { parse(it, target) }
        return parsed.firstOrNull { it.unknown.isEmpty() && it.letters.isNotEmpty() }
            ?: parsed.maxByOrNull { it.letters.length - it.unknown.size * 3 }
            ?: Result("", emptyList())
    }
}
