package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CompanyScopedCrudService;
import org.springframework.transaction.annotation.Transactional;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.dto.company.CompanyFilterDTO;
import br.com.cshub.taxify.mapper.CompanyMapper;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.search.CompanySpecification;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CompanyService extends CompanyScopedCrudService<Company, UUID, CompanyDTO, CompanyFilterDTO> {

    private final CompanyTaxRegimeService taxRegimeService;
    private final CompanyRepository companyRepository;

    @Autowired
    protected CompanyService(CompanyRepository repository, CompanyMapper mapper, CompanyValidation validation, MessageService messageService, CompanySpecification specification,
                              TenantAccessService tenantAccessService, CompanyTaxRegimeService taxRegimeService) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                company -> company, root -> root);
        setMessageNotFound("error.Company.not.found");
        this.taxRegimeService = taxRegimeService;
        this.companyRepository = repository;
    }

    /** A regime chosen on the form starts a period in the regime history ("vigente a partir de"). */
    @Override
    @Transactional
    public CompanyDTO create(CompanyDTO model) {
        CompanyDTO created = super.create(model);
        recordRegime(created, model);
        return created;
    }

    @Override
    @Transactional
    public CompanyDTO update(UUID id, CompanyDTO model) {
        CompanyDTO updated = super.update(id, model);
        recordRegime(updated, model);
        return updated;
    }

    private void recordRegime(CompanyDTO saved, CompanyDTO request) {
        companyRepository.findById(UUID.fromString(saved.id()))
                .ifPresent(company -> taxRegimeService.recordChange(company, CompanyTaxRegimeService.monthOrNull(request.taxRegimeValidFrom())));
    }


}
