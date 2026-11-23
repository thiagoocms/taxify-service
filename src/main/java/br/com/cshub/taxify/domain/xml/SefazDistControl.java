package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Where the SEFAZ distribution (NFeDistribuicaoDFe) stands for one company: the last NSU
 * read, so the next run continues from there, when SEFAZ allows asking again (it wants an
 * hour between queries once everything was read - cStat 656 otherwise) and how the last
 * run went, for the screen.
 */
@Entity
@Table(name = "sefaz_dist_control", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
public class SefazDistControl extends AbstractAuditingEntity {

    public enum Status { OK, SEM_MOVIMENTO, ERRO }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "ult_nsu", nullable = false, length = 15)
    private String ultNsu = "000000000000000";

    @Column(name = "max_nsu", length = 15)
    private String maxNsu;

    @Column(name = "next_query_at")
    private LocalDateTime nextQueryAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 20)
    private Status lastStatus;

    @Column(name = "last_detail", length = 500)
    private String lastDetail;

    /** Notes (procNFe) stored by the last run. */
    @Column(name = "last_new_notes")
    private Integer lastNewNotes;

    /** Summaries (resNFe) seen by the last run - entries still waiting for "Ciência da Operação". */
    @Column(name = "last_summaries")
    private Integer lastSummaries;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getUltNsu() {
        return ultNsu;
    }

    public void setUltNsu(String ultNsu) {
        this.ultNsu = ultNsu;
    }

    public String getMaxNsu() {
        return maxNsu;
    }

    public void setMaxNsu(String maxNsu) {
        this.maxNsu = maxNsu;
    }

    public LocalDateTime getNextQueryAt() {
        return nextQueryAt;
    }

    public void setNextQueryAt(LocalDateTime nextQueryAt) {
        this.nextQueryAt = nextQueryAt;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public Status getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(Status lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastDetail() {
        return lastDetail;
    }

    public void setLastDetail(String lastDetail) {
        this.lastDetail = lastDetail;
    }

    public Integer getLastNewNotes() {
        return lastNewNotes;
    }

    public void setLastNewNotes(Integer lastNewNotes) {
        this.lastNewNotes = lastNewNotes;
    }

    public Integer getLastSummaries() {
        return lastSummaries;
    }

    public void setLastSummaries(Integer lastSummaries) {
        this.lastSummaries = lastSummaries;
    }
}
