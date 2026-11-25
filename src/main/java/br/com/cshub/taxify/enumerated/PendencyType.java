package br.com.cshub.taxify.enumerated;

/**
 * What a pendency is about. Each type has its default severity and the kind of object it points to
 * (the screen links to it). New modules add their own types here.
 */
public enum PendencyType {
    CERTIFICATE_EXPIRED(PendencySeverity.ALTA, "Certificado A1 vencido"),
    CERTIFICATE_EXPIRING(PendencySeverity.MEDIA, "Certificado A1 vencendo"),
    SEFAZ_CAPTURE_ERROR(PendencySeverity.ALTA, "Captura SEFAZ com erro"),
    NFSE_CAPTURE_ERROR(PendencySeverity.ALTA, "Captura de NFS-e (Ambiente Nacional) com erro"),
    CIENCIA_REFUSED(PendencySeverity.MEDIA, "Ciência recusada pela SEFAZ"),
    MANIFESTATION_DEADLINE(PendencySeverity.ALTA, "Prazo de manifestação perto do fim"),
    SOURCE_ONLY_SIEG(PendencySeverity.BAIXA, "Nota só no SIEG"),
    SOURCE_ONLY_SEFAZ(PendencySeverity.BAIXA, "Nota só na SEFAZ"),
    NOTE_DIVERGENT(PendencySeverity.MEDIA, "Nota conferida com divergência"),
    REGIME_MISSING(PendencySeverity.MEDIA, "Regime tributário não informado"),
    RETENTION_UNDUE(PendencySeverity.MEDIA, "Provável retenção indevida"),
    ISS_RETAINED_WITHOUT_VALUE(PendencySeverity.BAIXA, "ISS retido sem valor"),
    /** Certidão SEFAZ-PE (M5): the newest certidão is positiva (ALTA) or positiva com efeito de negativa (BAIXA). */
    CERTIDAO_PE_POSITIVA(PendencySeverity.ALTA, "Certidão estadual (PE) positiva"),
    CERTIDAO_PE_VENCENDO(PendencySeverity.MEDIA, "Certidão estadual (PE) vencendo"),
    CERTIDAO_PE_VENCIDA(PendencySeverity.ALTA, "Certidão estadual (PE) vencida"),
    /** A finding of the Auditoria tipo malha (M9) - written by AuditService per company and competence. */
    AUDIT_FINDING(PendencySeverity.MEDIA, "Auditoria tipo malha");

    public final PendencySeverity defaultSeverity;
    public final String label;

    PendencyType(PendencySeverity defaultSeverity, String label) {
        this.defaultSeverity = defaultSeverity;
        this.label = label;
    }
}
