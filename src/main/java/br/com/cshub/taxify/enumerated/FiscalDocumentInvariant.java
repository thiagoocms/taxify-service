package br.com.cshub.taxify.enumerated;

/**
 * Closed list of the invariants of a fiscal document (spec 3.3) - the code that goes to the log
 * ({@code fiscal.document.invariant.violated}) and to the upload/capture failure count, never a free text. I-16 (items
 * only in goods invoices) has no code: it is guaranteed by the types.
 */
public enum FiscalDocumentInvariant {
    /** company, documentType, sourceSystem, status, companyRole and metadataVersion filled. Always. */
    I01_MANDATORY_FIELDS(true),
    /** AUTHORIZED implies no canceledAt. Always. */
    I02_AUTHORIZED_WITHOUT_CANCELED_AT(true),
    /** CANCELED implies canceledAt, except NFS-e. CREATE. */
    I03_CANCELED_WITH_CANCELED_AT(false),
    /** NF-e, NFC-e, NFS-e: direction null iff the role books nothing. Always. */
    I04_DIRECTION_MATCHES_ROLE(true),
    /**
     * CT-e: direction derived from the role (ISSUER = OUTBOUND, TAKER = INBOUND, others null). Always; on UPDATE a CT-e
     * copied from the old model ({@code metadataVersion = 0}) with role RECIPIENT may keep INBOUND.
     */
    I04T_CTE_DIRECTION_FROM_ROLE(true),
    /** role ISSUER iff the issuer document is the company CNPJ, when both exist. CREATE. */
    I05_ISSUER_ROLE_MATCHES_COMPANY(false),
    /** document type of each party coherent with its size. Always. */
    I06_PARTY_DOCUMENT_TYPE(true),
    /** access key filled, except goods invoices uploaded by hand. CREATE. */
    I07_ACCESS_KEY(false),
    /** issue date filled. CREATE. */
    I08_ISSUE_DATE(false),
    /** XML file path filled while the XML is not purged, except CT-e copied without a file. CREATE. */
    I09_XML_FILE_PATH(false),
    /** NF-e: model 55 and a 44-digit key. CREATE. */
    I10_NFE_MODEL_AND_KEY(false),
    /** NFC-e: model 65 and a 44-digit key. CREATE. */
    I11_NFCE_MODEL_AND_KEY(false),
    /** CT-e: model 57 and a 44-digit key. CREATE. */
    I12_CTE_MODEL_AND_KEY(false),
    /** NFS-e: layout filled; NATIONAL = 50-digit key; ABRASF = synthetic ABRASF- key. Always. */
    I13_NFSE_LAYOUT_AND_KEY(true),
    /** NFS-e: ISS withheld and PIS/COFINS withheld flags not null. Always. */
    I14_NFSE_WITHHELD_FLAGS(true),
    /** CT-e: at least one participation flag true, except with role THIRD_PARTY. CREATE. */
    I15_CTE_PARTICIPATION(false),
    /** Money with scale 2 and rates with scale 4, never rounded. Always (checked when the value is built). */
    I17_DECIMAL_SCALE(true);

    private final boolean always;

    FiscalDocumentInvariant(boolean always) {
        this.always = always;
    }

    /** Whether the rule applies to {@code scope}. */
    public boolean appliesTo(InvariantScope scope) {
        return always || scope.isCreate();
    }
}
