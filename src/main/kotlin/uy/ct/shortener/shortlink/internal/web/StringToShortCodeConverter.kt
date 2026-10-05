package uy.ct.shortener.shortlink.internal.web

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.ShortCode

/**
 * Binds path variables to [ShortCode]. A malformed value is rejected with a 400.
 */
@Component
class StringToShortCodeConverter : Converter<String, ShortCode> {

    override fun convert(source: String): ShortCode = ShortCode(source)
}
