package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.YearMonth;

/** {@link YearMonth} as {@code char(7)} {@code yyyy-MM} (the month of Recife, as the dates of the project). */
@Converter
public class YearMonthConverter implements AttributeConverter<YearMonth, String> {

    @Override
    public String convertToDatabaseColumn(YearMonth attribute) {
        return attribute == null ? null : attribute.toString();
    }

    @Override
    public YearMonth convertToEntityAttribute(String dbData) {
        return dbData == null ? null : YearMonth.parse(dbData);
    }
}
