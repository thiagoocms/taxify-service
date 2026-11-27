package br.com.cshub.taxify.domain.fronteira;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fronteira PE: the e-Fisco extract of the ICMS antecipado of one company and fiscal period, as imported
 * (header, footer totals and its notes). Importing the same period again replaces it.
 */
@Entity
@Table(name = "efisco_extracts",
        uniqueConstraints = @UniqueConstraint(name = "uk_efisco_extracts_company_periodo", columnNames = {"company_id", "periodo"}))
public class EfiscoExtract extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** "2026-11". */
    @Column(name = "periodo", length = 7, nullable = false)
    private String period;

    @Column(name = "numero_extrato", length = 30)
    private String extractNumber;

    @Column(name = "inscricao_estadual", length = 30)
    private String stateRegistration;

    @Column(name = "razao_social")
    private String legalName;

    @Column(name = "are", length = 120)
    private String are;

    @Column(name = "codigo_receita", length = 20)
    private String revenueCode;

    @Column(name = "credenciamento_antecipacao", length = 20)
    private String credenciamentoAntecipacao;

    @Column(name = "credenciamento_interno")
    private String credenciamentoInterno;

    @Column(name = "data_vencimento")
    private LocalDate dueDate;

    @Column(name = "emitido_em")
    private LocalDateTime issuedAt;

    @Column(name = "icms_a_recolher", precision = 19, scale = 2)
    private BigDecimal icmsARecolher;

    @Column(name = "icms_pago", precision = 19, scale = 2)
    private BigDecimal icmsPago;

    @Column(name = "icms_em_processo", precision = 19, scale = 2)
    private BigDecimal icmsEmProcesso;

    @Column(name = "icms_autuado", precision = 19, scale = 2)
    private BigDecimal icmsAutuado;

    @Column(name = "icms_mandado_seguranca", precision = 19, scale = 2)
    private BigDecimal icmsMandadoSeguranca;

    @Column(name = "icms_anistiado", precision = 19, scale = 2)
    private BigDecimal icmsAnistiado;

    @Column(name = "icms_reg_debito", precision = 19, scale = 2)
    private BigDecimal icmsRegDebito;

    @Column(name = "file_name")
    private String fileName;

    /** Where the extract's PDF is kept (FiscalXmlFileStorage) - null for extracts imported before it was kept. */
    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "imported_by", length = 50, nullable = false)
    private String importedBy;

    @OneToMany(mappedBy = "extract", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<EfiscoExtractNote> notes = new ArrayList<>();

    /** Swaps the notes for a new import's (the old rows are removed with the flush). */
    public void replaceNotes(List<EfiscoExtractNote> replacement) {
        notes.clear();
        for (EfiscoExtractNote note : replacement) {
            note.setExtract(this);
            notes.add(note);
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getExtractNumber() { return extractNumber; }
    public void setExtractNumber(String extractNumber) { this.extractNumber = extractNumber; }
    public String getStateRegistration() { return stateRegistration; }
    public void setStateRegistration(String stateRegistration) { this.stateRegistration = stateRegistration; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getAre() { return are; }
    public void setAre(String are) { this.are = are; }
    public String getRevenueCode() { return revenueCode; }
    public void setRevenueCode(String revenueCode) { this.revenueCode = revenueCode; }
    public String getCredenciamentoAntecipacao() { return credenciamentoAntecipacao; }
    public void setCredenciamentoAntecipacao(String credenciamentoAntecipacao) { this.credenciamentoAntecipacao = credenciamentoAntecipacao; }
    public String getCredenciamentoInterno() { return credenciamentoInterno; }
    public void setCredenciamentoInterno(String credenciamentoInterno) { this.credenciamentoInterno = credenciamentoInterno; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public BigDecimal getIcmsARecolher() { return icmsARecolher; }
    public void setIcmsARecolher(BigDecimal icmsARecolher) { this.icmsARecolher = icmsARecolher; }
    public BigDecimal getIcmsPago() { return icmsPago; }
    public void setIcmsPago(BigDecimal icmsPago) { this.icmsPago = icmsPago; }
    public BigDecimal getIcmsEmProcesso() { return icmsEmProcesso; }
    public void setIcmsEmProcesso(BigDecimal icmsEmProcesso) { this.icmsEmProcesso = icmsEmProcesso; }
    public BigDecimal getIcmsAutuado() { return icmsAutuado; }
    public void setIcmsAutuado(BigDecimal icmsAutuado) { this.icmsAutuado = icmsAutuado; }
    public BigDecimal getIcmsMandadoSeguranca() { return icmsMandadoSeguranca; }
    public void setIcmsMandadoSeguranca(BigDecimal icmsMandadoSeguranca) { this.icmsMandadoSeguranca = icmsMandadoSeguranca; }
    public BigDecimal getIcmsAnistiado() { return icmsAnistiado; }
    public void setIcmsAnistiado(BigDecimal icmsAnistiado) { this.icmsAnistiado = icmsAnistiado; }
    public BigDecimal getIcmsRegDebito() { return icmsRegDebito; }
    public void setIcmsRegDebito(BigDecimal icmsRegDebito) { this.icmsRegDebito = icmsRegDebito; }
    public String getFileName() { return fileName; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
    public List<EfiscoExtractNote> getNotes() { return notes; }
}
