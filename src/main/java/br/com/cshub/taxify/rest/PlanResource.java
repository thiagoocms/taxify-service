package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.plan.PlanDTO;
import br.com.cshub.taxify.dto.plan.PlanFilterDTO;
import br.com.cshub.taxify.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/plans")
public class PlanResource extends AbstractCrudResource<UUID, PlanDTO, PlanFilterDTO> {

    @Autowired
    protected PlanResource(PlanService service) {
        super(service);
    }
}
