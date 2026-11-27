package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.pendency.PendencyAlertDigest;
import br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.pendency.alert.PendencyAlertService.Recipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AV-RN-03, AV-RN-05, AV-RN-07, AV-RN-09, AV-RN-10: CA-AV-02, CA-AV-07, CA-AV-09, CA-AV-10, CA-AV-11, CA-AV-13,
 * CT-AV-03, CT-AV-04, CT-AV-12, CT-AV-17, CT-AV-19, CT-AV-20, CT-AV-21. O serviço é um falso em memória que guarda os
 * resumos por (usuário, dia) - a chave única do banco.
 */
class PendencyAlertSchedulerTest {

    private static final UUID FIRM_A = UUID.randomUUID();
    private static final UUID FIRM_B = UUID.randomUUID();
    private static final LocalDateTime TUESDAY = LocalDateTime.of(2026, 12, 1, 7, 50);

    private final PendencyAlertService service = mock(PendencyAlertService.class);
    private final AccountingFirmRepository firms = mock(AccountingFirmRepository.class);
    private final MailSender mail = mock(MailSender.class);
    private final PendencyAlertProperties properties = new PendencyAlertProperties();
    private final SaasProperties saas = new SaasProperties();
    private final MutableClock clock = new MutableClock(TUESDAY);
    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();
    /** (user, day) -> digest: the unique key. */
    private final Map<String, PendencyAlertDigest> digests = new ConcurrentHashMap<>();
    private final Map<UUID, List<PendencyAlertCandidate>> pending = new HashMap<>();
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
    private final List<Recipient> recipients = new ArrayList<>();

    private PendencyAlertScheduler scheduler(FirmAccessService access) {
        return new PendencyAlertScheduler(service, firms, access, mail, properties, saas, clock);
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(firms.findIdsInUse()).thenReturn(List.of(FIRM_A, FIRM_B));
        lenient().when(mail.accepts(any())).thenReturn(true);
        lenient().doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(mail).send(any());
        lenient().when(service.recipients(anyCollection())).thenAnswer(invocation -> {
            Collection<UUID> allowed = invocation.getArgument(0);
            return recipients.stream().filter(r -> allowed.contains(r.firmId())).toList();
        });
        lenient().when(service.newPendencies(any())).thenAnswer(invocation -> pending.getOrDefault(
                invocation.<Recipient>getArgument(0).userId(), List.of()).stream()
                .filter(item -> !notified.contains(item.pendencyId())).toList());
        lenient().when(service.openHigh(any())).thenReturn(0L);
        lenient().when(service.withDigestOn(any(), anyCollection())).thenAnswer(invocation -> {
            LocalDate day = invocation.getArgument(0);
            Set<UUID> result = new HashSet<>();
            digests.values().stream().filter(d -> d.getReferenceDate().equals(day)).forEach(d -> result.add(d.getUserId()));
            return result;
        });
        lenient().when(service.lastSentAt(anyCollection())).thenAnswer(invocation -> {
            Map<UUID, LocalDateTime> result = new HashMap<>();
            digests.values().stream().filter(d -> d.getStatus() == PendencyAlertDigestStatus.SENT)
                    .forEach(d -> result.merge(d.getUserId(), d.getSentAt(), (a, b) -> a.isAfter(b) ? a : b));
            return result;
        });
        lenient().when(service.attemptsOn(any())).thenAnswer(invocation -> digests.values().stream()
                .filter(d -> d.getReferenceDate().equals(invocation.getArgument(0)) && PendencyAlertService.ATTEMPTS.contains(d.getStatus()))
                .count());
        // the real one counts and inserts under a MySQL named lock - here a monitor plays that part
        lenient().when(service.reserve(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            Recipient recipient = invocation.getArgument(0);
            LocalDate day = invocation.getArgument(1);
            List<PendencyAlertCandidate> items = invocation.getArgument(2);
            int max = invocation.getArgument(3);
            synchronized (digests) {
            long attempts = digests.values().stream().filter(d -> d.getReferenceDate().equals(day)
                    && PendencyAlertService.ATTEMPTS.contains(d.getStatus())).count();
            PendencyAlertDigest digest = new PendencyAlertDigest(recipient.firmId(), recipient.userId(), day,
                    attempts < max ? PendencyAlertDigestStatus.SENDING : PendencyAlertDigestStatus.CAPPED, 1, items.size());
            ReflectionTestUtils.setField(digest, "id", UUID.randomUUID());
            return digests.putIfAbsent(recipient.userId() + "|" + day, digest) == null ? digest : null;
            }
        });
        lenient().doAnswer(invocation -> {
            PendencyAlertDigest digest = invocation.getArgument(0);
            digest.setStatus(PendencyAlertDigestStatus.SENT);
            digest.setSentAt(invocation.getArgument(2));
            invocation.<List<PendencyAlertCandidate>>getArgument(1).forEach(item -> notified.add(item.pendencyId()));
            return null;
        }).when(service).markSent(any(), any(), any());
        lenient().doAnswer(invocation -> {
            invocation.<PendencyAlertDigest>getArgument(0).setStatus(PendencyAlertDigestStatus.FAILED);
            return null;
        }).when(service).markFailed(any());
        lenient().when(service.cleanup(any(), any())).thenReturn(new int[]{0, 0});
    }

