package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleFilterDTO;
import br.com.cshub.taxify.mapper.ProductFiscalRuleMapper;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.search.ProductFiscalRuleSpecification;
import br.com.cshub.taxify.validation.ProductFiscalRuleValidation;
import jakarta.persistence.criteria.JoinType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ProductFiscalRuleService extends AbstractCrudService<ProductFiscalRule, UUID, ProductFiscalRuleDTO, ProductFiscalRuleFilterDTO> {

    private final TenantAccessService tenantAccessService;

    @Autowired
    protected ProductFiscalRuleService(ProductFiscalRuleRepository repository, ProductFiscalRuleMapper mapper,
                                        ProductFiscalRuleValidation validation, MessageService messageService,
                                        ProductFiscalRuleSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.productFiscalRule.not.found");
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    protected void checkAccess(ProductFiscalRule entity) {
        tenantAccessService.assertSameFirm(entity.getCompany().getAccountingFirm().getId());
    }

    @Override
    protected Specification<ProductFiscalRule> restrictQuery(Specification<ProductFiscalRule> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<ProductFiscalRule> restriction = (root, query, builder) ->
                builder.equal(root.join("company", JoinType.LEFT).get("accountingFirm").get("id"), firmId.get());
        return spec == null ? restriction : spec.and(restriction);
    }
}
