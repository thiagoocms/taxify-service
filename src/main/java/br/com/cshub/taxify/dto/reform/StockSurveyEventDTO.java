package br.com.cshub.taxify.dto.reform;

import java.time.OffsetDateTime;

/** One contact status change (CE-RN-10): {@code from} null on the first change from the default. */
public record StockSurveyEventDTO(String from, String to, String note, String changedBy, OffsetDateTime changedAt) {
}
