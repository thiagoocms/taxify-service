package br.com.cshub.taxify.domain.fiscalrule;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The correct CFOP/NCM (and, optionally, tax classification - {@link ProductTaxRule}) for one product of one company, kept in taxify so already
 * downloaded/stored {@link br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem}s can be
 * checked against it (see {@link br.com.cshub.taxify.service.ProductFiscalDivergenceService})
 * to find notes that were issued with the wrong classification and need rectifying.
 */
@Entity
@Table(name = "product_fiscal_rules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "codigo_produto", "documento_fornecedor"}))
public class ProductFiscalRule extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "codigo_produto", length = 60, nullable = false)
    private String productCode;

    /**
     * CNPJ/CPF (digits) of the supplier this rule is for - on a received note the product code
     * is the supplier's. Null = the code's general rule ({@link ProductRules}).
     */
    @Column(name = "documento_fornecedor", length = 14)
    private String supplierDocument;

    @Column(name = "descricao_produto", length = 500)
    private String productDescription;

    @Column(name = "ncm_correto", length = 10)
    private String correctNcm;

    @Column(name = "cfop_correto", length = 10, nullable = false)
    private String correctCfop;

    @Column(name = "observacoes", length = 1000)
    private String notes;

    /** Null when no tax field is set - only CFOP/NCM are checked then. */
    @Embedded
    private ProductTaxRule taxRule;

    public ProductTaxRule getTaxRule() {
        return taxRule;
    }

    public void setTaxRule(ProductTaxRule taxRule) {
        this.taxRule = taxRule;
    }

    public ProductFiscalRule() {
    }

    public String getSupplierDocument() {
        return supplierDocument;
    }

    public void setSupplierDocument(String supplierDocument) {
        this.supplierDocument = supplierDocument;
    }

    public ProductFiscalRule(UUID id) {
        this.id = id;
    }

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

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductDescription() {
        return productDescription;
    }

    public void setProductDescription(String productDescription) {
        this.productDescription = productDescription;
    }

    public String getCorrectNcm() {
        return correctNcm;
    }

    public void setCorrectNcm(String correctNcm) {
        this.correctNcm = correctNcm;
    }

    public String getCorrectCfop() {
        return correctCfop;
    }

    public void setCorrectCfop(String correctCfop) {
        this.correctCfop = correctCfop;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
