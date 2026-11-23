package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A service invoice (NFS-e) of one company - provided (PRESTADA) or taken (TOMADA) - in the
 * national or the ABRASF layout. Kept apart from fiscal_xml_documents: different fields, no
 * items, and it feeds the retentions (M1) rather than the goods checks.
 */
@Entity
@Table(name = "service_invoices",
        uniqueConstraints = @UniqueConstraint(name = "uk_service_invoices_company_key", columnNames = {"company_id", "access_key"}),
        indexes = @Index(name = "idx_service_invoices_company_issue", columnList = "company_id, issue_date"))
public class ServiceInvoice extends AbstractAuditingEntity {

    public static final String PRESTADA = "PRESTADA";
    public static final String TOMADA = "TOMADA";
    /** The company is neither provider nor taker (an intermediary, or a file of another CNPJ). */
    public static final String TERCEIRO = "TERCEIRO";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** National: the 50-digit key; ABRASF: "ABRASF-city-provider-number". */
    @Column(name = "access_key", length = 80, nullable = false)
    private String accessKey;

    @Column(name = "layout", length = 10, nullable = false)
    private String layout;

    @Column(name = "numero", length = 20)
    private String numero;

    @Column(name = "codigo_verificacao", length = 60)
    private String codigoVerificacao;

    @Column(name = "issue_date")
    private LocalDateTime issueDate;

    @Column(name = "competence")
    private LocalDate competence;

    @Column(name = "direction", length = 10, nullable = false)
    private String direction;

    @Column(name = "prestador_documento", length = 14)
    private String prestadorDocumento;

    @Column(name = "prestador_nome", length = 150)
    private String prestadorNome;

    @Column(name = "prestador_municipio", length = 7)
    private String prestadorMunicipio;

    @Column(name = "tomador_documento", length = 14)
    private String tomadorDocumento;

    @Column(name = "tomador_nome", length = 150)
    private String tomadorNome;

    @Column(name = "municipio_incidencia", length = 7)
    private String municipioIncidencia;

    @Column(name = "codigo_servico", length = 20)
    private String codigoServico;

    @Column(name = "codigo_tributacao_municipal", length = 30)
    private String codigoTributacaoMunicipal;

    @Column(name = "descricao_servico", length = 1000)
    private String descricaoServico;

    @Column(name = "valor_servico", precision = 15, scale = 2)
    private BigDecimal valorServico;

    @Column(name = "valor_deducoes", precision = 15, scale = 2)
    private BigDecimal valorDeducoes;

    @Column(name = "base_calculo", precision = 15, scale = 2)
    private BigDecimal baseCalculo;

    @Column(name = "aliquota_iss", precision = 9, scale = 4)
    private BigDecimal aliquotaIss;

    @Column(name = "valor_iss", precision = 15, scale = 2)
    private BigDecimal valorIss;

    @Column(name = "iss_retido", nullable = false)
    private boolean issRetido;

    @Column(name = "valor_iss_retido", precision = 15, scale = 2)
    private BigDecimal valorIssRetido;

    @Column(name = "valor_irrf", precision = 15, scale = 2)
    private BigDecimal valorIrrf;

    @Column(name = "valor_pis", precision = 15, scale = 2)
    private BigDecimal valorPis;

    @Column(name = "valor_cofins", precision = 15, scale = 2)
    private BigDecimal valorCofins;

    /** PIS/COFINS of the note were withheld (national tpRetPisCofins = 1) - otherwise they're just informed. */
    @Column(name = "pis_cofins_retidos", nullable = false)
    private boolean pisCofinsRetidos;

    @Column(name = "valor_csll", precision = 15, scale = 2)
    private BigDecimal valorCsll;

    @Column(name = "valor_inss", precision = 15, scale = 2)
    private BigDecimal valorInss;

    @Column(name = "valor_liquido", precision = 15, scale = 2)
    private BigDecimal valorLiquido;

    @Column(name = "canceled", nullable = false)
    private boolean canceled;

    @Column(name = "xml_file_path", length = 1000, nullable = false)
    private String xmlFilePath;

