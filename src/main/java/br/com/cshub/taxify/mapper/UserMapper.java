package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.user.UserDTO;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserMapper extends AbstractMapper<User, UserDTO> {

    private final DocumentMapper documentMapper;

    public UserMapper(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Override
    protected User toEntity(UserDTO source) {
        User user = new User();
        user.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        user.setName(source.name());
        user.setEmail(source.email());
        user.setLogin(source.login());
        user.setPassword(source.password());
        user.setDocument(documentMapper.toEntity(source.documentType(), source.documentNumber()));
        user.setProfile(UserProfileEnum.valueOf(source.profile()));
        user.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return user;
    }

    @Override
    protected UserDTO toModel(User source) {
        return new UserDTO(
                source.getId().toString(),
                source.getName(),
                source.getDocument().getNumber(),
                source.getDocument().getType().toString(),
                source.getLogin(),
                source.getPassword(),
                source.getEmail(),
                source.getProfile().toString(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(User source, User target) {
        target.setName(source.getName());
        target.setEmail(source.getEmail());
        target.setLogin(source.getLogin());
        target.setPassword(source.getPassword());
        target.setProfile(source.getProfile());
        target.setDeleted(source.isDeleted());
    }
}
