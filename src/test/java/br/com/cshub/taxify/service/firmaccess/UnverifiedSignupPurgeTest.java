package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.EmailVerificationRepository;
import br.com.cshub.taxify.repository.FirmNotificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionEventRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.TermsAcceptanceRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** F1-RN-03: CA1-24 / CT1-05 - o expurgo conta 7 × 24 h desde o cadastro, e reenvios não reiniciam a contagem. */
class UnverifiedSignupPurgeTest {

    private static final LocalDateTime SIGNUP = LocalDateTime.of(2026, 11, 26, 10, 0);

    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final TermsAcceptanceRepository terms = mock(TermsAcceptanceRepository.class);
    private final EmailVerificationRepository verifications = mock(EmailVerificationRepository.class);
    private final FirmNotificationRepository notifications = mock(FirmNotificationRepository.class);
    private final FirmSubscriptionEventRepository eventRows = mock(FirmSubscriptionEventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserCompanyRepository userCompanies = mock(UserCompanyRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final AccountingFirmRepository firms = mock(AccountingFirmRepository.class);
    private final FirmEventService events = mock(FirmEventService.class);

    private UnverifiedSignupPurge purge(Clock clock) {
        return new UnverifiedSignupPurge(subscriptions, terms, verifications, notifications, eventRows, users, userCompanies,
                documents, firms, events, new SaasProperties(), clock, mock(PlatformTransactionManager.class));
    }

    /** Review item 2: verified between the candidate list and the purge's transaction - locked, re-read, nothing deleted. */
    @Test
    void aSignupVerifiedMeanwhileIsKept() {
        UnverifiedSignupPurge purge = purge(new MutableClock(LocalDateTime.of(2026, 12, 4, 10, 0)));
        UUID firmId = UUID.randomUUID();
        FirmSubscription stale = new FirmSubscription();
        stale.setAccountingFirmId(firmId);
        stale.setStatus(FirmSubscriptionStatus.PENDING_VERIFICATION);
        when(subscriptions.findByStatusInAndDeletedIsFalse(any())).thenReturn(List.of(stale));
        when(terms.findSignupAt(firmId)).thenReturn(Optional.of(SIGNUP));
        when(subscriptions.findStatusForUpdate(firmId.toString())).thenReturn(List.of("TRIAL"));

        assertThat(purge.purgeExpired()).isZero();
        verify(subscriptions, never()).deleteByFirm(any());
        verify(firms, never()).deleteByIdPhysically(any());
        verifyNoInteractions(verifications, notifications, eventRows, userCompanies, documents, events);
    }

    @Test
    void sevenTimes24HoursFromTheSignup() {
        assertThat(UnverifiedSignupPurge.isExpired(SIGNUP, LocalDateTime.of(2026, 12, 3, 9, 59), 7)).isFalse();
        assertThat(UnverifiedSignupPurge.isExpired(SIGNUP, LocalDateTime.of(2026, 12, 3, 10, 0), 7)).isFalse();
        assertThat(UnverifiedSignupPurge.isExpired(SIGNUP, LocalDateTime.of(2026, 12, 3, 10, 1), 7)).isTrue();
        assertThat(UnverifiedSignupPurge.isExpired(null, LocalDateTime.of(2027, 1, 1, 0, 0), 7)).isFalse();
    }

    /** 09:59 mantém; 10:01 apaga escritório, usuário, documento, aceite, links e assinatura, e grava o evento sem dado pessoal. */
    @Test
    void purgesThePendingSignupAfterThePeriod() {
        MutableClock clock = new MutableClock(LocalDateTime.of(2026, 12, 3, 9, 59));
        UnverifiedSignupPurge purge = purge(clock);
        UUID firmId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        FirmSubscription pending = new FirmSubscription();
        pending.setAccountingFirmId(firmId);
        pending.setStatus(FirmSubscriptionStatus.PENDING_VERIFICATION);
        when(subscriptions.findByStatusInAndDeletedIsFalse(List.of(FirmSubscriptionStatus.PENDING_VERIFICATION))).thenReturn(List.of(pending));
        when(terms.findSignupAt(firmId)).thenReturn(Optional.of(SIGNUP));
        when(users.findIdsAndDocumentIdsByFirm(firmId)).thenReturn(List.<Object[]>of(new Object[]{userId, documentId}));
        when(subscriptions.findStatusForUpdate(firmId.toString())).thenReturn(List.of("PENDING_VERIFICATION"));

        assertThat(purge.purgeExpired()).isZero();
        verify(firms, never()).deleteByIdPhysically(any());

        clock.set(LocalDateTime.of(2026, 12, 3, 10, 1));
        assertThat(purge.purgeExpired()).isEqualTo(1);
        // children first, in foreign key order: the users' links, profiles and portfolio, the firm's rows, the users,
        // their CPFs and, last, the firm itself
        InOrder order = inOrder(verifications, users, userCompanies, terms, notifications, subscriptions, documents, firms);
        order.verify(verifications).deleteByUserIds(List.of(userId));
        order.verify(users).deleteAccessProfilesOf(List.of(userId.toString()));
        order.verify(userCompanies).deleteByUserIds(List.of(userId));
        order.verify(terms).deleteByFirm(firmId);
        order.verify(notifications).deleteByFirm(firmId);
        order.verify(subscriptions).deleteByFirm(firmId);
        order.verify(users).deleteByFirm(firmId);
        order.verify(documents).deleteByIds(List.of(documentId));
        order.verify(firms).deleteByIdPhysically(firmId);
        // the contact events (personal data) go before the purge event is written
        InOrder trail = inOrder(eventRows, events);
        trail.verify(eventRows).deleteByFirmAndType(firmId, FirmEventType.CONTACT_UPDATED);
        trail.verify(events).record(firmId, FirmEventType.UNVERIFIED_PURGED, null, null, null, FirmEventService.SYSTEM);
    }
}
