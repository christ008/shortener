package uy.ct.shortener.analytics.internal

import uy.ct.shortener.shortlink.ShortCode

/**
 * Counts one click, on the thread of the redirect that caused it.
 *
 * - [ClickAggregator] adds it to a count held in memory.
 * - [NoClickRecorder] is the switched-off counting: it keeps nothing.
 * - An implementation never blocks and never throws.
 */
interface ClickRecorder {

    fun record(shortCode: ShortCode, device: DeviceClass)
}

object NoClickRecorder : ClickRecorder {

    override fun record(shortCode: ShortCode, device: DeviceClass) = Unit
}
