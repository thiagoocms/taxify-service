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
    private String description;

    /** Digits; a prefix matches every NCM that starts with it. */
    @Column(name = "ncm", length = 10)
    private String ncm;

    @Column(name = "cest", length = 10)
    private String cest;

    @Column(name = "codigo_produto", length = 60)
    private String productCode;

    @Column(name = "cnpj_fornecedor", length = 14)
    private String supplierCnpj;

    @Column(name = "aliquota", precision = 9, scale = 4)
    private BigDecimal rate;

    @Column(name = "mva", precision = 9, scale = 4)
    private BigDecimal mva;

    @Column(name = "percentual", precision = 9, scale = 4)
    private BigDecimal percentage;

    /** AGREGACAO: the agregação of the base, in percent (20 = coefficient 1,20). */
    @Column(name = "agregacao", precision = 9, scale = 4)
    private BigDecimal aggregationRate;

    /** AGREGACAO: SUL_SUDESTE or DEMAIS (Norte, Nordeste, Centro-Oeste); null = any origin. */
    @Column(name = "regiao_origem", length = 20)
    private String originRegion;

    /** AGREGACAO: only for a supplier of the Simples (true), only for one that isn't (false); null = any. */
    @Column(name = "fornecedor_simples")
    private Boolean simplesSupplier;

    @Column(name = "formula", length = 500)
    private String formula;

    /**
     * FORMULA only: the standard treatment it takes the place of (SEM_ENCERRAMENTO, ST, MERCADORIA,
     * CARGA_LIQUIDA, USO_CONSUMO - see FronteiraCalculator.Treatment); blank = any of them.
     */
    @Column(name = "aplica_em", length = 30)
    private String appliesTo;

    @Column(name = "fundamento", length = 500)
    private String legalBasis;

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
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getNcm() { return ncm; }
    public void setNcm(String ncm) { this.ncm = ncm; }
    public String getCest() { return cest; }
    public void setCest(String cest) { this.cest = cest; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getSupplierCnpj() { return supplierCnpj; }
    public void setSupplierCnpj(String supplierCnpj) { this.supplierCnpj = supplierCnpj; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public BigDecimal getMva() { return mva; }
    public void setMva(BigDecimal mva) { this.mva = mva; }
    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
    public BigDecimal getAggregationRate() { return aggregationRate; }
    public void setAggregationRate(BigDecimal aggregationRate) { this.aggregationRate = aggregationRate; }
    public String getOriginRegion() { return originRegion; }
    public void setOriginRegion(String originRegion) { this.originRegion = originRegion; }
    public Boolean getSimplesSupplier() { return simplesSupplier; }
    public void setSimplesSupplier(Boolean simplesSupplier) { this.simplesSupplier = simplesSupplier; }
    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }
    public String getAppliesTo() { return appliesTo; }
    public void setAppliesTo(String appliesTo) { this.appliesTo = appliesTo; }
    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String legalBasis) { this.legalBasis = legalBasis; }
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
