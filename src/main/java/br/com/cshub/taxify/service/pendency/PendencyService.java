package br.com.cshub.taxify.service.pendency;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.domain.xml.NfseAdnControl;
import br.com.cshub.taxify.domain.pendency.CaptureRun;
import br.com.cshub.taxify.repository.NfseAdnControlRepository;
import br.com.cshub.taxify.dto.pendency.PendencyDTOs;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single pendencies table (roadmap M13): keeps it in step with what {@link PendencyFinder}
 * finds, and serves the screen - list, counters, integrations' health - and the manual
 * resolve / dismiss / reopen, always with a comment.
 */
@Service
public class PendencyService {

    static final String SYSTEM = "sistema";
    static final String AUTO_CLOSE_COMMENT = "Resolvida automaticamente: a causa não existe mais.";
    private static final int MAX_PAGE_SIZE = 200;
    private static final int HEALTH_DAYS = 7;

    private final PendencyRepository repository;
    private final PendencyFinder finder;
    private final CompanyRepository companyRepository;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final SefazDistControlRepository controlRepository;
    private final CaptureRunRepository captureRunRepository;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;
    private final NfseAdnControlRepository adnControlRepository;

    public PendencyService(PendencyRepository repository, PendencyFinder finder, CompanyRepository companyRepository,
                           CompanyValidation companyValidation, TenantAccessService tenantAccessService,
                           SefazDistControlRepository controlRepository, CaptureRunRepository captureRunRepository,
                           MessageService messageService, AuditorAware<String> auditorAware,
                           NfseAdnControlRepository adnControlRepository) {
        this.adnControlRepository = adnControlRepository;
        this.repository = repository;
        this.finder = finder;
        this.companyRepository = companyRepository;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        this.controlRepository = controlRepository;
        this.captureRunRepository = captureRunRepository;
        this.messageService = messageService;
        this.auditorAware = auditorAware;
    }

    /** Re-reads the company's causes and brings its pendencies in line. @return pendencies open after the sync */
    @Transactional
    public int refresh(Company company) {
        return sync(company, finder.find(company));
    }

    /**
     * New cause = new open pendency; known cause = refreshed (and reopened if it had gone away after
     * being closed); a cause no longer found closes its open pendency by itself.
     */
    @Transactional
    public int sync(Company company, List<PendencyFinder.Finding> findings) {
        return sync(company, findings, repository.findByCompany_IdAndTypeIn(company.getId(), PendencyFinder.MANAGED));
    }

    /**
     * Same as {@link #sync(Company, List)} but only over the pendencies of these types and competence -
     * the audit writes one company and month at a time and must not touch the other months.
     */
    @Transactional
    public int syncScoped(Company company, Set<PendencyType> types, String competence, List<PendencyFinder.Finding> findings) {
        List<Pendency> existing = repository.findByCompany_IdAndTypeIn(company.getId(), types).stream()
                .filter(pendency -> competence.equals(pendency.getCompetence()))
                .toList();
        return sync(company, findings, existing);
    }

