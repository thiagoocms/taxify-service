package br.com.cshub.taxify.enumerated;

/**
 * Which side of a note the company is on - relative to the company, not to the note's
 * own {@code tpNF} (that one is from the issuer's point of view, e.g. a return issued by
 * the company is tpNF=0 but still a note the company emitted).
 * <ul>
 *   <li>{@link #ENTRADA}: the company is the recipient (CNPJdest) - what was synced so far.</li>
 *   <li>{@link #SAIDA}: the company is the issuer (CNPJemit).</li>
 * </ul>
 */
public enum FiscalXmlDirection {
    ENTRADA,
    SAIDA
}
