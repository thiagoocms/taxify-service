package br.com.cshub.taxify.domain.fronteira;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One note of an e-Fisco extract, as it came: the extract has no access key, so the Taxify note is
 * found on reading by issuer CNPJ + number (it may only arrive after the import).
 */
@Entity
@Table(name = "efisco_extract_notes", indexes = @Index(name = "idx_efisco_extract_notes_extract", columnList = "extract_id"))
public class EfiscoExtractNote extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extract_id", nullable = false)
    private EfiscoExtract extract;

    @Column(name = "linha", nullable = false)
    private int lineNumber;

    @Column(name = "registro", length = 40)
    private String recordNumber;

    @Column(name = "data_passagem", length = 20)
    private String crossingDate;

    /** Without leading zeros, as nNF is stored. */
    @Column(name = "numero_nota", length = 20, nullable = false)
    private String invoiceNumber;

    @Column(name = "uf", length = 2)
    private String uf;

    @Column(name = "valor_nota", precision = 19, scale = 2, nullable = false)
    private BigDecimal invoiceAmount;

    @Column(name = "icms_devido", precision = 19, scale = 2, nullable = false)
    private BigDecimal icmsDue;

    /** Digits only (CNPJ, or CPF of a rural producer). */
    @Column(name = "cnpj_emitente", length = 14, nullable = false)
    private String issuerCnpj;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public EfiscoExtract getExtract() { return extract; }
    public void setExtract(EfiscoExtract extract) { this.extract = extract; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public String getRecordNumber() { return recordNumber; }
    public void setRecordNumber(String recordNumber) { this.recordNumber = recordNumber; }
    public String getCrossingDate() { return crossingDate; }
    public void setCrossingDate(String crossingDate) { this.crossingDate = crossingDate; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public String getUf() { return uf; }
    public void setUf(String uf) { this.uf = uf; }
    public BigDecimal getInvoiceAmount() { return invoiceAmount; }
    public void setInvoiceAmount(BigDecimal invoiceAmount) { this.invoiceAmount = invoiceAmount; }
    public BigDecimal getIcmsDue() { return icmsDue; }
    public void setIcmsDue(BigDecimal icmsDue) { this.icmsDue = icmsDue; }
    public String getIssuerCnpj() { return issuerCnpj; }
    public void setIssuerCnpj(String issuerCnpj) { this.issuerCnpj = issuerCnpj; }
}
