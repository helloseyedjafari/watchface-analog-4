package com.seyedjafari.analogplus.phone.accounts.vault

import java.text.Normalizer
import kotlin.math.ln

enum class PassphraseStrength { TOO_SHORT, WEAK, GOOD, STRONG }

/**
 * Why a passphrase was rated [PassphraseStrength.WEAK], for the UI to explain (it maps each value to a string resource).
 * [NONE] means nothing specific: too short, or simply not enough guesses to get past, or the verdict is GOOD or better.
 */
enum class PassphraseIssue {
    NONE,

    /** A known common password, possibly with a few digits or symbols in front or behind and letters swapped for look-alikes. */
    COMMON_PASSWORD,

    /** Mostly an ascending or descending run such as `abcdef` or `87654`. */
    SEQUENCE,

    /** Mostly neighbouring keys of one keyboard row, such as `qwerty` or `asdfgh`, in either direction. */
    KEYBOARD_PATTERN,

    /** Mostly a chunk repeated, such as `abcabcabc` or `zzzzzz`. */
    REPETITION,
}

/** The verdict of [assessPassphrase]: how strong it is and, when it is weak for a nameable reason, why. */
data class PassphraseAssessment(val strength: PassphraseStrength, val issue: PassphraseIssue)

/** The shortest passphrase accepted, in characters (code points after Unicode NFKC normalisation). */
const val MIN_PASSPHRASE_LENGTH = 12

/** A passphrase is accepted at [PassphraseStrength.GOOD] or better. */
fun PassphraseStrength.isAcceptable(): Boolean = this >= PassphraseStrength.GOOD

/** The strength alone; see [assessPassphrase] for the reason as well. */
fun checkPassphrase(passphrase: String): PassphraseStrength = assessPassphrase(passphrase).strength

/**
 * A rough guess, not a guarantee, made on the same text that [KeyVault] derives the key from (Unicode NFKC).
 *
 * 1. Under [MIN_PASSPHRASE_LENGTH] characters is [PassphraseStrength.TOO_SHORT].
 * 2. A known common password (the blocklist below), in any case, with look-alike substitutions undone (`@`->a, `0`->o, ...),
 *    with up to four digits or symbols in front and behind, is [PassphraseStrength.WEAK] with [PassphraseIssue.COMMON_PASSWORD]
 *    whatever its length or mix of characters: `Password1234` has twelve characters in three classes and is still one of the
 *    first things an attacker tries.
 * 3. Otherwise the estimate is `bits = sum of per-character cost x log2(size of the character pool used)`. A character costs 1
 *    when it is new, 0.25 when it was used before, and 0.1 when it is predictable from what precedes it: it continues an
 *    ascending or descending run (`abcd`, `4321`), it continues a run along a keyboard row (`qwer`, `lkjh`), it is part of a
 *    chunk of three or more characters already seen (`abcabc`), or it is part of a blocklisted word of five letters or more
 *    hidden inside the text (`xxpasswordxx`, with the same look-alike handling). Under 60 bits is WEAK, under 80 GOOD,
 *    otherwise STRONG. Four random words score STRONG; sixteen random characters score GOOD or STRONG.
 * 4. When the verdict is WEAK and a pattern accounts for at least 40% of the characters, [PassphraseIssue] names it.
 *
 * The first 256 characters are examined; more characters can only add bits, so cutting there is conservative.
 */
