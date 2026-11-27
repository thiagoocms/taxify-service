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

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_login", columnNames = "login"),
        indexes = @Index(name = "idx_users_email", columnList = "email"))
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

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "profile", length = 20, nullable = false)
    private UserProfileEnum profile;

    /**
     * Fixed access profiles (Fiscal, Financeiro...) of a non-ADMIN user - several add up; see
     * AccessProfile#permissionsOf. Ignored for ADMIN, who can do everything.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_access_profiles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "access_profile", length = 30, nullable = false)
    private Set<AccessProfile> accessProfiles = EnumSet.noneOf(AccessProfile.class);

    @OneToMany(mappedBy = "user" )
    private List<UserCompany> userCompanies;

    /**
     * Versão da sessão (SN-RN-11): vai no claim {@code sv} do JWT e sobe a cada troca ou redefinição de senha - um token
     * com outra versão responde 401 {@code error.auth.session.expired}.
     */
    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    /** Última troca ou redefinição de senha (horário de Recife); nula para quem nunca trocou. */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    /** Erros de senha atual na troca pelo próprio usuário dentro da janela (SN-RN-09). */
    @Column(name = "password_fail_count", nullable = false)
    private int passwordFailCount;

    /** Início da janela de 60 minutos do contador de erros (horário de Recife). */
    @Column(name = "password_fail_window_start")
    private LocalDateTime passwordFailWindowStart;

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

    public int getSessionVersion() {
        return sessionVersion;
    }

    public void setSessionVersion(int sessionVersion) {
        this.sessionVersion = sessionVersion;
    }

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public int getPasswordFailCount() {
        return passwordFailCount;
    }

    public void setPasswordFailCount(int passwordFailCount) {
        this.passwordFailCount = passwordFailCount;
    }

    public LocalDateTime getPasswordFailWindowStart() {
        return passwordFailWindowStart;
    }

    public void setPasswordFailWindowStart(LocalDateTime passwordFailWindowStart) {
        this.passwordFailWindowStart = passwordFailWindowStart;
    }

    /** What this user can do - every permission for ADMIN, the union of their profiles otherwise. */
    public Set<Permission> getPermissions() {
        return AccessProfile.permissionsOf(profile, accessProfiles);
    }
}
