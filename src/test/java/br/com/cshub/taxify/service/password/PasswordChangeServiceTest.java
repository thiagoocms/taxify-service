package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.BusinessException;
import br.com.cshub.taxify.exception.TooManyRequestsException;
import br.com.cshub.taxify.exception.UnauthorizedException;
import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.TokenService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.validation.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SN-RN-09: CT-SN-21, CT-SN-22, CA-SN-12, CA-SN-13. Horários de Recife, dados fictícios. */
class PasswordChangeServiceTest {

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final MessageService messages = mock(MessageService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordProperties properties = new PasswordProperties();
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 27, 10, 0));
    private PasswordChangeService service;
    private User ana;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(currentUser, users, tokens, new PasswordPolicy(properties, encoder, messages), encoder,
                tokenService, properties, messages, events, clock, mock(PlatformTransactionManager.class),
                mock(jakarta.persistence.EntityManager.class));
        lenient().when(messages.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messages.getMessage(anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(0));
        ana = new User(UUID.randomUUID());
        ana.setLogin("ana.fiscal");
        ana.setProfile(UserProfileEnum.EMPLOYEE);
        ana.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        ana.setPassword(encoder.encode("SenhaAtual2026"));
        ana.setSessionVersion(1);
        lenient().when(currentUser.getCurrentUser()).thenReturn(Optional.of(ana));
        lenient().when(users.findForUpdate(ana.getId())).thenReturn(Optional.of(ana));
        lenient().when(tokenService.generateToken(any(Auth.class)))
                .thenAnswer(invocation -> "jwt-sv-" + invocation.<Auth>getArgument(0).getSessionVersion());
    }

    private String codeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.getCode();
        }
        return null;
    }

    private void at(int hour, int minute) {
        clock.set(LocalDateTime.of(2026, 11, 27, hour, minute));
    }

    /** CT-SN-21 / CA-SN-12: 200 with a new token carrying the new version; the other sessions fall. */
    @Test
    void theRightCurrentPasswordChangesItAndReturnsATokenWithTheNewVersion() {
        var response = service.changeOwn("SenhaAtual2026", "NovaSenha#2026");

        assertThat(response.token()).isEqualTo("jwt-sv-2");
        assertThat(ana.getSessionVersion()).isEqualTo(2);
        assertThat(encoder.matches("NovaSenha#2026", ana.getPassword())).isTrue();
        assertThat(ana.getPasswordChangedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
        verify(tokens).invalidateOpen(ana.getId(), LocalDateTime.of(2026, 11, 27, 10, 0));
        ArgumentCaptor<PasswordChangedEvent> event = ArgumentCaptor.forClass(PasswordChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().channel()).isEqualTo(PasswordChangeChannel.SELF);
    }

    /** CT-SN-22 / CA-SN-13: 5 errors 10:00-10:05 = 400 x5; 10:06 with the right one = 429; 11:01 = 200. */
    @Test
    void afterFiveErrorsInAnHourEvenTheRightPasswordGets429UntilTheWindowEnds() {
        for (int minute = 0; minute <= 4; minute++) {
            at(10, minute == 4 ? 5 : minute);
            assertThat(codeOf(() -> service.changeOwn("errada", "NovaSenha#2026"))).isEqualTo("error.user.password.current.invalid");
        }
        assertThat(ana.getPasswordFailCount()).isEqualTo(5);
        assertThat(ana.getPasswordFailWindowStart()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));

        at(10, 6);
        assertThatThrownBy(() -> service.changeOwn("SenhaAtual2026", "NovaSenha#2026"))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.password.change.rate.limited"));
        assertThat(ana.getSessionVersion()).isEqualTo(1);

        at(11, 1);
        assertThat(service.changeOwn("SenhaAtual2026", "NovaSenha#2026").token()).isEqualTo("jwt-sv-2");
        assertThat(ana.getPasswordFailCount()).isZero();
        assertThat(ana.getPasswordFailWindowStart()).isNull();
    }

    /** The window counts from the first error: 4 errors, an hour later the counter starts over. */
    @Test
    void theCounterStartsOverWhenTheWindowEnds() {
        for (int i = 0; i < 4; i++) {
            codeOf(() -> service.changeOwn("errada", "x"));
        }
        at(11, 0);
        assertThat(codeOf(() -> service.changeOwn("errada", "x"))).isEqualTo("error.user.password.current.invalid");
        assertThat(ana.getPasswordFailCount()).isEqualTo(1);
        assertThat(ana.getPasswordFailWindowStart()).isEqualTo(LocalDateTime.of(2026, 11, 27, 11, 0));
    }

    /** The rule of the new password (400 with its code) does not count as an error of the current one. */
    @Test
    void aRefusedNewPasswordDoesNotCountAsAFailure() {
        assertThat(codeOf(() -> service.changeOwn("SenhaAtual2026", "curta"))).isEqualTo("error.user.password.too.short");
        assertThat(codeOf(() -> service.changeOwn("SenhaAtual2026", "SenhaAtual2026"))).isEqualTo("error.user.password.same");
        assertThat(ana.getPasswordFailCount()).isZero();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void anEmptyCurrentPasswordIsAWrongOne() {
        assertThatThrownBy(() -> service.changeOwn(null, "NovaSenha#2026")).isInstanceOf(BadRequestException.class);
        assertThat(ana.getPasswordFailCount()).isEqualTo(1);
    }

    @Test
    void withoutAUserItIs401() {
        when(currentUser.getCurrentUser()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeOwn("a", "b")).isInstanceOf(UnauthorizedException.class);
    }
}
