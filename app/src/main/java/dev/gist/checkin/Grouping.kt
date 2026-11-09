package dev.gist.checkin

/**
 * How the start page lists check-ins: in your order ([UNGROUPED]), or under a heading per
 * category ([BY_CATEGORY]; check-ins without one come last, under "Other"). Within a group
 * they keep your order, which you change by dragging.
 */
object Grouping {
    const val UNGROUPED = "ungrouped"
    const val BY_CATEGORY = "category"
    const val OTHER = "Other"

    /**
     * Expand all / Collapse all: everything is folded when every group in [groups] and every
     * check-in in [ids] is. Then the button expands them all; otherwise it folds them all.
     */
    fun allFolded(groups: Set<String>, foldedGroups: Set<String>, ids: Set<String>, foldedIds: Set<String>): Boolean =
        foldedGroups.containsAll(groups) && foldedIds.containsAll(ids)

    /** The key for remembering a group's position and collapsed state ("Health" = "health "). */
    fun key(category: String): String = category.trim().lowercase()

    /**
     * (category, its check-ins in your order). Groups follow [order] (keys, see [key]); groups
     * not in it come after, A–Z, with uncategorized ("Other") last.
     */
    fun byCategory(checkins: List<Checkin>, order: List<String> = emptyList()): List<Pair<String, List<Checkin>>> {
        // "Health" and "health " are the same category, shown as first written.
        val named = checkins.filter { it.category.isNotBlank() }
            .groupBy { it.category.trim().lowercase() }
            .map { (_, v) -> v.first().category.trim() to v }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        val rest = checkins.filter { it.category.isBlank() }
        val all = if (rest.isEmpty()) named else named + (OTHER to rest)
        if (order.isEmpty()) return all
        val pos = order.withIndex().associate { (i, k) -> k to i }
        // Stable sort: groups you've placed first, in your order; the rest keep A–Z / Other last.
        return all.sortedBy { pos[key(it.first)] ?: Int.MAX_VALUE }
    }

    /** The group order after dragging the group at [from] to [to] (in [shown], the order on screen). */
    fun moveGroup(shown: List<String>, from: Int, to: Int): List<String> {
        val keys = shown.map { key(it) }.toMutableList()
        if (from !in keys.indices || to !in keys.indices) return keys
        keys.add(to, keys.removeAt(from))
        return keys
    }

    /** The categories in use, A–Z. */
    fun categories(checkins: List<Checkin>): List<String> =
        checkins.map { it.category.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /**
     * The whole list's order after moving item [from] to [to] within [group] (ids, in their
     * current order). Everything outside the group stays where it was.
     */
    fun reorderWithin(all: List<String>, group: List<String>, from: Int, to: Int): List<String> {
        if (from !in group.indices || to !in group.indices || from == to) return all
        val moved = group.toMutableList().apply { add(to, removeAt(from)) }
        val members = group.toSet()
        var k = 0
        return all.map { if (it in members) moved[k++] else it }
    }
}
