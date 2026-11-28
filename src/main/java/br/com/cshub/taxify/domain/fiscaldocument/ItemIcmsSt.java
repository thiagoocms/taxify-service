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
    /** No ICMS-ST field (every column null). */
    public static final ItemIcmsSt EMPTY = new ItemIcmsSt(null, null, null, null, null);

    public ItemIcmsSt {
        mva = FiscalDecimals.rate(mva, "ItemIcmsSt.mva");
        base = money(base, "ItemIcmsSt.base");
        rate = FiscalDecimals.rate(rate, "ItemIcmsSt.rate");
        amount = money(amount, "ItemIcmsSt.amount");
    }
}
