package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.repository.PlanRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * O direito de cada empresa aos canais de captura (CP-RN-05), com a <b>mesma regra</b> dos agendadores
 * (FiscalXmlSyncScheduler#hasFiscalXmlImportEntitlement, SefazDistScheduler#entitled, NfseAdnScheduler#entitled): a
 * assinatura vigente no dia ({@code start_date <= dia <= end_date}, não excluída) de {@code start_date} mais recente, e
 * o plano dela com {@code FISCAL_XML_IMPORT} + a integração do canal ({@link PlanServiceEnum#importsFrom}). Aqui em
 * lote - duas consultas para todas as empresas - em vez de uma por empresa.
 *
 * <p>O dia é o mesmo dos agendadores ({@link #schedulerToday()}: a data de referência de teste ou o dia da JVM), para o
 * painel nunca dizer "fora do plano" de uma empresa que o agendador atende, nem o contrário, na virada do dia.
 */
@Component
public class CaptureEntitlement {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final FiscalXmlSyncProperties syncProperties;

    public CaptureEntitlement(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                              FiscalXmlSyncProperties syncProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.syncProperties = syncProperties;
    }

    /** O "hoje" dos agendadores de captura (LocalDate.now() da JVM, ou fiscal-xml.sync.reference-date). */
    public LocalDate schedulerToday() {
        return syncProperties.getReferenceDate() != null ? syncProperties.getReferenceDate() : LocalDate.now();
    }

    /**
     * Os serviços do plano vigente de cada empresa no dia; a empresa sem assinatura vigente não aparece no mapa ("Sem
     * assinatura vigente").
     */
    @Transactional(readOnly = true)
    public Map<UUID, Set<PlanServiceEnum>> currentServices(LocalDate day) {
        List<Object[]> rows = subscriptionRepository.findCurrentPlans(day);
        Map<UUID, Object[]> latest = new HashMap<>();
        for (Object[] row : rows) {
            latest.merge((UUID) row[0], row, CaptureEntitlement::mostRecent);
        }
        Set<UUID> planIds = new HashSet<>();
        latest.values().forEach(row -> planIds.add((UUID) row[3]));
        Map<UUID, Set<PlanServiceEnum>> servicesByPlan = new HashMap<>();
        if (!planIds.isEmpty()) {
            for (Object[] row : planRepository.findServicesOf(planIds)) {
                servicesByPlan.computeIfAbsent((UUID) row[0], key -> EnumSet.noneOf(PlanServiceEnum.class))
                        .add((PlanServiceEnum) row[1]);
            }
        }
        Map<UUID, Set<PlanServiceEnum>> result = new HashMap<>();
        latest.forEach((companyId, row) -> result.put(companyId,
                Collections.unmodifiableSet(servicesByPlan.getOrDefault((UUID) row[3], EnumSet.noneOf(PlanServiceEnum.class)))));
        return result;
    }

    /** start_date mais recente; empate (duas com a mesma data, só possível fora da tela) pelo id, para ser estável. */
    private static Object[] mostRecent(Object[] a, Object[] b) {
        int byStart = ((LocalDate) a[1]).compareTo((LocalDate) b[1]);
        if (byStart != 0) {
            return byStart > 0 ? a : b;
        }
        return a[2].toString().compareTo(b[2].toString()) >= 0 ? a : b;
    }

    public static boolean sieg(Set<PlanServiceEnum> services) {
        return PlanServiceEnum.importsFrom(services, PlanServiceEnum.SIEG_INTEGRATION);
    }

    public static boolean sefaz(Set<PlanServiceEnum> services) {
        return PlanServiceEnum.importsFrom(services, PlanServiceEnum.SEFAZ_INTEGRATION);
    }

    public static boolean adn(Set<PlanServiceEnum> services) {
        return PlanServiceEnum.importsFrom(services, PlanServiceEnum.NFSE_NATIONAL_INTEGRATION);
    }

    /** O canal SIEG de uma empresa ("Sincronizar agora", CP-RN-17 #4) - a mesma consulta do agendador. */
    @Transactional(readOnly = true)
    public boolean siegEntitled(UUID companyId) {
        LocalDate today = schedulerToday();
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        companyId, today, today)
                .map(subscription -> sieg(subscription.getPlan().getServices()))
                .orElse(false);
    }
}
