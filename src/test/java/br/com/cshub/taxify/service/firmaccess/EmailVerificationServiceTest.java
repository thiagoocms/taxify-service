package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.dto.signup.SignupVerifyResponseDTO;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.firmaccess.EmailVerification;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.EmailVerificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1-RN-03 / F1-RN-05: CA1-02, CA1-23, CT1-02, CT1-03 e CT1-04. Horários de Recife. */
class EmailVerificationServiceTest {

    private final EmailVerificationRepository verifications = mock(EmailVerificationRepository.class);
    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FirmEventService events = mock(FirmEventService.class);
    private final MailSender mail = mock(MailSender.class);
    private final MessageService messages = mock(MessageService.class);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 26, 10, 0));
    private final SaasProperties properties = new SaasProperties();
    private final List<EmailVerification> saved = new ArrayList<>();

    private EmailVerificationService service;
    private User user;
    private FirmSubscription subscription;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(verifications, subscriptions, users, events, mail, properties, messages, clock,
                mock(PlatformTransactionManager.class));
        lenient().when(messages.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        firm.setName("Escritório Fictício");
        user = new User(UUID.randomUUID());
        user.setAccountingFirm(firm);
        user.setLogin("dono.ficticio");
        user.setEmail("dono@ficticio.test");
        subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firm.getId());
        subscription.setStatus(FirmSubscriptionStatus.PENDING_VERIFICATION);
        lenient().when(users.findById(user.getId())).thenReturn(Optional.of(user));
        lenient().when(users.findByLoginAndDeletedIsFalse("dono.ficticio")).thenReturn(user);
        lenient().when(subscriptions.findByAccountingFirmIdAndDeletedIsFalse(firm.getId())).thenReturn(Optional.of(subscription));
        lenient().when(subscriptions.findForUpdate(firm.getId())).thenReturn(Optional.of(subscription));
        lenient().when(verifications.save(any(EmailVerification.class))).thenAnswer(invocation -> {
            EmailVerification verification = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(verification, "id", UUID.randomUUID());
            saved.add(verification);
            return verification;
        });
        // UPDATE ... SET used_at = ? WHERE id = ? AND used_at IS NULL
        lenient().when(verifications.markUsed(any(), any())).thenAnswer(invocation -> {
            for (EmailVerification verification : saved) {
                if (verification.getId().equals(invocation.getArgument(0)) && verification.getUsedAt() == null) {
                    verification.setUsedAt(invocation.getArgument(1));
                    return 1;
                }
            }
            return 0;
        });
        lenient().when(verifications.findByTokenHash(anyString())).thenAnswer(invocation -> saved.stream()
                .filter(v -> v.getTokenHash().equals(invocation.getArgument(0))).findFirst());
    }

    /** O token em claro nunca é gravado: só o SHA-256. Vale 24 h a partir da emissão. */
    @Test
    void onlyTheHashIsStoredAndTheLinkLasts24Hours() {
        String token = service.issue(user.getId(), false);

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getTokenHash()).isNotEqualTo(token).hasSize(64);
        assertThat(saved.getFirst().getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
    }

    /** CA1-02 / CT1-02: aberto em 26/11 → TRIAL até 09/12, eventos EMAIL_VERIFIED e TRIAL_STARTED; de novo → 400. */
    @Test
    void verifyingStartsTheTrialAndTheLinkWorksOnce() {
        String token = service.issue(user.getId(), false);

        SignupVerifyResponseDTO response = service.verify(token);

        assertThat(response.status()).isEqualTo("TRIAL");
        assertThat(response.trialEndsOn()).isEqualTo(LocalDate.of(2026, 12, 9));
        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        verify(events).record(eq(subscription.getAccountingFirmId()), eq(FirmEventType.EMAIL_VERIFIED), any(), any(), any(), any());
        verify(events).record(eq(subscription.getAccountingFirmId()), eq(FirmEventType.TRIAL_STARTED), any(), any(), any(), any());

        assertThatThrownBy(() -> service.verify(token)).isInstanceOfSatisfying(BadRequestException.class,
                e -> assertThat(e.getCode()).isEqualTo("error.signup.token.invalid"));
    }

    /** CA1-23 / CT1-03: emitido 26/11 10:00 → vale em 27/11 09:59; um emitido no mesmo horário, aberto às 10:01, não. */
    @Test
    void theLinkExpires24HoursAfterIssue() {
        String a = service.issue(user.getId(), false);
        clock.set(LocalDateTime.of(2026, 11, 27, 9, 59));
        assertThat(service.verify(a).status()).isEqualTo("TRIAL");

        subscription.setStatus(FirmSubscriptionStatus.PENDING_VERIFICATION);
        clock.set(LocalDateTime.of(2026, 11, 26, 10, 0));
        String b = service.issue(user.getId(), false);
        clock.set(LocalDateTime.of(2026, 11, 27, 10, 1));
        assertThatThrownBy(() -> service.verify(b)).isInstanceOf(BadRequestException.class);
        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.PENDING_VERIFICATION);
    }

    /** Review suggestion: two clicks at the same time - only the one that marks the token starts the trial. */
    @Test
    void aTokenMarkedByAnotherRequestIsInvalid() {
        String token = service.issue(user.getId(), false);
        when(verifications.markUsed(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(BadRequestException.class);
        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.PENDING_VERIFICATION);
    }

    /** Review item 2: the verification reads the subscription with the lock the purge also takes. */
    @Test
    void theVerificationLocksTheSubscription() {
        service.verify(service.issue(user.getId(), false));
        // retention: the resend IPs (daily cap) are erased once the e-mail is verified
        verify(verifications).clearRequestedIp(user.getId());
        verify(subscriptions).findForUpdate(subscription.getAccountingFirmId());
        verify(subscriptions, never()).findByAccountingFirmIdAndDeletedIsFalse(any());
    }

    @Test
    void anUnknownTokenIsInvalid() {
        assertThatThrownBy(() -> service.verify("desconhecido")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.verify(null)).isInstanceOf(BadRequestException.class);
    }

    /** CT1-04: login inexistente não recebe nada; o existente recebe link novo e o anterior é invalidado. */
    @Test
    void resendOnlyMailsAnExistingPendingLogin() {
        service.resend("nao.existe");
        verify(mail, never()).send(any());

        clock.set(LocalDateTime.of(2026, 11, 26, 11, 0));
        service.resend("dono.ficticio");

        verify(verifications).invalidateOpen(eq(user.getId()), any());
        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().to()).containsExactly("dono@ficticio.test");
        assertThat(message.getValue().body()).contains("/auth/verificar?token=");
        assertThat(saved.getLast().isResend()).isTrue();
        assertThat(saved.getLast().getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 11, 0));
    }

    /** CT1-04 / CA1-23: com 3 reenvios nos últimos 60 minutos, o 4º não manda nada (e quem chamou recebe 202). */
    @Test
    void resendIsLimitedPerHour() {
        when(verifications.countByUserIdAndResendIsTrueAndCreatedDateAfter(eq(user.getId()), any())).thenReturn(3L);

        service.resend("dono.ficticio");

        verify(mail, never()).send(any());
        verify(verifications, never()).invalidateOpen(any(), any());
    }

    /** A janela é de 60 minutos contados para trás: às 12:01, o reenvio das 11:00 já saiu dela. */
    @Test
    void theResendWindowIsTheLast60Minutes() {
        clock.set(LocalDateTime.of(2026, 11, 26, 12, 1));
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        when(verifications.countByUserIdAndResendIsTrueAndCreatedDateAfter(eq(user.getId()), since.capture())).thenReturn(2L);

        service.resend("dono.ficticio");

        assertThat(since.getValue()).isEqualTo(LocalDateTime.of(2026, 11, 26, 11, 1));
        verify(mail, times(1)).send(any());
    }

    /** Teto diário por login (padrão 5 no dia de Recife): o 6º reenvio do dia não sai, conta 00:00 a 00:00 de Recife. */
    @Test
    void theDailyCapPerLogin() {
        clock.set(LocalDateTime.of(2026, 11, 26, 23, 30));
        when(verifications.countByUserIdAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(user.getId(),
                LocalDateTime.of(2026, 11, 26, 0, 0), LocalDateTime.of(2026, 11, 27, 0, 0))).thenReturn(5L);

        service.resend("dono.ficticio", "198.51.100.7");
        verify(mail, never()).send(any());

        // the next civil day of Recife starts again
        clock.set(LocalDateTime.of(2026, 11, 27, 0, 5));
        service.resend("dono.ficticio", "198.51.100.7");
        verify(mail, times(1)).send(any());
        assertThat(saved.getLast().getRequestedIp()).isEqualTo("198.51.100.7");
    }

    /** Teto diário por IP (padrão 10): acima dele nada é enviado, para qualquer login. */
    @Test
    void theDailyCapPerIp() {
        when(verifications.countByRequestedIpAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(eq("198.51.100.7"),
                eq(LocalDateTime.of(2026, 11, 26, 0, 0)), eq(LocalDateTime.of(2026, 11, 27, 0, 0)))).thenReturn(10L);

        service.resend("dono.ficticio", "198.51.100.7");
        verify(mail, never()).send(any());
        verify(verifications, never()).invalidateOpen(any(), any());

        service.resend("dono.ficticio", "203.0.113.9");
        verify(mail, times(1)).send(any());
    }

    /** O teto diário loga signup.resend.limited, como o limite por hora (F1-RN-03, CT1-43) - sem login, e-mail ou IP. */
    @Test
    void theCapLogsNothingPersonal() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EmailVerificationService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(verifications.countByUserIdAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(any(), any(), any()))
                    .thenReturn(5L);
            service.resend("dono.ficticio", "198.51.100.7");
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).containsExactly("signup.resend.limited");
    }

    /** Um escritório já verificado não recebe reenvio. */
    @Test
    void aVerifiedFirmGetsNoResend() {
        subscription.setStatus(FirmSubscriptionStatus.TRIAL);
        service.resend("dono.ficticio");
        verify(mail, never()).send(any());
    }
}
