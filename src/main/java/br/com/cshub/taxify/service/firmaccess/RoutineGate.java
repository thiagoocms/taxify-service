package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.domain.company.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * F1-RN-10 numa rodada de agendador: cada escritório é consultado uma vez ({@link FirmAccessService#allowsAutomaticRoutines})
 * e as empresas dos bloqueados, suspensos, cancelados ou não verificados ficam de fora. Nada é zerado - o NSU, o
 * DtLastDH e as janelas de cada rotina continuam de onde pararam. {@link #logSkipped()} registra quantos ficaram de
 * fora, sem nome nem CNPJ.
 */
public class RoutineGate {

    private static final Logger log = LoggerFactory.getLogger(RoutineGate.class);

    private final String routine;
    private final Predicate<UUID> allows;
    private final Map<UUID, Boolean> answers = new HashMap<>();
    private final Set<UUID> skippedFirms = new HashSet<>();
    private int skippedCompanies;

    public RoutineGate(String routine, Predicate<UUID> allows) {
        this.routine = routine;
        this.allows = allows;
    }

    /** Um gate que deixa tudo passar - para testes e para quem não depende da situação do escritório. */
    public static RoutineGate allowAll(String routine) {
        return new RoutineGate(routine, firmId -> true);
    }

    public synchronized boolean allowsFirm(UUID firmId) {
        if (firmId == null) {
            return false;
        }
        boolean allowed = answers.computeIfAbsent(firmId, allows::test);
        if (!allowed) {
            skippedFirms.add(firmId);
        }
        return allowed;
    }

    /** Uma empresa sem escritório (não existe no banco: a coluna é NOT NULL) não tem situação a conferir e passa. */
    public synchronized boolean allows(Company company) {
        if (company == null) {
            return false;
        }
        boolean allowed = company.getAccountingFirm() == null || allowsFirm(company.getAccountingFirm().getId());
        if (!allowed) {
            skippedCompanies++;
        }
        return allowed;
    }

    public synchronized int skippedFirms() {
        return skippedFirms.size();
    }

    public synchronized int skippedCompanies() {
        return skippedCompanies;
    }

    public void logSkipped() {
        if (skippedFirms() > 0) {
            log.info("firm.access.routine.skipped routine={} firms={} companies={}", routine, skippedFirms(), skippedCompanies());
        }
    }
}
