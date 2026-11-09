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

    /** (category, its check-ins in your order); categories A–Z, uncategorized last. */
    fun byCategory(checkins: List<Checkin>): List<Pair<String, List<Checkin>>> {
        // "Health" and "health " are the same category, shown as first written.
        val named = checkins.filter { it.category.isNotBlank() }
            .groupBy { it.category.trim().lowercase() }
            .map { (_, v) -> v.first().category.trim() to v }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        val rest = checkins.filter { it.category.isBlank() }
        return if (rest.isEmpty()) named else named + (OTHER to rest)
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
