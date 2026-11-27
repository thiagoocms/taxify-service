package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** FirmAccessService falso para os testes dos agendadores. */
public final class FirmAccessTestSupport {

    private FirmAccessTestSupport() {
    }

    /** Todos os escritórios liberados (TRIAL/ACTIVE). */
    public static FirmAccessService allowAll() {
        return blocking(Set.of());
    }

    /** Os escritórios de {@code blocked} em somente leitura; os demais liberados. */
    public static FirmAccessService blocking(Set<UUID> blocked) {
        FirmAccessService service = mock(FirmAccessService.class);
        lenient().when(service.routineGate(anyString()))
                .thenAnswer(invocation -> new RoutineGate(invocation.getArgument(0), firmId -> !blocked.contains(firmId)));
        lenient().when(service.allowsAutomaticRoutines(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0) != null && !blocked.contains(invocation.<UUID>getArgument(0)));
        return service;
    }

    /**
     * O FirmAccessService de verdade (as regras da F1-RN-07 e o relógio de Recife) sobre assinaturas em memória: o teste
     * muda a assinatura (ou o relógio) e a próxima rodada do agendador enxerga. O escritório sem assinatura listada é
     * tratado como SUSPENDED, igual ao real.
     */
    public static FirmAccessService real(Clock clock, FirmSubscription... subscriptions) {
        FirmSubscriptionRepository repository = mock(FirmSubscriptionRepository.class);
        for (FirmSubscription subscription : subscriptions) {
            lenient().when(repository.findByAccountingFirmIdAndDeletedIsFalse(subscription.getAccountingFirmId()))
                    .thenAnswer(invocation -> Optional.of(subscription));
        }
        PlatformAdminService platform = mock(PlatformAdminService.class);
        return new FirmAccessService(repository, platform, mock(CurrentUserService.class), clock);
    }
}
