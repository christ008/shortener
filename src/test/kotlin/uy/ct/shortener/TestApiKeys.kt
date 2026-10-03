package uy.ct.shortener

/**
 * API key used by integration tests. [SHA256] is the digest of [PLAINTEXT], which is what the
 * application is configured with; tests send [PLAINTEXT] as the bearer token.
 */
object TestApiKeys {
    const val CLIENT = "test-client"
    const val PLAINTEXT = "shk_test_Zk3Qm9xV7bW2nR5tY8cA1dF4gH6jL0pS"
    const val SHA256 = "9fc413a8b2fbf3d0c84a5cdfba51e62ac24f89016d3d1f0ea28bd7fe59249ff7"
    const val PROPERTY = "shortener.security.api-keys.$CLIENT=$SHA256"
}
