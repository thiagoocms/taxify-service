package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * The ICMS credit a Simples Nacional supplier passes on in an item (pCredSN and vCredICMSSN, LC 123/2006, art. 23,
 * § 1º) - kept exactly as informed.
 */
@Embeddable
public record SimplesCredit(
        @Column(name = "simples_credit_rate", precision = 9, scale = 4) BigDecimal rate,
        @Column(name = "simples_credit_amount", precision = 19, scale = 2) BigDecimal amount
) {
    /** No Simples credit (every column null). */
    public static final SimplesCredit EMPTY = new SimplesCredit(null, null);

    public SimplesCredit {
        rate = FiscalDecimals.rate(rate, "SimplesCredit.rate");
        amount = money(amount, "SimplesCredit.amount");
    }
}
