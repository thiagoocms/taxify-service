package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.SubscriptionMapper;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionValidationTest {

    @Mock
    private SubscriptionMapper mapper;
    @Mock
    private SubscriptionRepository repository;
    @Mock
    private MessageService messageService;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private PlanValidation planValidation;
    @Mock
    private TenantAccessService tenantAccessService;

    private SubscriptionValidation validation;

    @BeforeEach
    void setUp() {
        validation = new SubscriptionValidation(mapper, repository, messageService, companyValidation, planValidation, tenantAccessService);
    }

    private Subscription subscription(UUID companyId, LocalDate start, LocalDate end) {
        Subscription s = new Subscription();
        s.setCompany(new Company(companyId));
        s.setPlan(new Plan(UUID.randomUUID()));
        s.setStartDate(start);
        s.setEndDate(end);
        return s;
    }

    @Test
    void checkOwnerFieldsToCreateResetsIdAndDeleted() {
        Subscription entity = new Subscription(UUID.randomUUID());
        entity.setDeleted(true);

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void checkUpdateConsistenceThrowsWhenIdsDiffer() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        Subscription toUpdate = new Subscription(UUID.randomUUID());

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID id = UUID.randomUUID();
        Subscription toUpdate = new Subscription(id);
        Subscription persisted = new Subscription(id);
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        persisted.setCompany(company);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        Subscription result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkCompanyResolvesAnExistingCompanyById() {
        UUID companyId = UUID.randomUUID();
        Subscription entity = new Subscription();
        entity.setCompany(new Company(companyId));
        Company resolved = new Company(companyId);
        when(companyValidation.checkExist(companyId)).thenReturn(resolved);

        validation.checkCompany(entity);

        assertThat(entity.getCompany()).isSameAs(resolved);
    }

    @Test
    void checkCompanyLeavesReferenceUntouchedWhenItHasNoId() {
        Subscription entity = new Subscription();
        Company reference = new Company();
        entity.setCompany(reference);

        validation.checkCompany(entity);

        assertThat(entity.getCompany()).isSameAs(reference);
        verifyNoInteractions(companyValidation);
    }

    @Test
    void checkPlanResolvesAnExistingPlanById() {
        UUID planId = UUID.randomUUID();
        Subscription entity = new Subscription();
        entity.setPlan(new Plan(planId));
        Plan resolved = new Plan(planId);
        when(planValidation.checkExist(planId)).thenReturn(resolved);

        validation.checkPlan(entity);

        assertThat(entity.getPlan()).isSameAs(resolved);
    }

    @Test
    void checkValidationsExtrasThrowsWhenStartDateIsAfterEndDate() {
        Subscription entity = subscription(UUID.randomUUID(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));
        when(messageService.getMessage("error.subscription.invalid.dates")).thenReturn("invalid dates");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("invalid dates");
        verifyNoInteractions(repository);
    }

    @Test
    void checkValidationsExtrasOnCreateThrowsConflictWhenOverlapExists() {
        UUID companyId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        Subscription entity = subscription(companyId, start, end);
        when(repository.existsByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqual(companyId, end, start))
                .thenReturn(true);
        when(messageService.getMessage("error.subscription.overlap")).thenReturn("overlap");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOf(ConflictException.class)
                .hasMessage("overlap");
    }

    @Test
    void checkValidationsExtrasOnCreatePassesWhenNoOverlap() {
        UUID companyId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        Subscription entity = subscription(companyId, start, end);
        when(repository.existsByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqual(companyId, end, start))
                .thenReturn(false);

        validation.checkValidationsExtras(entity);
    }

    @Test
    void checkValidationsExtrasOnUpdateExcludesItsOwnIdFromTheOverlapCheck() {
        UUID id = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        Subscription entity = subscription(companyId, start, end);
        entity.setId(id);
        when(repository.existsByCompanyIdAndDeletedIsFalseAndIdNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(companyId, id, end, start))
                .thenReturn(true);
        when(messageService.getMessage("error.subscription.overlap")).thenReturn("overlap");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOf(ConflictException.class);
        verify(repository).existsByCompanyIdAndDeletedIsFalseAndIdNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(companyId, id, end, start);
    }
}
