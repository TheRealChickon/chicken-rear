package dev.kb896.aiusage.data

/**
 * "Match the front notifications": which of the back's cards stay, given the keys of the
 * notifications on the front now. A card stays while its notification is there; one saved
 * before cards carried a key (an empty key) is left alone, there being nothing to match.
 */
object NoticeMatch {
    fun keep(keys: List<String>, active: Set<String>): List<Boolean> =
        keys.map { it.isEmpty() || it in active || remote(it) }

    /**
     * A card put up from outside (remote control), by its key. It was never on the front, so
     * "only what the front still has" would take it away the moment it was compared; it goes
     * when whoever put it up takes it down, or when it is swiped away on the back.
     */
    const val REMOTE = "remote:"
    fun remote(key: String): Boolean = key.startsWith(REMOTE)
}
