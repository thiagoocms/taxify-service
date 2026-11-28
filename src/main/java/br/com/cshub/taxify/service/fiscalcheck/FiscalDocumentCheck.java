package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;

import java.util.List;

/**
 * A check over a whole note rather than one item - totals against the sum of the items,
 * a group missing from every item... Same contract as {@link FiscalCheck}: a Spring bean,
 * no database access per note.
 */
public interface FiscalDocumentCheck {

    List<FiscalDivergence> check(GoodsInvoice document, List<FiscalDocumentItem> items, FiscalCheckContext context);
}
