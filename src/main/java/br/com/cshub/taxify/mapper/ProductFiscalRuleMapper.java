package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProductFiscalRuleMapper extends AbstractMapper<ProductFiscalRule, ProductFiscalRuleDTO> {

    @Override
    protected ProductFiscalRule toEntity(ProductFiscalRuleDTO source) {
        ProductFiscalRule entity = new ProductFiscalRule();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setCompany(source.companyId() != null ? new Company(UUID.fromString(source.companyId())) : null);
        entity.setCodigoProduto(source.codigoProduto());
        entity.setDocumentoFornecedor(supplierDigits(source.documentoFornecedor()));
        entity.setDescricaoProduto(source.descricaoProduto());
        entity.setNcmCorreto(source.ncmCorreto());
        entity.setCfopCorreto(source.cfopCorreto());
        entity.setObservacoes(source.observacoes());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        entity.setTaxRule(taxRuleOf(source));
        return entity;
    }

    @Override
    protected ProductFiscalRuleDTO toModel(ProductFiscalRule source) {
        ProductTaxRule tax = source.getTaxRule();
        return new ProductFiscalRuleDTO(
                source.getId().toString(),
                source.getCompany().getId().toString(),
                source.getCodigoProduto(),
                source.getDescricaoProduto(),
                source.getNcmCorreto(),
                source.getCfopCorreto(),
                source.getObservacoes(),
                source.isDeleted(),
                tax == null ? null : tax.origemMercadoria(),
                tax == null ? null : tax.cstIcms(),
                tax == null ? null : tax.csosn(),
                tax == null ? null : tax.aliquotaIcms(),
                tax == null ? null : tax.cest(),
                tax == null ? null : tax.cstIpi(),
                tax == null ? null : tax.aliquotaIpi(),
                tax == null ? null : tax.cstPis(),
                tax == null ? null : tax.aliquotaPis(),
                tax == null ? null : tax.cstCofins(),
                tax == null ? null : tax.aliquotaCofins(),
                tax == null ? null : tax.cstIbsCbs(),
                tax == null ? null : tax.classificacaoTributaria(),
                source.getDocumentoFornecedor()
        );
    }

    @Override
    public void merge(ProductFiscalRule source, ProductFiscalRule target) {
        target.setCompany(source.getCompany());
        target.setCodigoProduto(source.getCodigoProduto());
        target.setDocumentoFornecedor(source.getDocumentoFornecedor());
        target.setDescricaoProduto(source.getDescricaoProduto());
        target.setNcmCorreto(source.getNcmCorreto());
        target.setCfopCorreto(source.getCfopCorreto());
        target.setObservacoes(source.getObservacoes());
        target.setDeleted(source.isDeleted());
        target.setTaxRule(source.getTaxRule());
    }

    /** Blank fields become null ("don't check"); a rule with no tax field at all has no ProductTaxRule. */
    private static ProductTaxRule taxRuleOf(ProductFiscalRuleDTO source) {
        ProductTaxRule rule = new ProductTaxRule(
                trimToNull(source.origemMercadoria()),
                trimToNull(source.cstIcms()),
                trimToNull(source.csosn()),
                source.aliquotaIcms(),
                trimToNull(source.cest()),
                trimToNull(source.cstIpi()),
                source.aliquotaIpi(),
                trimToNull(source.cstPis()),
                source.aliquotaPis(),
                trimToNull(source.cstCofins()),
                source.aliquotaCofins(),
                trimToNull(source.cstIbsCbs()),
                trimToNull(source.classificacaoTributaria()));
        return rule.equals(EMPTY) ? null : rule;
    }

    private static final ProductTaxRule EMPTY = new ProductTaxRule(null, null, null, null, null, null, null, null,
            null, null, null, null, null);

    /** Stored as digits; blank = every supplier. */
    private static String supplierDigits(String value) {
        String digits = br.com.cshub.taxify.common.DocumentNumbers.onlyDigits(value);
        return digits == null || digits.isEmpty() ? null : digits;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
