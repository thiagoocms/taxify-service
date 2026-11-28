package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * Federal withholdings of a document ({@code total/retTrib} of the NF-e; CSLL, IRRF and INSS of the NFS-e). On the
 * NFS-e, PIS and COFINS stay null here: they are informed in {@code pis_amount}/{@code cofins_amount} with the
 * withheld flag ({@link Nfse#withheldPis()}).
 */
@Embeddable
public record Withholdings(
        @Column(name = "withheld_pis_amount", precision = 19, scale = 2) BigDecimal pis,
        @Column(name = "withheld_cofins_amount", precision = 19, scale = 2) BigDecimal cofins,
        @Column(name = "withheld_csll_amount", precision = 19, scale = 2) BigDecimal csll,
        @Column(name = "withheld_irrf_amount", precision = 19, scale = 2) BigDecimal irrf,
        @Column(name = "withheld_inss_amount", precision = 19, scale = 2) BigDecimal inss
) {
    /** No withholding (every column null). */
    public static final Withholdings EMPTY = new Withholdings(null, null, null, null, null);

    public Withholdings {
        pis = money(pis);
        cofins = money(cofins);
        csll = money(csll);
        irrf = money(irrf);
        inss = money(inss);
    }
}
