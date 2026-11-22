package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;

import java.util.List;

/**
 * One fiscal check over a note item - e.g. "matches the product's rule", "CST fits the
 * issuer's CRT", "ICMS = base x rate". Each check is a Spring bean; {@link FiscalCheckEngine}
 * runs all of them, so adding a check is adding a bean. A check must not query the database
 * per item: whatever it needs beyond the item and its note comes in the {@link FiscalCheckContext}.
 */
public interface FiscalCheck {

    /** @return what is wrong with the item - empty when nothing (or when the check doesn't apply) */
    List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context);
}
