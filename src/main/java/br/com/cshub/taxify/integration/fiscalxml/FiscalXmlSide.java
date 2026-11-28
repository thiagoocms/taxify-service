package br.com.cshub.taxify.integration.fiscalxml;

import br.com.cshub.taxify.enumerated.CaptureSide;

/**
 * Which side of the company a provider is asked for: notes it RECEIVED (company is the
 * recipient - SIEG "CNPJdest") or notes it ISSUED (company is the issuer - SIEG "CNPJemit").
 * Each side is counted and paged on its own, so each keeps its own sync bookkeeping.
 */
public enum FiscalXmlSide {
    RECEIVED,
    ISSUED;

    /** Notes stored with this side - issued by the company ({@code company_role = ISSUER}) or not. */
    public boolean ownIssue() {
        return this == ISSUED;
    }

    /** The side of the capture counts ({@code capture_period_counts.side}). */
    public CaptureSide captureSide() {
        return this == ISSUED ? CaptureSide.ISSUED : CaptureSide.RECEIVED;
    }
}
