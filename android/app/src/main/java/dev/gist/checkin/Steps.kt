package dev.gist.checkin

import org.json.JSONArray
import org.json.JSONObject

/**
 * One step that runs when a check-in rings. A check-in has any number of them, of any kinds,
 * in the order you choose (two math steps around a scan, ten very hard math steps…):
 *  - [Steps.MATH]: solve [mathCount] problems at [mathLevel];
 *  - [Steps.SCAN]: scan [scanCode] (at [scanLabel]);
 *  - [Steps.QUESTIONS]: answer [questions]. Their answers are what gets logged.
 * A step that isn't set up yet (a scan without a code, questions without any) is skipped.
 */
data class StepConfig(
    val id: String,
    val kind: String,
    val mathCount: Int = 3,
    val mathLevel: Int = 2,       // 1..4, see MathGen.LEVELS
    val scanCode: String? = null, // exact text of the registered QR code / barcode
    val scanLabel: String = "",   // where it is, e.g. "Bathroom sink"
    val questions: List<Question> = emptyList(),
) {
    val configured: Boolean get() = when (kind) {
        Steps.MATH -> mathCount > 0
        Steps.SCAN -> scanCode != null
        Steps.QUESTIONS -> questions.isNotEmpty()
        else -> false
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).apply {
        when (kind) {
            Steps.MATH -> put("mathCount", mathCount).put("mathLevel", mathLevel)
            Steps.SCAN -> put("scanCode", scanCode ?: "").put("scanLabel", scanLabel)
            Steps.QUESTIONS -> put("questions", JSONArray().apply {
                questions.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("yesIsGood", it.yesIsGood)) }
            })
        }
    }

    companion object {
        fun fromJson(o: JSONObject): StepConfig? {
            val kind = o.optString("kind").takeIf { it in Steps.ALL } ?: return null
            val qs = o.optJSONArray("questions") ?: JSONArray()
            return StepConfig(
                id = o.optString("id", "").ifEmpty { Store.newId() },
                kind = kind,
                mathCount = o.optInt("mathCount", 3).coerceIn(0, 100),
                mathLevel = o.optInt("mathLevel", 2).coerceIn(1, MathGen.LEVELS.size),
                scanCode = o.optString("scanCode", "").ifEmpty { null },
                scanLabel = o.optString("scanLabel", ""),
                questions = (0 until qs.length()).mapNotNull { i ->
                    qs.optJSONObject(i)?.let { q ->
                        q.optString("text", "").takeIf { it.isNotBlank() }?.let {
                            Question(q.optString("id", "").ifEmpty { Store.newId() }, it, q.optBoolean("yesIsGood", false))
                        }
                    }
                },
            )
        }
    }
}

object Steps {
    const val MATH = "math"
    const val SCAN = "scan"
    const val QUESTIONS = "questions"
    val ALL = listOf(MATH, SCAN, QUESTIONS)
    const val MAX_STEPS = 100
    const val MAX_PROBLEMS = 100

    fun math(count: Int = 3, level: Int = 2) = StepConfig(Store.newId(), MATH, mathCount = count, mathLevel = level)
    fun scan(code: String?, label: String = "") = StepConfig(Store.newId(), SCAN, scanCode = code, scanLabel = label)
    fun questions(vararg qs: Question) = StepConfig(Store.newId(), QUESTIONS, questions = qs.toList())
    fun questions(qs: List<Question>) = StepConfig(Store.newId(), QUESTIONS, questions = qs)

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

    /** What actually runs when it rings, in order. */
    fun active(c: Checkin): List<StepConfig> = c.steps.filter { it.configured }

    /** e.g. "3 × Medium", "Bathroom sink", "5 questions"; or what's missing. */
    fun summary(s: StepConfig): String = when (s.kind) {
        MATH -> if (s.mathCount > 0) "${s.mathCount} × ${MathGen.levelName(s.mathLevel)}" else "Not set up"
        SCAN -> if (s.scanCode != null) s.scanLabel.ifEmpty { "Registered code" } else "Not set up: register a code"
        else -> if (s.questions.isEmpty()) "Not set up: add a question" else
            "${s.questions.size} question${if (s.questions.size == 1) "" else "s"}"
    }

    /** Adds a [kind] step at the end, with defaults (math: 3 medium problems). */
    fun add(c: Checkin, kind: String): Checkin =
        if (c.steps.size >= MAX_STEPS) c else c.copy(steps = c.steps + StepConfig(Store.newId(), kind))

    /** Removes the step with [stepId] (a scan's code, or its questions, go with it; logged answers stay). */
    fun remove(c: Checkin, stepId: String): Checkin = c.copy(steps = c.steps.filter { it.id != stepId })

    /** A copy of the step right after it. */
    fun duplicate(c: Checkin, stepId: String): Checkin {
        val i = c.steps.indexOfFirst { it.id == stepId }
        if (i < 0 || c.steps.size >= MAX_STEPS) return c
        val src = c.steps[i]
        // Questions get new ids: each question is logged separately.
        val copy = src.copy(id = Store.newId(), questions = src.questions.map { it.copy(id = Store.newId()) })
        return c.copy(steps = c.steps.toMutableList().apply { add(i + 1, copy) })
    }

    fun update(c: Checkin, stepId: String, f: (StepConfig) -> StepConfig): Checkin =
        c.copy(steps = c.steps.map { if (it.id == stepId) f(it) else it })

    /** Moves the step at [from] to [to]. */
    fun move(c: Checkin, from: Int, to: Int): Checkin {
        val list = c.steps.toMutableList()
        if (from !in list.indices || to !in list.indices) return c
        list.add(to, list.removeAt(from))
        return c.copy(steps = list)
    }
}