    private int sync(Company company, List<PendencyFinder.Finding> findings, List<Pendency> current) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Pendency> existing = current.stream()
                .collect(Collectors.toMap(Pendency::getNaturalKey, Function.identity(), (a, b) -> a));
        Set<String> found = new java.util.HashSet<>();
        List<Pendency> changed = new ArrayList<>();
        for (PendencyFinder.Finding finding : findings) {
            if (!found.add(finding.naturalKey())) {
                continue;
            }
            Pendency pendency = existing.get(finding.naturalKey());
            if (pendency == null) {
                pendency = new Pendency();
                pendency.setCompany(company);
                pendency.setNaturalKey(finding.naturalKey());
                pendency.setFirstSeenAt(now);
                pendency.setDeleted(false);
            } else if (!pendency.isOpen() && pendency.getAbsentSince() != null) {
                reopen(pendency, now);
            }
            pendency.setType(finding.type());
            pendency.setSeverity(finding.severity());
            pendency.setCompetence(finding.competence());
            pendency.setObjectType(finding.objectType());
            pendency.setObjectId(finding.objectId());
            pendency.setTitle(truncate(finding.title(), 200));
            pendency.setDetail(truncate(finding.detail(), 1000));
            pendency.setLastSeenAt(now);
            pendency.setAbsentSince(null);
            changed.add(pendency);
        }
        for (Pendency pendency : existing.values()) {
            if (found.contains(pendency.getNaturalKey())) {
                continue;
            }
            if (pendency.isOpen()) {
                pendency.setStatus(PendencyStatus.RESOLVIDA);
                pendency.setClosedAt(now);
                pendency.setClosedBy(SYSTEM);
                pendency.setCloseComment(AUTO_CLOSE_COMMENT);
                pendency.setAutoClosed(true);
                pendency.setAbsentSince(now);
                changed.add(pendency);
            } else if (pendency.getAbsentSince() == null) {
                pendency.setAbsentSince(now);
                changed.add(pendency);
            }
        }
        repository.saveAll(changed);
        return (int) changed.stream().filter(Pendency::isOpen).count();
    }

    @Transactional(readOnly = true)
    public Page<PendencyDTOs.Pendency> list(String companyId, PendencyStatus status, PendencySeverity severity,
                                           PendencyType type, String competence, int page, int size) {
        Collection<UUID> scope = scope(companyId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        if (scope.isEmpty()) {
            return Page.empty(pageable);
        }
        String month = competence == null || competence.isBlank() ? null : competence.strip();
        return repository.search(scope, status, severity, type, month, pageable).map(PendencyService::toDto);
    }

    @Transactional(readOnly = true)
    public PendencyDTOs.Summary summary(String companyId) {
        Collection<UUID> scope = scope(companyId);
        Map<PendencyType, long[]> counts = new EnumMap<>(PendencyType.class);
        if (!scope.isEmpty()) {
            for (Object[] row : repository.countOpen(scope)) {
                long[] bySeverity = counts.computeIfAbsent((PendencyType) row[0], key -> new long[PendencySeverity.values().length]);
                bySeverity[((PendencySeverity) row[1]).ordinal()] += (Long) row[2];
            }
        }
        List<PendencyDTOs.Counter> counters = new ArrayList<>();
        long total = 0;
        for (Map.Entry<PendencyType, long[]> entry : counts.entrySet()) {
            long[] c = entry.getValue();
            long sum = c[0] + c[1] + c[2] + c[3];
            total += sum;
            counters.add(new PendencyDTOs.Counter(entry.getKey().name(), entry.getKey().label, sum, c[0], c[1], c[2], c[3]));
        }
        counters.sort(Comparator.comparingLong(PendencyDTOs.Counter::alta).reversed()
                .thenComparing(Comparator.comparingLong(PendencyDTOs.Counter::total).reversed()));
        return new PendencyDTOs.Summary(counters, total);
    }

    /** The collectors (SEFAZ, NFS-e Nacional and the certidão SEFAZ-PE) of every company in scope that has one: last run and the last 7 days. */
    @Transactional(readOnly = true)
    public List<PendencyDTOs.IntegrationHealth> health(String companyId) {
        Collection<UUID> scope = scope(companyId);
        if (scope.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> runs = new HashMap<>();
        for (Object[] row : captureRunRepository.countSince(scope, LocalDateTime.now().minusDays(HEALTH_DAYS))) {
            long[] counts = runs.computeIfAbsent(row[0] + "|" + row[1], key -> new long[2]);
            counts[0] += (Long) row[3];
            if ("ERRO".equals(row[2])) {
                counts[1] += (Long) row[3];
            }
        }
        List<PendencyDTOs.IntegrationHealth> health = new ArrayList<>();
        for (SefazDistControl control : controlRepository.findByCompany_IdIn(scope)) {
            long[] counts = runs.getOrDefault(control.getCompany().getId() + "|" + CaptureRun.SEFAZ_DIST, new long[2]);
            health.add(new PendencyDTOs.IntegrationHealth(control.getCompany().getId(), control.getCompany().getName(),
                    "SEFAZ", control.getLastRunAt(), control.getLastStatus() == null ? null : control.getLastStatus().name(),
                    control.getLastDetail(), control.getNextQueryAt(), counts[0], counts[1]));
        }
        for (NfseAdnControl control : adnControlRepository.findByCompany_IdIn(scope)) {
            long[] counts = runs.getOrDefault(control.getCompany().getId() + "|" + CaptureRun.NFSE_ADN, new long[2]);
            health.add(new PendencyDTOs.IntegrationHealth(control.getCompany().getId(), control.getCompany().getName(),
                    "NFS-e Nacional", control.getLastRunAt(), control.getLastStatus() == null ? null : control.getLastStatus().name(),
                    control.getLastDetail(), control.getNextQueryAt(), counts[0], counts[1]));
        }
        // Certidão SEFAZ-PE: no control table - its newest attempt per company
        for (CaptureRun run : captureRunRepository.findLatestOf(scope, CaptureRun.TAX_CLEARANCE_PE)) {
            long[] counts = runs.getOrDefault(run.getCompany().getId() + "|" + CaptureRun.TAX_CLEARANCE_PE, new long[2]);
            health.add(new PendencyDTOs.IntegrationHealth(run.getCompany().getId(), run.getCompany().getName(),
                    "Certidão SEFAZ-PE", run.getStartedAt(), run.getStatus(), run.getDetail(), null, counts[0], counts[1]));
        }
        health.sort(Comparator.comparing((PendencyDTOs.IntegrationHealth item) -> !"ERRO".equals(item.lastStatus()))
                .thenComparing(PendencyDTOs.IntegrationHealth::companyName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return health;
    }

    @Transactional
    public PendencyDTOs.Pendency close(UUID id, PendencyStatus status, String comment) {
        if (status == PendencyStatus.ABERTA) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
        Pendency pendency = owned(id);
        pendency.setStatus(status);
        pendency.setClosedAt(LocalDateTime.now());
        pendency.setClosedBy(auditorAware.getCurrentAuditor().orElse(SYSTEM));
        pendency.setCloseComment(truncate(comment.strip(), 500));
        pendency.setAutoClosed(false);
        return toDto(repository.save(pendency));
    }

    @Transactional
    public PendencyDTOs.Pendency reopen(UUID id) {
        Pendency pendency = owned(id);
        reopen(pendency, LocalDateTime.now());
        return toDto(repository.save(pendency));
    }

    private static void reopen(Pendency pendency, LocalDateTime now) {
        pendency.setStatus(PendencyStatus.ABERTA);
        pendency.setClosedAt(null);
        pendency.setClosedBy(null);
        pendency.setCloseComment(null);
        pendency.setAutoClosed(false);
        pendency.setFirstSeenAt(now);
    }

    private Pendency owned(UUID id) {
        Pendency pendency = repository.findById(id)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request")));
        tenantAccessService.assertCompanyAccess(pendency.getCompany());
        return pendency;
    }

    /** One company (access checked) or every active company of the user's firm they can see. */
    Collection<UUID> scope(String companyId) {
        if (companyId != null && !companyId.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(companyId.strip());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
            }
            return List.of(companyValidation.checkExist(id).getId());
        }
        UUID firmId = tenantAccessService.getCurrentAccountingFirmId()
                .orElseThrow(() -> new BadRequestException("error.pendency.firm.required", messageService.getMessage("error.pendency.firm.required")));
        return tenantAccessService.accessibleCompanyIds(
                companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmId).stream()
                        .filter(company -> !Boolean.FALSE.equals(company.getActive()))
                        .map(Company::getId)
                        .toList());
    }

    private static PendencyDTOs.Pendency toDto(Pendency pendency) {
        Company company = pendency.getCompany();
        return new PendencyDTOs.Pendency(pendency.getId(), company.getId(), company.getName(),
                company.getDocument() == null ? null : company.getDocument().getNumber(), pendency.getType().name(),
                pendency.getType().label, pendency.getSeverity().name(), pendency.getCompetence(), pendency.getObjectType(),
                pendency.getObjectId(), pendency.getTitle(), pendency.getDetail(), pendency.getStatus().name(),
                pendency.getFirstSeenAt(), pendency.getLastSeenAt(), pendency.getClosedAt(), pendency.getClosedBy(),
                pendency.getCloseComment(), pendency.isAutoClosed());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
