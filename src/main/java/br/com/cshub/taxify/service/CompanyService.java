package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CompanyService extends AbstractCrudService<Company, UUID, CompanyDTO, CompanyFilterDTO> {

    private final TenantAccessService tenantAccessService;

    @Autowired
    protected CompanyService(CompanyRepository repository, CompanyMapper mapper, CompanyValidation validation, MessageService messageService, CompanySpecification specification,
                              TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.Company.not.found");
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    protected void checkAccess(Company entity) {
        tenantAccessService.assertCompanyAccess(entity);
    }

    @Override
    protected Specification<Company> restrictQuery(Specification<Company> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<Company> restriction = (root, query, builder) -> builder.equal(root.get("accountingFirm").get("id"), firmId.get());
        spec = spec == null ? restriction : spec.and(restriction);
        return tenantAccessService.restrictToUserCompanies(spec, root -> root);
    }
}
