package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * The own ICMS of an item ({@code ICMSxx}/{@code ICMSSNxxx}). {@code cst} keeps the CST or the CSOSN, as the old
 * model did; {@code csosn} is filled only when the issuer used a CSOSN.
 */
@Embeddable
public record ItemIcms(
        /** orig: 0 national, 1/2/3/8 imported... */
        @Column(name = "goods_origin", length = 1) String origin,
        @Column(name = "icms_cst", length = 5) String cst,
        @Column(name = "icms_csosn", length = 4) String csosn,
        @Column(name = "icms_base_modality", length = 1) String baseModality,
        @Column(name = "icms_base", precision = 19, scale = 2) BigDecimal base,
        @Column(name = "icms_rate", precision = 9, scale = 4) BigDecimal rate,
        /** pRedBC, in percent. */
        @Column(name = "icms_base_reduction", precision = 9, scale = 4) BigDecimal baseReduction,
        @Column(name = "icms_amount", precision = 19, scale = 2) BigDecimal amount,
        @Column(name = "icms_relief_amount", precision = 19, scale = 2) BigDecimal reliefAmount
) {
    public ItemIcms {
        base = money(base);
        rate = FiscalDecimals.rate(rate);
        baseReduction = FiscalDecimals.rate(baseReduction);
        amount = money(amount);
        reliefAmount = money(reliefAmount);
    }
}
