package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * The IBS/CBS group of an item ({@code det/imposto/IBSCBS}): CST, cClassTrib, the shared base and, per tax (IBS of the
 * state, IBS of the city, CBS), rate, rate reduction and value. Rates and reductions are percentages, as in the XML.
 * {@code deferralOrRefund} is true when any tax has a gDif or gDevTrib group.
 */
@Embeddable
public record ItemIbsCbs(
        @Column(name = "ibs_cbs_cst", length = 3) String cst,
        /** cClassTrib. */
        @Column(name = "ibs_cbs_class_code", length = 6) String classCode,
        @Column(name = "ibs_cbs_base", precision = 19, scale = 2) BigDecimal base,
        @Column(name = "ibs_state_rate", precision = 9, scale = 4) BigDecimal stateRate,
        @Column(name = "ibs_state_rate_reduction", precision = 9, scale = 4) BigDecimal stateRateReduction,
        @Column(name = "ibs_state_amount", precision = 19, scale = 2) BigDecimal stateAmount,
        @Column(name = "ibs_city_rate", precision = 9, scale = 4) BigDecimal cityRate,
        @Column(name = "ibs_city_rate_reduction", precision = 9, scale = 4) BigDecimal cityRateReduction,
        @Column(name = "ibs_city_amount", precision = 19, scale = 2) BigDecimal cityAmount,
        @Column(name = "ibs_amount", precision = 19, scale = 2) BigDecimal ibsAmount,
        @Column(name = "cbs_rate", precision = 9, scale = 4) BigDecimal cbsRate,
        @Column(name = "cbs_rate_reduction", precision = 9, scale = 4) BigDecimal cbsRateReduction,
        @Column(name = "cbs_amount", precision = 19, scale = 2) BigDecimal cbsAmount,
        @Column(name = "ibs_cbs_deferral_or_refund") Boolean deferralOrRefund
) {
    public ItemIbsCbs {
        base = money(base);
        stateRate = FiscalDecimals.rate(stateRate);
        stateRateReduction = FiscalDecimals.rate(stateRateReduction);
        stateAmount = money(stateAmount);
        cityRate = FiscalDecimals.rate(cityRate);
        cityRateReduction = FiscalDecimals.rate(cityRateReduction);
        cityAmount = money(cityAmount);
        ibsAmount = money(ibsAmount);
        cbsRate = FiscalDecimals.rate(cbsRate);
        cbsRateReduction = FiscalDecimals.rate(cbsRateReduction);
        cbsAmount = money(cbsAmount);
    }
}
