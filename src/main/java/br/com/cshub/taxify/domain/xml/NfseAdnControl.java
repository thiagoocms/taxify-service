package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Where the NFS-e capture from the Ambiente Nacional (ADN) stands for one company: the last NSU
 * read (the next run continues from it), when to ask again and how the last run went.
 */
@Entity
@Table(name = "nfse_adn_control", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
public class NfseAdnControl extends AbstractAuditingEntity {

    public enum Status { OK, SEM_MOVIMENTO, ERRO }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "ult_nsu", nullable = false)
    private long ultNsu;

    @Column(name = "next_query_at")
    private LocalDateTime nextQueryAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 20)
    private Status lastStatus;

    @Column(name = "last_detail", length = 500)
    private String lastDetail;

    @Column(name = "last_new_notes")
    private Integer lastNewNotes;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public long getUltNsu() { return ultNsu; }
    public void setUltNsu(long ultNsu) { this.ultNsu = ultNsu; }
    public LocalDateTime getNextQueryAt() { return nextQueryAt; }
    public void setNextQueryAt(LocalDateTime nextQueryAt) { this.nextQueryAt = nextQueryAt; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public Status getLastStatus() { return lastStatus; }
    public void setLastStatus(Status lastStatus) { this.lastStatus = lastStatus; }
    public String getLastDetail() { return lastDetail; }
    public void setLastDetail(String lastDetail) { this.lastDetail = lastDetail; }
    public Integer getLastNewNotes() { return lastNewNotes; }
    public void setLastNewNotes(Integer lastNewNotes) { this.lastNewNotes = lastNewNotes; }
}