    private Recipient recipient(String name, UUID firm, int newPendencies) {
        Recipient recipient = new Recipient(UUID.randomUUID(), firm, "Escritório Exemplo", name,
                name.toLowerCase().replace(' ', '.') + "@exemplo.test", List.of(UUID.randomUUID()));
        recipients.add(recipient);
        List<PendencyAlertCandidate> items = new ArrayList<>();
        for (int i = 0; i < newPendencies; i++) {
            items.add(new PendencyAlertCandidate(UUID.randomUUID(), recipient.companyIds().getFirst(), "Têxtil Exemplo Ltda",
                    PendencyType.CERTIFICATE_EXPIRED, TUESDAY.minusHours(20)));
        }
        pending.put(recipient.userId(), items);
        return recipient;
    }

    private long statusCount(PendencyAlertDigestStatus status) {
        return digests.values().stream().filter(d -> d.getStatus() == status).count();
    }

    /** CT-AV-03 / CA-AV-02 / CT-AV-04: one e-mail per user per day; nothing new - no e-mail and no row. */
    @Test
    void oneDigestPerUserPerDayAndNothingWhenThereIsNothingNew() {
        recipient("Carla Admin", FIRM_A, 14);
        recipient("Ana Fiscal", FIRM_A, 13);
        recipient("Sem Novidade", FIRM_A, 0);
        PendencyAlertScheduler scheduler = scheduler(FirmAccessTestSupport.allowAll());

        PendencyAlertScheduler.RunResult first = scheduler.sendDue();
        clock.set(TUESDAY.plusHours(1));
        PendencyAlertScheduler.RunResult second = scheduler.sendDue();

        assertThat(first.sent()).isEqualTo(2);
        assertThat(second.sent()).isZero();
        assertThat(sent).hasSize(2);
        assertThat(digests).hasSize(2);
        assertThat(sent).allSatisfy(message -> assertThat(message.to()).hasSize(1));

        // next day, nothing new: nothing at all
        clock.set(TUESDAY.plusDays(1));
        assertThat(scheduler.sendDue().sent()).isZero();
        assertThat(digests).hasSize(2);
    }

    /** CA-AV-07 / CT-AV-12: a blocked firm gets nothing; after reactivation the open ones go. */
    @Test
    void aBlockedFirmGetsNothingUntilItIsReactivated() {
        recipient("Ana Fiscal", FIRM_A, 2);
        recipient("Bruno Outro", FIRM_B, 3);

        scheduler(FirmAccessTestSupport.blocking(Set.of(FIRM_B))).sendDue();
        assertThat(sent).hasSize(1);

        clock.set(TUESDAY.plusDays(1));
        scheduler(FirmAccessTestSupport.allowAll()).sendDue();
        assertThat(sent).hasSize(2);
        assertThat(sent.getLast().subject()).contains("3 pendência(s)");
    }

    /** CA-AV-09 / CT-AV-17: cap 2, 3 eligible - 2 SENT, 1 CAPPED; the next day the CAPPED one goes first. */
    @Test
    void theGlobalCapLeavesTheRestCappedAndTheyGoFirstNextDay() {
        properties.setMaxPerDay(2);
        Recipient a = recipient("Usuario A", FIRM_A, 1);
        Recipient b = recipient("Usuario B", FIRM_A, 1);
        Recipient c = recipient("Usuario C", FIRM_A, 1);
        PendencyAlertScheduler scheduler = scheduler(FirmAccessTestSupport.allowAll());

        PendencyAlertScheduler.RunResult result = scheduler.sendDue();
        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.capped()).isEqualTo(1);
        assertThat(statusCount(PendencyAlertDigestStatus.SENT)).isEqualTo(2);
        assertThat(statusCount(PendencyAlertDigestStatus.CAPPED)).isEqualTo(1);
        UUID cappedUser = digests.values().stream().filter(d -> d.getStatus() == PendencyAlertDigestStatus.CAPPED)
                .findFirst().orElseThrow().getUserId();
        assertThat(notified).hasSize(2);

