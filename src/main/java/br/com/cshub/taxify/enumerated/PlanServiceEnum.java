package br.com.cshub.taxify.enumerated;

/**
 * Services a plan includes. Note import: {@link #FISCAL_XML_IMPORT} puts the company in the
 * automatic import; the source comes from the integrations on the same plan -
 * {@link #SIEG_INTEGRATION}, {@link #SEFAZ_INTEGRATION} or both.
 */
public enum PlanServiceEnum {
    FISCAL_XML_IMPORT,
    INVOICE_AUDIT,
    SIEG_INTEGRATION,
    MULTI_USER_ACCESS,
    REPORTS,
    /** Capture straight from SEFAZ (NFeDistribuicaoDFe) with the company's A1 certificate. */
    SEFAZ_INTEGRATION;

    /** Whether a plan with these services imports notes from this source (SIEG or SEFAZ integration). */
    public static boolean importsFrom(java.util.Collection<PlanServiceEnum> services, PlanServiceEnum source) {
        return services != null && services.contains(FISCAL_XML_IMPORT) && services.contains(source);
    }
}
