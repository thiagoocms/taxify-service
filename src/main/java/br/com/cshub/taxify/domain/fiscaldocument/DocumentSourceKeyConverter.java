package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link DocumentSourceKey} as its text ({@code varchar(30)}). */
@Converter
public class DocumentSourceKeyConverter implements AttributeConverter<DocumentSourceKey, String> {

    @Override
    public String convertToDatabaseColumn(DocumentSourceKey attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public DocumentSourceKey convertToEntityAttribute(String dbData) {
        return dbData == null ? null : DocumentSourceKey.of(dbData);
    }
}
