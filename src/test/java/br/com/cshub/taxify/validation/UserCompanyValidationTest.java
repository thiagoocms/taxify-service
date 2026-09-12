package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.UserCompanyMapper;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCompanyValidationTest {

    @Mock
    private UserCompanyMapper mapper;
    @Mock
    private UserCompanyRepository repository;
    @Mock
    private MessageService messageService;
    @Mock
    private UserValidation userValidation;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private TenantAccessService tenantAccessService;

    private UserCompanyValidation validation;

    @BeforeEach
    void setUp() {
        validation = new UserCompanyValidation(mapper, repository, messageService, userValidation, companyValidation, tenantAccessService);
    }

    @Test
    void checkOwnerFieldsToCreateResetsDeleted() {
        UserCompany entity = new UserCompany(new User(UUID.randomUUID()), new Company(UUID.randomUUID()), "ADMIN");
        entity.setDeleted(true);

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void checkUpdateConsistenceThrowsWhenIdsDiffer() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        UserCompany toUpdate = new UserCompany(new User(UUID.randomUUID()), new Company(UUID.randomUUID()), "ADMIN");

        assertThatThrownBy(() -> validation.checkUpdateConsistence(new UserCompanyId(UUID.randomUUID(), UUID.randomUUID()), toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UserCompanyId id = new UserCompanyId(userId, companyId);
        UserCompany toUpdate = new UserCompany(new User(userId), new Company(companyId), "ADMIN");
        Company persistedCompany = new Company(companyId);
        persistedCompany.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        UserCompany persisted = new UserCompany(new User(userId), persistedCompany, "MEMBER");
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        UserCompany result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkUserClearsTheReferenceWhenItHasNoId() {
        UserCompany entity = new UserCompany();
        entity.setUser(new User());

        validation.checkUser(entity);

        assertThat(entity.getUser()).isNull();
    }

    @Test
    void checkUserResolvesAnExistingUserById() {
        UUID userId = UUID.randomUUID();
        UserCompany entity = new UserCompany();
        entity.setUser(new User(userId));
        User resolved = new User(userId);
        when(userValidation.checkExist(userId)).thenReturn(resolved);

        validation.checkUser(entity);

        assertThat(entity.getUser()).isSameAs(resolved);
    }

    @Test
    void checkCompanyClearsTheReferenceWhenItHasNoId() {
        UserCompany entity = new UserCompany();
        entity.setCompany(new Company());

        validation.checkCompany(entity);

        assertThat(entity.getCompany()).isNull();
    }

    @Test
    void checkCompanyResolvesAnExistingCompanyById() {
        UUID companyId = UUID.randomUUID();
        UserCompany entity = new UserCompany();
        entity.setCompany(new Company(companyId));
        Company resolved = new Company(companyId);
        when(companyValidation.checkExist(companyId)).thenReturn(resolved);

        validation.checkCompany(entity);

        assertThat(entity.getCompany()).isSameAs(resolved);
    }
}
