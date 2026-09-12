package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.dto.accountingfirm.AccountingFirmDTO;
import br.com.cshub.taxify.dto.accountingfirm.AccountingFirmFilterDTO;
import br.com.cshub.taxify.mapper.AccountingFirmMapper;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.search.AccountingFirmSpecification;
import br.com.cshub.taxify.validation.AccountingFirmValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountingFirmService extends AbstractCrudService<AccountingFirm, UUID, AccountingFirmDTO, AccountingFirmFilterDTO> {

    private final TenantAccessService tenantAccessService;

    @Autowired
    protected AccountingFirmService(AccountingFirmRepository repository, AccountingFirmMapper mapper, AccountingFirmValidation validation,
                                     MessageService messageService, AccountingFirmSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.accountingFirm.not.found");
        this.tenantAccessService = tenantAccessService;
    }

    /**
     * Same tenant boundary as everywhere else, but here the entity's own id already IS
     * the firm id - a firm only ever lists/reads/edits itself.
     */
    @Override
    protected void checkAccess(AccountingFirm entity) {
        tenantAccessService.assertSameFirm(entity.getId());
    }

    @Override
    protected Specification<AccountingFirm> restrictQuery(Specification<AccountingFirm> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<AccountingFirm> restriction = (root, query, builder) -> builder.equal(root.get("id"), firmId.get());
        return spec == null ? restriction : spec.and(restriction);
    }
}
