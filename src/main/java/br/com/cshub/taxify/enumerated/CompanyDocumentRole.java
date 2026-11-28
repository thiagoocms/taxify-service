package br.com.cshub.taxify.enumerated;

import java.util.EnumSet;
import java.util.Set;

/**
 * What the owning company is in a fiscal document (FD-RN-08). Replaces {@code own_issue} of the NF-e and the
 * PRESTADA/TOMADA/TERCEIRO of the NFS-e: the direction says how the document is booked, the role says who the company
 * is in it.
 */
public enum CompanyDocumentRole {
    ISSUER,
    RECIPIENT,
    TAKER,
    SENDER,
    SHIPPER,
    RECEIVER,
    THIRD_PARTY;

    private static final Set<CompanyDocumentRole> WITHOUT_BOOKING = EnumSet.of(THIRD_PARTY, SENDER, SHIPPER, RECEIVER);

    /** Roles in which the company books nothing - the document has no direction (I-04). */
    public boolean isWithoutBooking() {
        return WITHOUT_BOOKING.contains(this);
    }
}
