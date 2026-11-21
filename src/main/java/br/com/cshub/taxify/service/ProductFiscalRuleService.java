package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CompanyScopedCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleFilterDTO;
import br.com.cshub.taxify.mapper.ProductFiscalRuleMapper;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.search.ProductFiscalRuleSpecification;
import br.com.cshub.taxify.validation.ProductFiscalRuleValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ProductFiscalRuleService extends CompanyScopedCrudService<ProductFiscalRule, UUID, ProductFiscalRuleDTO, ProductFiscalRuleFilterDTO> {


    @Autowired
    protected ProductFiscalRuleService(ProductFiscalRuleRepository repository, ProductFiscalRuleMapper mapper,
                                        ProductFiscalRuleValidation validation, MessageService messageService,
                                        ProductFiscalRuleSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                ProductFiscalRule::getCompany, root -> root.get("company"));
        setMessageNotFound("error.productFiscalRule.not.found");
    }


}
