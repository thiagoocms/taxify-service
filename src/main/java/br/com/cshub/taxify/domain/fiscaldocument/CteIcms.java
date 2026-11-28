package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * The ICMS of a CT-e ({@code imp/ICMS}). Base and value share the columns {@code icms_base}/{@code icms_amount} with
 * the goods invoices ({@link GoodsTotals}); group, CST and rate are CT-e only.
 */
@Embeddable
public record CteIcms(
        /** The ICMS group tag of the CT-e (ICMS00, ICMS20, ICMS45, ICMS60, ICMS90, ICMSOutraUF, ICMSSN). */
        @Column(name = "cte_icms_group", length = 12) String group,
        @Column(name = "cte_icms_cst", length = 3) String cst,
        @Column(name = "icms_base", precision = 19, scale = 2) BigDecimal base,
        @Column(name = "cte_icms_rate", precision = 9, scale = 4) BigDecimal rate,
        @Column(name = "icms_amount", precision = 19, scale = 2) BigDecimal amount
) {
    public CteIcms {
        base = money(base, "CteIcms.base");
        rate = FiscalDecimals.rate(rate, "CteIcms.rate");
        amount = money(amount, "CteIcms.amount");
    }
}
