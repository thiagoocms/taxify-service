package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/** The ICMS-ST of an item. */
@Embeddable
public record ItemIcmsSt(
        @Column(name = "icms_st_base_modality", length = 1) String baseModality,
        /** pMVAST, in percent. */
        @Column(name = "icms_st_mva", precision = 9, scale = 4) BigDecimal mva,
        @Column(name = "icms_st_base", precision = 19, scale = 2) BigDecimal base,
        @Column(name = "icms_st_rate", precision = 9, scale = 4) BigDecimal rate,
        @Column(name = "icms_st_amount", precision = 19, scale = 2) BigDecimal amount
) {
    public ItemIcmsSt {
        mva = FiscalDecimals.rate(mva);
        base = money(base);
        rate = FiscalDecimals.rate(rate);
        amount = money(amount);
    }
}
