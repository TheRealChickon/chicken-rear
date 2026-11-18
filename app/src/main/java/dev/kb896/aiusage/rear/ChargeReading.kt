package dev.kb896.aiusage.rear

import dev.kb896.aiusage.service.ChargePrecise

/** A page can only display a battery sample taken since it was last shown. Guarded by ShellCharge's frame lock. */
internal class ChargeReading {
    data class Sample(val detail: ChargePrecise.Detail, val kind: String)

    private var sampledAt = 0L
    var sample: Sample? = null
        private set

    fun refresh(now: Long) {
        sampledAt = now
        sample = null
    }

    fun update(at: Long, next: Sample) {
        if (at <= sampledAt) return
        sampledAt = at
        sample = next.takeIf { it.detail.level in 0..100 }
    }
}
