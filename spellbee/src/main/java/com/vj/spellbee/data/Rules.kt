package com.vj.spellbee.data

object Rules {
    val steps = listOf(
        "🎤 Your turn" to "Step up to the microphone. The pronouncer says your word.",
        "🙋 Ask questions" to "Before you spell, you can ask the pronouncer to: repeat the word, give the definition, use it in a sentence, tell the part of speech, tell the language of origin, or give other ways to pronounce it. Ask as many as you like while the clock runs.",
        "🔤 Spell it out loud" to "Say the letters clearly, one at a time. Many spellers say the word first, spell it, then say the word again — that's allowed but not required.",
        "✋ No take-backs" to "You may stop and start over, but you can't change letters you've already said or their order.",
        "⏱ Time limit" to "Classroom and School levels give you 2 minutes per word. Regional and National levels give you 90 seconds. You'll hear a warning at 30 seconds.",
        "🔔 The bell" to "In Competition mode, one misspelling and the bell rings — you're out. Spell every word in the round to win the level.",
        "🏆 The road to the finals" to "Classroom Bee → School Bee → Regional Bee → National Prelims → National Finals. Score 80% or better to unlock the next level.",
        "📝 Practice mode" to "Keep going after a mistake and learn the right spelling. Great for studying.",
        "📚 Where the words come from" to "Our featured words are hand-written. Thousands more come from Wiktionary, the free dictionary (en.wiktionary.org), shared under the CC BY-SA 4.0 license. Some of those words don't have a sentence or language of origin listed.",
        "📱 In this app" to "Tap the mic and say the letters, or type them. You'll see what the app heard before you tap Lock it in, because phone microphones sometimes mishear.",
    )

    const val spokenSummary =
        "Here's how a spelling bee works. The pronouncer says your word. " +
            "You can ask for the definition, a sentence, the part of speech, the language of origin, " +
            "or other ways to pronounce it. Then spell the word out loud, one letter at a time. " +
            "Once you say a letter, you can't change it. If you misspell, the bell rings and you're out. " +
            "Spell every word to move up from the Classroom Bee all the way to the National Finals. Good luck!"
}
