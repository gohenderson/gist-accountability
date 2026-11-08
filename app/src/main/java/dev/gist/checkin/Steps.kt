package dev.gist.checkin

/**
 * The steps that run when a check-in rings, in the order you choose (the edit page's
 * "When it rings" list, reordered by dragging). Each kind appears at most once and is set
 * up on its own page ([StepEditActivity]):
 *  - [MATH]: solve [Checkin.mathCount] problems at [Checkin.mathLevel];
 *  - [SCAN]: scan the registered [Checkin.scanCode];
 *  - [QUESTIONS]: answer [Checkin.questions] (the only answers that get logged).
 * A step that's in the list but not set up yet (no code registered, no questions) is
 * skipped when ringing.
 */
object Steps {
    const val MATH = "math"
    const val SCAN = "scan"
    const val QUESTIONS = "questions"
    val ALL = listOf(MATH, SCAN, QUESTIONS)

    fun title(kind: String): String = when (kind) {
        MATH -> "Math problems"
        SCAN -> "Scan a code"
        else -> "Questions"
    }

    fun blurb(kind: String): String = when (kind) {
        MATH -> "Solve a few problems to prove you're awake."
        SCAN -> "Get up and scan a QR code or barcode you've registered (e.g. on the bathroom sink)."
        else -> "Yes/no questions. These answers are logged and shown in History."
    }

    /** Is [kind] set up enough to run? */
    fun configured(c: Checkin, kind: String): Boolean = when (kind) {
        MATH -> c.mathCount > 0
        SCAN -> c.scanCode != null
        QUESTIONS -> c.questions.isNotEmpty()
        else -> false
    }

    /**
     * The check-in's steps in order: the saved order, then any step that's set up but not
     * in it (e.g. a check-in made in code with just mathCount), in the default order.
     */
    fun order(c: Checkin): List<String> =
        (c.steps.filter { it in ALL } + ALL.filter { configured(c, it) }).distinct()

    /** What actually runs when it rings, in order. */
    fun active(c: Checkin): List<String> = order(c).filter { configured(c, it) }

    /** Kinds that can still be added. */
    fun available(c: Checkin): List<String> = ALL - order(c).toSet()

    /** e.g. "3 × Medium", "Bathroom sink", "5 questions"; or what's missing. */
    fun summary(c: Checkin, kind: String): String = when (kind) {
        MATH -> if (c.mathCount > 0) "${c.mathCount} × ${MathGen.levelName(c.mathLevel)}" else "Not set up"
        SCAN -> if (c.scanCode != null) c.scanLabel.ifEmpty { "Registered code" } else "Not set up: register a code"
        else -> if (c.questions.isEmpty()) "Not set up: add a question" else
            "${c.questions.size} question${if (c.questions.size == 1) "" else "s"}"
    }

    /** Adds [kind] at the end, with sensible defaults (math: 3 medium problems). */
    fun add(c: Checkin, kind: String): Checkin {
        val withKind = c.copy(steps = order(c) + kind)
        return if (kind == MATH && c.mathCount == 0) withKind.copy(mathCount = 3, mathLevel = 2) else withKind
    }

    /** Removes [kind] and its setup (the code, or the questions; logged answers stay). */
    fun remove(c: Checkin, kind: String): Checkin {
        val rest = order(c) - kind
        return when (kind) {
            MATH -> c.copy(steps = rest, mathCount = 0)
            SCAN -> c.copy(steps = rest, scanCode = null, scanLabel = "")
            else -> c.copy(steps = rest, questions = emptyList())
        }
    }

    /** Moves the step at [from] to [to] (indices into [order]). */
    fun move(c: Checkin, from: Int, to: Int): Checkin {
        val list = order(c).toMutableList()
        if (from !in list.indices || to !in list.indices) return c
        list.add(to, list.removeAt(from))
        return c.copy(steps = list)
    }
}
