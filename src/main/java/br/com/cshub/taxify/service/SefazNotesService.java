package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus;
import br.com.cshub.taxify.dto.company.SefazNotesDTO;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/** The screen's view of the SEFAZ summaries (entries waiting for their XML) and the SIEG x SEFAZ comparison. */
@Service
public class SefazNotesService {

    private static final int MAX_LISTED = 500;
    /** SEFAZ only distributes the last 90 days - older SIEG notes can't be compared. */
    private static final int SEFAZ_WINDOW_DAYS = 90;

    private final SefazNoteSummaryRepository summaryRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SefazProperties properties;
    private final MessageService messageService;
    private final SefazManifestService manifestService;

    public SefazNotesService(SefazNoteSummaryRepository summaryRepository, FiscalXmlDocumentRepository documentRepository,
                             SubscriptionRepository subscriptionRepository, SefazProperties properties,
                             MessageService messageService, SefazManifestService manifestService) {
        this.manifestService = manifestService;
        this.summaryRepository = summaryRepository;
        this.documentRepository = documentRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.properties = properties;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public SefazNotesDTO of(Company company) {
        UUID companyId = company.getId();
        Long onlySieg = null;
        Long onlySefaz = null;
        if (hasBothSources(companyId)) {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(FiscalXmlQueryService.SOURCE_COMPARISON_HOURS);
            onlySieg = documentRepository.countOnlySieg(companyId, cutoff, LocalDateTime.now().minusDays(SEFAZ_WINDOW_DAYS));
            onlySefaz = documentRepository.countOnlySefaz(companyId, cutoff);
        }
        return new SefazNotesDTO(
                manifestService.manifests(companyId),
                summaryRepository.countByCompany_IdAndCompletedAtIsNullAndCienciaStatus(companyId, CienciaStatus.PENDENTE),
                summaryRepository.countByCompany_IdAndCompletedAtIsNullAndCienciaStatus(companyId, CienciaStatus.ENVIADA),
                summaryRepository.countByCompany_IdAndCompletedAtIsNullAndCienciaStatus(companyId, CienciaStatus.ERRO),
                onlySieg,
                onlySefaz,
                summaryRepository.findByCompany_IdAndCompletedAtIsNullOrderByIssueDateAsc(companyId, PageRequest.of(0, MAX_LISTED))
                        .stream().map(SefazNotesService::toDto).toList());
    }

    /** Puts a refused (or given-up) ciência back in line for the next capture. */
    @Transactional
    public SefazNotesDTO retryCiencia(Company company, String accessKey) {
        SefazNoteSummary summary = summaryRepository.findByCompany_IdAndAccessKey(company.getId(), accessKey == null ? null : accessKey.strip())
                .filter(found -> !found.isCompleted())
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.generic.bad.request")));
        summary.setCienciaStatus(CienciaStatus.PENDENTE);
        summary.setCienciaAttempts(0);
        summary.setCienciaDetail(null);
        summary.setLastFetchAt(null);
        summary.setFetchDetail(null);
        summaryRepository.save(summary);
        return of(company);
    }

    private boolean hasBothSources(UUID companyId) {
        LocalDate today = LocalDate.now();
        Set<PlanServiceEnum> services = subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        companyId, today, today)
                .map(Subscription::getPlan)
                .map(plan -> plan.getServices())
                .orElse(Set.of());
        return PlanServiceEnum.importsFrom(services, PlanServiceEnum.SIEG_INTEGRATION)
                && PlanServiceEnum.importsFrom(services, PlanServiceEnum.SEFAZ_INTEGRATION);
    }

    private static SefazNotesDTO.Summary toDto(SefazNoteSummary summary) {
        return new SefazNotesDTO.Summary(summary.getAccessKey(), summary.getIssuerDocument(), summary.getIssuerName(),
                summary.getIssueDate(), summary.getTotalValue(), summary.getSituation(), summary.getCienciaStatus().name(),
                summary.getCienciaAt(), summary.getCienciaDetail(), summary.getCienciaAttempts(), summary.getLastFetchAt(),
                summary.getFetchDetail(), summary.getCreatedDate());
    }
}