        // the next day: one more pendency for the two that got it - the capped one (never received) goes first
        for (Recipient recipient : List.of(a, b, c)) {
            if (!recipient.userId().equals(cappedUser)) {
                pending.get(recipient.userId()).add(new PendencyAlertCandidate(UUID.randomUUID(),
                        recipient.companyIds().getFirst(), "Têxtil Exemplo Ltda", PendencyType.SEFAZ_CAPTURE_ERROR, TUESDAY));
            }
        }
        sent.clear();
        clock.set(TUESDAY.plusDays(1));
        scheduler.sendDue();
        Recipient capped = List.of(a, b, c).stream().filter(r -> r.userId().equals(cappedUser)).findFirst().orElseThrow();
        assertThat(sent.getFirst().to()).containsExactly(capped.email());
        assertThat(sent).hasSize(2);
    }

    /** CA-AV-10 / CT-AV-19: SES fails for Ana - FAILED, nothing notified; Carla gets hers; the next day Ana gets them. */
    @Test
    void aFailureIsIsolatedAndNothingIsMarked() {
        Recipient ana = recipient("Ana Fiscal", FIRM_A, 13);
        recipient("Carla Admin", FIRM_A, 14);
        doAnswer(invocation -> {
            MailMessage message = invocation.getArgument(0);
            if (message.to().contains(ana.email())) {
                throw new MailSendException("Falha no envio pelo SES (error=Throttling)");
            }
            sent.add(message);
            return null;
        }).when(mail).send(any());
        PendencyAlertScheduler scheduler = scheduler(FirmAccessTestSupport.allowAll());

        PendencyAlertScheduler.RunResult result = scheduler.sendDue();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        assertThat(statusCount(PendencyAlertDigestStatus.FAILED)).isEqualTo(1);
        assertThat(notified).hasSize(14);

        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(mail).send(any());
        clock.set(TUESDAY.plusDays(1));
        scheduler.sendDue();
        assertThat(sent.getLast().to()).containsExactly(ana.email());
        assertThat(sent.getLast().subject()).contains("13 pendência(s)");
    }

    /** CT-AV-20: a SENDING left from yesterday is turned into FAILED before the run. */
    @Test
    void staleSendingIsFailedFirst() {
        scheduler(FirmAccessTestSupport.allowAll()).sendDue();
        verify(service).failStaleSending(LocalDate.of(2026, 12, 1));
    }

    /** CA-AV-11 / CT-AV-21: two runs at the same time (two instances) - one e-mail per user. */
    @Test
    void twoRunsAtTheSameTimeSendOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            recipient("Usuario " + i, FIRM_A, 2);
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            PendencyAlertScheduler instance = scheduler(FirmAccessTestSupport.allowAll());
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    instance.sendDue();
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        assertThat(errors).isEmpty();
        assertThat(sent).hasSize(5);
        assertThat(sent.stream().map(m -> m.to().getFirst()).distinct()).hasSize(5);
    }

    /** CA-AV-13: switched off - the tick does nothing; with an allowlist only the accepted addresses get a digest. */
    @Test
    void switchedOffOrOutsideTheAllowlist() {
        Recipient inside = recipient("Carla Admin", FIRM_A, 1);
        recipient("Ana Fiscal", FIRM_A, 1);
        properties.setEnabled(false);
        PendencyAlertScheduler scheduler = scheduler(FirmAccessTestSupport.allowAll());
        scheduler.tick();
        verify(service, never()).recipients(anyCollection());
        assertThat(digests).isEmpty();

        properties.setEnabled(true);
        when(mail.accepts(any())).thenAnswer(invocation -> inside.email().equals(invocation.getArgument(0)));
        scheduler.tick();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().to()).containsExactly(inside.email());
        // the one outside the list was not reserved nor marked: nothing lost for when the list goes away
        assertThat(digests).hasSize(1);
    }

    /** A failure while building one user's digest does not stop the others (convention 3.8.8). */
    @Test
    void anUnexpectedErrorInOneUserDoesNotStopTheOthers() {
        Recipient broken = recipient("Usuario Quebrado", FIRM_A, 1);
        recipient("Usuario Bom", FIRM_A, 1);
        doAnswer(invocation -> {
            Recipient recipient = invocation.getArgument(0);
            if (recipient.userId().equals(broken.userId())) {
                throw new IllegalStateException("boom");
            }
            return pending.get(recipient.userId());
        }).when(service).newPendencies(any());
        PendencyAlertScheduler.RunResult result = scheduler(FirmAccessTestSupport.allowAll()).sendDue();
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    /** Review item 5: two instances at the same time never go over the global cap together. */
    @Test
    void twoInstancesTogetherStayUnderTheCap() throws Exception {
        properties.setMaxPerDay(3);
        for (int i = 0; i < 8; i++) {
            recipient("Usuario " + i, FIRM_A, 1);
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            PendencyAlertScheduler instance = scheduler(FirmAccessTestSupport.allowAll());
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    instance.sendDue();
                } catch (Exception ignored) {
                    // asserted below
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        assertThat(sent).hasSize(3);
        assertThat(statusCount(PendencyAlertDigestStatus.SENT)).isEqualTo(3);
        assertThat(statusCount(PendencyAlertDigestStatus.CAPPED)).isEqualTo(5);
    }
}
