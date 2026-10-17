package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.util.Locale

/**
 * The classification does not depend on the default locale of the JVM. Under Turkish rules `I` lowercases to a dotless `ı`, so a
 * classifier that lowercased in the default locale would miss `iphone`, `mobi`, `spider` and `facebookexternalhit` in an uppercase header.
 *
 * - The test sets the default locale of the whole JVM, so it is [Isolated]: no other test runs while it does, including the classes
 *   that run concurrently.
 */
@Isolated
class DeviceClassifierLocaleTest {

    @Test
    fun `case does not matter under Turkish rules either`() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.of("tr"))
        try {
            assertThat(DeviceClassifier.classify("MOZILLA/5.0 (IPHONE; CPU IPHONE OS 18_0 LIKE MAC OS X) MOBILE/15E148")).isEqualTo(DeviceClass.MOBILE)
            assertThat(DeviceClassifier.classify("MOZILLA/5.0 (COMPATIBLE; BAIDUSPIDER/2.0)")).isEqualTo(DeviceClass.BOT)
            assertThat(DeviceClassifier.classify("FACEBOOKEXTERNALHIT/1.1")).isEqualTo(DeviceClass.BOT)
            assertThat(DeviceClassifier.classify("MOZILLA/5.0 (WINDOWS NT 10.0; WIN64; X64) CHROME/130.0")).isEqualTo(DeviceClass.DESKTOP)
        } finally {
            Locale.setDefault(default)
        }
    }
}
