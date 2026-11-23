package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.nfse.AdnProperties;
import br.com.cshub.taxify.repository.CompanyCertificateRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Hourly NFS-e capture from the Ambiente Nacional for every company whose firm holds a certificate of
 * its CNPJ root and whose plan has the fiscal XML import with the national NFS-e integration. Runs on
 * a virtual thread; a run still going isn't doubled.
 */
@Component
public class NfseAdnScheduler {

    private static final Logger log = LoggerFactory.getLogger(NfseAdnScheduler.class);

    private final CompanyCertificateRepository certificateRepository;
    private final CompanyRepository companyRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final NfseAdnService service;
    private final AdnProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NfseAdnScheduler(CompanyCertificateRepository certificateRepository, CompanyRepository companyRepository,
                            SubscriptionRepository subscriptionRepository, NfseAdnService service, AdnProperties properties) {
        this.certificateRepository = certificateRepository;
        this.companyRepository = companyRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(cron = "${nfse.adn.cron:0 25 * * * *}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("ADN capture still running from the previous tick - skipped");
            return;
        }
        Thread.ofVirtual().name("nfse-adn").start(() -> {
            try {
                for (Company company : candidates()) {
                    try {
                        service.capture(company);
                    } catch (RuntimeException e) {
                        log.warn("ADN capture failed for company {}", company.getId(), e);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("ADN capture run failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    /** Companies whose firm holds a certificate of their CNPJ root, entitled today. */
    List<Company> candidates() {
        Map<UUID, Set<String>> rootsByFirm = certificateRepository.findAllWithCompany().stream()
                .map(CompanyCertificate::getCompany)
                .filter(company -> company.getAccountingFirm() != null && CompanyCertificateService.cnpjOf(company) != null)
                .collect(Collectors.groupingBy(company -> company.getAccountingFirm().getId(),
                        Collectors.mapping(company -> CompanyCertificateService.cnpjOf(company).substring(0, 8), Collectors.toSet())));
        LocalDate today = LocalDate.now();
        return rootsByFirm.entrySet().stream()
                .flatMap(entry -> companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(entry.getKey()).stream()
                        .filter(company -> {
                            String cnpj = CompanyCertificateService.cnpjOf(company);
                            return cnpj != null && entry.getValue().contains(cnpj.substring(0, 8));
                        }))
                .filter(company -> entitled(company.getId(), today))
                .toList();
    }

    /** Today's plan has the fiscal XML import with the national NFS-e integration. */
    public boolean entitled(UUID companyId, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        companyId, today, today)
                .map(Subscription::getPlan)
                .map(plan -> PlanServiceEnum.importsFrom(plan.getServices(), PlanServiceEnum.NFSE_NATIONAL_INTEGRATION))
                .orElse(false);
    }
}
