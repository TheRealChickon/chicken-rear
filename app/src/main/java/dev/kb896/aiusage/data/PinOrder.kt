package dev.kb896.aiusage.data

object PinOrder {
    /** [list] with the item at [from] taken out and put in at [to]; the others keep their order. Out of range: unchanged. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** [list] with the items at [i] and [j] in each other's places. Out of range: unchanged. */
    fun <T> swap(list: List<T>, i: Int, j: Int): List<T> {
        if (i !in list.indices || j !in list.indices || i == j) return list
        val out = list.toMutableList()
        val a = out[i]; out[i] = out[j]; out[j] = a
        return out
    }

    /**
     * [current] put in [order] (names, the first first): the items [order] names that are still in [current] take the
     * places those items hold in [current], in [order]'s sequence; an item it does not name (pinned since) keeps its own
     * place; a name with no item (binned since) and a name said twice count once.
     */
    fun <T> apply(current: List<T>, order: List<Long>, id: (T) -> Long): List<T> {
        val there = HashMap<Long, T>(current.size * 2)
        for (item in current) there.putIfAbsent(id(item), item)
        val seen = HashSet<Long>()
        val named = order.filter { it in there && seen.add(it) }
        if (named.isEmpty()) return current
        val nameSet = named.toHashSet()
        val queue = named.iterator()
        val placed = HashSet<Long>()
        return current.map { item ->
            val k = id(item)
            if (k in nameSet && placed.add(k)) there.getValue(queue.next()) else item
        }
    }

    /** Whether [list] is already in [order] as far as [order] names what it holds ([apply] would change nothing). */
    fun <T> agrees(list: List<T>, order: List<Long>, id: (T) -> Long): Boolean = apply(list, order, id) == list

    fun applyPins(current: List<Pin>, order: List<Long>): List<Pin> = apply(current, order) { it.at }
}
