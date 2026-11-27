package br.com.cshub.taxify.integration.fiscalxml;

/**
 * @param countFailed         the provider's count of what it has (SIEG's contar-xmls) failed and the download went on
 *                            anyway - the stage ends with the warning SIEG_COUNT_FAILED on the capture panel (CP-RN-10)
 * @param serviceInvoiceCount the provider's NFS-e count for the same side and window, when this sync counted (SIEG's
 *                            contar-xmls breaks the count down by type); null = not counted this time. The daily NFS-e
 *                            check reuses it instead of asking again (A1).
 */
public record FiscalXmlSyncResult(
        int persistedNew,
        int duplicated,
        int parseErrors,
        boolean countFailed,
        Integer serviceInvoiceCount
) {

    public FiscalXmlSyncResult(int persistedNew, int duplicated, int parseErrors) {
        this(persistedNew, duplicated, parseErrors, false, null);
    }

    public FiscalXmlSyncResult(int persistedNew, int duplicated, int parseErrors, boolean countFailed) {
        this(persistedNew, duplicated, parseErrors, countFailed, null);
    }
}
