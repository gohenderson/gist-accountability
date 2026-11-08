package dev.gist.checkin

import kotlin.random.Random

/** Wake-up math problems. Answers are always positive whole numbers. */
object MathGen {
    data class Problem(val text: String, val answer: Long)
    data class Level(val name: String, val example: String)

    val LEVELS = listOf(
        Level("Easy", "47 + 38"),
        Level("Medium", "47 × 6 + 23"),
        Level("Hard", "47 × 38 + 512"),
        Level("Very hard", "347 × 38 + 47 × 26"),
    )

    fun levelName(level: Int): String = LEVELS[(level - 1).coerceIn(0, LEVELS.size - 1)].name

    fun make(level: Int, rnd: Random = Random.Default): Problem {
        fun r(range: IntRange) = rnd.nextInt(range).toLong()
        return when (level) {
            1 -> {
                val a = r(10..99); val b = r(10..99)
                Problem("$a + $b", a + b)
            }
            2 -> {
                val a = r(10..99); val b = r(3..9); val c = r(10..99)
                Problem("$a × $b + $c", a * b + c)
            }
            3 -> {
                val a = r(11..99); val b = r(11..99); val c = r(100..999)
                Problem("$a × $b + $c", a * b + c)
            }
            else -> {
                val a = r(101..999); val b = r(11..99); val c = r(11..99); val d = r(11..99)
                Problem("$a × $b + $c × $d", a * b + c * d)
            }
        }
    }
}
