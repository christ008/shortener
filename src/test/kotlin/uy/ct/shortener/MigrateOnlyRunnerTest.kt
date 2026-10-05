package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.env.MockEnvironment

/**
 * With `shortener.migrate-only` the process ends once the schema is up to date, which is how the init container of the
 * Deployment applies migrations; without it, the application carries on and serves requests.
 */
class MigrateOnlyRunnerTest {

    private class Recording(context: GenericApplicationContext, environment: MockEnvironment) : MigrateOnlyRunner(context, environment) {
        val exitCodes = mutableListOf<Int>()
        override fun terminate(code: Int) {
            exitCodes += code
        }
    }

    private fun runner(property: String?): Recording {
        val context = GenericApplicationContext().apply { refresh() }
        val environment = MockEnvironment().apply { if (property != null) setProperty("shortener.migrate-only", property) }
        return Recording(context, environment)
    }

    @Test
    fun `ends the process, successfully, when migrate-only is set`() {
        val runner = runner("true")

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCodes).containsExactly(0)
    }

    @Test
    fun `leaves the application running when migrate-only is not set`() {
        val runner = runner(null)

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCodes).isEmpty()
    }

    @Test
    fun `leaves the application running when migrate-only is false`() {
        val runner = runner("false")

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCodes).isEmpty()
    }
}
