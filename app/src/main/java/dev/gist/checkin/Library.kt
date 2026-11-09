package dev.gist.checkin

/**
 * Ready-made check-ins to pick from (start page → + Add check-in). Adding one copies it
 * into your list with a new id; after that it's yours to change like any other.
 */
object Library {
    data class Template(val key: String, val category: String, val blurb: String, val checkin: Checkin)

    private fun q(id: String, text: String, yesIsGood: Boolean = false) = Question(id, text, yesIsGood)
    private fun t(h: Int, m: Int = 0) = AlarmTime(h, m)
    private fun qsteps(vararg qs: Question) = listOf(StepConfig("q", Steps.QUESTIONS, questions = qs.toList()))

    val TEMPLATES: List<Template> = listOf(
        Template(
            "diet-evening", "Food",
            "The original: five yes/no questions about what you ate today.",
            Checkin(
                id = "", name = "Diet gist", times = listOf(t(21, 30)),
                steps = qsteps(
                    q("sweetdrink", "Did you drink any sweetened beverages today?"),
                    q("fastfood", "Did you eat fast food today?"),
                    q("sweets", "Did you eat anything sweet today?"),
                    q("veg", "Did you eat vegetables or fruit today?", true),
                    q("homecooked", "Did you eat a home-cooked meal today?", true),
                ),
            ),
        ),
        Template(
            "last-night", "Food",
            "A morning look back at last night. Answers count for the previous day.",
            Checkin(
                id = "", name = "Last night", times = listOf(t(7)), aboutYesterday = true,
                steps = qsteps(
                    q("latefood", "Did you eat anything after 9 PM last night?"),
                    q("alcohol", "Did you drink alcohol last night?"),
                ),
            ),
        ),
        Template(
            "water", "Food",
            "Three nudges a day to drink water.",
            Checkin(
                id = "", name = "Water", times = listOf(t(11), t(15), t(19)),
                steps = qsteps(q("water", "Have you had a glass of water in the last few hours?", true)),
            ),
        ),
        Template(
            "wake-up", "Wake-up",
            "Hard to dismiss: solve three math problems. Tip: add a scan of something in another room so you have to get up.",
            Checkin(
                id = "", name = "Wake up", times = listOf(t(6, 30)),
                repeat = Repeat.weekly(Repeat.WEEKDAYS), steps = listOf(StepConfig("m", Steps.MATH, mathCount = 3, mathLevel = 3)),
            ),
        ),
        Template(
            "weekend-wake-up", "Wake-up",
            "A gentler weekend alarm: one easy math problem.",
            Checkin(
                id = "", name = "Weekend wake up", times = listOf(t(8, 30)),
                repeat = Repeat.weekly(Repeat.WEEKENDS), steps = listOf(StepConfig("m", Steps.MATH, mathCount = 1, mathLevel = 1)),
            ),
        ),
        Template(
            "medication", "Health",
            "A daily reminder that won't go away until you confirm. Tip: register a scan of the pill bottle.",
            Checkin(
                id = "", name = "Medication", times = listOf(t(8)),
                steps = qsteps(q("meds", "Did you take your medication?", true)),
            ),
        ),
        Template(
            "teeth", "Health",
            "Morning and night. Tip: register a scan of your toothbrush holder.",
            Checkin(
                id = "", name = "Brush teeth", times = listOf(t(7, 30), t(22, 15)),
                steps = qsteps(q("floss", "Did you floss?", true)),
            ),
        ),
        Template(
            "bedtime", "Health",
            "Wind down for better sleep.",
            Checkin(
                id = "", name = "Bedtime", times = listOf(t(22, 30)),
                steps = qsteps(
                    q("screens", "Are screens off for the night?", true),
                    q("caffeine", "Did you have caffeine after 2 PM?"),
                ),
            ),
        ),
        Template(
            "workout", "Fitness",
            "Mon, Wed and Fri evenings.",
            Checkin(
                id = "", name = "Workout", times = listOf(t(18)),
                repeat = Repeat.weekly(setOf(1, 3, 5)),
                steps = qsteps(q("workout", "Did you work out today?", true)),
            ),
        ),
        Template(
            "stretch", "Fitness",
            "Twice each work day: get up and move.",
            Checkin(
                id = "", name = "Stand & stretch", times = listOf(t(10, 30), t(14, 30)),
                repeat = Repeat.weekly(Repeat.WEEKDAYS),
                steps = qsteps(q("stretch", "Have you stood up and stretched in the last hour?", true)),
            ),
        ),
        Template(
            "steps", "Fitness",
            "An evening check on your daily movement.",
            Checkin(
                id = "", name = "Daily steps", times = listOf(t(20)),
                steps = qsteps(
                    q("walk", "Did you walk for 30 minutes or more today?", true),
                    q("outside", "Did you spend time outside today?", true),
                ),
            ),
        ),
        Template(
            "gym", "Fitness",
            "Rings when you arrive at the gym (on work-out days), with a quick question. Pick your gym after adding it.",
            Checkin(
                id = "", name = "At the gym", trigger = Trigger(kind = Trigger.ARRIVE, oncePerDay = true),
                repeat = Repeat.weekly(setOf(1, 3, 5)),
                steps = qsteps(q("warmup", "Did you plan today's workout before you started?", true)),
            ),
        ),
        Template(
            "after-run", "Fitness",
            "Rings when you finish a run of 10 minutes or more (noticed from your steps): a cool-down check.",
            Checkin(
                id = "", name = "After my run", trigger = Trigger(kind = Trigger.RUN_END, minRunMinutes = 10, oncePerDay = true),
                steps = qsteps(
                    q("stretch-after", "Did you stretch after your run?", true),
                    q("water-after", "Did you drink water after your run?", true),
                ),
            ),
        ),
        Template(
            "weigh-in", "Fitness",
            "Sunday mornings.",
            Checkin(
                id = "", name = "Weekly weigh-in", times = listOf(t(8)),
                repeat = Repeat.weekly(setOf(7)),
                steps = qsteps(q("weighed", "Did you weigh yourself and note it down?", true)),
            ),
        ),
        Template(
            "journal", "Mind",
            "A few minutes of reflection at the end of the day.",
            Checkin(
                id = "", name = "Journal", times = listOf(t(21)),
                steps = qsteps(
                    q("journal", "Did you write in your journal today?", true),
                    q("grateful", "Did you note one thing you're grateful for?", true),
                ),
            ),
        ),
        Template(
            "reading", "Mind",
            "Read a little every day.",
            Checkin(
                id = "", name = "Reading", times = listOf(t(21, 30)),
                steps = qsteps(q("read", "Did you read for at least 15 minutes today?", true)),
            ),
        ),
        Template(
            "money", "Money",
            "On the 1st of each month, look back at last month's spending.",
            Checkin(
                id = "", name = "Monthly money check", times = listOf(t(9)),
                repeat = Repeat.monthly(setOf(1)),
                steps = qsteps(
                    q("reviewed", "Did you review last month's spending?", true),
                    q("budget", "Did you stay within budget last month?", true),
                ),
            ),
        ),
        Template(
            "bills", "Money",
            "Mid-month and month-end bill reminders.",
            Checkin(
                id = "", name = "Pay bills", times = listOf(t(19)),
                repeat = Repeat.monthly(setOf(15, Repeat.LAST_DAY)),
                steps = qsteps(q("paid", "Are all bills due this week paid?", true)),
            ),
        ),
    )

    val CATEGORIES: List<String> get() = TEMPLATES.map { it.category }.distinct()

    fun template(key: String): Template? = TEMPLATES.firstOrNull { it.key == key }

    /**
     * A copy of [template] ready to add to [existing]: a fresh id, and a number after the
     * name if you already have one called that ("Water 2").
     */
    fun instantiate(template: Template, existing: List<Checkin>, newId: String): Checkin {
        val names = existing.map { it.name }.toSet()
        val base = template.checkin.name
        val name = if (base !in names) base else (2..999).map { "$base $it" }.first { it !in names }
        return template.checkin.copy(id = newId, name = name, enabled = true, category = template.category)
    }

    /** A blank check-in for "Create your own". */
    fun blank(existing: List<Checkin>, newId: String) = Checkin(
        id = newId,
        name = "Check-in ${existing.size + 1}",
        times = listOf(AlarmTime(12, 0)),
    )
}
