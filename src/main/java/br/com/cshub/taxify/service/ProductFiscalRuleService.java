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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ProductFiscalRuleService extends CompanyScopedCrudService<ProductFiscalRule, UUID, ProductFiscalRuleDTO, ProductFiscalRuleFilterDTO> {


    private final ApplicationEventPublisher events;

    @Autowired
    protected ProductFiscalRuleService(ProductFiscalRuleRepository repository, ProductFiscalRuleMapper mapper,
                                        ProductFiscalRuleValidation validation, MessageService messageService,
                                        ProductFiscalRuleSpecification specification, TenantAccessService tenantAccessService,
                                        ApplicationEventPublisher events) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                ProductFiscalRule::getCompany, root -> root.get("company"));
        setMessageNotFound("error.productFiscalRule.not.found");
        this.events = events;
    }

    /** Property names before the English-naming refactor, still accepted in {@code sort=}. */
    static final Map<String, String> LEGACY_SORT_PROPERTIES = Map.ofEntries(
            Map.entry("codigoProduto", "productCode"),
            Map.entry("documentoFornecedor", "supplierDocument"),
            Map.entry("descricaoProduto", "productDescription"),
            Map.entry("ncmCorreto", "correctNcm"),
            Map.entry("cfopCorreto", "correctCfop"),
            Map.entry("observacoes", "notes"),
            Map.entry("taxRule.origemMercadoria", "taxRule.goodsOrigin"),
            Map.entry("taxRule.aliquotaIcms", "taxRule.icmsRate"),
            Map.entry("taxRule.aliquotaIpi", "taxRule.ipiRate"),
            Map.entry("taxRule.aliquotaPis", "taxRule.pisRate"),
            Map.entry("taxRule.aliquotaCofins", "taxRule.cofinsRate"),
            Map.entry("taxRule.classificacaoTributaria", "taxRule.taxClassification"));

    @Override
    protected Map<String, String> legacySortProperties() {
        return LEGACY_SORT_PROPERTIES;
    }

    /** Saved, and the notes with the product are rectified right after the commit (ProductFiscalRuleSavedEvent). */
    @Override
    @Transactional
    public ProductFiscalRuleDTO create(ProductFiscalRuleDTO model) {
        ProductFiscalRuleDTO saved = super.create(model);
        publish(saved);
        return saved;
    }

    @Override
    @Transactional
    public ProductFiscalRuleDTO update(UUID id, ProductFiscalRuleDTO model) {
        ProductFiscalRuleDTO saved = super.update(id, model);
        publish(saved);
        return saved;
    }

    private void publish(ProductFiscalRuleDTO rule) {
        if (rule != null && rule.companyId() != null) {
            events.publishEvent(new ProductFiscalRuleSavedEvent(UUID.fromString(rule.companyId()), rule.codigoProduto(),
                    rule.documentoFornecedor()));
        }
    }


}