    /** SIEG, ADN (Ambiente Nacional) or UPLOAD. */
    @Column(name = "source_system", length = 20, nullable = false)
    private String sourceSystem;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getLayout() { return layout; }
    public void setLayout(String layout) { this.layout = layout; }
    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }
    public String getCodigoVerificacao() { return codigoVerificacao; }
    public void setCodigoVerificacao(String codigoVerificacao) { this.codigoVerificacao = codigoVerificacao; }
    public LocalDateTime getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDateTime issueDate) { this.issueDate = issueDate; }
    public LocalDate getCompetence() { return competence; }
    public void setCompetence(LocalDate competence) { this.competence = competence; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getPrestadorDocumento() { return prestadorDocumento; }
    public void setPrestadorDocumento(String prestadorDocumento) { this.prestadorDocumento = prestadorDocumento; }
    public String getPrestadorNome() { return prestadorNome; }
    public void setPrestadorNome(String prestadorNome) { this.prestadorNome = prestadorNome; }
    public String getPrestadorMunicipio() { return prestadorMunicipio; }
    public void setPrestadorMunicipio(String prestadorMunicipio) { this.prestadorMunicipio = prestadorMunicipio; }
    public String getTomadorDocumento() { return tomadorDocumento; }
    public void setTomadorDocumento(String tomadorDocumento) { this.tomadorDocumento = tomadorDocumento; }
    public String getTomadorNome() { return tomadorNome; }
    public void setTomadorNome(String tomadorNome) { this.tomadorNome = tomadorNome; }
    public String getMunicipioIncidencia() { return municipioIncidencia; }
    public void setMunicipioIncidencia(String municipioIncidencia) { this.municipioIncidencia = municipioIncidencia; }
    public String getCodigoServico() { return codigoServico; }
    public void setCodigoServico(String codigoServico) { this.codigoServico = codigoServico; }
    public String getCodigoTributacaoMunicipal() { return codigoTributacaoMunicipal; }
    public void setCodigoTributacaoMunicipal(String codigoTributacaoMunicipal) { this.codigoTributacaoMunicipal = codigoTributacaoMunicipal; }
    public String getDescricaoServico() { return descricaoServico; }
    public void setDescricaoServico(String descricaoServico) { this.descricaoServico = descricaoServico; }
    public BigDecimal getValorServico() { return valorServico; }
    public void setValorServico(BigDecimal valorServico) { this.valorServico = valorServico; }
    public BigDecimal getValorDeducoes() { return valorDeducoes; }
    public void setValorDeducoes(BigDecimal valorDeducoes) { this.valorDeducoes = valorDeducoes; }
    public BigDecimal getBaseCalculo() { return baseCalculo; }
    public void setBaseCalculo(BigDecimal baseCalculo) { this.baseCalculo = baseCalculo; }
    public BigDecimal getAliquotaIss() { return aliquotaIss; }
    public void setAliquotaIss(BigDecimal aliquotaIss) { this.aliquotaIss = aliquotaIss; }
    public BigDecimal getValorIss() { return valorIss; }
    public void setValorIss(BigDecimal valorIss) { this.valorIss = valorIss; }
    public boolean isIssRetido() { return issRetido; }
    public void setIssRetido(boolean issRetido) { this.issRetido = issRetido; }
    public BigDecimal getValorIssRetido() { return valorIssRetido; }
    public void setValorIssRetido(BigDecimal valorIssRetido) { this.valorIssRetido = valorIssRetido; }
    public BigDecimal getValorIrrf() { return valorIrrf; }
    public void setValorIrrf(BigDecimal valorIrrf) { this.valorIrrf = valorIrrf; }
    public BigDecimal getValorPis() { return valorPis; }
    public void setValorPis(BigDecimal valorPis) { this.valorPis = valorPis; }
    public BigDecimal getValorCofins() { return valorCofins; }
    public void setValorCofins(BigDecimal valorCofins) { this.valorCofins = valorCofins; }
    public boolean isPisCofinsRetidos() { return pisCofinsRetidos; }
    public void setPisCofinsRetidos(boolean pisCofinsRetidos) { this.pisCofinsRetidos = pisCofinsRetidos; }
    public BigDecimal getValorCsll() { return valorCsll; }
    public void setValorCsll(BigDecimal valorCsll) { this.valorCsll = valorCsll; }
    public BigDecimal getValorInss() { return valorInss; }
    public void setValorInss(BigDecimal valorInss) { this.valorInss = valorInss; }
    public BigDecimal getValorLiquido() { return valorLiquido; }
    public void setValorLiquido(BigDecimal valorLiquido) { this.valorLiquido = valorLiquido; }
    public boolean isCanceled() { return canceled; }
    public void setCanceled(boolean canceled) { this.canceled = canceled; }
    public String getXmlFilePath() { return xmlFilePath; }
    public void setXmlFilePath(String xmlFilePath) { this.xmlFilePath = xmlFilePath; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
}
