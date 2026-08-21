package com.kristian.jarvis.voice

/**
 * Turns a growing stream of text into speakable chunks.
 *
 * Claude's reply arrives token by token; waiting for the whole thing before
 * speaking feels dead. Feed text in as it arrives and this hands back complete
 * sentences the moment they are complete, so speech starts within a second or
 * so of the first token.
 */
class SpeechChunker(
    /** Don't emit a chunk shorter than this unless it's the final flush. */
    private val minChunkLength: Int = 24
) {
    private val pending = StringBuilder()

    /** Append streamed text; returns any chunks that are now ready to speak. */
    fun offer(text: String): List<String> {
        pending.append(text)
        val ready = mutableListOf<String>()
        while (true) {
            val cut = findSentenceEnd(pending) ?: break
            val chunk = pending.substring(0, cut).trim()
            pending.delete(0, cut)
            if (chunk.length >= minChunkLength || ready.isEmpty()) {
                if (chunk.isNotEmpty()) ready += chunk
            } else if (ready.isNotEmpty()) {
                // Glue very short fragments onto the previous chunk.
                ready[ready.lastIndex] = ready.last() + " " + chunk
            }
        }
        return ready
    }

    /** Everything still buffered, spoken as-is. Call when the stream ends. */
    fun flush(): String? {
        val rest = pending.toString().trim()
        pending.setLength(0)
        return rest.ifEmpty { null }
    }

    /**
     * Index just past a sentence terminator, or null if the buffer doesn't
     * contain a complete sentence yet. Decimals and abbreviations like "e.g."
     * are deliberately not treated as endings.
     */
    private fun findSentenceEnd(buffer: CharSequence): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c != '.' && c != '!' && c != '?' && c != '\n') continue
            if (c == '.') {
                val prev = buffer.getOrNull(i - 1)
                val next = buffer.getOrNull(i + 1)
                // 3.5 / 3. 5 -> not a sentence end
                if (prev != null && prev.isDigit() && next != null && next.isDigit()) continue
                // "e.g." style single-letter abbreviations
                if (prev != null && prev.isLetter() && buffer.getOrNull(i - 2) == '.') continue
            }
            // Swallow any trailing quote/bracket so it isn't spoken alone.
            var end = i + 1
            while (end < buffer.length && buffer[end] in "\"')]}") end++
            if (end < buffer.length || c == '\n') return end
        }
        return null
    }
}

/**
 * Strips the markdown Claude uses for the screen so it isn't read aloud as
 * punctuation soup. Code blocks are replaced with a spoken placeholder.
 */
fun String.forSpeech(): String {
    var out = this
    out = Regex("```[a-zA-Z0-9+#-]*\\n?([\\s\\S]*?)```").replace(out) { " (code shown on screen) " }
    out = Regex("`([^`]*)`").replace(out) { it.groupValues[1] }
    out = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(out) { it.groupValues[1] }
    out = Regex("(\\*\\*|__|\\*|_)").replace(out, "")
    out = Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE).replace(out, "")
    out = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).replace(out, "")
    out = Regex("^\\s*>\\s?", RegexOption.MULTILINE).replace(out, "")
    return out.replace(Regex("[ \\t]+"), " ").trim()
}
