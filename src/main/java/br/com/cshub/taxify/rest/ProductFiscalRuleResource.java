package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fiscalrule.CompanyProductDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalDivergenceDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleFilterDTO;
import br.com.cshub.taxify.service.CompanyProductService;
import br.com.cshub.taxify.service.ProductFiscalDivergenceService;
import br.com.cshub.taxify.service.ProductFiscalRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RequiresPermission(value = Permission.FISCAL_RULES_VIEW, write = Permission.FISCAL_RULES_MANAGE)
@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/product-fiscal-rules")
public class ProductFiscalRuleResource extends AbstractCrudResource<UUID, ProductFiscalRuleDTO, ProductFiscalRuleFilterDTO> {

    private final ProductFiscalDivergenceService divergenceService;
    private final CompanyProductService companyProductService;

    @Autowired
    protected ProductFiscalRuleResource(ProductFiscalRuleService service, ProductFiscalDivergenceService divergenceService,
                                        CompanyProductService companyProductService) {
        super(service);
        this.divergenceService = divergenceService;
        this.companyProductService = companyProductService;
    }

    /**
     * The company's products (distinct codes from its stored notes) matching {@code search}
     * by code or description - what the rule form picks the product from.
     */
    @GetMapping("/produtos")
    public ResponseEntity<List<CompanyProductDTO>> produtos(@RequestParam String companyId,
                                                            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(companyProductService.search(companyId, search));
    }

    /**
     * Notes already downloaded/stored whose item CFOP (and/or NCM, when the rule pins
     * one) diverges from the registered rule for that product - what needs rectifying.
     */
    @GetMapping("/divergencias")
    public ResponseEntity<List<ProductFiscalDivergenceDTO>> divergencias(
            @RequestParam String companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataEmissaoInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataEmissaoFim) {
        return ResponseEntity.ok(divergenceService.findDivergences(companyId, dataEmissaoInicio, dataEmissaoFim));
    }
}
