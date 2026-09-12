package br.com.cshub.taxify.domain.accountingfirm;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The tenant boundary: every {@link br.com.cshub.taxify.domain.user.User} and
 * {@link br.com.cshub.taxify.domain.company.Company} belongs to exactly one accounting
 * firm, and TenantAccessService uses that to make sure one firm's team never sees another
 * firm's data.
 */
@Entity
@Table(name = "accounting_firms")
public class AccountingFirm extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    /**
     * This firm's own SIEG "X-API-Key" (Minha Conta > Integrações API SIEG) - null until
     * the firm configures it, at which point calls to SIEG for this firm fail clearly
     * (see SiegClient) instead of silently using someone else's key.
     */
    @Column(name = "sieg_api_key", length = 255)
    private String siegApiKey;

    public AccountingFirm() {
    }

    public AccountingFirm(UUID id) {
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

    public String getSiegApiKey() {
        return siegApiKey;
    }

    public void setSiegApiKey(String siegApiKey) {
        this.siegApiKey = siegApiKey;
    }
}
