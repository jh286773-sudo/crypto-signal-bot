package com.cryptoscalp.app

/** Per-symbol protection after two net losing LONG positions in a rolling 24-hour window. */
internal object LossCooldownPolicy {
    const val LOSS_WINDOW_MS = 24L * 60L * 60L * 1000L
    const val COOLDOWN_MS = 24L * 60L * 60L * 1000L
    const val LOSS_LIMIT = 2

    data class Update(val recentLosses: List<Long>, val cooldownUntilMs: Long)

    fun recordLoss(existing: List<Long>, nowMs: Long): Update {
        val recent = (existing.filter { it in (nowMs - LOSS_WINDOW_MS + 1)..nowMs } + nowMs).sorted()
        return if (recent.size >= LOSS_LIMIT) {
            Update(emptyList(), nowMs + COOLDOWN_MS)
        } else {
            Update(recent, 0L)
        }
    }

    /** Kept as a source-compatible alias for older callers and tests. */
    fun recordFullStop(existing: List<Long>, nowMs: Long): Update = recordLoss(existing, nowMs)

    fun isActive(cooldownUntilMs: Long, nowMs: Long): Boolean =
        cooldownUntilMs > nowMs
}
