package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.firmaccess.FirmNotification;
import br.com.cshub.taxify.enumerated.FirmNotificationType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmNotificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1-RN-16: CA1-22, CT1-37, CT1-38, CT1-39 (e-mail pela implementação falsa, nada sai de verdade). */
class TrialNoticeSchedulerTest {

    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final FirmNotificationRepository notifications = mock(FirmNotificationRepository.class);
    private final AccountingFirmRepository firms = mock(AccountingFirmRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FirmAccessService access = mock(FirmAccessService.class);
    private final MailSender mail = mock(MailSender.class);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 12, 6, 9, 0));
    private final Set<String> sent = new HashSet<>();
    private TrialNoticeScheduler scheduler;
    private FirmSubscription subscription;
    private AccountingFirm firm;

    @BeforeEach
    void setUp() {
        scheduler = new TrialNoticeScheduler(subscriptions, notifications, firms, users, access, mail, new SaasProperties(), clock);
        firm = new AccountingFirm(UUID.randomUUID());
        firm.setName("Escritório Fictício");
        subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firm.getId());
        subscription.setStatus(FirmSubscriptionStatus.TRIAL);
        subscription.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        User admin = new User(UUID.randomUUID());
        admin.setProfile(UserProfileEnum.ADMIN);
        admin.setEmail("dona@ficticio.test");
        User employee = new User(UUID.randomUUID());
        employee.setProfile(UserProfileEnum.EMPLOYEE);
        employee.setEmail("colaborador@ficticio.test");
        when(subscriptions.findByStatusInAndDeletedIsFalse(List.of(FirmSubscriptionStatus.TRIAL))).thenReturn(List.of(subscription));
        when(firms.findFirstByIdAndDeletedIsFalse(firm.getId())).thenReturn(Optional.of(firm));
        when(users.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())).thenReturn(List.of(admin, employee));
        lenient().when(access.evaluate(any(), any())).thenAnswer(invocation ->
                FirmAccessRules.evaluate(firm.getId(), invocation.getArgument(1), false, LocalDate.now(clock)));
        // the unique key (firm, type, trial_ends_on), in memory
        lenient().when(notifications.existsByAccountingFirmIdAndTypeAndReferenceDate(any(), any(), any()))
                .thenAnswer(invocation -> sent.contains(invocation.getArgument(1) + "@" + invocation.getArgument(2)));
        lenient().when(notifications.save(any(FirmNotification.class))).thenAnswer(invocation -> {
            FirmNotification notification = invocation.getArgument(0);
            sent.add(notification.getType() + "@" + notification.getReferenceDate());
            return notification;
        });
    }

    private void runOn(int day, int hour) {
        clock.set(LocalDateTime.of(2026, 12, day, hour, 0));
        scheduler.sendDue();
    }

    /** CA1-22 / CT1-37: 06, 08 e 09/12, duas vezes por dia → exatamente 1 D3, 1 D1 e 1 D0, só aos ADMIN. */
    @Test
    void oneNoticeOfEachKindEvenRunningTwiceADay() {
        for (int day : new int[]{6, 7, 8, 9}) {
            runOn(day, 9);
            runOn(day, 15);
        }

        ArgumentCaptor<MailMessage> messages = ArgumentCaptor.forClass(MailMessage.class);
        verify(mail, times(3)).send(messages.capture());
        assertThat(messages.getAllValues()).extracting(MailMessage::subject).containsExactly(
                "Seu teste do Taxify termina em 3 dias", "Seu teste do Taxify termina amanhã", "Seu teste do Taxify termina hoje");
        assertThat(messages.getAllValues()).allSatisfy(message -> {
            assertThat(message.to()).containsExactly("dona@ficticio.test");
            assertThat(message.body()).contains("Escritório Fictício").contains("09/12/2026");
        });
        assertThat(sent).containsExactlyInAnyOrder("TRIAL_D3@2026-12-09", "TRIAL_D1@2026-12-09", "TRIAL_D0@2026-12-09");
    }

    /** CT1-38: D3 enviado e o teste estendido até 20/12 → novos D3, D1 e D0 para 20/12. */
    @Test
    void anExtendedTrialGetsNewNotices() {
        runOn(6, 9);
        subscription.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        runOn(17, 9);
        runOn(19, 9);
        runOn(20, 9);

        assertThat(sent).containsExactlyInAnyOrder("TRIAL_D3@2026-12-09",
                "TRIAL_D3@2026-12-20", "TRIAL_D1@2026-12-20", "TRIAL_D0@2026-12-20");
    }

    /** CT1-39: agendador parado de 05 a 07/12 → o D3 não sai atrasado; o D1 sai em 08/12. */
    @Test
    void aMissedNoticeIsNotSentLate() {
        runOn(8, 9);

        assertThat(sent).containsExactly("TRIAL_D1@2026-12-09");
    }

    /** F1-RN-17: falha no envio não grava o aviso, e a rodada seguinte do mesmo dia tenta de novo. */
    @Test
    void aFailedSendIsRetriedOnTheNextRun() {
        doThrow(new MailSendException("SES fora")).doNothing().when(mail).send(any());

        runOn(6, 9);
        assertThat(sent).isEmpty();
        runOn(6, 15);
        assertThat(sent).containsExactly("TRIAL_D3@2026-12-09");
    }
}
