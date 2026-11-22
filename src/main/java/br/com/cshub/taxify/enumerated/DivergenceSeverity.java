package br.com.cshub.taxify.enumerated;

/** How bad a fiscal check finding is. */
public enum DivergenceSeverity {
    /** The note is wrong: a code that doesn't match the product's rule, a tax miscalculated... */
    ERROR,
    /** Likely wrong, worth a look - the check can't be sure from the note alone. */
    WARNING
}
