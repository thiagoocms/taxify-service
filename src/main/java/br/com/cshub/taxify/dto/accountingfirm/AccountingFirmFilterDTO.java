package br.com.cshub.taxify.dto.accountingfirm;

public record AccountingFirmFilterDTO(
        String id,
        String name,
        Boolean deleted
) {
}
