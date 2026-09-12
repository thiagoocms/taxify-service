package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
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
public class ProductFiscalRuleService extends AbstractCrudService<ProductFiscalRule, UUID, ProductFiscalRuleDTO, ProductFiscalRuleFilterDTO> {

    @Autowired
    protected ProductFiscalRuleService(ProductFiscalRuleRepository repository, ProductFiscalRuleMapper mapper,
                                        ProductFiscalRuleValidation validation, MessageService messageService,
                                        ProductFiscalRuleSpecification specification) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.productFiscalRule.not.found");
    }
}
