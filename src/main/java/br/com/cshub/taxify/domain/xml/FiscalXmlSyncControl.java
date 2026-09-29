package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FiscalXmlDirection;
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
 *
 * <p>One row per company AND {@link FiscalXmlDirection}: SIEG counts/paginates entrada
 * (CNPJdest) and saída (CNPJemit) as two separate queries, so totalSieg, totalImportado
 * (used as SIEG's skip) and ultimaVerificacaoEm only make sense per direction.
 */
@Entity
@Table(name = "fiscal_xml_sync_control", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "direcao"}))
public class FiscalXmlSyncControl extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "direcao", length = 10)
    private FiscalXmlDirection direcao = FiscalXmlDirection.ENTRADA;

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

    public FiscalXmlDirection getDirecao() {
        return direcao;
    }

    public void setDirecao(FiscalXmlDirection direcao) {
        this.direcao = direcao;
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
}
