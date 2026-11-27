package br.com.cshub.taxify.dto.reform;

/** Body of {@code PUT /reform/stock-credit/{companyId}/status} (CE-RN-10). {@code version} = the one the client read. */
public record StockSurveyStatusChangeDTO(Integer year, String status, String note, Integer version) {
}
