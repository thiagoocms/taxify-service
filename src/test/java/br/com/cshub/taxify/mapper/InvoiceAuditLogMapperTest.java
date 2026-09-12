package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceAuditLogMapperTest {

    private final InvoiceAuditLogMapper mapper = new InvoiceAuditLogMapper();

    @Test
    void toEntityBuildsReferenceOnlyUserAndCompanyAndParsesStatus() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        InvoiceAuditLogDTO dto = new InvoiceAuditLogDTO(UUID.randomUUID().toString(), "NFE-1", userId.toString(), companyId.toString(), "OK", "all good", true);

        InvoiceAuditLog entity = mapper.toEntity(dto);

        assertThat(entity.getUser().getId()).isEqualTo(userId);
        assertThat(entity.getCompany().getId()).isEqualTo(companyId);
        assertThat(entity.getExternalInvoiceKey()).isEqualTo("NFE-1");
        assertThat(entity.getStatus()).isEqualTo(InvoiceAuditStatus.OK);
        assertThat(entity.getNotes()).isEqualTo("all good");
        assertThat(entity.isDeleted()).isTrue();
    }

    // Documents a real gap in InvoiceAuditLogMapper#toEntity: unlike every other mapper
    // in the codebase, it never calls entity.setId(...). This means the update flow
    // (which round-trips the DTO's id through checkUpdateConsistence) always sees a
    // null id here and would fail its id-consistency check.
    @Test
    void toEntityCurrentlyDoesNotPropagateTheDtoId() {
        InvoiceAuditLogDTO dto = new InvoiceAuditLogDTO(UUID.randomUUID().toString(), "NFE-1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "OK", "notes", false);

        InvoiceAuditLog entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isNull();
    }

    @Test
    void toEntityRejectsAnUnknownStatus() {
        InvoiceAuditLogDTO dto = new InvoiceAuditLogDTO(null, "NFE-1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "NOT_A_STATUS", "notes", false);

        assertThatThrownBy(() -> mapper.toEntity(dto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toModelExposesStatusUserAndCompanyIds() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        InvoiceAuditLog entity = new InvoiceAuditLog();
        entity.setId(id);
        entity.setUser(new User(userId));
        entity.setCompany(new Company(companyId));
        entity.setExternalInvoiceKey("NFE-1");
        entity.setStatus(InvoiceAuditStatus.NEEDS_RECTIFICATION);
        entity.setNotes("please fix");

        InvoiceAuditLogDTO dto = mapper.toModel(entity);

        assertThat(dto.id()).isEqualTo(id.toString());
        assertThat(dto.userId()).isEqualTo(userId.toString());
        assertThat(dto.companyId()).isEqualTo(companyId.toString());
        assertThat(dto.status()).isEqualTo("NEEDS_RECTIFICATION");
        assertThat(dto.notes()).isEqualTo("please fix");
    }

    @Test
    void mergeIsANoOp() {
        InvoiceAuditLog source = new InvoiceAuditLog();
        source.setNotes("new notes");
        InvoiceAuditLog target = new InvoiceAuditLog();
        target.setNotes("old notes");

        mapper.merge(source, target);

        assertThat(target.getNotes()).isEqualTo("old notes");
    }
}
