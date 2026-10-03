package uy.ct.shortener.security.internal

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Native-image hints that let Caffeine build its caches.
 *
 * Caffeine generates one cache class and one entry class per feature combination (`SSMSA` is
 * strong keys, strong values, size bound, expire-after-access) and picks one by name at runtime,
 * instantiating it reflectively and reading its fields through `VarHandle`. Nothing registers
 * these for a native image, and the community metadata only covers the combinations in
 * Caffeine's own tests. Rather than guess which one a configuration needs, every generated class
 * found on the classpath is registered when the application is processed, so the hints follow
 * the Caffeine version in use.
 */
class CaffeineRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        PathMatchingResourcePatternResolver(classLoader)
            .getResources("classpath*:${PACKAGE.replace('.', '/')}/*.class")
            .mapNotNull { it.filename?.removeSuffix(".class") }
            .filter { GENERATED.matches(it) || it in FIELD_ACCESSED }
            .forEach {
                hints.reflection().registerType(
                    TypeReference.of("$PACKAGE.$it"),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                )
            }
    }

    private companion object {
        const val PACKAGE = "com.github.benmanes.caffeine.cache"

        val GENERATED = Regex("[A-Z]{1,9}")

        val FIELD_ACCESSED = setOf(
            "BBHeader\$ReadAndWriteCounterRef",
            "BBHeader\$ReadCounterRef",
            "BLCHeader\$DrainStatusRef",
            "BaseMpscLinkedArrayQueueColdProducerFields",
            "BaseMpscLinkedArrayQueueConsumerFields",
            "BaseMpscLinkedArrayQueueProducerFields",
            "BoundedLocalCache",
            "StripedBuffer",
            "UnboundedLocalCache",
        )
    }
}
