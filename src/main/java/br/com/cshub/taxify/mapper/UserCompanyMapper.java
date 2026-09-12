package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.dto.user_company.UserCompanyDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserCompanyMapper extends AbstractMapper<UserCompany, UserCompanyDTO> {

    @Override
    protected UserCompany toEntity(UserCompanyDTO source) {
        UserCompany entity = new UserCompany();
        entity.setUser(source.userId() != null ? new User(UUID.fromString(source.userId())) : null);
        entity.setCompany(source.companyId() != null ? new Company(UUID.fromString(source.companyId())) : null);
        entity.setRole(source.role());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected UserCompanyDTO toModel(UserCompany source) {
        return new UserCompanyDTO(
                source.getUser().getId().toString(),
                source.getCompany().getId().toString(),
                source.getRole(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(UserCompany source, UserCompany target) {

    }

}
