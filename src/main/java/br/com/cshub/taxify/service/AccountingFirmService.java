package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.FirmScopedCrudService;
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
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountingFirmService extends FirmScopedCrudService<AccountingFirm, UUID, AccountingFirmDTO, AccountingFirmFilterDTO> {


    @Autowired
    protected AccountingFirmService(AccountingFirmRepository repository, AccountingFirmMapper mapper, AccountingFirmValidation validation,
                                     MessageService messageService, AccountingFirmSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                AccountingFirm::getId, root -> root.get("id"));
        setMessageNotFound("error.accountingFirm.not.found");
    }

    /**
     * Same tenant boundary as everywhere else, but here the entity's own id already IS
     * the firm id - a firm only ever lists/reads/edits itself.
     */

}
