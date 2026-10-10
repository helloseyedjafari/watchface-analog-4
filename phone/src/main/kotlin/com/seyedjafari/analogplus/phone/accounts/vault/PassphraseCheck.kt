package com.seyedjafari.analogplus.phone.accounts.vault

import kotlin.math.ln

enum class PassphraseStrength { TOO_SHORT, WEAK, GOOD, STRONG }

/** The shortest passphrase accepted, in characters (code points). */
const val MIN_PASSPHRASE_LENGTH = 12

/** A passphrase is accepted at [PassphraseStrength.GOOD] or better. */
fun PassphraseStrength.isAcceptable(): Boolean = this >= PassphraseStrength.GOOD

/**
 * A rough guess, not a guarantee: estimated bits = effective length x log2(size of the character pool used), where
 * every character counts fully the first time it appears and a quarter after that. Four random words score STRONG;
 * `aaaaaaaaaaaa` and `qwertyuiopas` do not reach GOOD. Under 60 bits is WEAK, under 80 GOOD, otherwise STRONG.
 */
fun checkPassphrase(passphrase: String): PassphraseStrength {
    val points = passphrase.codePoints().toArray()
    if (points.size < MIN_PASSPHRASE_LENGTH) return PassphraseStrength.TOO_SHORT
    var pool = 0
    if (points.any { it in 'a'.code..'z'.code }) pool += 26
    if (points.any { it in 'A'.code..'Z'.code }) pool += 26
    if (points.any { it in '0'.code..'9'.code }) pool += 10
    if (points.any { it < 128 && !Character.isLetterOrDigit(it) }) pool += 33
    if (points.any { it >= 128 }) pool += 100
    val distinct = points.toSet().size
    // a repeated character adds little: each one beyond its first use counts a quarter
    val effectiveLength = distinct + 0.25 * (points.size - distinct)
    val bits = effectiveLength * ln(pool.toDouble()) / ln(2.0)
    return when {
        bits < 60 -> PassphraseStrength.WEAK
        bits < 80 -> PassphraseStrength.GOOD
        else -> PassphraseStrength.STRONG
    }
}
