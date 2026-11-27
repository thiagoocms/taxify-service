package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1-RN-10a: CA1-15, CA1-16, CA1-25, CT1-25, CT1-28, CT1-31, CT1-42 (a contagem em si é a dos repositórios). */
class PlanLimitServiceTest {

    private static final UUID FIRM = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final AccountingFirmRepository firms = mock(AccountingFirmRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final FirmAccessService access = mock(FirmAccessService.class);
    private final MessageService messages = mock(MessageService.class);
    private final SaasProperties properties = new SaasProperties();
    private PlanLimitService service;

    @BeforeEach
    void setUp() {
        service = new PlanLimitService(firms, companies, users, subscriptions, access, properties, messages, mock(PlatformTransactionManager.class));
        lenient().when(messages.getMessage(anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messages.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** The same situation before and under the lock. */
    private void status(FirmSubscriptionStatus status, boolean exempt) {
        FirmAccess situation = new FirmAccess(FIRM, status, exempt, false, null, null, null, null, null, null, false);
        when(access.accessOf(FIRM)).thenReturn(situation);
        lenient().when(access.evaluate(eq(FIRM), any())).thenReturn(situation);
    }

    private void companiesInUse(long count) {
        when(companies.countInUseLocking(FIRM.toString())).thenReturn(count);
    }

    /** CA1-15 / CT1-25: limite 5, uso 5 → 409 error.plan.limit.companies com limit=5, inUse=5, planName "Teste grátis". */
    @Test
    void theSixthCompanyOfATrialIsRefused() {
        status(FirmSubscriptionStatus.TRIAL, false);
        companiesInUse(5);

        assertThatThrownBy(() -> service.assertCompanySlot(FIRM)).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.getCode()).isEqualTo("error.plan.limit.companies");
            assertThat(e.getExtras()).containsEntry("kind", "COMPANY").containsEntry("limit", 5).containsEntry("inUse", 5L)
                    .containsEntry("planName", "Teste grátis");
        });
        // waits at most 5 s for the firm's lock, counts under it, then restores the session's value
        InOrder order = inOrder(firms, companies);
        order.verify(firms).setLockWaitTimeout(5);
        order.verify(firms).lock(FIRM);
        order.verify(companies).countInUseLocking(FIRM.toString());
        order.verify(firms).setLockWaitTimeout(50);
    }

    /** CA1-25 / CT1-26 / CT1-42: com 4 em uso (inativa não conta, filial conta à parte) a próxima entra. */
    @Test
    void withARoomLeftTheCompanyIsAccepted() {
        status(FirmSubscriptionStatus.TRIAL, false);
        companiesInUse(4);

        service.assertCompanySlot(FIRM);

        // the count is the locking one (LOCK IN SHARE MODE) of companies in use - see CompanyRepository#countInUseLocking
        verify(companies).countInUseLocking(FIRM.toString());
        verify(users, never()).countInUseLocking(anyString());
    }

    /** CA1-15 / CT1-28: limite 2 usuários com o ADMIN e 1 colaborador → 409 error.plan.limit.users. */
    @Test
    void theThirdUserOfATrialIsRefused() {
        status(FirmSubscriptionStatus.TRIAL, false);
        when(users.countInUseLocking(FIRM.toString())).thenReturn(2L);

        assertThatThrownBy(() -> service.assertUserSlot(FIRM)).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.getCode()).isEqualTo("error.plan.limit.users");
            assertThat(e.getExtras()).containsEntry("kind", "USER").containsEntry("limit", 2).containsEntry("inUse", 2L);
        });
    }

    /** CT1-31 / CT1-32: ACTIVE (liberado ou migrado) e o isento não têm limite - nem trava. */
    @Test
    void releasedAndExemptFirmsHaveNoLimit() {
        status(FirmSubscriptionStatus.ACTIVE, false);
        service.assertCompanySlot(FIRM);
        status(FirmSubscriptionStatus.ACTIVE, true);
        service.assertUserSlot(FIRM);
        verify(firms, never()).lock(any());
        verify(firms, never()).lockById(anyString());
    }

    /** Estouro da espera da trava → 409 error.plan.limit.busy, e a espera da sessão volta ao que era. */
    @Test
    void lockTimeoutIsABusyConflict() {
        status(FirmSubscriptionStatus.TRIAL, false);
        when(firms.findLockWaitTimeout()).thenReturn(30);
        doThrow(new CannotAcquireLockException("Lock wait timeout exceeded")).when(firms).lock(FIRM);

        assertThatThrownBy(() -> service.assertCompanySlot(FIRM)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("error.plan.limit.busy"));
        verify(firms).setLockWaitTimeout(30);
    }

    /** Review: the trial is decided again under the lock - a release committed meanwhile wins, and nothing is counted. */
    @Test
    void aReleaseCommittedBeforeTheLockWins() {
        when(access.accessOf(FIRM)).thenReturn(new FirmAccess(FIRM, FirmSubscriptionStatus.TRIAL, false, false, null, null,
                null, null, 3, null, false));
        when(subscriptions.findAccessForUpdate(FIRM.toString()))
                .thenReturn(List.<Object[]>of(new Object[]{"ACTIVE", java.sql.Date.valueOf("2026-12-09"), null, null, null}));
        when(access.evaluate(eq(FIRM), argThat(s -> s != null && s.getStatus() == FirmSubscriptionStatus.ACTIVE
                && java.time.LocalDate.of(2026, 12, 9).equals(s.getTrialEndsOn()))))
                .thenReturn(new FirmAccess(FIRM, FirmSubscriptionStatus.ACTIVE, false, false, null, null, null, null, null, null, false));
        companiesInUse(5);

        service.assertCompanySlot(FIRM);

        InOrder order = inOrder(firms, subscriptions);
        order.verify(firms).lock(FIRM);
        order.verify(subscriptions).findAccessForUpdate(FIRM.toString());
        verify(companies, never()).countInUseLocking(anyString());
    }

    /** A firm without a row under the lock is SUSPENDED (FirmAccessRules) - evaluated with no subscription. */
    @Test
    void underTheLockAMissingRowIsEvaluatedAsSuch() {
        status(FirmSubscriptionStatus.TRIAL, false);
        companiesInUse(1);
        service.assertCompanySlot(FIRM);
        verify(access).evaluate(eq(FIRM), isNull());
    }

    /** CA1-16: na importação, sem vaga a empresa não é criada (vazio) e o job segue. */
    @Test
    void theImportOnlyWritesWithARoom() {
        status(FirmSubscriptionStatus.TRIAL, false);
        companiesInUse(5);
        assertThat(service.withCompanySlot(FIRM, () -> "criada")).isEqualTo(Optional.empty());

        companiesInUse(4);
        assertThat(service.withCompanySlot(FIRM, () -> "criada")).contains("criada");
    }

    /** O uso × limite da tela (sem trava) só no teste. */
    @Test
    void theScreenLimitsAreOnlyForTheTrial() {
        when(companies.countInUseByFirm(FIRM)).thenReturn(3L);
        when(users.countByAccountingFirm_IdAndDeletedIsFalse(FIRM)).thenReturn(1L);
        FirmAccess trial = new FirmAccess(FIRM, FirmSubscriptionStatus.TRIAL, false, false, null, null, null, null, 5, null, false);

        assertThat(service.limitsFor(trial)).satisfies(limits -> {
            assertThat(limits.companiesInUse()).isEqualTo(3);
            assertThat(limits.usersInUse()).isEqualTo(1);
            assertThat(limits.maxCompanies()).isEqualTo(5);
            assertThat(limits.planName()).isEqualTo("Teste grátis");
        });
        assertThat(service.limitsFor(new FirmAccess(FIRM, FirmSubscriptionStatus.ACTIVE, false, false, null, null, null, null,
                null, null, false))).isNull();
    }
}
