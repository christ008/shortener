package uy.ct.shortener.shortlink.internal

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import uy.ct.shortener.shortlink.ShortCode

/** autoApply so every [ShortCode]-typed attribute converts without `@Convert` boilerplate. */
@Converter(autoApply = true)
class ShortCodeConverter : AttributeConverter<ShortCode, String> {

    override fun convertToDatabaseColumn(attribute: ShortCode?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): ShortCode? = dbData?.let(::ShortCode)
}
