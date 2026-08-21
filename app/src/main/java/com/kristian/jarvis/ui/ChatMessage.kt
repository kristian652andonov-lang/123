package com.kristian.jarvis.ui

/** One line in the transcript. */
data class ChatMessage(
    val role: Role,
    val text: String,
    val id: Long = nextId(),
    /** True while a message is still being streamed in / transcribed. */
    val partial: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM }

    companion object {
        /**
         * Fixed id for the in-progress transcript line, so recomposing while
         * you speak updates one row instead of spawning a new one each frame.
         */
        const val LIVE_ID = -1L

        private var counter = 0L
        private fun nextId(): Long = ++counter
    }
}
