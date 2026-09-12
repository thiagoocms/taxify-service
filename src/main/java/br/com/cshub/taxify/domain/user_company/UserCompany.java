package br.com.cshub.taxify.domain.user_company;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import jakarta.persistence.*;

@Entity
@Table(name = "users_companies")
public class UserCompany extends AbstractAuditingEntity {

    @EmbeddedId
    private UserCompanyId id = new UserCompanyId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("companyId")
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "role", length = 50, nullable = false)
    private String role;

    public UserCompany() {
    }

    public UserCompany(User user, Company company, String role) {
        this.user = user;
        this.company = company;
        this.role = role;
        this.id = new UserCompanyId(user.getId(), company.getId());
    }

    // Getters e Setters
    public UserCompanyId getId() {
        return id;
    }

    public void setId(UserCompanyId id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
        this.id.setUserId(user != null ? user.getId() : null);
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
        this.id.setCompanyId(company != null ? company.getId() : null);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

}
