package com.dividendstream.app.core

/**
 * Compares dotted release numbers, so the app can tell whether it is behind.
 *
 * Deliberately narrow: it understands "1.0.1" and stops at the first thing it does not, because
 * the alternative -- guessing at "1.0.1-beta2" -- risks nagging every user on a release that is
 * actually current. An unparseable version is treated as "cannot tell", and the app then says
 * nothing at all.
 */
object AppVersion {

    /**
     * True when [latest] is a strictly higher release than [current].
     *
     * False whenever the comparison cannot be trusted -- either side blank, unparseable, or
     * equal. False is the safe answer: a missed prompt is a mild inconvenience, while a prompt
     * that will not go away because the app misread its own version is worse than no prompt.
     */
    fun isOutdated(current: String, latest: String?): Boolean {
        val a = parse(current) ?: return false
        val b = parse(latest ?: return false) ?: return false

        // Compare position by position, treating a missing part as zero, so 1.1 beats 1.0.9
        // and 1.0 does not beat 1.0.0.
        for (i in 0 until maxOf(a.size, b.size)) {
            val mine = a.getOrElse(i) { 0 }
            val theirs = b.getOrElse(i) { 0 }
            if (theirs != mine) return theirs > mine
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val parts = version.trim().takeIf { it.isNotEmpty() }?.split('.') ?: return null
        return parts.map { part -> part.toIntOrNull() ?: return null }
    }
}
