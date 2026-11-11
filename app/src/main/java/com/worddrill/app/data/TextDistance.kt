package com.worddrill.app.data

import kotlin.math.min

/** 纯字符串距离工具，出题器和易混词检索共用。 */
object TextDistance {

    /** Levenshtein 距离，滚动数组实现。 */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** 相对长度差是否在容忍范围内。 */
    fun similarLength(a: String, b: String, tolerance: Int = 2): Boolean =
        kotlin.math.abs(a.length - b.length) <= tolerance
}