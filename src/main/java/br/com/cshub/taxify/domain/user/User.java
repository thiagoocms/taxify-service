package br.com.cshub.taxify.domain.user;


import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.enumerated.AccessProfile;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    /**
     * The accounting firm this team member belongs to - a public signup (no authenticated
     * caller) creates a brand-new firm; an authenticated caller creating a teammate joins
     * them to their own firm instead (see UserValidation).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_firm_id", nullable = false)
    private AccountingFirm accountingFirm;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @OneToOne
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "login", length = 50, nullable = false)
    private String login;

    @Column(name = "password", length = 255, nullable = false)
    private String password;

    @Column(name = "email", length = 300, nullable = false)
    private String email;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "profile", nullable = false)
    private UserProfileEnum profile;

    /**
     * Fixed access profiles (Fiscal, Financeiro...) of a non-ADMIN user - several add up; see
     * AccessProfile#permissionsOf. Ignored for ADMIN, who can do everything.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_access_profiles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "access_profile", length = 30, nullable = false)
    private Set<AccessProfile> accessProfiles = EnumSet.noneOf(AccessProfile.class);

    @OneToMany(mappedBy = "user" )
    private List<UserCompany> userCompanies;

    public User() {
    }

    public User(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UserProfileEnum getProfile() {
        return profile;
    }

    public void setProfile(UserProfileEnum profile) {
        this.profile = profile;
    }

    public List<UserCompany> getUserCompanies() {
        return userCompanies;
    }

    public void setUserCompanies(List<UserCompany> userCompanies) {
        this.userCompanies = userCompanies;
    }

    public AccountingFirm getAccountingFirm() {
        return accountingFirm;
    }

    public void setAccountingFirm(AccountingFirm accountingFirm) {
        this.accountingFirm = accountingFirm;
    }

    public Set<AccessProfile> getAccessProfiles() {
        return accessProfiles;
    }

    public void setAccessProfiles(Set<AccessProfile> accessProfiles) {
        this.accessProfiles = accessProfiles == null || accessProfiles.isEmpty()
                ? EnumSet.noneOf(AccessProfile.class)
                : EnumSet.copyOf(accessProfiles);
    }

    /** What this user can do - every permission for ADMIN, the union of their profiles otherwise. */
    public Set<Permission> getPermissions() {
        return AccessProfile.permissionsOf(profile, accessProfiles);
    }
}
