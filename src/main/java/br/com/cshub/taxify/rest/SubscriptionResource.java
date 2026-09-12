package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.subscription.SubscriptionDTO;
import br.com.cshub.taxify.dto.subscription.SubscriptionFilterDTO;
import br.com.cshub.taxify.service.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/subscriptions")
public class SubscriptionResource extends AbstractCrudResource<UUID, SubscriptionDTO, SubscriptionFilterDTO> {

    private final SubscriptionService service;

    @Autowired
    protected SubscriptionResource(SubscriptionService service) {
        super(service);
        this.service = service;
    }

    @GetMapping("/current")
    public ResponseEntity<SubscriptionDTO> getCurrentByCompany(@RequestParam UUID companyId) {
        return ResponseEntity.ok(service.findCurrentByCompany(companyId));
    }
}
