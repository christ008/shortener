package uy.ct.shortener.shortlink.internal.web

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode

/** Lets controllers bind `@PathVariable` directly to [ShortCode]; a malformed value fails binding with a 400 rather than reaching the service. */
@Component
class StringToShortCodeConverter : Converter<String, ShortCode> {

    override fun convert(source: String): ShortCode = ShortCode(source)
}
