package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.enumerated.DivergenceSeverity;

/**
 * One thing a {@link FiscalCheck} found wrong in a note item.
 *
 * @param code     stable id of the finding (e.g. {@code RULE_CFOP}) - what the front-end and
 *                 filters key on; the message is for people and may change
 * @param field    the item field it is about (CFOP, NCM, CST_ICMS...)
 * @param found    the value in the note
 * @param expected the value it should have, when the check knows it
 */
public record FiscalDivergence(String code, String field, String found, String expected,
                               DivergenceSeverity severity, String message) {
}
