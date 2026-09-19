package uy.ct.shortener.shortlink.internal

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.net.URI

/** [URI] over [java.net.URL]: `URL.equals`/`hashCode` do a DNS lookup, a hazard for untrusted addresses. Scheme rules live on [ShortLink], not here. */
@Converter(autoApply = true)
class UriAttributeConverter : AttributeConverter<URI, String> {

    override fun convertToDatabaseColumn(attribute: URI?): String? = attribute?.toString()

    override fun convertToEntityAttribute(dbData: String?): URI? = dbData?.let(URI::create)
}
