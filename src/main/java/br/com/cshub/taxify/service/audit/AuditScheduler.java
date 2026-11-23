package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daily Auditoria tipo malha (05:30): the previous and the current competence of every active company
 * whose plan has "Auditoria de notas fiscais". On a virtual thread; a run still going isn't doubled.
 */
@Component
public class AuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditScheduler.class);

    private final CompanyRepository companyRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AuditService auditService;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AuditScheduler(CompanyRepository companyRepository, SubscriptionRepository subscriptionRepository,
                          AuditService auditService, @Value("${audit.enabled:true}") boolean enabled) {
        this.companyRepository = companyRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.auditService = auditService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${audit.cron:0 30 5 * * *}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("audit").start(() -> {
            try {
                YearMonth current = YearMonth.now();
                int companies = 0;
                for (Company company : candidates()) {
                    companies++;
                    for (YearMonth month : List.of(current.minusMonths(1), current)) {
                        try {
                            auditService.audit(company, month, "sistema");
                        } catch (RuntimeException e) {
                            log.warn("Audit failed for company {} ({})", company.getId(), month, e);
                        }
                    }
                }
                log.info("Audit: {} companies audited", companies);
            } finally {
                running.set(false);
            }
        });
    }

    List<Company> candidates() {
        LocalDate today = LocalDate.now();
        return companyRepository.findAll().stream()
                .filter(company -> !company.isDeleted() && !Boolean.FALSE.equals(company.getActive()))
                .filter(company -> subscriptionRepository
                        .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                                company.getId(), today, today)
                        .map(Subscription::getPlan)
                        .map(plan -> plan.getServices().contains(PlanServiceEnum.INVOICE_AUDIT))
                        .orElse(false))
                .toList();
    }
}
