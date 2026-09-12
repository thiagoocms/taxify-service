package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;

import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import br.com.cshub.taxify.dto.user_company.UserCompanyDTO;
import br.com.cshub.taxify.dto.user_company.UserCompanyFilterDTO;
import br.com.cshub.taxify.mapper.UserCompanyMapper;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.search.UserCompanySpecification;
import br.com.cshub.taxify.validation.UserCompanyValidation;
import jakarta.persistence.criteria.JoinType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class UserCompanyService extends AbstractCrudService<UserCompany, UserCompanyId, UserCompanyDTO, UserCompanyFilterDTO> {

    private final TenantAccessService tenantAccessService;

    @Autowired
    protected UserCompanyService(UserCompanyRepository repository, UserCompanyMapper mapper, UserCompanyValidation validation, MessageService messageService, UserCompanySpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.Company.not.found");
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    protected void checkAccess(UserCompany entity) {
        tenantAccessService.assertSameFirm(entity.getCompany().getAccountingFirm().getId());
    }

    @Override
    protected Specification<UserCompany> restrictQuery(Specification<UserCompany> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<UserCompany> restriction = (root, query, builder) ->
                builder.equal(root.join("company", JoinType.LEFT).get("accountingFirm").get("id"), firmId.get());
        return spec == null ? restriction : spec.and(restriction);
    }
}
