package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.user.UserDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper(new DocumentMapper());

    @Test
    void toEntityParsesEnumsAndDelegatesDocumentCreation() {
        UUID id = UUID.randomUUID();
        UserDTO dto = new UserDTO(id.toString(), "Jane", "12345678909", "CPF", "jane", "secret", "jane@acme.com", "ADMIN", true);

        User entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getName()).isEqualTo("Jane");
        assertThat(entity.getLogin()).isEqualTo("jane");
        assertThat(entity.getPassword()).isEqualTo("secret");
        assertThat(entity.getEmail()).isEqualTo("jane@acme.com");
        assertThat(entity.getProfile()).isEqualTo(UserProfileEnum.ADMIN);
        assertThat(entity.getDocument().getType()).isEqualTo(DocumentTypeEnum.CPF);
        assertThat(entity.getDocument().getNumber()).isEqualTo("12345678909");
        assertThat(entity.isDeleted()).isTrue();
    }

    @Test
    void toEntityRejectsAnUnknownProfile() {
        UserDTO dto = new UserDTO(null, "Jane", "12345678909", "CPF", "jane", "secret", "jane@acme.com", "NOT_A_PROFILE", false);

        assertThatThrownBy(() -> mapper.toEntity(dto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toModelExposesEnumsAsPlainStrings() {
        User entity = new User(UUID.randomUUID());
        entity.setName("Jane");
        entity.setLogin("jane");
        entity.setPassword("secret");
        entity.setEmail("jane@acme.com");
        entity.setProfile(UserProfileEnum.EMPLOYEE);
        entity.setDocument(new Document("12345678909", DocumentTypeEnum.CPF));
        entity.setDeleted(false);

        UserDTO dto = mapper.toModel(entity);

        assertThat(dto.profile()).isEqualTo("EMPLOYEE");
        assertThat(dto.documentType()).isEqualTo("CPF");
        assertThat(dto.documentNumber()).isEqualTo("12345678909");
        assertThat(dto.login()).isEqualTo("jane");
    }

    @Test
    void mergeCopiesEditableFieldsButNotDocumentOrId() {
        User source = new User();
        source.setName("New Name");
        source.setEmail("new@acme.com");
        source.setLogin("newlogin");
        source.setPassword("newpass");
        source.setProfile(UserProfileEnum.ADMIN);
        source.setDeleted(true);

        User target = new User(UUID.randomUUID());
        Document originalDocument = new Document("12345678909", DocumentTypeEnum.CPF);
        target.setDocument(originalDocument);
        target.setProfile(UserProfileEnum.USER);

        mapper.merge(source, target);

        assertThat(target.getName()).isEqualTo("New Name");
        assertThat(target.getEmail()).isEqualTo("new@acme.com");
        assertThat(target.getLogin()).isEqualTo("newlogin");
        assertThat(target.getPassword()).isEqualTo("newpass");
        assertThat(target.getProfile()).isEqualTo(UserProfileEnum.ADMIN);
        assertThat(target.isDeleted()).isTrue();
        assertThat(target.getDocument()).isEqualTo(originalDocument);
    }
}
