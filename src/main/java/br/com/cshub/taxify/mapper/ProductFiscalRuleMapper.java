package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
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
        entity.setDescricaoProduto(source.descricaoProduto());
        entity.setNcmCorreto(source.ncmCorreto());
        entity.setCfopCorreto(source.cfopCorreto());
        entity.setObservacoes(source.observacoes());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected ProductFiscalRuleDTO toModel(ProductFiscalRule source) {
        return new ProductFiscalRuleDTO(
                source.getId().toString(),
                source.getCompany().getId().toString(),
                source.getCodigoProduto(),
                source.getDescricaoProduto(),
                source.getNcmCorreto(),
                source.getCfopCorreto(),
                source.getObservacoes(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(ProductFiscalRule source, ProductFiscalRule target) {
        target.setCompany(source.getCompany());
        target.setCodigoProduto(source.getCodigoProduto());
        target.setDescricaoProduto(source.getDescricaoProduto());
        target.setNcmCorreto(source.getNcmCorreto());
        target.setCfopCorreto(source.getCfopCorreto());
        target.setObservacoes(source.getObservacoes());
        target.setDeleted(source.isDeleted());
    }
}
