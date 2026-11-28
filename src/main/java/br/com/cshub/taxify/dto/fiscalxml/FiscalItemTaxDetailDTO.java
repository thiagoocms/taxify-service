package br.com.cshub.taxify.dto.fiscalxml;


import java.math.BigDecimal;

/**
 * The tax detail of a note item beyond the CST/value summary the item
 * always had - what the fiscal checks need to tell a wrong classification or a wrong
 * calculation apart (origin, CSOSN, base reduction, ST, IPI/PIS/COFINS base and rate).
 * Straight from the XML, no interpretation: a tag that isn't in the note stays null.
 *
 * <p>{@code cst_icms} on the item keeps holding the CST <em>or</em> the CSOSN (as before);
 * {@link #csosn()} is only filled when the note used a CSOSN, which is how a check knows
 * which of the two the issuer reported.
 */
public record FiscalItemTaxDetailDTO(
        /** prod/CEST. */
        String cest,
        /** prod/cBenef - benefício fiscal code of the state. */
        String codigoBeneficio,
        /** ICMSxx/orig - 0 nacional, 1/2/3/8 importada... */
        String origemMercadoria,
        /** ICMSSNxxx/CSOSN - null when the note used a CST. */
        String csosn,
        String modalidadeBcIcms,
        /** pRedBC, in percent. */
        BigDecimal reducaoBcIcms,
        BigDecimal valorIcmsDesonerado,
        String modalidadeBcIcmsSt,
        /** pMVAST, in percent. */
        BigDecimal mvaIcmsSt,
        BigDecimal baseCalculoIcmsSt,
        BigDecimal aliquotaIcmsSt,
        BigDecimal valorIcmsSt,
        BigDecimal baseCalculoIpi,
        BigDecimal aliquotaIpi,
        BigDecimal baseCalculoPis,
        BigDecimal aliquotaPis,
        BigDecimal baseCalculoCofins,
        BigDecimal aliquotaCofins,
        /** pCredSN: the credit rate a Simples Nacional supplier may pass on (LC 123/2006, art. 23, § 1º). */
        BigDecimal aliquotaCreditoSn,
        /** vCredICMSSN: the credit value a Simples Nacional supplier passes on. */
        BigDecimal valorCreditoSn
) {

    /** Without the Simples credit (notes parsed before version 6, and tests). */
    public FiscalItemTaxDetailDTO(String cest, String codigoBeneficio, String origemMercadoria, String csosn, String modalidadeBcIcms,
                               BigDecimal reducaoBcIcms, BigDecimal valorIcmsDesonerado, String modalidadeBcIcmsSt,
                               BigDecimal mvaIcmsSt, BigDecimal baseCalculoIcmsSt, BigDecimal aliquotaIcmsSt, BigDecimal valorIcmsSt,
                               BigDecimal baseCalculoIpi, BigDecimal aliquotaIpi, BigDecimal baseCalculoPis, BigDecimal aliquotaPis,
                               BigDecimal baseCalculoCofins, BigDecimal aliquotaCofins) {
        this(cest, codigoBeneficio, origemMercadoria, csosn, modalidadeBcIcms, reducaoBcIcms, valorIcmsDesonerado,
                modalidadeBcIcmsSt, mvaIcmsSt, baseCalculoIcmsSt, aliquotaIcmsSt, valorIcmsSt, baseCalculoIpi, aliquotaIpi,
                baseCalculoPis, aliquotaPis, baseCalculoCofins, aliquotaCofins, null, null);
    }
}
