package br.com.cshub.taxify.enumerated;

/** Conferência of a note by the fiscal team before it is booked (null in the database = PENDING). */
public enum ReviewStatus {
    PENDING,
    REVIEWED,
    /** Checked and something is wrong (CFOP, CST, value, note not of the company...) - see the review note. */
    DIVERGENT
}
