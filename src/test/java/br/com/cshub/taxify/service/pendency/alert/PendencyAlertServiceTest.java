package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.AccessProfile;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.PendencyAlertDigestRepository;
import br.com.cshub.taxify.repository.PendencyAlertItemRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.notification.NotificationSettingsService;
import br.com.cshub.taxify.service.pendency.alert.PendencyAlertService.Recipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AV-RN-01 e AV-RN-02 com o cenário base dos critérios (fictício): escritório com X, Y e Z; Carla ADMIN; Ana FISCAL
 * (carteira X e Y); Bruno PAYROLL (sem INVOICES_VIEW, carteira Z). CA-AV-01, CA-AV-08, CA-AV-14, CT-AV-08, CT-AV-14,
 * CT-AV-16, CT-AV-23, CT-AV-24, CT-AV-25, CT-AV-26.
 */
class PendencyAlertServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserCompanyRepository userCompanies = mock(UserCompanyRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final PendencyRepository pendencies = mock(PendencyRepository.class);
    private final NotificationSettingsService settings = mock(NotificationSettingsService.class);
    private final PendencyAlertProperties properties = new PendencyAlertProperties();
    private PendencyAlertService service;

    private final AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
    private final UUID x = UUID.randomUUID();
    private final UUID y = UUID.randomUUID();
    private final UUID z = UUID.randomUUID();
    private final List<User> people = new ArrayList<>();
    private final Map<UUID, Boolean> enabled = new HashMap<>();
    private User carla;
    private User ana;
    private User bruno;

    @BeforeEach
    void setUp() {
        service = new PendencyAlertService(users, userCompanies, companies, pendencies, mock(PendencyAlertDigestRepository.class),
                mock(PendencyAlertItemRepository.class), settings, properties, mock(PlatformTransactionManager.class));
        firm.setName("Escritório Exemplo");
        carla = user("Carla Admin", "carla@exemplo.test", UserProfileEnum.ADMIN, Set.of());
        ana = user("Ana Fiscal", "ana@exemplo.test", UserProfileEnum.EMPLOYEE, Set.of(AccessProfile.FISCAL));
        bruno = user("Bruno DP", "bruno@exemplo.test", UserProfileEnum.EMPLOYEE, Set.of(AccessProfile.PAYROLL));
        lenient().when(users.findInUseByFirms(anyCollection())).thenAnswer(invocation -> people);
        lenient().when(companies.findActiveIdsByFirm(firm.getId())).thenReturn(List.of(x, y, z));
        lenient().when(userCompanies.findPortfolios(anyCollection())).thenReturn(List.of(
                new Object[]{ana.getId(), x}, new Object[]{ana.getId(), y}, new Object[]{bruno.getId(), z},
                // a company of another firm in the portfolio never shows up
                new Object[]{ana.getId(), UUID.randomUUID()}));
        lenient().when(settings.pendencyDigestEnabled(anyCollection())).thenAnswer(invocation -> {
            Map<UUID, Boolean> result = new HashMap<>();
            invocation.<Collection<UUID>>getArgument(0).forEach(id -> result.put(id, enabled.getOrDefault(id, true)));
            return result;
        });
    }

    private User user(String name, String email, UserProfileEnum profile, Set<AccessProfile> profiles) {
        User user = new User(UUID.randomUUID());
        user.setName(name);
        user.setEmail(email);
        user.setProfile(profile);
        user.setAccessProfiles(profiles);
        user.setAccountingFirm(firm);
        people.add(user);
        return user;
    }

    private Map<UUID, Recipient> recipients() {
        Map<UUID, Recipient> result = new HashMap<>();
        service.recipients(List.of(firm.getId())).forEach(r -> result.put(r.userId(), r));
        return result;
    }

    /** CA-AV-01: Carla sees X, Y, Z; Ana sees X and Y; Bruno (no INVOICES_VIEW) gets nothing. */
    @Test
    void theAdminSeesTheWholeFirmTheOthersTheirPortfolio() {
        Map<UUID, Recipient> recipients = recipients();
        assertThat(recipients).containsOnlyKeys(carla.getId(), ana.getId());
        assertThat(recipients.get(carla.getId()).companyIds()).containsExactly(x, y, z);
        assertThat(recipients.get(ana.getId()).companyIds()).containsExactly(x, y);
        assertThat(recipients.get(ana.getId()).firmName()).isEqualTo("Escritório Exemplo");
        assertThat(recipients.get(ana.getId()).email()).isEqualTo("ana@exemplo.test");
    }

    /** CA-AV-08 / CT-AV-14 / CT-AV-16: switched off (or default off) - not a recipient. */
    @Test
    void whoSwitchedOffIsNotARecipient() {
        enabled.put(ana.getId(), false);
        assertThat(recipients()).containsOnlyKeys(carla.getId());
        enabled.put(carla.getId(), false);
        assertThat(recipients()).isEmpty();
    }

    /** CT-AV-24: deleted user or no e-mail. */
    @Test
    void deletedUsersAndUsersWithoutEmailAreOut() {
        ana.setDeleted(true);
        carla.setEmail("  ");
        assertThat(recipients()).isEmpty();
    }

    /** CA-AV-14 / CT-AV-23: an inactive or deleted company is not in the list the repository gives (findActiveIdsByFirm). */
    @Test
    void onlyActiveCompaniesCount() {
        when(companies.findActiveIdsByFirm(firm.getId())).thenReturn(List.of(x, z));
        Map<UUID, Recipient> recipients = recipients();
        assertThat(recipients.get(ana.getId()).companyIds()).containsExactly(x);
        assertThat(recipients.get(carla.getId()).companyIds()).containsExactly(x, z);
    }

    /** CT-AV-26: two users with the same address get two digests (one per user). */
    @Test
    void sameAddressTwoUsersTwoRecipients() {
        ana.setEmail("carla@exemplo.test");
        assertThat(recipients()).hasSize(2);
    }

    /** CT-AV-08: the type filter goes to the query; empty = every type. */
    @Test
    void theTypeFilterGoesToTheQuery() {
        Recipient recipient = new Recipient(ana.getId(), firm.getId(), "Escritório Exemplo", "Ana", "ana@exemplo.test", List.of(x));
        service.newPendencies(recipient);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PendencyType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(pendencies).findAlertCandidates(eq(List.of(x)), types.capture(), eq(ana.getId()));
        assertThat(types.getValue()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PendencyType.class));

        properties.setTypes(List.of("certificate_expired", " SEFAZ_CAPTURE_ERROR "));
        service.newPendencies(recipient);
        verify(pendencies).findAlertCandidates(eq(List.of(x)),
                eq(EnumSet.of(PendencyType.CERTIFICATE_EXPIRED, PendencyType.SEFAZ_CAPTURE_ERROR)), eq(ana.getId()));
        assertThat(properties.includes(PendencyType.MANIFESTATION_DEADLINE)).isFalse();
    }

    /** CT-AV-25: only the given firms are read - the gate decides, and a user never gets companies of another firm. */
    @Test
    void noFirmsNoRecipients() {
        assertThat(service.recipients(List.of())).isEmpty();
        verify(users, org.mockito.Mockito.never()).findInUseByFirms(any());
    }
}
