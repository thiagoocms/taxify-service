package br.com.cshub.taxify.enumerated;

/**
 * Where a firm's contracts and fee charges come from. MANUAL = no integration: fees and
 * charges are typed in Taxify. Each other value has a BillingProvider implementation and
 * the FirmIntegration it authenticates with.
 */
public enum BillingProviderType {
    MANUAL(null),
    CONTA_AZUL(IntegrationType.CONTA_AZUL);

    private final IntegrationType integrationType;

    BillingProviderType(IntegrationType integrationType) {
        this.integrationType = integrationType;
    }

    /** The integration holding this provider's credentials - null for MANUAL. */
    public IntegrationType integrationType() {
        return integrationType;
    }
}
