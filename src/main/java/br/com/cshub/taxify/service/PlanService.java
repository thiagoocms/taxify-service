package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
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
public class PlanService extends AbstractCrudService<Plan, UUID, PlanDTO, PlanFilterDTO> {

    @Autowired
    protected PlanService(PlanRepository repository, PlanMapper mapper, PlanValidation validation, MessageService messageService, PlanSpecification specification) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.plan.not.found");
    }

}
