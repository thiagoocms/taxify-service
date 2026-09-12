package br.com.cshub.taxify.domain.xml;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fiscal_xml_document_items")
public class FiscalXmlDocumentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private FiscalXmlDocument document;

    @Column(name = "numero_item")
    private Integer numeroItem;

    @Column(name = "codigo", length = 60)
    private String codigo;

    @Column(name = "codigo_barras", length = 20)
    private String codigoBarras;

    @Column(name = "descricao", length = 500)
    private String descricao;

    @Column(name = "ncm", length = 10)
    private String ncm;

    @Column(name = "cfop", length = 10)
    private String cfop;

    @Column(name = "unidade_comercial", length = 10)
    private String unidadeComercial;

    @Column(name = "quantidade_comercial", precision = 19, scale = 4)
    private BigDecimal quantidadeComercial;

    @Column(name = "valor_unitario_comercial", precision = 19, scale = 4)
    private BigDecimal valorUnitarioComercial;

    @Column(name = "valor_total", precision = 19, scale = 2)
    private BigDecimal valorTotal;

    @Column(name = "unidade_tributavel", length = 10)
    private String unidadeTributavel;

    @Column(name = "quantidade_tributavel", precision = 19, scale = 4)
    private BigDecimal quantidadeTributavel;

    @Column(name = "valor_unitario_tributavel", precision = 19, scale = 4)
    private BigDecimal valorUnitarioTributavel;

    @Column(name = "cst_icms", length = 5)
    private String situacaoTributariaIcms;

    @Column(name = "base_calculo_icms", precision = 19, scale = 2)
    private BigDecimal baseCalculoIcms;

    @Column(name = "aliquota_icms", precision = 7, scale = 4)
    private BigDecimal aliquotaIcms;

    @Column(name = "valor_icms", precision = 19, scale = 2)
    private BigDecimal valorIcms;

    @Column(name = "cst_ipi", length = 5)
    private String situacaoTributariaIpi;

    @Column(name = "valor_ipi", precision = 19, scale = 2)
    private BigDecimal valorIpi;

    @Column(name = "cst_pis", length = 5)
    private String situacaoTributariaPis;

    @Column(name = "valor_pis", precision = 19, scale = 2)
    private BigDecimal valorPis;

    @Column(name = "cst_cofins", length = 5)
    private String situacaoTributariaCofins;

    @Column(name = "valor_cofins", precision = 19, scale = 2)
    private BigDecimal valorCofins;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public FiscalXmlDocument getDocument() {
        return document;
    }

    public void setDocument(FiscalXmlDocument document) {
        this.document = document;
    }

    public Integer getNumeroItem() {
        return numeroItem;
    }

    public void setNumeroItem(Integer numeroItem) {
        this.numeroItem = numeroItem;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigoBarras() {
        return codigoBarras;
    }

    public void setCodigoBarras(String codigoBarras) {
        this.codigoBarras = codigoBarras;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public String getNcm() {
        return ncm;
    }

    public void setNcm(String ncm) {
        this.ncm = ncm;
    }

    public String getCfop() {
        return cfop;
    }

    public void setCfop(String cfop) {
        this.cfop = cfop;
    }

    public String getUnidadeComercial() {
        return unidadeComercial;
    }

    public void setUnidadeComercial(String unidadeComercial) {
        this.unidadeComercial = unidadeComercial;
    }

    public BigDecimal getQuantidadeComercial() {
        return quantidadeComercial;
    }

    public void setQuantidadeComercial(BigDecimal quantidadeComercial) {
        this.quantidadeComercial = quantidadeComercial;
    }

    public BigDecimal getValorUnitarioComercial() {
        return valorUnitarioComercial;
    }

    public void setValorUnitarioComercial(BigDecimal valorUnitarioComercial) {
        this.valorUnitarioComercial = valorUnitarioComercial;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public void setValorTotal(BigDecimal valorTotal) {
        this.valorTotal = valorTotal;
    }

    public String getUnidadeTributavel() {
        return unidadeTributavel;
    }

    public void setUnidadeTributavel(String unidadeTributavel) {
        this.unidadeTributavel = unidadeTributavel;
    }

    public BigDecimal getQuantidadeTributavel() {
        return quantidadeTributavel;
    }

    public void setQuantidadeTributavel(BigDecimal quantidadeTributavel) {
        this.quantidadeTributavel = quantidadeTributavel;
    }

    public BigDecimal getValorUnitarioTributavel() {
        return valorUnitarioTributavel;
    }

    public void setValorUnitarioTributavel(BigDecimal valorUnitarioTributavel) {
        this.valorUnitarioTributavel = valorUnitarioTributavel;
    }

    public String getSituacaoTributariaIcms() {
        return situacaoTributariaIcms;
    }

    public void setSituacaoTributariaIcms(String situacaoTributariaIcms) {
        this.situacaoTributariaIcms = situacaoTributariaIcms;
    }

    public BigDecimal getBaseCalculoIcms() {
        return baseCalculoIcms;
    }

    public void setBaseCalculoIcms(BigDecimal baseCalculoIcms) {
        this.baseCalculoIcms = baseCalculoIcms;
    }

    public BigDecimal getAliquotaIcms() {
        return aliquotaIcms;
    }

    public void setAliquotaIcms(BigDecimal aliquotaIcms) {
        this.aliquotaIcms = aliquotaIcms;
    }

    public BigDecimal getValorIcms() {
        return valorIcms;
    }

    public void setValorIcms(BigDecimal valorIcms) {
        this.valorIcms = valorIcms;
    }

    public String getSituacaoTributariaIpi() {
        return situacaoTributariaIpi;
    }

    public void setSituacaoTributariaIpi(String situacaoTributariaIpi) {
        this.situacaoTributariaIpi = situacaoTributariaIpi;
    }

    public BigDecimal getValorIpi() {
        return valorIpi;
    }

    public void setValorIpi(BigDecimal valorIpi) {
        this.valorIpi = valorIpi;
    }

    public String getSituacaoTributariaPis() {
        return situacaoTributariaPis;
    }

    public void setSituacaoTributariaPis(String situacaoTributariaPis) {
        this.situacaoTributariaPis = situacaoTributariaPis;
    }

    public BigDecimal getValorPis() {
        return valorPis;
    }

    public void setValorPis(BigDecimal valorPis) {
        this.valorPis = valorPis;
    }

    public String getSituacaoTributariaCofins() {
        return situacaoTributariaCofins;
    }

    public void setSituacaoTributariaCofins(String situacaoTributariaCofins) {
        this.situacaoTributariaCofins = situacaoTributariaCofins;
    }

    public BigDecimal getValorCofins() {
        return valorCofins;
    }

    public void setValorCofins(BigDecimal valorCofins) {
        this.valorCofins = valorCofins;
    }
}
