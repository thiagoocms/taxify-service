package br.com.cshub.taxify.service;

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

    public FiscalXmlSyncControlService(FiscalXmlSyncControlRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<FiscalXmlSyncControlDTO> findAll() {
        return repository.findAll().stream().map(this::toDTO).toList();
    }

    private FiscalXmlSyncControlDTO toDTO(FiscalXmlSyncControl control) {
        return new FiscalXmlSyncControlDTO(
                control.getCompany().getId().toString(),
                control.getCompany().getName(),
                control.getDataEmissaoInicio(),
                control.getDataEmissaoFim(),
                control.getTotalSieg(),
                control.getTotalImportado(),
                control.getTotalSieg() - control.getTotalImportado(),
                control.getUltimaVerificacaoEm()
        );
    }
}
