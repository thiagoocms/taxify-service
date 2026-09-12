package br.com.cshub.taxify.integration.fiscalxml;

public record FiscalXmlSyncResult(
        int persistedNew,
        int duplicated,
        int parseErrors
) {
}
