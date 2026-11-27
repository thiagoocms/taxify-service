package br.com.cshub.taxify.integration.sieg;

/**
 * What {@code contar-xmls} answered for one filter, for the types the capture uses: NF-e (the regular sync) and NFS-e (the
 * service invoices' daily check reuses the count the NF-e side already paid for - SIEG breaks the count down by every
 * type, whatever the request's TipoXml). A type SIEG left out counts as 0.
 */
public record SiegDocumentCounts(int nfe, int nfse) {

    static SiegDocumentCounts of(SiegCountXmlsDataDTO data) {
        return new SiegDocumentCounts(valueOf(data.nfe()), valueOf(data.nfse()));
    }

    private static int valueOf(Integer value) {
        return value == null ? 0 : value;
    }
}
