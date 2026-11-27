package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/** The IBS/CBS totals of a document ({@code total/IBSCBSTot}); null when the document has no such group. */
@Embeddable
public record IbsCbsTotals(
        @Column(name = "ibs_cbs_base", precision = 19, scale = 2) BigDecimal base,
        @Column(name = "ibs_state_amount", precision = 19, scale = 2) BigDecimal ibsStateAmount,
        @Column(name = "ibs_city_amount", precision = 19, scale = 2) BigDecimal ibsCityAmount,
        @Column(name = "ibs_amount", precision = 19, scale = 2) BigDecimal ibsAmount,
        @Column(name = "cbs_amount", precision = 19, scale = 2) BigDecimal cbsAmount
) {
    public IbsCbsTotals {
        base = money(base);
        ibsStateAmount = money(ibsStateAmount);
        ibsCityAmount = money(ibsCityAmount);
        ibsAmount = money(ibsAmount);
        cbsAmount = money(cbsAmount);
    }
}
