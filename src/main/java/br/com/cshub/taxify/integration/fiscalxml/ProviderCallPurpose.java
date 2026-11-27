package br.com.cshub.taxify.integration.fiscalxml;

import java.util.Locale;

/**
 * What a call to a fiscal-XML provider is for - the {@code purpose} tag of the provider's request counter (SIEG:
 * {@code sieg.requests}). Set by whoever knows it ({@link ProviderCallContext}); the provider's client reads it.
 */
public enum ProviderCallPurpose {
    /** A side's count of the regular capture (SIEG contar-xmls). */
    COUNT,
    /** A side's download page of the regular capture. */
    PAGE,
    /** The re-read from the first page when the paging ended short of the provider's count (B1). */
    RECONCILE,
    /** The previous month's NF-e checked again in the first days of the month (count and pages). */
    PREVIOUS,
    /** The daily sweep of the recent notes' events. */
    EVENTS,
    /** The service invoices (NFS-e): their count and their pages. */
    NFSE,
    /** The recovery of the months a blocked firm missed (QA-02). */
    RECOVERY,
    /** Any call of a "Sincronizar agora" attempt (CP-RN-15) - wins over the stage's own purpose. */
    PRIORITY,
    /** Outside the scheduler: a user's own request (manual sync, NFS-e of a period). */
    MANUAL;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
