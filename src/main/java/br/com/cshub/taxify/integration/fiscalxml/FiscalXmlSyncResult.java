package br.com.cshub.taxify.integration.fiscalxml;

/**
 * @param countFailed the provider's count of what it has (SIEG's contar-xmls) failed and the download went on anyway -
 *                    the stage ends with the warning SIEG_COUNT_FAILED on the capture panel (CP-RN-10)
 */
public record FiscalXmlSyncResult(
        int persistedNew,
        int duplicated,
        int parseErrors,
        boolean countFailed
) {

    public FiscalXmlSyncResult(int persistedNew, int duplicated, int parseErrors) {
        this(persistedNew, duplicated, parseErrors, false);
    }
}
