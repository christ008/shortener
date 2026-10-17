package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Which requests came in through the management port.
 *
 * - Before that server is up the environment does not know its port, and no request is one of its own.
 * - Once the environment answers the port is kept, and only a request to that port is the management server's.
 * - With no separate management port, nothing is.
 */
class ManagementPortTest {

    private fun request(port: Int) = MockHttpServletRequest().apply { localPort = port }

    @Test
    fun `a request to the management port is told apart once the port is known, and not before`() {
        val environment = MockEnvironment()
        val management = ManagementPort(environment)

        assertThat(management.serves(request(8081))).isFalse()

        environment.setProperty("local.management.port", "8081")

        assertThat(management.serves(request(8081))).isTrue()
        assertThat(management.serves(request(8080))).isFalse()
    }

    @Test
    fun `the port is kept once it is known`() {
        val environment = MockEnvironment().apply { setProperty("local.management.port", "8081") }
        val management = ManagementPort(environment)
        assertThat(management.serves(request(8081))).isTrue()

        environment.setProperty("local.management.port", "9999")

        assertThat(management.serves(request(8081))).isTrue()
        assertThat(management.serves(request(9999))).isFalse()
    }
}
