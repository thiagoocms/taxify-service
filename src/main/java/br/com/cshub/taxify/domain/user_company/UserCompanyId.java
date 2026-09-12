package br.com.cshub.taxify.domain.user_company;

import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserCompanyId implements Serializable {

    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID companyId;

    public UserCompanyId() {}

    public UserCompanyId(UUID userId, UUID companyId) {
        this.userId = userId;
        this.companyId = companyId;
    }

    public UUID getUserId() {
        return userId;
    }
    public void setUserId(UUID userId) {
        this.userId = userId;
    }
    public UUID getCompanyId() {
        return companyId;
    }
    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserCompanyId that = (UserCompanyId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(companyId, that.companyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, companyId);
    }


    public int compareTo(UserCompanyId id) {
        if (this.userId.equals(id.userId)) {
            return this.companyId.compareTo(id.companyId);
        } else {
            return this.userId.compareTo(id.userId);
        }
    }
}
