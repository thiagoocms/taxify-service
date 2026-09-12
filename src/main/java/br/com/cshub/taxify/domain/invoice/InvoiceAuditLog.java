package br.com.cshub.taxify.domain.invoice;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "invoice_audit_log")
public class InvoiceAuditLog extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "external_invoice_key", length = 100, nullable = false)
    private String externalInvoiceKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status", nullable = false)
    private InvoiceAuditStatus status;

    @Column(name = "notes", length = 500, nullable = false)
    private String notes;

    public InvoiceAuditLog() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getExternalInvoiceKey() {
        return externalInvoiceKey;
    }

    public void setExternalInvoiceKey(String externalInvoiceKey) {
        this.externalInvoiceKey = externalInvoiceKey;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public InvoiceAuditStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceAuditStatus status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
