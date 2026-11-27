package br.com.cshub.taxify.domain.fronteira;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * e-Fisco's "Memória de Cálculo" of one note of a company, by the extract's recordNumber - the text as e-Fisco shows it
 * (EfiscoMemoryParser reads the numbers from it when the screen asks). One per recordNumber; fetching again replaces it.
 */
@Entity
@Table(name = "efisco_memories", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "registro"}))
public class EfiscoMemory extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "registro", length = 30, nullable = false)
    private String recordNumber;

    @Column(name = "chave_acesso", length = 44)
    private String accessKey;

    @Column(name = "texto", columnDefinition = "LONGTEXT", nullable = false)
    private String text;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    public UUID getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getRecordNumber() { return recordNumber; }
    public void setRecordNumber(String recordNumber) { this.recordNumber = recordNumber; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
}
