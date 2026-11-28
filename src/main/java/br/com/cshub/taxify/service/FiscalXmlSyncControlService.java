package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.CaptureCounts;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSyncControlDTO;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.service.capture.CaptureCountsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only: the queue rows ({@link FiscalXmlSyncControl}) and the received side's counts of the window last checked
 * ({@code capture_period_counts}, FD-RN-32), written by {@link FiscalXmlIntegrationService#syncFromSieg} - this only
 * reports them, with the same JSON as before.
 */
@Service
public class FiscalXmlSyncControlService {

    private final FiscalXmlSyncControlRepository repository;
    private final TenantAccessService tenantAccessService;
    private final CaptureCountsService countsService;

    public FiscalXmlSyncControlService(FiscalXmlSyncControlRepository repository, TenantAccessService tenantAccessService,
                                       CaptureCountsService countsService) {
        this.repository = repository;
        this.tenantAccessService = tenantAccessService;
        this.countsService = countsService;
    }

    @Transactional(readOnly = true)
    public List<FiscalXmlSyncControlDTO> findAll() {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        List<FiscalXmlSyncControl> controls = tenantAccessService.filterAccessible(repository.findAll().stream()
                        .filter(control -> firmId.isEmpty() || firmId.get().equals(control.getCompany().getAccountingFirm().getId()))
                        .toList(),
                control -> control.getCompany().getId());
        List<Company> companies = controls.stream().map(FiscalXmlSyncControl::getCompany).toList();
        Map<UUID, CaptureCounts> counts = countsService.siegNfe(companies);
        return controls.stream().map(control -> toDTO(control, counts.get(control.getCompany().getId()))).toList();
    }

    /**
     * The received side as the old row had it: its last checked month (provider total and imported, 0 = never), the
     * shared window and the time of the check - a company never checked shows the time it entered the queue.
     */
    private FiscalXmlSyncControlDTO toDTO(FiscalXmlSyncControl control, CaptureCounts counts) {
        CapturePeriodCount received = counts == null ? null : counts.current(CaptureSide.RECEIVED).orElse(null);
        int totalSieg = received == null || received.getProviderTotal() == null ? 0 : received.getProviderTotal();
        int imported = received == null ? 0 : received.getImportedTotal();
        LocalDateTime checkedAt = received == null || received.getImportedCountedAt() == null
                ? control.getCreatedDate() : received.getImportedCountedAt();
        return new FiscalXmlSyncControlDTO(
                control.getCompany().getId().toString(),
                control.getCompany().getName(),
                counts == null ? null : counts.windowStart(),
                counts == null ? null : counts.windowEnd(),
                totalSieg,
                imported,
                totalSieg - imported,
                checkedAt
        );
    }
}
