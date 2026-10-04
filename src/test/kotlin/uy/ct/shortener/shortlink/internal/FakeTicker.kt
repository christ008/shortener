package uy.ct.shortener.shortlink.internal

import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration

/** A clock the test moves by hand, so cache expiry is exact and nothing sleeps. */
class FakeTicker : Ticker {
    private var nanos = 0L

    override fun read(): Long = nanos

    fun advance(duration: Duration) {
        nanos += duration.toNanos()
    }
}
