package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyMapperTest {

    private final CompanyMapper mapper = new CompanyMapper(new DocumentMapper());

    @Test
    void toEntityParsesIdAndBuildsDocumentFromRawFields() {
        UUID id = UUID.randomUUID();
        CompanyDTO dto = new CompanyDTO(id.toString(), "12345678000199", "CNPJ", "Acme", true);

        Company entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getName()).isEqualTo("Acme");
        assertThat(entity.getDocument().getNumber()).isEqualTo("12345678000199");
        assertThat(entity.getDocument().getType()).isEqualTo(DocumentTypeEnum.CNPJ);
        assertThat(entity.isDeleted()).isTrue();
    }

    @Test
    void toEntityLeavesIdNullWhenDtoIdIsNull() {
        CompanyDTO dto = new CompanyDTO(null, "12345678000199", "CNPJ", "Acme", null);

        Company entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void toModelExposesIdAndDocumentAsPlainStrings() {
        UUID id = UUID.randomUUID();
        Company entity = new Company(id);
        entity.setName("Acme");
        entity.setDocument(new Document("12345678000199", DocumentTypeEnum.CNPJ));
        entity.setDeleted(false);

        CompanyDTO dto = mapper.toModel(entity);

        assertThat(dto.id()).isEqualTo(id.toString());
        assertThat(dto.documentNumber()).isEqualTo("12345678000199");
        assertThat(dto.documentType()).isEqualTo("CNPJ");
        assertThat(dto.name()).isEqualTo("Acme");
        assertThat(dto.deleted()).isFalse();
    }

    @Test
    void mergeOnlyCopiesNameAndDeletedLeavingDocumentAndIdUntouched() {
        Company source = new Company();
        source.setName("New Name");
        source.setDeleted(true);

        Company target = new Company(UUID.randomUUID());
        Document originalDocument = new Document("12345678000199", DocumentTypeEnum.CNPJ);
        target.setDocument(originalDocument);
        target.setName("Old Name");
        target.setDeleted(false);

        mapper.merge(source, target);

        assertThat(target.getName()).isEqualTo("New Name");
        assertThat(target.isDeleted()).isTrue();
        assertThat(target.getDocument()).isEqualTo(originalDocument);
    }
}
