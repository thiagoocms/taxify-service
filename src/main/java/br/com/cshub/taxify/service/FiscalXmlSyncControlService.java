package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSyncControlDTO;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only: {@link FiscalXmlSyncControl} rows are written exclusively by
 * {@link FiscalXmlIntegrationService#syncFromSieg} - this only reports them.
 */
@Service
public class FiscalXmlSyncControlService {

    private final FiscalXmlSyncControlRepository repository;
    private final TenantAccessService tenantAccessService;

    public FiscalXmlSyncControlService(FiscalXmlSyncControlRepository repository, TenantAccessService tenantAccessService) {
        this.repository = repository;
        this.tenantAccessService = tenantAccessService;
    }

    @Transactional(readOnly = true)
    public List<FiscalXmlSyncControlDTO> findAll() {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        return repository.findAll().stream()
                .filter(control -> firmId.isEmpty() || firmId.get().equals(control.getCompany().getAccountingFirm().getId()))
                .filter(control -> tenantAccessService.canAccessCompany(control.getCompany().getId()))
                .map(this::toDTO)
                .toList();
    }

    private FiscalXmlSyncControlDTO toDTO(FiscalXmlSyncControl control) {
        return new FiscalXmlSyncControlDTO(
                control.getCompany().getId().toString(),
                control.getCompany().getName(),
                control.getDirecao(),
                control.getDataEmissaoInicio(),
                control.getDataEmissaoFim(),
                control.getTotalSieg(),
                control.getTotalImportado(),
                control.getTotalSieg() - control.getTotalImportado(),
                control.getUltimaVerificacaoEm()
        );
    }
}
