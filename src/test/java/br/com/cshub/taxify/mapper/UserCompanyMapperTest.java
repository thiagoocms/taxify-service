package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.dto.user_company.UserCompanyDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserCompanyMapperTest {

    private final UserCompanyMapper mapper = new UserCompanyMapper();

    @Test
    void toEntityBuildsReferenceOnlyUserAndCompany() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UserCompanyDTO dto = new UserCompanyDTO(userId.toString(), companyId.toString(), "ADMIN", true);

        UserCompany entity = mapper.toEntity(dto);

        assertThat(entity.getUser().getId()).isEqualTo(userId);
        assertThat(entity.getCompany().getId()).isEqualTo(companyId);
        assertThat(entity.getRole()).isEqualTo("ADMIN");
        assertThat(entity.isDeleted()).isTrue();
    }

    @Test
    void toEntityLeavesUserAndCompanyNullWhenIdsAreNull() {
        UserCompanyDTO dto = new UserCompanyDTO(null, null, "ADMIN", null);

        UserCompany entity = mapper.toEntity(dto);

        assertThat(entity.getUser()).isNull();
        assertThat(entity.getCompany()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void toModelExposesUserAndCompanyIdsAsStrings() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UserCompany entity = new UserCompany(new User(userId), new Company(companyId), "MEMBER");

        UserCompanyDTO dto = mapper.toModel(entity);

        assertThat(dto.userId()).isEqualTo(userId.toString());
        assertThat(dto.companyId()).isEqualTo(companyId.toString());
        assertThat(dto.role()).isEqualTo("MEMBER");
    }

    @Test
    void mergeIsANoOp() {
        UserCompany source = new UserCompany(new User(UUID.randomUUID()), new Company(UUID.randomUUID()), "ADMIN");
        UserCompany target = new UserCompany(new User(UUID.randomUUID()), new Company(UUID.randomUUID()), "MEMBER");

        mapper.merge(source, target);

        assertThat(target.getRole()).isEqualTo("MEMBER");
    }
}
