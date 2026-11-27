package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/** The ISS of an NFS-e: base, rate, value and whether (and how much) it was withheld. */
@Embeddable
public record IssTax(
        @Column(name = "iss_base", precision = 19, scale = 2) BigDecimal base,
        @Column(name = "iss_rate", precision = 9, scale = 4) BigDecimal rate,
        @Column(name = "iss_amount", precision = 19, scale = 2) BigDecimal amount,
        /** Never null in a valid NFS-e (I-14); false when the layout does not say it was withheld. */
        @Column(name = "iss_withheld") Boolean withheld,
        @Column(name = "withheld_iss_amount", precision = 19, scale = 2) BigDecimal withheldAmount
) {
    public IssTax {
        base = money(base);
        rate = FiscalDecimals.rate(rate);
        amount = money(amount);
        withheldAmount = money(withheldAmount);
    }

    /** No ISS informed and not withheld - the default of a new NFS-e. */
    public static IssTax notWithheld() {
        return new IssTax(null, null, null, false, null);
    }

    public boolean isWithheld() {
        return Boolean.TRUE.equals(withheld);
    }
}
