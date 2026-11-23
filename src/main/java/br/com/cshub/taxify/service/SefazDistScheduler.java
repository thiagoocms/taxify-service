package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
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
 * Hourly SEFAZ capture, alongside SIEG: every company of the configured UFs
 * ({@code sefaz.dist.ufs}, PE to start) with an A1 certificate (its own or one of the same
 * CNPJ root in the firm) and the fiscal XML import service on its current plan. Runs on a
 * virtual thread so the shared scheduler thread (the SIEG sync ticks every minute) is
 * never held by the SEFAZ calls; a run still going when the next one fires is not doubled.
 * Each company decides itself whether SEFAZ's waiting hour is over (SefazDistService).
 */
@Component
public class SefazDistScheduler {

    private static final Logger log = LoggerFactory.getLogger(SefazDistScheduler.class);

    private final CompanyCertificateRepository certificateRepository;
    private final CompanyRepository companyRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SefazDistService service;
    private final SefazProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SefazDistScheduler(CompanyCertificateRepository certificateRepository, CompanyRepository companyRepository,
                              SubscriptionRepository subscriptionRepository, SefazDistService service,
                              SefazProperties properties) {
        this.certificateRepository = certificateRepository;
        this.companyRepository = companyRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(cron = "${sefaz.dist.cron:0 15 * * * *}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("SEFAZ capture still running from the previous tick - skipped");
            return;
        }
        Thread.ofVirtual().name("sefaz-dist").start(() -> {
            try {
                captureAll();
            } catch (RuntimeException e) {
                log.warn("SEFAZ capture run failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    void captureAll() {
        List<Company> companies = candidates();
        if (companies.isEmpty()) {
            return;
        }
        log.info("SEFAZ capture: {} companies with certificate and the XML import service", companies.size());
        for (Company company : companies) {
            try {
                service.capture(company);
            } catch (RuntimeException e) {
                log.warn("SEFAZ capture failed for company {}", company.getId(), e);
            }
        }
    }

    /** Companies of the covered UFs whose firm holds a certificate of their CNPJ root, entitled today. */
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
                .filter(company -> properties.coversUf(company.getUf()))
                .filter(company -> entitled(company, today))
                .toList();
    }

    private boolean entitled(Company company, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        company.getId(), today, today)
                .map(Subscription::getPlan)
                .map(plan -> plan.getServices().contains(PlanServiceEnum.FISCAL_XML_IMPORT))
                .orElse(false);
    }
}
