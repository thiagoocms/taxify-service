package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * The {@code total/ICMSTot} of a goods invoice except the note total, which is {@code total_amount} of every document.
 * {@code icms_base}/{@code icms_amount} are shared with the CT-e ({@link CteIcms}) and {@code pis_amount}/
 * {@code cofins_amount} with the NFS-e: same physical column, same meaning (the tax highlighted in the document).
 */
@Embeddable
public record GoodsTotals(
        @Column(name = "icms_base", precision = 19, scale = 2) BigDecimal icmsBase,
        @Column(name = "icms_amount", precision = 19, scale = 2) BigDecimal icmsAmount,
        @Column(name = "icms_relief_amount", precision = 19, scale = 2) BigDecimal icmsReliefAmount,
        @Column(name = "fcp_amount", precision = 19, scale = 2) BigDecimal fcpAmount,
        @Column(name = "icms_st_base", precision = 19, scale = 2) BigDecimal icmsStBase,
        @Column(name = "icms_st_amount", precision = 19, scale = 2) BigDecimal icmsStAmount,
        @Column(name = "fcp_st_amount", precision = 19, scale = 2) BigDecimal fcpStAmount,
        @Column(name = "products_amount", precision = 19, scale = 2) BigDecimal productsAmount,
        @Column(name = "freight_amount", precision = 19, scale = 2) BigDecimal freightAmount,
        @Column(name = "insurance_amount", precision = 19, scale = 2) BigDecimal insuranceAmount,
        @Column(name = "discount_amount", precision = 19, scale = 2) BigDecimal discountAmount,
        @Column(name = "import_tax_amount", precision = 19, scale = 2) BigDecimal importTaxAmount,
        @Column(name = "ipi_amount", precision = 19, scale = 2) BigDecimal ipiAmount,
        @Column(name = "pis_amount", precision = 19, scale = 2) BigDecimal pisAmount,
        @Column(name = "cofins_amount", precision = 19, scale = 2) BigDecimal cofinsAmount,
        @Column(name = "other_expenses_amount", precision = 19, scale = 2) BigDecimal otherExpensesAmount,
        @Column(name = "approximate_taxes_amount", precision = 19, scale = 2) BigDecimal approximateTaxesAmount
) {
    /** No total (every column null). */
    public static final GoodsTotals EMPTY = new GoodsTotals(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);

    public GoodsTotals {
        icmsBase = money(icmsBase, "GoodsTotals.icmsBase");
        icmsAmount = money(icmsAmount, "GoodsTotals.icmsAmount");
        icmsReliefAmount = money(icmsReliefAmount, "GoodsTotals.icmsReliefAmount");
        fcpAmount = money(fcpAmount, "GoodsTotals.fcpAmount");
        icmsStBase = money(icmsStBase, "GoodsTotals.icmsStBase");
        icmsStAmount = money(icmsStAmount, "GoodsTotals.icmsStAmount");
        fcpStAmount = money(fcpStAmount, "GoodsTotals.fcpStAmount");
        productsAmount = money(productsAmount, "GoodsTotals.productsAmount");
        freightAmount = money(freightAmount, "GoodsTotals.freightAmount");
        insuranceAmount = money(insuranceAmount, "GoodsTotals.insuranceAmount");
        discountAmount = money(discountAmount, "GoodsTotals.discountAmount");
        importTaxAmount = money(importTaxAmount, "GoodsTotals.importTaxAmount");
        ipiAmount = money(ipiAmount, "GoodsTotals.ipiAmount");
        pisAmount = money(pisAmount, "GoodsTotals.pisAmount");
        cofinsAmount = money(cofinsAmount, "GoodsTotals.cofinsAmount");
        otherExpensesAmount = money(otherExpensesAmount, "GoodsTotals.otherExpensesAmount");
        approximateTaxesAmount = money(approximateTaxesAmount, "GoodsTotals.approximateTaxesAmount");
    }
}
