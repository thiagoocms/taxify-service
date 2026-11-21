package br.com.cshub.taxify.enumerated;

/**
 * External systems an accounting firm can connect to Taxify - one FirmIntegration row each.
 * SIEG / ACESSORIAS authenticate with an API key, CONTA_AZUL with OAuth2 tokens.
 */
public enum IntegrationType {
    SIEG,
    ACESSORIAS,
    CONTA_AZUL
}
