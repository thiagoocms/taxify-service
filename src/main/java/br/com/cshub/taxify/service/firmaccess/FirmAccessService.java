package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A situação de acesso do escritório (F1-RN-07), o ponto único que o filtro de somente leitura (F1-RN-08), o limite
 * do teste (F1-RN-10a) e os agendadores (F1-RN-10) consultam. Calculada a cada requisição, a partir da
 * {@code firm_subscription} e do dia de Recife - o token de 2 h não guarda a situação. Na requisição HTTP fica em cache
 * só durante a própria requisição.
 */
@Service
public class FirmAccessService {

    private static final Logger log = LoggerFactory.getLogger(FirmAccessService.class);
    private static final String REQUEST_KEY = FirmAccessService.class.getName() + ".access";

    private final FirmSubscriptionRepository repository;
    private final PlatformAdminService platformAdminService;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    public FirmAccessService(FirmSubscriptionRepository repository, PlatformAdminService platformAdminService,
                             CurrentUserService currentUserService, Clock clock) {
        this.repository = repository;
        this.platformAdminService = platformAdminService;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /** Hoje em America/Recife. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @Transactional(readOnly = true)
    public FirmAccess accessOf(UUID firmId) {
        FirmSubscription subscription = repository.findByAccountingFirmIdAndDeletedIsFalse(firmId).orElse(null);
        return evaluate(firmId, subscription);
    }

    /** Várias de uma vez (o console e os agendadores): uma consulta só. */
    @Transactional(readOnly = true)
    public Map<UUID, FirmAccess> accessOf(Collection<UUID> firmIds) {
        Map<UUID, FirmSubscription> byFirm = firmIds.isEmpty() ? Map.of()
                : repository.findByAccountingFirmIdInAndDeletedIsFalse(firmIds).stream()
                .collect(Collectors.toMap(FirmSubscription::getAccountingFirmId, Function.identity(), (a, b) -> a));
        Map<UUID, FirmAccess> result = new HashMap<>();
        for (UUID firmId : firmIds) {
            result.put(firmId, evaluate(firmId, byFirm.get(firmId)));
        }
        return result;
    }

    /** O escritório da CS Hub ({@code PLATFORM_FIRM_ID}, F1-RN-12). */
    public boolean isExempt(UUID firmId) {
        return platformAdminService.isPlatformFirm(firmId);
    }

    public FirmAccess evaluate(UUID firmId, FirmSubscription subscription) {
        boolean exempt = platformAdminService.isPlatformFirm(firmId);
        FirmAccess access = FirmAccessRules.evaluate(firmId, subscription, exempt, today());
        if (access.missingSubscription() && !exempt) {
            log.warn("firm.access.missing firm={} - no firm_subscription, treated as SUSPENDED", firmId);
        }
        return access;
    }

    /** A situação do escritório do usuário logado; vazia sem usuário (anônimo, agendador). */
    public Optional<FirmAccess> currentAccess() {
        Optional<User> user = currentUserService.getCurrentUser();
        if (user.isEmpty() || user.get().getAccountingFirm() == null) {
            return Optional.empty();
        }
        UUID firmId = user.get().getAccountingFirm().getId();
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST) instanceof FirmAccess cached
                && cached.firmId().equals(firmId)) {
            return Optional.of(cached);
        }
        FirmAccess access = accessOf(firmId);
        if (request != null) {
            request.setAttribute(REQUEST_KEY, access, RequestAttributes.SCOPE_REQUEST);
        }
        return Optional.of(access);
    }

    /**
     * F1-RN-10: as capturas e as rotinas automáticas que gravam dados só rodam para TRIAL, ACTIVE (e PAST_DUE, fase 2)
     * e para o escritório isento. Para um lote, use {@link #routineGate(String)}, que consulta cada escritório uma vez.
     */
    public boolean allowsAutomaticRoutines(UUID firmId) {
        return firmId != null && accessOf(firmId).allowsAutomaticRoutines();
    }

    /** Um portão por rodada de agendador: lembra a resposta de cada escritório e conta os pulados para o log. */
    public RoutineGate routineGate(String routine) {
        return new RoutineGate(routine, this::allowsAutomaticRoutines);
    }

    /** Força a nova leitura na mesma requisição (depois de uma mudança feita nela). */
    public void forgetCurrent() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.removeAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
