package br.com.cshub.taxify.domain.xml;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * The tax detail of a note item beyond the CST/value summary {@link FiscalXmlDocumentItem}
 * always had - what the fiscal checks need to tell a wrong classification or a wrong
 * calculation apart (origin, CSOSN, base reduction, ST, IPI/PIS/COFINS base and rate).
 * Straight from the XML, no interpretation: a tag that isn't in the note stays null.
 *
 * <p>{@code cst_icms} on the item keeps holding the CST <em>or</em> the CSOSN (as before);
 * {@link #csosn()} is only filled when the note used a CSOSN, which is how a check knows
 * which of the two the issuer reported.
 */
@Embeddable
public record FiscalItemTaxDetail(
        /** prod/CEST. */
        @Column(name = "cest", length = 10) String cest,
        /** prod/cBenef - benefício fiscal code of the state. */
        @Column(name = "codigo_beneficio", length = 10) String codigoBeneficio,
        /** ICMSxx/orig - 0 nacional, 1/2/3/8 importada... */
        @Column(name = "origem_mercadoria", length = 1) String origemMercadoria,
        /** ICMSSNxxx/CSOSN - null when the note used a CST. */
        @Column(name = "csosn", length = 4) String csosn,
        @Column(name = "modalidade_bc_icms", length = 1) String modalidadeBcIcms,
        /** pRedBC, in percent. */
        @Column(name = "reducao_bc_icms", precision = 7, scale = 4) BigDecimal reducaoBcIcms,
        @Column(name = "valor_icms_desonerado", precision = 19, scale = 2) BigDecimal valorIcmsDesonerado,
        @Column(name = "modalidade_bc_icms_st", length = 1) String modalidadeBcIcmsSt,
        /** pMVAST, in percent. */
        @Column(name = "mva_icms_st", precision = 7, scale = 4) BigDecimal mvaIcmsSt,
        @Column(name = "base_calculo_icms_st", precision = 19, scale = 2) BigDecimal baseCalculoIcmsSt,
        @Column(name = "aliquota_icms_st", precision = 7, scale = 4) BigDecimal aliquotaIcmsSt,
        @Column(name = "valor_icms_st", precision = 19, scale = 2) BigDecimal valorIcmsSt,
        @Column(name = "base_calculo_ipi", precision = 19, scale = 2) BigDecimal baseCalculoIpi,
        @Column(name = "aliquota_ipi", precision = 7, scale = 4) BigDecimal aliquotaIpi,
        @Column(name = "base_calculo_pis", precision = 19, scale = 2) BigDecimal baseCalculoPis,
        @Column(name = "aliquota_pis", precision = 7, scale = 4) BigDecimal aliquotaPis,
        @Column(name = "base_calculo_cofins", precision = 19, scale = 2) BigDecimal baseCalculoCofins,
        @Column(name = "aliquota_cofins", precision = 7, scale = 4) BigDecimal aliquotaCofins,
        /** pCredSN: the credit rate a Simples Nacional supplier may pass on (LC 123/2006, art. 23, § 1º). */
        @Column(name = "aliquota_credito_sn", precision = 7, scale = 4) BigDecimal aliquotaCreditoSn,
        /** vCredICMSSN: the credit value a Simples Nacional supplier passes on. */
        @Column(name = "valor_credito_sn", precision = 19, scale = 2) BigDecimal valorCreditoSn
) {

    /** Without the Simples credit (notes parsed before version 6, and tests). */
    public FiscalItemTaxDetail(String cest, String codigoBeneficio, String origemMercadoria, String csosn, String modalidadeBcIcms,
                               BigDecimal reducaoBcIcms, BigDecimal valorIcmsDesonerado, String modalidadeBcIcmsSt,
                               BigDecimal mvaIcmsSt, BigDecimal baseCalculoIcmsSt, BigDecimal aliquotaIcmsSt, BigDecimal valorIcmsSt,
                               BigDecimal baseCalculoIpi, BigDecimal aliquotaIpi, BigDecimal baseCalculoPis, BigDecimal aliquotaPis,
                               BigDecimal baseCalculoCofins, BigDecimal aliquotaCofins) {
        this(cest, codigoBeneficio, origemMercadoria, csosn, modalidadeBcIcms, reducaoBcIcms, valorIcmsDesonerado,
                modalidadeBcIcmsSt, mvaIcmsSt, baseCalculoIcmsSt, aliquotaIcmsSt, valorIcmsSt, baseCalculoIpi, aliquotaIpi,
                baseCalculoPis, aliquotaPis, baseCalculoCofins, aliquotaCofins, null, null);
    }
}
