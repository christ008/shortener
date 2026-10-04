package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.context.annotation.Import

/**
 * Tomcat accepts a bounded number of connections, because every open connection keeps about 150 KB
 * of buffers on the heap and an unbounded number made the native image run out of memory under
 * overload. The excess waits in the accept queue and then is refused, which sheds load instead of
 * exhausting memory. Both limits can be changed through `SERVER_TOMCAT_MAXCONNECTIONS` and
 * `SERVER_TOMCAT_ACCEPTCOUNT`.
 */
@WithTestIdp
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ConnectionLimitsTest {

    @Autowired
    lateinit var tomcat: TomcatServerProperties

    @Test
    fun `bounds the connections Tomcat accepts and queues`() {
        assertThat(tomcat.maxConnections).isEqualTo(500)
        assertThat(tomcat.acceptCount).isEqualTo(100)
    }
}