fun assessPassphrase(passphrase: String): PassphraseAssessment {
    val points = Normalizer.normalize(passphrase, Normalizer.Form.NFKC).codePoints().toArray()
    if (points.size < MIN_PASSPHRASE_LENGTH) return PassphraseAssessment(PassphraseStrength.TOO_SHORT, PassphraseIssue.NONE)
    val head = if (points.size > MAX_EXAMINED) points.copyOf(MAX_EXAMINED) else points
    val lower = IntArray(head.size) { Character.toLowerCase(head[it]) }

    if (isCommonPassword(lower)) return PassphraseAssessment(PassphraseStrength.WEAK, PassphraseIssue.COMMON_PASSWORD)

    val cost = DoubleArray(head.size) { 1.0 }
    val kind = Array(head.size) { PassphraseIssue.NONE }
    val seen = HashSet<Int>()
    for (i in head.indices) if (!seen.add(head[i])) cost[i] = REPEATED_CHARACTER_COST
    fun predictable(i: Int, why: PassphraseIssue, price: Double) {
        if (cost[i] > price) { cost[i] = price; kind[i] = why }
    }

    // the first pattern to claim a character names it, so a run along a keyboard row reads as a keyboard pattern even
    // when the same letters are also a blocklisted word
    markRepeatedChunks(lower, ::predictable)
    markSequences(lower, ::predictable)
    markKeyboardRuns(lower, ::predictable)
    markBlocklistedWords(lower) { predictable(it, PassphraseIssue.COMMON_PASSWORD, HIDDEN_WORD_COST) }

    var pool = 0
    if (head.any { it in 'a'.code..'z'.code }) pool += 26
    if (head.any { it in 'A'.code..'Z'.code }) pool += 26
    if (head.any { it in '0'.code..'9'.code }) pool += 10
    if (head.any { it < 128 && !Character.isLetterOrDigit(it) }) pool += 33
    if (head.any { it >= 128 }) pool += 100
    val bits = cost.sum() * ln(pool.toDouble()) / ln(2.0)
    val strength = when {
        bits < 60 -> PassphraseStrength.WEAK
        bits < 80 -> PassphraseStrength.GOOD
        else -> PassphraseStrength.STRONG
    }
    if (strength != PassphraseStrength.WEAK) return PassphraseAssessment(strength, PassphraseIssue.NONE)
    val counts = kind.filter { it != PassphraseIssue.NONE }.groupingBy { it }.eachCount()
    val main = counts.maxByOrNull { it.value }
    val issue = if (main != null && main.value * 10 >= head.size * 4) main.key else PassphraseIssue.NONE
    return PassphraseAssessment(strength, issue)
}

private const val MAX_EXAMINED = 256
private const val REPEATED_CHARACTER_COST = 0.25
private const val PREDICTABLE_COST = 0.1
private const val HIDDEN_WORD_COST = 0.15

/** A run or repeated chunk is worth its first few characters; a hundred more of the same add nothing a guesser must search. */
private const val FULL_PRICE_RUN = 3
private const val MAX_DECORATION = 4
private const val MIN_HIDDEN_WORD = 5
private const val MIN_CHUNK = 3

private val KEYBOARD_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")

/**
 * Common passwords and their kin, lower case. Matched after look-alike substitution, so `p@ssw0rd` is `password`. Not a
 * dictionary: the point is the strings that every guessing list starts with, so that "twelve characters, three classes"
 * does not pass for strength. Entries of five letters or more also count when hidden inside a longer text.
 */
private val BLOCKLIST: List<String> = listOf(
    "password", "passwort", "pass1234", "qwerty", "qwertyui", "qwertyuiop", "qwertz", "azerty", "asdfgh", "asdfghjkl",
    "zxcvbn", "zxcvbnm", "1qaz2wsx", "1q2w3e4r", "qazwsx", "letmein", "welcome", "admin", "administrator", "iloveyou",
    "abc123", "123456", "1234567", "12345678", "123456789", "1234567890", "111111", "000000", "123123", "654321",
    "monkey", "dragon", "master", "login", "princess", "sunshine", "football", "baseball", "superman", "batman",
    "trustno1", "starwars", "whatever", "shadow", "michael", "michelle", "jessica", "freedom", "hello", "secret",
    "changeme", "default", "guest", "root", "mustang", "ninja", "loveme", "hottie", "soccer", "hockey", "killer",
    "samsung", "google", "android", "recovery", "backup",
)

/** The usual look-alikes, one character to one character so that positions stay aligned. `1` is both `i` and `l`. */
private fun undoLookAlikes(cps: IntArray, oneIs: Char): IntArray = IntArray(cps.size) {
    when (cps[it]) {
        '@'.code, '4'.code -> 'a'.code
        '8'.code -> 'b'.code
        '3'.code -> 'e'.code
        '9'.code -> 'g'.code
        '!'.code -> 'i'.code
        '1'.code -> oneIs.code
        '0'.code -> 'o'.code
        '$'.code, '5'.code -> 's'.code
        '7'.code, '+'.code -> 't'.code
        else -> cps[it]
    }
}

private val LOOK_ALIKE_ONES = charArrayOf('i', 'l')

private fun IntArray.asKey(): String = String(this, 0, size)

private fun String.codePointArray(): IntArray = codePoints().toArray()

