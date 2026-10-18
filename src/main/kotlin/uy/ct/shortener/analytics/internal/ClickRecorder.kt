package uy.ct.shortener.analytics.internal

import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.shortlink.ShortCode

/** One followed link, as counting sees it: the link, the class of the client, where it came from and its language. */
data class Click(val shortCode: ShortCode, val device: DeviceClass, val referrer: Referrer, val language: Language)

/**
 * Counts one click, on the thread of the redirect that caused it.
 *
 * - [ClickAggregator] adds it to counts held in memory.
 * - [NoClickRecorder] is the switched-off counting: it keeps nothing.
 * - An implementation never blocks and never throws.
 */
fun interface ClickRecorder {

    fun record(click: Click)
}

object NoClickRecorder : ClickRecorder {

    override fun record(click: Click) = Unit
}
