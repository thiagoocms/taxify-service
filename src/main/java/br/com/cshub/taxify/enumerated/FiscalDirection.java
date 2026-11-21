package br.com.cshub.taxify.enumerated;

/**
 * Whether a note is an entry or an exit for the company that owns it - how a fiscal team
 * books it, not just who issued it:
 * <ul>
 *   <li>received from a supplier (company is the recipient) → INBOUND;</li>
 *   <li>issued by the company itself with tpNF = 0 (own entry: return, import...) → INBOUND;</li>
 *   <li>issued by the company itself with tpNF = 1 (a sale, a shipment...) → OUTBOUND.</li>
 * </ul>
 */
public enum FiscalDirection {
    INBOUND,
    OUTBOUND
}
