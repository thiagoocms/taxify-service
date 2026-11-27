package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.OneTimeTokens;
import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.password.PasswordResetToken;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.validation.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SN-RN-01 a SN-RN-07 e SN-RN-14 com os casos numéricos da spec (CT-SN-01 a CT-SN-20, CT-SN-32). Hoje = 2026-11-27,
 * horário de Recife. Dados fictícios; repositórios em memória; o processamento "depois da resposta" roda na hora.
 */
class PasswordResetServiceTest {

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
    private static final String IP = "203.0.113.10";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    private final FirmAccessService firmAccess = mock(FirmAccessService.class);
    private final MailSender mail = mock(MailSender.class);
    private final MessageService messages = mock(MessageService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordProperties properties = new PasswordProperties();
    private final SaasProperties saas = new SaasProperties();
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 27, 10, 0));

    private final List<PasswordResetToken> stored = new CopyOnWriteArrayList<>();
    private final Map<UUID, User> byId = new HashMap<>();
    private final Map<UUID, FirmSubscriptionStatus> firmStatus = new HashMap<>();
    private final List<MailMessage> sent = new ArrayList<>();
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        properties.setResetEnabled(true);
        saas.setPublicUrl("https://taxify.exemplo.test/");
        service = new PasswordResetService(users, tokens, firmAccess, new PasswordPolicy(properties, encoder, messages), encoder,
                mail, properties, saas, messages, events, clock, mock(PlatformTransactionManager.class), Runnable::run);
        lenient().when(tokens.getLock(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1L);
        lenient().when(messages.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messages.getMessage(anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(mail).send(any());

        lenient().when(users.findByLoginAndDeletedIsFalse(anyString())).thenAnswer(invocation -> byId.values().stream()
                .filter(user -> !user.isDeleted() && user.getLogin().equals(invocation.getArgument(0))).findFirst().orElse(null));
        lenient().when(users.findByEmailForPasswordReset(anyString())).thenAnswer(invocation -> byId.values().stream()
                .filter(user -> !user.isDeleted() && user.getEmail().equalsIgnoreCase(invocation.getArgument(0)))
                .sorted((a, b) -> b.getCreatedDate().compareTo(a.getCreatedDate())).toList());
        lenient().when(users.findForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(byId.get(invocation.<UUID>getArgument(0))));
        lenient().when(users.findById(any())).thenAnswer(invocation -> Optional.ofNullable(byId.get(invocation.<UUID>getArgument(0))));
        lenient().when(firmAccess.accessOf(any(UUID.class))).thenAnswer(invocation -> {
            UUID firmId = invocation.getArgument(0);
            return new FirmAccess(firmId, firmStatus.getOrDefault(firmId, FirmSubscriptionStatus.ACTIVE), false, false, null,
                    null, null, null, null, null, false);
        });

        lenient().when(tokens.save(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
            stored.add(token);
            return token;
        });
        lenient().when(tokens.findByTokenHashAndDeletedIsFalse(anyString())).thenAnswer(invocation -> stored.stream()
                .filter(token -> token.getTokenHash().equals(invocation.getArgument(0))).findFirst());
        lenient().when(tokens.countByUserIdAndRequestedAtGreaterThanEqual(any(), any())).thenAnswer(invocation -> stored.stream()
                .filter(token -> token.getUserId().equals(invocation.getArgument(0))
                        && !token.getRequestedAt().isBefore(invocation.getArgument(1))).count());
        lenient().when(tokens.countByUserIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(any(), any(), any()))
                .thenAnswer(invocation -> stored.stream().filter(token -> token.getUserId().equals(invocation.getArgument(0))
                        && within(token, invocation.getArgument(1), invocation.getArgument(2))).count());
        lenient().when(tokens.countByIpLocking(anyString(), any(), any()))
                .thenAnswer(invocation -> stored.stream().filter(token -> Objects.equals(token.getRequestedIp(), invocation.getArgument(0))
                        && within(token, invocation.getArgument(1), invocation.getArgument(2))).count());
        lenient().when(tokens.invalidateOpen(any(), any())).thenAnswer(invocation -> {
            int count = 0;
            for (PasswordResetToken token : stored) {
                if (token.getUserId().equals(invocation.getArgument(0)) && token.getUsedAt() == null && token.getInvalidatedAt() == null) {
                    token.setInvalidatedAt(invocation.getArgument(1));
                    count++;
                }
            }
            return count;
        });
        // UPDATE ... SET used_at = :now WHERE id = :id AND used_at IS NULL AND invalidated_at IS NULL AND expires_at >= :now
        lenient().when(tokens.markUsed(any(), any())).thenAnswer(invocation -> {
            LocalDateTime now = invocation.getArgument(1);
            for (PasswordResetToken token : stored) {
                if (token.getId().equals(invocation.getArgument(0)) && token.isUsableAt(now)) {
                    token.setUsedAt(now);
                    return 1;
                }
            }
            return 0;
        });
        lenient().when(tokens.clearRequestedIp(any())).thenAnswer(invocation -> {
            stored.stream().filter(token -> token.getUserId().equals(invocation.getArgument(0)))
                    .forEach(token -> ReflectionTestUtils.setField(token, "requestedIp", null));
            return 1;
        });
    }

    private static boolean within(PasswordResetToken token, LocalDateTime from, LocalDateTime to) {
        return !token.getRequestedAt().isBefore(from) && token.getRequestedAt().isBefore(to);
    }

    private User user(String login, String email, String firmName, LocalDateTime created) {
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        firm.setName(firmName);
        User user = new User(UUID.randomUUID());
        user.setLogin(login);
        user.setEmail(email);
        user.setAccountingFirm(firm);
        user.setPassword(encoder.encode("SenhaAntiga2026"));
        ReflectionTestUtils.setField(user, "createdDate", created);
        byId.put(user.getId(), user);
        return user;
    }

    private User ana() {
        return user("ana.fiscal", "ana@escritorio-ficticio.com.br", "Escritório Exemplo", LocalDateTime.of(2026, 1, 1, 9, 0));
    }

    private void at(int hour, int minute) {
        clock.set(LocalDateTime.of(2026, 11, 27, hour, minute));
    }

    private static String tokenOf(MailMessage message) {
        Matcher matcher = TOKEN.matcher(message.body());
        assertThat(matcher.find()).as("link with #token=").isTrue();
        return matcher.group(1);
    }

    private static String codeOf(Runnable call) {
        try {
            call.run();
        } catch (BadRequestException e) {
            return e.getCode();
        }
        return null;
    }

    /** CT-SN-01 / CA-SN-01. */
    @Test
    void aRequestByLoginStoresOnlyTheHashAndMailsTheLinkInTheFragment() {
        User ana = ana();
        service.requestReset("  ana.fiscal ", IP);

        assertThat(stored).hasSize(1);
        PasswordResetToken token = stored.getFirst();
        assertThat(token.getUserId()).isEqualTo(ana.getId());
        assertThat(token.getRequestedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
        assertThat(token.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 11, 0));
        assertThat(token.getRequestedIp()).isEqualTo(IP);
        assertThat(sent).hasSize(1);
        MailMessage message = sent.getFirst();
        assertThat(message.to()).containsExactly("ana@escritorio-ficticio.com.br");
        assertThat(message.body()).contains("https://taxify.exemplo.test/auth/redefinir-senha#token=")
                .contains("ana.fiscal").contains("Escritório Exemplo").contains("60 minutos")
                .doesNotContain("?token=");
        String clear = tokenOf(message);
        assertThat(token.getTokenHash()).isEqualTo(OneTimeTokens.hash(clear)).hasSize(64).isNotEqualTo(clear);
    }

    /** CT-SN-02 / CT-SN-03: unknown, empty or null - nothing stored, nothing sent (and no exception for the caller). */
    @Test
    void unknownOrEmptyIdentifiersDoNothing() {
        ana();
        service.requestReset("nao.existe", IP);
        service.requestReset("   ", IP);
        service.requestReset(null, IP);
        service.requestReset("ninguem@exemplo.test", IP);
        assertThat(stored).isEmpty();
        assertThat(sent).isEmpty();
    }

    /** CT-SN-04 / CA-SN-03: the e-mail of two accounts (case does not matter) - one e-mail per account. */
    @Test
    void anEmailSharedByTwoAccountsGetsOneMailPerAccount() {
        user("ana.fiscal", "contato@escritorio-ficticio.com.br", "Escritório A", LocalDateTime.of(2026, 1, 1, 9, 0));
        user("ana.admin", "contato@escritorio-ficticio.com.br", "Escritório B", LocalDateTime.of(2026, 2, 1, 9, 0));

        service.requestReset("Contato@Escritorio-Ficticio.com.br", IP);

        assertThat(stored).hasSize(2);
        assertThat(sent).hasSize(2);
        assertThat(sent).anySatisfy(m -> assertThat(m.body()).contains("ana.fiscal").contains("Escritório A"));
        assertThat(sent).anySatisfy(m -> assertThat(m.body()).contains("ana.admin").contains("Escritório B"));
        assertThat(tokenOf(sent.get(0))).isNotEqualTo(tokenOf(sent.get(1)));
    }

    /** CT-SN-05: 7 accounts with the same e-mail - the 5 newest. */
    @Test
    void atMostFiveAccountsPerRequestTheNewest() {
        List<User> created = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            created.add(user("conta." + i, "mesmo@exemplo.test", "Escritório " + i, LocalDateTime.of(2026, i, 1, 9, 0)));
        }
        service.requestReset("mesmo@exemplo.test", null);

        assertThat(sent).hasSize(5);
        assertThat(stored).extracting(PasswordResetToken::getUserId)
                .containsExactlyInAnyOrderElementsOf(created.subList(2, 7).stream().map(User::getId).toList());
    }

    /** CT-SN-06 / CA-SN-04: 3 per hour per account; at 11:01 it goes out again. */
    @Test
    void threeLinksPerHourPerAccount() {
        ana();
        at(10, 0);
        service.requestReset("ana.fiscal", IP);
        at(10, 10);
        service.requestReset("ana.fiscal", IP);
        at(10, 20);
        service.requestReset("ana.fiscal", IP);
        at(10, 30);
        service.requestReset("ana.fiscal", IP);
        assertThat(sent).hasSize(3);
        at(11, 1);
        service.requestReset("ana.fiscal", IP);
        assertThat(sent).hasSize(4);
    }

    /** CT-SN-07: 5 per day per account; 00:00 of Recife resets it. */
    @Test
    void fiveLinksPerDayPerAccount() {
        ana();
        int[][] times = {{8, 0}, {8, 10}, {8, 20}, {9, 30}, {9, 40}, {11, 0}};
        for (int[] time : times) {
            at(time[0], time[1]);
            service.requestReset("ana.fiscal", IP);
        }
        assertThat(sent).hasSize(5);
        clock.set(LocalDateTime.of(2026, 11, 28, 0, 0));
        service.requestReset("ana.fiscal", IP);
        assertThat(sent).hasSize(6);
    }

    /** CT-SN-08: 10 per day per IP, adding every account. */
    @Test
    void tenLinksPerDayPerIp() {
        for (int i = 1; i <= 11; i++) {
            user("conta." + i, "conta" + i + "@exemplo.test", "Escritório", LocalDateTime.of(2026, 1, i, 9, 0));
        }
        for (int i = 1; i <= 11; i++) {
            service.requestReset("conta." + i, IP);
        }
        assertThat(sent).hasSize(10);
        // another IP is not affected
        service.requestReset("conta.11", "198.51.100.7");
        assertThat(sent).hasSize(11);
    }

    /** CT-SN-09 / CA-SN-05: issued at 10:00 - 204 at 10:59:59, 400 at 11:00:01; used once. */
    @Test
    void theLinkIsValidForSixtyMinutesAndOnce() {
        ana();
        service.requestReset("ana.fiscal", IP);
        String token = tokenOf(sent.getFirst());

        clock.set(LocalDateTime.of(2026, 11, 27, 10, 59, 59));
        service.check(token);
        service.reset(token, "NovaSenha2026");
        assertThat(encoder.matches("NovaSenha2026", byId.values().iterator().next().getPassword())).isTrue();
        assertThat(codeOf(() -> service.reset(token, "OutraSenha2026"))).isEqualTo("error.password.reset.token.invalid");

        service.requestReset("ana.fiscal", IP);
        String late = tokenOf(sent.getLast());
        clock.set(LocalDateTime.of(2026, 11, 27, 12, 0, 1));
        assertThat(codeOf(() -> service.check(late))).isEqualTo("error.password.reset.token.invalid");
        assertThat(codeOf(() -> service.reset(late, "OutraSenha2026"))).isEqualTo("error.password.reset.token.invalid");
    }

    /** CT-SN-11 / CA-SN-06: a new link invalidates the open one; the second keeps working. */
    @Test
    void aNewLinkInvalidatesTheOpenOne() {
        ana();
        at(10, 0);
        service.requestReset("ana.fiscal", IP);
        String first = tokenOf(sent.get(0));
        at(10, 5);
        service.requestReset("ana.fiscal", IP);
        String second = tokenOf(sent.get(1));

        assertThat(codeOf(() -> service.reset(first, "NovaSenha2026"))).isEqualTo("error.password.reset.token.invalid");
        service.reset(second, "NovaSenha2026");
    }

    /** CT-SN-12 / CA-SN-07 / CT-SN-13 / CT-SN-14: the password rule refuses - and the link is NOT consumed. */
    @Test
    void aRefusedPasswordKeepsTheLink() {
        ana();
        service.requestReset("ana.fiscal", IP);
        String token = tokenOf(sent.getFirst());

        assertThat(codeOf(() -> service.reset(token, "Abc1234"))).isEqualTo("error.user.password.too.short");
        assertThat(codeOf(() -> service.reset(token, "ç".repeat(37)))).isEqualTo("error.user.password.invalid");
        assertThat(codeOf(() -> service.reset(token, "SenhaAntiga2026"))).isEqualTo("error.user.password.same");
        assertThat(codeOf(() -> service.reset(token, null))).isEqualTo("error.user.password.required");
        service.check(token);
        service.reset(token, "Abc12345");
    }

    /** CT-SN-15 / CA-SN-08: the reset raises the session version, invalidates the other links, clears the IPs, notifies. */
    @Test
    void aResetRevokesTheSessionsAndClearsTheIps() {
        User ana = ana();
        ana.setSessionVersion(4);
        ana.setPasswordFailCount(3);
        service.requestReset("ana.fiscal", IP);
        String token = tokenOf(sent.getFirst());
        at(10, 30);

        service.reset(token, "NovaSenha2026");

        assertThat(ana.getSessionVersion()).isEqualTo(5);
        assertThat(ana.getPasswordChangedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 30));
        assertThat(ana.getPasswordFailCount()).isZero();
        assertThat(stored).allSatisfy(t -> assertThat(t.getRequestedIp()).isNull());
        verify(tokens).invalidateOpen(ana.getId(), LocalDateTime.of(2026, 11, 27, 10, 30));
        verify(events).publishEvent(new PasswordChangedEvent(ana.getId(), PasswordChangeChannel.RESET,
                LocalDateTime.of(2026, 11, 27, 10, 30)));
    }

    /** CT-SN-16 / CT-SN-17 / CA-SN-09: blocked, suspended, canceled firms get the link (SN-RN-07, P-SN-05). */
    @Test
    void blockedSuspendedAndCanceledFirmsGetTheLink() {
        for (FirmSubscriptionStatus status : List.of(FirmSubscriptionStatus.BLOCKED, FirmSubscriptionStatus.SUSPENDED,
                FirmSubscriptionStatus.CANCELED, FirmSubscriptionStatus.TRIAL)) {
            User user = user("conta." + status, status + "@exemplo.test", "Escritório", LocalDateTime.of(2026, 1, 1, 9, 0));
            firmStatus.put(user.getAccountingFirm().getId(), status);
            service.requestReset("conta." + status, null);
        }
        assertThat(sent).hasSize(4);
        service.reset(tokenOf(sent.getFirst()), "NovaSenha2026");
    }

    /** CT-SN-18 / CA-SN-10: an unverified signup gets nothing. */
    @Test
    void anUnverifiedSignupGetsNothing() {
        User ana = ana();
        firmStatus.put(ana.getAccountingFirm().getId(), FirmSubscriptionStatus.PENDING_VERIFICATION);
        service.requestReset("ana.fiscal", IP);
        assertThat(stored).isEmpty();
        assertThat(sent).isEmpty();
    }

    /** CT-SN-19 / CA-SN-11: deleted before the request - nothing; deleted between the request and the use - 400. */
    @Test
    void aDeletedUserGetsNothingAndHisOldLinkIsInvalid() {
        User ana = ana();
        service.requestReset("ana.fiscal", IP);
        String token = tokenOf(sent.getFirst());
        ana.setDeleted(true);

        assertThat(codeOf(() -> service.check(token))).isEqualTo("error.password.reset.token.invalid");
        assertThat(codeOf(() -> service.reset(token, "NovaSenha2026"))).isEqualTo("error.password.reset.token.invalid");
        service.requestReset("ana.fiscal", IP);
        assertThat(sent).hasSize(1);
    }

    /** CT-SN-20: a deleted firm - nothing; and its old link is invalid. */
    @Test
    void aDeletedFirmGetsNothing() {
        User ana = ana();
        service.requestReset("ana.fiscal", IP);
        String token = tokenOf(sent.getFirst());
        ana.getAccountingFirm().setDeleted(true);
        service.requestReset("ana.fiscal", IP);
        assertThat(sent).hasSize(1);
        assertThat(codeOf(() -> service.reset(token, "NovaSenha2026"))).isEqualTo("error.password.reset.token.invalid");
    }

    /** CT-SN-32 / CA-SN-20: switched off - config available=false, the POSTs 503. */
    @Test
    void switchedOffAnswers503AndTheConfigSaysUnavailable() {
        properties.setResetEnabled(false);
        assertThat(service.config().available()).isFalse();
        assertThat(service.config().minLength()).isEqualTo(8);
        assertThat(service.config().resetMinutes()).isEqualTo(60);
        assertThatThrownBy(() -> service.requestReset("ana.fiscal", IP)).isInstanceOfSatisfying(ServiceUnavailableException.class,
                e -> assertThat(e.getCode()).isEqualTo("error.password.reset.unavailable"));
        assertThatThrownBy(() -> service.check("x")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> service.reset("x", "y")).isInstanceOf(ServiceUnavailableException.class);
        properties.setResetEnabled(true);
        assertThat(service.config().available()).isTrue();
    }

    /** A SES failure for one account does not stop the others nor reach the caller. */
    @Test
    void aMailFailureIsIsolated() {
        user("ana.fiscal", "contato@exemplo.test", "Escritório A", LocalDateTime.of(2026, 1, 1, 9, 0));
        user("ana.admin", "contato@exemplo.test", "Escritório B", LocalDateTime.of(2026, 2, 1, 9, 0));
        doAnswer(invocation -> {
            MailMessage message = invocation.getArgument(0);
            if (message.body().contains("ana.admin")) {
                throw new MailSendException("Falha no envio pelo SES (error=X)");
            }
            sent.add(message);
            return null;
        }).when(mail).send(any());

        service.requestReset("contato@exemplo.test", IP);
        assertThat(sent).hasSize(1);
        assertThat(stored).hasSize(2);
    }

    /** Unknown and empty tokens are 400, never 404. */
    @Test
    void unknownTokensAre400() {
        assertThat(codeOf(() -> service.check(null))).isEqualTo("error.password.reset.token.invalid");
        assertThat(codeOf(() -> service.check("desconhecido"))).isEqualTo("error.password.reset.token.invalid");
        assertThat(codeOf(() -> service.reset("", "NovaSenha2026"))).isEqualTo("error.password.reset.token.invalid");
        verify(tokens, never()).markUsed(any(), any());
    }

    /** Review item 1: the IP is serialized by a MySQL named lock (never the IP itself in the name) and released. */
    @Test
    void theIpCapIsCountedUnderANamedLockThatIsReleased() {
        ana();
        service.requestReset("ana.fiscal", IP);
        String name = br.com.cshub.taxify.common.NamedLocks.name(PasswordResetService.IP_LOCK_PREFIX, IP);
        assertThat(name).startsWith("pwreset:").doesNotContain(IP).hasSize(64);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tokens, users);
        order.verify(tokens).getLock(name, PasswordResetService.IP_LOCK_SECONDS);
        order.verify(users).findForUpdate(any());
        order.verify(tokens).countByIpLocking(org.mockito.ArgumentMatchers.eq(IP), any(), any());
        order.verify(tokens).save(any());
        order.verify(tokens).releaseLock(name);
        assertThat(sent).hasSize(1);
    }

    /** The lock did not come in time: nothing is issued for that account (still no error to the caller). */
    @Test
    void aBusyIpLockSendsNothing() {
        ana();
        org.mockito.Mockito.when(tokens.getLock(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);
        service.requestReset("ana.fiscal", IP);
        assertThat(stored).isEmpty();
        assertThat(sent).isEmpty();
        verify(tokens, never()).releaseLock(anyString());
    }

    /** Without an IP there is no IP cap and no lock. */
    @Test
    void noIpNoLock() {
        ana();
        service.requestReset("ana.fiscal", null);
        verify(tokens, never()).getLock(anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sent).hasSize(1);
    }

    /** Review item 2: a full executor drops the request quietly - the caller still answers 202. */
    @Test
    void aFullExecutorDropsTheRequest() {
        ana();
        PasswordResetService full = new PasswordResetService(users, tokens, firmAccess, new PasswordPolicy(properties, encoder, messages),
                encoder, mail, properties, saas, messages, events, clock, mock(PlatformTransactionManager.class), command -> {
                    throw new java.util.concurrent.RejectedExecutionException("full");
                });
        org.assertj.core.api.Assertions.assertThatCode(() -> full.requestReset("ana.fiscal", IP)).doesNotThrowAnyException();
        assertThat(sent).isEmpty();
    }

    /** Review item 2: the real executor bounds what runs at once and what waits; the rest is refused. */
    @Test
    void theExecutorIsBounded() throws Exception {
        PasswordProperties small = new PasswordProperties();
        small.setAsyncMaxConcurrent(2);
        small.setAsyncQueueSize(3);
        java.util.concurrent.ExecutorService executor = new br.com.cshub.taxify.config.PasswordAsyncConfig().passwordMailExecutor(small);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        int accepted = 0;
        int refused = 0;
        try {
            for (int i = 0; i < 8; i++) {
                try {
                    executor.execute(() -> {
                        try {
                            hold.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    accepted++;
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    refused++;
                }
            }
        } finally {
            hold.countDown();
            executor.shutdown();
        }
        assertThat(accepted).isEqualTo(5);
        assertThat(refused).isEqualTo(3);
    }
}
