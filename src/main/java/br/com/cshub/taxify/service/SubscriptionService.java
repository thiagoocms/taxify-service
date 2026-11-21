package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CompanyScopedCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.subscription.SubscriptionDTO;
import br.com.cshub.taxify.dto.subscription.SubscriptionFilterDTO;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.mapper.SubscriptionMapper;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.search.SubscriptionSpecification;
import br.com.cshub.taxify.validation.CompanyValidation;
import br.com.cshub.taxify.validation.SubscriptionValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class SubscriptionService extends CompanyScopedCrudService<Subscription, UUID, SubscriptionDTO, SubscriptionFilterDTO> {

    private final SubscriptionRepository repository;
    private final MessageService messageService;
    private final CompanyValidation companyValidation;

    @Autowired
    protected SubscriptionService(SubscriptionRepository repository, SubscriptionMapper mapper, SubscriptionValidation validation,
                                   MessageService messageService, SubscriptionSpecification specification,
                                   TenantAccessService tenantAccessService, CompanyValidation companyValidation) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                Subscription::getCompany, root -> root.get("company"));
        setMessageNotFound("error.subscription.not.found");
        this.repository = repository;
        this.messageService = messageService;
        this.companyValidation = companyValidation;
    }

    @Transactional(readOnly = true)
    public SubscriptionDTO findCurrentByCompany(UUID companyId) {
        companyValidation.checkExist(companyId);
        var today = LocalDate.now();
        var entity = repository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(companyId, today, today)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.subscription.not.found")));
        return toModel(entity);
    }


}
