package br.com.cshub.taxify.security;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.exception.FirmAccessBlockedException;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F1-RN-08 / F1-RN-09: CA1-09, CT1-16, CT1-17 e CT1-18 no filtro de somente leitura. */
class FirmAccessInterceptorTest {

    private final FirmAccessService accessService = mock(FirmAccessService.class);
    private final MessageService messages = mock(MessageService.class);
    private final FirmAccessInterceptor interceptor = new FirmAccessInterceptor(accessService, messages);

    static class Controller {
        @GetMapping
        public void list() {
        }

        @ReadOnlyOperation
        @PostMapping("/buscar")
        public void search() {
        }

        @PostMapping
        public void create() {
        }
    }

    @AllowedWhenBlocked
    static class AlwaysAllowed {
        @PostMapping
        public void action() {
        }
    }

    @BeforeEach
    void setUp() {
        when(messages.getMessage(anyString())).thenReturn("somente leitura");
    }

    private void firmIs(FirmSubscriptionStatus status, BlockedReason reason, boolean readOnly) {
        when(accessService.currentAccess()).thenReturn(Optional.of(new FirmAccess(UUID.randomUUID(), status, false, readOnly,
                reason, readOnly ? LocalDate.of(2026, 12, 10) : null, null, null, null, null, false)));
    }

    private boolean call(String httpMethod, Object controller, String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/x");
        return interceptor.preHandle(request, new MockHttpServletResponse(),
                new HandlerMethod(controller, controller.getClass().getMethod(method)));
    }

    /** CA1-09 / CT1-16 e CT1-17: bloqueado lê (GET e POST anotado) mas não escreve (403 com status, motivo e data). */
    @Test
    void aBlockedFirmReadsButDoesNotWrite() throws Exception {
        firmIs(FirmSubscriptionStatus.BLOCKED, BlockedReason.TRIAL_EXPIRED, true);

        assertThat(call("GET", new Controller(), "list")).isTrue();
        assertThat(call("POST", new Controller(), "search")).isTrue();
        assertThatThrownBy(() -> call("POST", new Controller(), "create"))
                .isInstanceOfSatisfying(FirmAccessBlockedException.class, e -> {
                    assertThat(e.getAccess().status()).isEqualTo(FirmSubscriptionStatus.BLOCKED);
                    assertThat(e.getAccess().reason()).isEqualTo(BlockedReason.TRIAL_EXPIRED);
                    assertThat(e.getAccess().blockedSince()).isEqualTo(LocalDate.of(2026, 12, 10));
                });
        assertThatThrownBy(() -> call("DELETE", new Controller(), "create")).isInstanceOf(FirmAccessBlockedException.class);
        assertThatThrownBy(() -> call("PUT", new Controller(), "create")).isInstanceOf(FirmAccessBlockedException.class);
    }

    /** CT1-18: suspensão manual tem o mesmo efeito. */
    @Test
    void suspendedIsTheSame() throws Exception {
        firmIs(FirmSubscriptionStatus.SUSPENDED, BlockedReason.MANUAL, true);
        assertThat(call("POST", new Controller(), "search")).isTrue();
        assertThatThrownBy(() -> call("POST", new Controller(), "create")).isInstanceOf(FirmAccessBlockedException.class);
    }

    /** F1-RN-09: as rotas sempre liberadas passam mesmo bloqueado. */
    @Test
    void alwaysAllowedRoutesPass() throws Exception {
        firmIs(FirmSubscriptionStatus.BLOCKED, BlockedReason.TRIAL_EXPIRED, true);
        assertThat(call("POST", new AlwaysAllowed(), "action")).isTrue();
    }

    @Test
    void anActiveFirmOrNoUserWrites() throws Exception {
        firmIs(FirmSubscriptionStatus.TRIAL, null, false);
        assertThat(call("POST", new Controller(), "create")).isTrue();
        when(accessService.currentAccess()).thenReturn(Optional.empty());
        assertThat(call("POST", new Controller(), "create")).isTrue();
    }
}