private val BLOCKSET: Set<String> by lazy {
    BLOCKLIST.flatMapTo(HashSet()) { word -> LOOK_ALIKE_ONES.map { undoLookAlikes(word.codePointArray(), it).asKey() } }
}

private val HIDDEN_WORDS: List<IntArray> by lazy {
    BLOCKLIST.filter { w -> w.count { it.isLetter() } >= MIN_HIDDEN_WORD }.flatMap { w ->
        LOOK_ALIKE_ONES.map { undoLookAlikes(w.codePointArray(), it) }
    }
}

/** True when the text is a blocklisted password with up to [MAX_DECORATION] digits or symbols in front and/or behind. */
private fun isCommonPassword(lower: IntArray): Boolean {
    var maxFront = 0
    while (maxFront < MAX_DECORATION && maxFront < lower.size && !Character.isLetter(lower[maxFront])) maxFront++
    var maxBack = 0
    while (maxBack < MAX_DECORATION && maxBack < lower.size && !Character.isLetter(lower[lower.size - 1 - maxBack])) maxBack++
    for (front in 0..maxFront) {
        for (back in 0..maxBack) {
            if (front + back >= lower.size) continue
            val core = lower.copyOfRange(front, lower.size - back)
            if (LOOK_ALIKE_ONES.any { undoLookAlikes(core, it).asKey() in BLOCKSET }) return true
        }
    }
    return false
}

/** Every position of every blocklisted word of five or more letters that occurs in the text (look-alikes undone). */
private fun markBlocklistedWords(lower: IntArray, mark: (Int) -> Unit) {
    for (oneIs in LOOK_ALIKE_ONES) {
        val text = undoLookAlikes(lower, oneIs)
        for (word in HIDDEN_WORDS) {
            for (at in 0..text.size - word.size) {
                var k = 0
                while (k < word.size && text[at + k] == word[k]) k++
                if (k == word.size) for (m in at until at + word.size) mark(m)
            }
        }
    }
}

/** A character that continues a run of constant step -1 or +1 (`abc`, `321`). Constant step 0 is a repetition, handled elsewhere. */
private fun markSequences(lower: IntArray, mark: (Int, PassphraseIssue, Double) -> Unit) {
    var run = 0
    for (i in 2 until lower.size) {
        val d1 = lower[i] - lower[i - 1]
        val d2 = lower[i - 1] - lower[i - 2]
        if (d1 == d2 && (d1 == 1 || d1 == -1)) {
            run++
            mark(i, PassphraseIssue.SEQUENCE, if (run <= FULL_PRICE_RUN) PREDICTABLE_COST else 0.0)
        } else {
            run = 0
        }
    }
}

private fun rowIndex(row: String, cp: Int): Int = if (cp < 128) row.indexOf(cp.toChar()) else -1

/** A character that continues a run of constant step -1 or +1 along one row of the keyboard. */
private fun markKeyboardRuns(lower: IntArray, mark: (Int, PassphraseIssue, Double) -> Unit) {
    for (row in KEYBOARD_ROWS) {
        var run = 0
        for (i in 2 until lower.size) {
            val a = rowIndex(row, lower[i - 2])
            val b = rowIndex(row, lower[i - 1])
            val c = rowIndex(row, lower[i])
            if (a >= 0 && b >= 0 && c >= 0 && b - a == c - b && (c - b == 1 || c - b == -1)) {
                run++
                mark(i, PassphraseIssue.KEYBOARD_PATTERN, if (run <= FULL_PRICE_RUN) PREDICTABLE_COST else 0.0)
            } else {
                run = 0
            }
        }
    }
}

/** Positions inside a chunk of [MIN_CHUNK] or more characters that already occurred earlier (overlap allowed: `zzzz`, `abab`). */
private fun markRepeatedChunks(lower: IntArray, mark: (Int, PassphraseIssue, Double) -> Unit) {
    var i = 1
    while (i < lower.size) {
        var best = 0
        for (j in 0 until i) {
            var l = 0
            while (i + l < lower.size && lower[j + l] == lower[i + l]) l++
            if (l > best) best = l
        }
        if (best >= MIN_CHUNK) {
            for (k in i until i + best) mark(k, PassphraseIssue.REPETITION, if (k - i < FULL_PRICE_RUN) PREDICTABLE_COST else 0.0)
            i += best
        } else {
            i++
        }
    }
}
