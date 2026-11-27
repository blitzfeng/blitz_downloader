package com.blitz.downloader.model

/** 两个命中必须来自不同证据内容和不同采样画面。129 大于两个 64 位哈希的最大总距离。 */
internal fun framePairScore(proofs: List<Long>, samples: List<Long>): Int {
    val p = proofs.distinct()
    val s = samples.distinct()
    if (p.size < 2 || s.size < 2) return 129
    var best = 9
    var proof = 0L
    var sample = 0L
    for (a in p) for (b in s) {
        val d = java.lang.Long.bitCount(a xor b)
        if (d < best) { best = d; proof = a; sample = b }
    }
    if (best > 8) return 129
    // 最优二元匹配要么包含最短边，要么另两条边分别占用该边的两个端点。
    var disjoint = 129
    var sameProof = 129
    var sameSample = 129
    for (a in p) for (b in s) {
        val d = java.lang.Long.bitCount(a xor b)
        if (d > 8) continue
        when {
            a != proof && b != sample -> disjoint = minOf(disjoint,d)
            a == proof && b != sample -> sameProof = minOf(sameProof,d)
            a != proof && b == sample -> sameSample = minOf(sameSample,d)
        }
    }
    return minOf(129,best + disjoint,sameProof + sameSample)
}
