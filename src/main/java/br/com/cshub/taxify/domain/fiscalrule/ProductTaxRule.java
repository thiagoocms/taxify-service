package br.com.cshub.taxify.domain.fiscalrule;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * The tax classification a product should have beyond CFOP/NCM - each field optional:
 * null means "don't check it". Rates are percentages.
 *
 * <p>{@link #aliquotaIcms()} is the rate of an <em>internal</em> operation; interstate rates
 * (4/7/12%) follow from the states and the goods' origin and are checked without a rule.
 * {@link #cstIcms()} is compared only with notes that used a CST and {@link #csosn()} only
 * with notes that used a CSOSN - a Simples issuer's note isn't wrong for not using a CST.
 */
@Embeddable
public record ProductTaxRule(
        @Column(name = "origem_mercadoria", length = 1) String origemMercadoria,
        @Column(name = "cst_icms", length = 2) String cstIcms,
        @Column(name = "csosn", length = 3) String csosn,
        @Column(name = "aliquota_icms", precision = 7, scale = 4) BigDecimal aliquotaIcms,
        @Column(name = "cest", length = 7) String cest,
        @Column(name = "cst_ipi", length = 2) String cstIpi,
        @Column(name = "aliquota_ipi", precision = 7, scale = 4) BigDecimal aliquotaIpi,
        @Column(name = "cst_pis", length = 2) String cstPis,
        @Column(name = "aliquota_pis", precision = 7, scale = 4) BigDecimal aliquotaPis,
        @Column(name = "cst_cofins", length = 2) String cstCofins,
        @Column(name = "aliquota_cofins", precision = 7, scale = 4) BigDecimal aliquotaCofins,
        @Column(name = "cst_ibscbs", length = 3) String cstIbsCbs,
        @Column(name = "class_trib_ibscbs", length = 6) String classificacaoTributaria
) {
}
