package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link ProviderKey} as its text ({@code varchar(30)}). */
@Converter
public class ProviderKeyConverter implements AttributeConverter<ProviderKey, String> {

    @Override
    public String convertToDatabaseColumn(ProviderKey attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public ProviderKey convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ProviderKey.of(dbData);
    }
}
