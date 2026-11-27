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
    public GoodsTotals {
        icmsBase = money(icmsBase);
        icmsAmount = money(icmsAmount);
        icmsReliefAmount = money(icmsReliefAmount);
        fcpAmount = money(fcpAmount);
        icmsStBase = money(icmsStBase);
        icmsStAmount = money(icmsStAmount);
        fcpStAmount = money(fcpStAmount);
        productsAmount = money(productsAmount);
        freightAmount = money(freightAmount);
        insuranceAmount = money(insuranceAmount);
        discountAmount = money(discountAmount);
        importTaxAmount = money(importTaxAmount);
        ipiAmount = money(ipiAmount);
        pisAmount = money(pisAmount);
        cofinsAmount = money(cofinsAmount);
        otherExpensesAmount = money(otherExpensesAmount);
        approximateTaxesAmount = money(approximateTaxesAmount);
    }
}
