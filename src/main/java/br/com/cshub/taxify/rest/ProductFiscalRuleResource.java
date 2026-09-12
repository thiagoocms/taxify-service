package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalDivergenceDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleFilterDTO;
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

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/product-fiscal-rules")
public class ProductFiscalRuleResource extends AbstractCrudResource<UUID, ProductFiscalRuleDTO, ProductFiscalRuleFilterDTO> {

    private final ProductFiscalDivergenceService divergenceService;

    @Autowired
    protected ProductFiscalRuleResource(ProductFiscalRuleService service, ProductFiscalDivergenceService divergenceService) {
        super(service);
        this.divergenceService = divergenceService;
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
