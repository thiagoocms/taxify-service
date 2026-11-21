package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.common.FirmScopedCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.dto.plan.PlanDTO;
import br.com.cshub.taxify.dto.plan.PlanFilterDTO;
import br.com.cshub.taxify.mapper.PlanMapper;
import br.com.cshub.taxify.repository.PlanRepository;
import br.com.cshub.taxify.search.PlanSpecification;
import br.com.cshub.taxify.validation.PlanValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PlanService extends FirmScopedCrudService<Plan, UUID, PlanDTO, PlanFilterDTO> {

    @Autowired
    protected PlanService(PlanRepository repository, PlanMapper mapper, PlanValidation validation, MessageService messageService, PlanSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                plan -> plan.getAccountingFirm() == null ? null : plan.getAccountingFirm().getId(), root -> root.get("accountingFirm").get("id"));
        setMessageNotFound("error.plan.not.found");
    }

}
