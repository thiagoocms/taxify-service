package br.com.cshub.taxify.domain.fiscalrule;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The correct CFOP/NCM for one product of one company, kept in taxify so already
 * downloaded/stored {@link br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem}s can be
 * checked against it (see {@link br.com.cshub.taxify.service.ProductFiscalDivergenceService})
 * to find notes that were issued with the wrong classification and need rectifying.
 */
@Entity
@Table(name = "product_fiscal_rules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "codigo_produto"}))
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
    private String codigoProduto;

    @Column(name = "descricao_produto", length = 500)
    private String descricaoProduto;

    @Column(name = "ncm_correto", length = 10)
    private String ncmCorreto;

    @Column(name = "cfop_correto", length = 10, nullable = false)
    private String cfopCorreto;

    @Column(name = "observacoes", length = 1000)
    private String observacoes;

    public ProductFiscalRule() {
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

    public String getCodigoProduto() {
        return codigoProduto;
    }

    public void setCodigoProduto(String codigoProduto) {
        this.codigoProduto = codigoProduto;
    }

    public String getDescricaoProduto() {
        return descricaoProduto;
    }

    public void setDescricaoProduto(String descricaoProduto) {
        this.descricaoProduto = descricaoProduto;
    }

    public String getNcmCorreto() {
        return ncmCorreto;
    }

    public void setNcmCorreto(String ncmCorreto) {
        this.ncmCorreto = ncmCorreto;
    }

    public String getCfopCorreto() {
        return cfopCorreto;
    }

    public void setCfopCorreto(String cfopCorreto) {
        this.cfopCorreto = cfopCorreto;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
    }
}
