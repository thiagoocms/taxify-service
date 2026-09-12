package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.dto.document.DocumentDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentMapperTest {

    private final DocumentMapper mapper = new DocumentMapper();

    @Test
    void toEntityParsesIdWhenPresent() {
        UUID id = UUID.randomUUID();
        DocumentDTO dto = new DocumentDTO(id.toString(), "12345678909", DocumentTypeEnum.CPF, true);

        Document entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getNumber()).isEqualTo("12345678909");
        assertThat(entity.getType()).isEqualTo(DocumentTypeEnum.CPF);
        assertThat(entity.isDeleted()).isTrue();
    }

    @Test
    void toEntityLeavesIdNullWhenDtoIdIsNull() {
        DocumentDTO dto = new DocumentDTO(null, "12345678909", DocumentTypeEnum.CPF, false);

        assertThat(mapper.toEntity(dto).getId()).isNull();
    }

    @Test
    void twoArgOverloadBuildsATransientDocumentFromRawTypeAndNumber() {
        Document entity = mapper.toEntity("CNPJ", "12345678000199");

        assertThat(entity.getId()).isNull();
        assertThat(entity.getType()).isEqualTo(DocumentTypeEnum.CNPJ);
        assertThat(entity.getNumber()).isEqualTo("12345678000199");
    }

    @Test
    void toModelExposesAllFields() {
        UUID id = UUID.randomUUID();
        Document entity = new Document("12345678909", DocumentTypeEnum.CPF);
        entity.setId(id);
        entity.setDeleted(true);

        DocumentDTO dto = mapper.toModel(entity);

        assertThat(dto.id()).isEqualTo(id.toString());
        assertThat(dto.number()).isEqualTo("12345678909");
        assertThat(dto.type()).isEqualTo(DocumentTypeEnum.CPF);
        assertThat(dto.deleted()).isTrue();
    }

    @Test
    void mergeCopiesTypeNumberAndDeleted() {
        Document source = new Document("99988877766", DocumentTypeEnum.CPF);
        source.setDeleted(true);
        Document target = new Document("00000000000", DocumentTypeEnum.RG);
        target.setId(UUID.randomUUID());

        mapper.merge(source, target);

        assertThat(target.getNumber()).isEqualTo("99988877766");
        assertThat(target.getType()).isEqualTo(DocumentTypeEnum.CPF);
        assertThat(target.isDeleted()).isTrue();
    }
}
