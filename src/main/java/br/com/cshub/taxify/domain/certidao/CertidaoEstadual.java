package br.com.cshub.taxify.domain.certidao;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CertidaoSituacao;
import br.com.cshub.taxify.enumerated.CertidaoTipo;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Certidão SEFAZ-PE (M5): one state certidão of a company, as read from its PDF. The newest one (by validity) is the
 * one that counts; the older ones stay as history. The PDF is kept in the XMLs' storage, as the Fronteira extract.
 */
@Entity
@Table(name = "certidoes_estaduais",
        indexes = @Index(name = "idx_certidoes_estaduais_company_validade", columnList = "company_id, valida_ate"))
public class CertidaoEstadual extends AbstractAuditingEntity {

    /** The PDF sent by a user (issued by them on e-Fisco). */
    public static final String ORIGEM_UPLOAD = "UPLOAD";
    /** Issued by Taxify on e-Fisco's public page. */
    public static final String ORIGEM_EFISCO = "EFISCO";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "uf", length = 2, nullable = false)
    private String uf;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tipo", length = 10, nullable = false)
    private CertidaoTipo tipo;

    @Column(name = "numero", length = 60)
    private String numero;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "situacao", length = 30, nullable = false)
    private CertidaoSituacao situacao;

    @Column(name = "emitida_em")
    private LocalDate emitidaEm;

    @Column(name = "valida_ate")
    private LocalDate validaAte;

    /** The CNPJ (digits) the certidão was issued for. */
    @Column(name = "documento", length = 20)
    private String documento;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "origem", length = 20, nullable = false)
    private String origem;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "imported_by", length = 50, nullable = false)
    private String importedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getUf() { return uf; }
    public void setUf(String uf) { this.uf = uf; }
    public CertidaoTipo getTipo() { return tipo; }
    public void setTipo(CertidaoTipo tipo) { this.tipo = tipo; }
    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }
    public CertidaoSituacao getSituacao() { return situacao; }
    public void setSituacao(CertidaoSituacao situacao) { this.situacao = situacao; }
    public LocalDate getEmitidaEm() { return emitidaEm; }
    public void setEmitidaEm(LocalDate emitidaEm) { this.emitidaEm = emitidaEm; }
    public LocalDate getValidaAte() { return validaAte; }
    public void setValidaAte(LocalDate validaAte) { this.validaAte = validaAte; }
    public String getDocumento() { return documento; }
    public void setDocumento(String documento) { this.documento = documento; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getOrigem() { return origem; }
    public void setOrigem(String origem) { this.origem = origem; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
}
