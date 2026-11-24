package br.com.cshub.taxify.domain.fronteira;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the Fronteira PE table: a rate of the standard calculation (internal rate, ST MVA,
 * Simples percentage) or the accountant's own formula. Global, or of one company. What it applies
 * to is narrowed by NCM (prefix), CEST, product code and supplier - blank = any. Rates are kept in
 * percent (20.5 = 20,5%). A rule nobody has reviewed yet still calculates, but the result says
 * "a conferir".
 */
@Entity
@Table(name = "fronteira_rules")
public class FronteiraRule extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    /** Null = every company. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", length = 30, nullable = false)
    private FronteiraRuleKind kind;

    /** What it is; for SIMPLES_PERCENTUAL also the credenciamento it matches on the extract. */
    @Column(name = "descricao")
    private String descricao;

    /** Digits; a prefix matches every NCM that starts with it. */
    @Column(name = "ncm", length = 10)
    private String ncm;

    @Column(name = "cest", length = 10)
    private String cest;

    @Column(name = "codigo_produto", length = 60)
    private String codigoProduto;

    @Column(name = "cnpj_fornecedor", length = 14)
    private String cnpjFornecedor;

    @Column(name = "aliquota", precision = 9, scale = 4)
    private BigDecimal aliquota;

    @Column(name = "mva", precision = 9, scale = 4)
    private BigDecimal mva;

    @Column(name = "percentual", precision = 9, scale = 4)
    private BigDecimal percentual;

    @Column(name = "formula", length = 500)
    private String formula;

    /**
     * FORMULA only: the standard treatment it takes the place of (SEM_ENCERRAMENTO, ST, CARGA_LIQUIDA,
     * USO_CONSUMO - see FronteiraCalculator.Treatment); blank = any of them.
     */
    @Column(name = "aplica_em", length = 30)
    private String aplicaEm;

    @Column(name = "fundamento", length = 500)
    private String fundamento;

    @Column(name = "link", length = 500)
    private String link;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** In force on that day (no dates = always). */
    public boolean validOn(LocalDate day) {
        return day == null || ((validFrom == null || !day.isBefore(validFrom)) && (validUntil == null || !day.isAfter(validUntil)));
    }

    public boolean isReviewed() {
        return reviewedAt != null;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public FronteiraRuleKind getKind() { return kind; }
    public void setKind(FronteiraRuleKind kind) { this.kind = kind; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getNcm() { return ncm; }
    public void setNcm(String ncm) { this.ncm = ncm; }
    public String getCest() { return cest; }
    public void setCest(String cest) { this.cest = cest; }
    public String getCodigoProduto() { return codigoProduto; }
    public void setCodigoProduto(String codigoProduto) { this.codigoProduto = codigoProduto; }
    public String getCnpjFornecedor() { return cnpjFornecedor; }
    public void setCnpjFornecedor(String cnpjFornecedor) { this.cnpjFornecedor = cnpjFornecedor; }
    public BigDecimal getAliquota() { return aliquota; }
    public void setAliquota(BigDecimal aliquota) { this.aliquota = aliquota; }
    public BigDecimal getMva() { return mva; }
    public void setMva(BigDecimal mva) { this.mva = mva; }
    public BigDecimal getPercentual() { return percentual; }
    public void setPercentual(BigDecimal percentual) { this.percentual = percentual; }
    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }
    public String getAplicaEm() { return aplicaEm; }
    public void setAplicaEm(String aplicaEm) { this.aplicaEm = aplicaEm; }
    public String getFundamento() { return fundamento; }
    public void setFundamento(String fundamento) { this.fundamento = fundamento; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
