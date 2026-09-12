package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.InvoiceAuditLogMapper;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
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
class InvoiceAuditLogValidationTest {

    @Mock
    private InvoiceAuditLogMapper mapper;
    @Mock
    private InvoiceAuditLogRepository repository;
    @Mock
    private MessageService messageService;
    @Mock
    private UserValidation userValidation;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private TenantAccessService tenantAccessService;

    private InvoiceAuditLogValidation validation;

    @BeforeEach
    void setUp() {
        validation = new InvoiceAuditLogValidation(mapper, repository, messageService, userValidation, companyValidation, tenantAccessService);
    }

    @Test
    void checkOwnerFieldsToCreateResetsDeleted() {
        InvoiceAuditLog entity = new InvoiceAuditLog();
        entity.setDeleted(true);

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void checkUpdateConsistenceThrowsWhenEntityIdIsNull() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        InvoiceAuditLog toUpdate = new InvoiceAuditLog();

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID id = UUID.randomUUID();
        InvoiceAuditLog toUpdate = new InvoiceAuditLog();
        toUpdate.setId(id);
        InvoiceAuditLog persisted = new InvoiceAuditLog();
        persisted.setId(id);
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        persisted.setCompany(company);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        InvoiceAuditLog result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkUserSetsReferenceToNullWhenItHasNoId() {
        InvoiceAuditLog entity = new InvoiceAuditLog();
        entity.setUser(new User());

        validation.checkUser(entity);

        assertThat(entity.getUser()).isNull();
    }

    @Test
    void checkUserResolvesAnExistingUserById() {
        UUID userId = UUID.randomUUID();
        InvoiceAuditLog entity = new InvoiceAuditLog();
        entity.setUser(new User(userId));
        User resolved = new User(userId);
        when(userValidation.checkExist(userId)).thenReturn(resolved);

        validation.checkUser(entity);

        assertThat(entity.getUser()).isSameAs(resolved);
    }

    @Test
    void checkCompanyResolvesAnExistingCompanyById() {
        UUID companyId = UUID.randomUUID();
        InvoiceAuditLog entity = new InvoiceAuditLog();
        entity.setCompany(new Company(companyId));
        Company resolved = new Company(companyId);
        when(companyValidation.checkExist(companyId)).thenReturn(resolved);

        validation.checkCompany(entity);

        assertThat(entity.getCompany()).isSameAs(resolved);
    }
}
