package uy.ct.shortener.security.internal

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/** Native-image hints that register every Caffeine-generated cache and entry class on the classpath for reflection. */
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
