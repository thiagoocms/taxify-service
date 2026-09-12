package br.com.cshub.taxify.integration.fiscalxml;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What to sync for one company - provider-agnostic on purpose. Document-type codes and
 * any other source-specific detail are the provider implementation's own concern (e.g.
 * {@code SiegFiscalXmlProvider} applies SIEG's defaults internally). {@code alreadyImportedCount}
 * comes from {@link br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl#getTotalImportado()} -
 * a provider that paginates (like SIEG's {@code skip}) uses it to resume where the last
 * sync left off instead of re-requesting notes already persisted.
 *
 * <p>{@code knownPending}: true when a previous check already found more notes than what's
 * imported so far ({@code totalImportado < totalSieg} on the control row) - the provider
 * can skip its own "how many are there" check and go straight to downloading, since the
 * answer is already known to be "yes, there's more". Saves a request against a
 * rate-limited count endpoint (e.g. SIEG's contar-xmls) for something already established.
 */
public record FiscalXmlSyncRequest(
        UUID companyId,
        LocalDate dataEmissaoInicio,
        LocalDate dataEmissaoFim,
        int alreadyImportedCount,
        boolean knownPending
) {
}
