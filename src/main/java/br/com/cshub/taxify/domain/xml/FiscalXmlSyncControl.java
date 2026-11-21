package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per company, tracking sync progress: how many NFe SIEG reports for the
 * current sync window ({@link #dataEmissaoInicio}/{@link #dataEmissaoFim}, always the
 * whole current month - see {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}),
 * updated by {@link br.com.cshub.taxify.integration.sieg.SiegClient#contarXmlsNfe},
 * against how many are actually persisted for that company. Updated on every
 * {@link br.com.cshub.taxify.service.FiscalXmlIntegrationService#syncFromSieg} call -
 * not written to when the contar-xmls check itself fails, so totalSieg only ever
 * reflects a real answer from SIEG, never a guess.
 */
@Entity
@Table(name = "fiscal_xml_sync_control", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
public class FiscalXmlSyncControl extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "data_emissao_inicio")
    private LocalDate dataEmissaoInicio;

    @Column(name = "data_emissao_fim")
    private LocalDate dataEmissaoFim;

    @Column(name = "total_sieg", nullable = false)
    private int totalSieg;

    @Column(name = "total_importado", nullable = false)
    private int totalImportado;

    @Column(name = "ultima_verificacao_em", nullable = false)
    private LocalDateTime ultimaVerificacaoEm;

    /*
     * The fields above track the RECEIVED side (SIEG "CNPJdest" - notes from suppliers); these
     * track the ISSUED side ("CNPJemit" - the company's own notes), counted and paged apart.
     * Nullable: rows created before outbound sync existed start empty.
     */
    @Column(name = "issued_total_sieg")
    private Integer issuedTotalSieg;

    @Column(name = "issued_total_imported")
    private Integer issuedTotalImported;

    @Column(name = "issued_checked_at")
    private LocalDateTime issuedCheckedAt;

    /** Last day the recent notes' events (cancelamentos...) were re-read, per side - see FiscalXmlSyncScheduler. */
    @Column(name = "received_events_checked_on")
    private LocalDate receivedEventsCheckedOn;

    @Column(name = "issued_events_checked_on")
    private LocalDate issuedEventsCheckedOn;

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

    public LocalDate getDataEmissaoInicio() {
        return dataEmissaoInicio;
    }

    public void setDataEmissaoInicio(LocalDate dataEmissaoInicio) {
        this.dataEmissaoInicio = dataEmissaoInicio;
    }

    public LocalDate getDataEmissaoFim() {
        return dataEmissaoFim;
    }

    public void setDataEmissaoFim(LocalDate dataEmissaoFim) {
        this.dataEmissaoFim = dataEmissaoFim;
    }

    public int getTotalSieg() {
        return totalSieg;
    }

    public void setTotalSieg(int totalSieg) {
        this.totalSieg = totalSieg;
    }

    public int getTotalImportado() {
        return totalImportado;
    }

    public void setTotalImportado(int totalImportado) {
        this.totalImportado = totalImportado;
    }

    public LocalDateTime getUltimaVerificacaoEm() {
        return ultimaVerificacaoEm;
    }

    public void setUltimaVerificacaoEm(LocalDateTime ultimaVerificacaoEm) {
        this.ultimaVerificacaoEm = ultimaVerificacaoEm;
    }

    public Integer getIssuedTotalSieg() {
        return issuedTotalSieg;
    }

    public void setIssuedTotalSieg(Integer issuedTotalSieg) {
        this.issuedTotalSieg = issuedTotalSieg;
    }

    public Integer getIssuedTotalImported() {
        return issuedTotalImported;
    }

    public void setIssuedTotalImported(Integer issuedTotalImported) {
        this.issuedTotalImported = issuedTotalImported;
    }

    public LocalDateTime getIssuedCheckedAt() {
        return issuedCheckedAt;
    }

    public void setIssuedCheckedAt(LocalDateTime issuedCheckedAt) {
        this.issuedCheckedAt = issuedCheckedAt;
    }

    public LocalDate getReceivedEventsCheckedOn() {
        return receivedEventsCheckedOn;
    }

    public void setReceivedEventsCheckedOn(LocalDate receivedEventsCheckedOn) {
        this.receivedEventsCheckedOn = receivedEventsCheckedOn;
    }

    public LocalDate getIssuedEventsCheckedOn() {
        return issuedEventsCheckedOn;
    }

    public void setIssuedEventsCheckedOn(LocalDate issuedEventsCheckedOn) {
        this.issuedEventsCheckedOn = issuedEventsCheckedOn;
    }
}
