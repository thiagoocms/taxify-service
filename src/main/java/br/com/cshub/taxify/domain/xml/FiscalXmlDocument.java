package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "fiscal_xml_documents", uniqueConstraints = @UniqueConstraint(columnNames = "access_key"))
public class FiscalXmlDocument extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "access_key", length = 44)
    private String accessKey;

    @Column(name = "document_type", length = 10, nullable = false)
    private String documentType;

    @Column(name = "issuer_cnpj", length = 14)
    private String issuerCnpj;

    @Column(name = "recipient_cnpj", length = 14)
    private String recipientCnpj;

    @Column(name = "issue_date")
    private LocalDateTime issueDate;

    @Column(name = "xml_file_path", length = 1000, nullable = false)
    private String xmlFilePath;

    @Column(name = "rectified_file_path", length = 1000)
    private String rectifiedFilePath;

    @Column(name = "last_rectification_checked_at")
    private LocalDateTime lastRectificationCheckedAt;

    @Column(name = "source_system", length = 20, nullable = false)
    private String sourceSystem = "SIEG";

    // ide
    @Column(name = "ide_codigo_uf", length = 5)
    private String codigoUf;
    @Column(name = "ide_natureza_operacao", length = 60)
    private String naturezaOperacao;
    @Column(name = "ide_modelo", length = 5)
    private String modelo;
    @Column(name = "ide_serie", length = 5)
    private String serie;
    @Column(name = "ide_numero", length = 20)
    private String numero;
    @Column(name = "ide_data_saida_entrada")
    private LocalDateTime dataSaidaEntrada;
    @Column(name = "ide_tipo_operacao", length = 5)
    private String tipoOperacao;
    @Column(name = "ide_destino_operacao", length = 5)
    private String destinoOperacao;
    @Column(name = "ide_codigo_municipio_fato_gerador", length = 10)
    private String codigoMunicipioFatoGerador;
    @Column(name = "ide_ambiente", length = 5)
    private String ambiente;
    @Column(name = "ide_finalidade_emissao", length = 5)
    private String finalidadeEmissao;
    @Column(name = "ide_consumidor_final", length = 5)
    private String consumidorFinal;
    @Column(name = "ide_indicador_presenca", length = 5)
    private String indicadorPresenca;

    // emit
    @Column(name = "emit_cpf", length = 11)
    private String emitenteCpf;
    @Column(name = "emit_nome", length = 200)
    private String emitenteNome;
    @Column(name = "emit_nome_fantasia", length = 200)
    private String emitenteNomeFantasia;
    @Column(name = "emit_inscricao_estadual", length = 20)
    private String emitenteInscricaoEstadual;
    @Column(name = "emit_inscricao_estadual_st", length = 20)
    private String emitenteInscricaoEstadualSubstitutoTributario;
    @Column(name = "emit_inscricao_municipal", length = 20)
    private String emitenteInscricaoMunicipal;
    @Column(name = "emit_cnae", length = 10)
    private String emitenteCnae;
    @Column(name = "emit_regime_tributario", length = 5)
    private String emitenteRegimeTributario;
    @Column(name = "emit_end_logradouro", length = 200)
    private String emitenteLogradouro;
    @Column(name = "emit_end_numero", length = 20)
    private String emitenteEnderecoNumero;
    @Column(name = "emit_end_complemento", length = 100)
    private String emitenteComplemento;
    @Column(name = "emit_end_bairro", length = 100)
    private String emitenteBairro;
    @Column(name = "emit_end_codigo_municipio", length = 10)
    private String emitenteCodigoMunicipio;
    @Column(name = "emit_end_municipio", length = 100)
    private String emitenteMunicipio;
    @Column(name = "emit_end_uf", length = 2)
    private String emitenteUf;
    @Column(name = "emit_end_cep", length = 10)
    private String emitenteCep;
    @Column(name = "emit_end_telefone", length = 20)
    private String emitenteTelefone;

    // dest
    @Column(name = "dest_cpf", length = 11)
    private String destinatarioCpf;
    @Column(name = "dest_id_estrangeiro", length = 20)
    private String destinatarioIdEstrangeiro;
    @Column(name = "dest_nome", length = 200)
    private String destinatarioNome;
    @Column(name = "dest_inscricao_estadual", length = 20)
    private String destinatarioInscricaoEstadual;
    @Column(name = "dest_indicador_ie", length = 5)
    private String destinatarioIndicadorIe;
    @Column(name = "dest_email", length = 200)
    private String destinatarioEmail;
    @Column(name = "dest_end_logradouro", length = 200)
    private String destinatarioLogradouro;
    @Column(name = "dest_end_numero", length = 20)
    private String destinatarioEnderecoNumero;
    @Column(name = "dest_end_complemento", length = 100)
    private String destinatarioComplemento;
    @Column(name = "dest_end_bairro", length = 100)
    private String destinatarioBairro;
    @Column(name = "dest_end_codigo_municipio", length = 10)
    private String destinatarioCodigoMunicipio;
    @Column(name = "dest_end_municipio", length = 100)
    private String destinatarioMunicipio;
    @Column(name = "dest_end_uf", length = 2)
    private String destinatarioUf;
    @Column(name = "dest_end_cep", length = 10)
    private String destinatarioCep;
    @Column(name = "dest_end_telefone", length = 20)
    private String destinatarioTelefone;

    // total/ICMSTot
    @Column(name = "total_base_calculo_icms", precision = 19, scale = 2)
    private BigDecimal baseCalculoIcms;
    @Column(name = "total_valor_icms", precision = 19, scale = 2)
    private BigDecimal valorIcms;
    @Column(name = "total_valor_icms_desonerado", precision = 19, scale = 2)
    private BigDecimal valorIcmsDesonerado;
    @Column(name = "total_valor_fcp", precision = 19, scale = 2)
    private BigDecimal valorFcp;
    @Column(name = "total_base_calculo_icms_st", precision = 19, scale = 2)
    private BigDecimal baseCalculoIcmsSt;
    @Column(name = "total_valor_icms_st", precision = 19, scale = 2)
    private BigDecimal valorIcmsSt;
    @Column(name = "total_valor_fcp_st", precision = 19, scale = 2)
    private BigDecimal valorFcpSt;
    @Column(name = "total_valor_produtos", precision = 19, scale = 2)
    private BigDecimal valorProdutos;
    @Column(name = "total_valor_frete", precision = 19, scale = 2)
    private BigDecimal valorFrete;
    @Column(name = "total_valor_seguro", precision = 19, scale = 2)
    private BigDecimal valorSeguro;
    @Column(name = "total_valor_desconto", precision = 19, scale = 2)
    private BigDecimal valorDesconto;
    @Column(name = "total_valor_importacao", precision = 19, scale = 2)
    private BigDecimal valorImportacao;
    @Column(name = "total_valor_ipi", precision = 19, scale = 2)
    private BigDecimal valorIpi;
    @Column(name = "total_valor_pis", precision = 19, scale = 2)
    private BigDecimal valorPis;
    @Column(name = "total_valor_cofins", precision = 19, scale = 2)
    private BigDecimal valorCofins;
    @Column(name = "total_valor_outras_despesas", precision = 19, scale = 2)
    private BigDecimal valorOutrasDespesas;
    @Column(name = "total_valor_total_nota", precision = 19, scale = 2)
    private BigDecimal valorTotalNota;
    @Column(name = "total_valor_aproximado_tributos", precision = 19, scale = 2)
    private BigDecimal valorAproximadoTributos;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<FiscalXmlDocumentItem> itens = new ArrayList<>();

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

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getIssuerCnpj() {
        return issuerCnpj;
    }

    public void setIssuerCnpj(String issuerCnpj) {
        this.issuerCnpj = issuerCnpj;
    }

    public String getRecipientCnpj() {
        return recipientCnpj;
    }

    public void setRecipientCnpj(String recipientCnpj) {
        this.recipientCnpj = recipientCnpj;
    }

    public LocalDateTime getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDateTime issueDate) {
        this.issueDate = issueDate;
    }

    public String getXmlFilePath() {
        return xmlFilePath;
    }

    public void setXmlFilePath(String xmlFilePath) {
        this.xmlFilePath = xmlFilePath;
    }

    public String getRectifiedFilePath() {
        return rectifiedFilePath;
    }

    public void setRectifiedFilePath(String rectifiedFilePath) {
        this.rectifiedFilePath = rectifiedFilePath;
    }

    public LocalDateTime getLastRectificationCheckedAt() {
        return lastRectificationCheckedAt;
    }

    public void setLastRectificationCheckedAt(LocalDateTime lastRectificationCheckedAt) {
        this.lastRectificationCheckedAt = lastRectificationCheckedAt;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getCodigoUf() {
        return codigoUf;
    }

    public void setCodigoUf(String codigoUf) {
        this.codigoUf = codigoUf;
    }

    public String getNaturezaOperacao() {
        return naturezaOperacao;
    }

    public void setNaturezaOperacao(String naturezaOperacao) {
        this.naturezaOperacao = naturezaOperacao;
    }

    public String getModelo() {
        return modelo;
    }

    public void setModelo(String modelo) {
        this.modelo = modelo;
    }

    public String getSerie() {
        return serie;
    }

    public void setSerie(String serie) {
        this.serie = serie;
    }

    public String getNumero() {
        return numero;
    }

    public void setNumero(String numero) {
        this.numero = numero;
    }

    public LocalDateTime getDataSaidaEntrada() {
        return dataSaidaEntrada;
    }

    public void setDataSaidaEntrada(LocalDateTime dataSaidaEntrada) {
        this.dataSaidaEntrada = dataSaidaEntrada;
    }

    public String getTipoOperacao() {
        return tipoOperacao;
    }

    public void setTipoOperacao(String tipoOperacao) {
        this.tipoOperacao = tipoOperacao;
    }

    public String getDestinoOperacao() {
        return destinoOperacao;
    }

    public void setDestinoOperacao(String destinoOperacao) {
        this.destinoOperacao = destinoOperacao;
    }

    public String getCodigoMunicipioFatoGerador() {
        return codigoMunicipioFatoGerador;
    }

    public void setCodigoMunicipioFatoGerador(String codigoMunicipioFatoGerador) {
        this.codigoMunicipioFatoGerador = codigoMunicipioFatoGerador;
    }

    public String getAmbiente() {
        return ambiente;
    }

    public void setAmbiente(String ambiente) {
        this.ambiente = ambiente;
    }

    public String getFinalidadeEmissao() {
        return finalidadeEmissao;
    }

    public void setFinalidadeEmissao(String finalidadeEmissao) {
        this.finalidadeEmissao = finalidadeEmissao;
    }

    public String getConsumidorFinal() {
        return consumidorFinal;
    }

    public void setConsumidorFinal(String consumidorFinal) {
        this.consumidorFinal = consumidorFinal;
    }

    public String getIndicadorPresenca() {
        return indicadorPresenca;
    }

    public void setIndicadorPresenca(String indicadorPresenca) {
        this.indicadorPresenca = indicadorPresenca;
    }

    public String getEmitenteCpf() {
        return emitenteCpf;
    }

    public void setEmitenteCpf(String emitenteCpf) {
        this.emitenteCpf = emitenteCpf;
    }

    public String getEmitenteNome() {
        return emitenteNome;
    }

    public void setEmitenteNome(String emitenteNome) {
        this.emitenteNome = emitenteNome;
    }

    public String getEmitenteNomeFantasia() {
        return emitenteNomeFantasia;
    }

    public void setEmitenteNomeFantasia(String emitenteNomeFantasia) {
        this.emitenteNomeFantasia = emitenteNomeFantasia;
    }

    public String getEmitenteInscricaoEstadual() {
        return emitenteInscricaoEstadual;
    }

    public void setEmitenteInscricaoEstadual(String emitenteInscricaoEstadual) {
        this.emitenteInscricaoEstadual = emitenteInscricaoEstadual;
    }

    public String getEmitenteInscricaoEstadualSubstitutoTributario() {
        return emitenteInscricaoEstadualSubstitutoTributario;
    }

    public void setEmitenteInscricaoEstadualSubstitutoTributario(String emitenteInscricaoEstadualSubstitutoTributario) {
        this.emitenteInscricaoEstadualSubstitutoTributario = emitenteInscricaoEstadualSubstitutoTributario;
    }

    public String getEmitenteInscricaoMunicipal() {
        return emitenteInscricaoMunicipal;
    }

    public void setEmitenteInscricaoMunicipal(String emitenteInscricaoMunicipal) {
        this.emitenteInscricaoMunicipal = emitenteInscricaoMunicipal;
    }

    public String getEmitenteCnae() {
        return emitenteCnae;
    }

    public void setEmitenteCnae(String emitenteCnae) {
        this.emitenteCnae = emitenteCnae;
    }

    public String getEmitenteRegimeTributario() {
        return emitenteRegimeTributario;
    }

    public void setEmitenteRegimeTributario(String emitenteRegimeTributario) {
        this.emitenteRegimeTributario = emitenteRegimeTributario;
    }

    public String getEmitenteLogradouro() {
        return emitenteLogradouro;
    }

    public void setEmitenteLogradouro(String emitenteLogradouro) {
        this.emitenteLogradouro = emitenteLogradouro;
    }

    public String getEmitenteEnderecoNumero() {
        return emitenteEnderecoNumero;
    }

    public void setEmitenteEnderecoNumero(String emitenteEnderecoNumero) {
        this.emitenteEnderecoNumero = emitenteEnderecoNumero;
    }

    public String getEmitenteComplemento() {
        return emitenteComplemento;
    }

    public void setEmitenteComplemento(String emitenteComplemento) {
        this.emitenteComplemento = emitenteComplemento;
    }

    public String getEmitenteBairro() {
        return emitenteBairro;
    }

    public void setEmitenteBairro(String emitenteBairro) {
        this.emitenteBairro = emitenteBairro;
    }

    public String getEmitenteCodigoMunicipio() {
        return emitenteCodigoMunicipio;
    }

    public void setEmitenteCodigoMunicipio(String emitenteCodigoMunicipio) {
        this.emitenteCodigoMunicipio = emitenteCodigoMunicipio;
    }

    public String getEmitenteMunicipio() {
        return emitenteMunicipio;
    }

    public void setEmitenteMunicipio(String emitenteMunicipio) {
        this.emitenteMunicipio = emitenteMunicipio;
    }

    public String getEmitenteUf() {
        return emitenteUf;
    }

    public void setEmitenteUf(String emitenteUf) {
        this.emitenteUf = emitenteUf;
    }

    public String getEmitenteCep() {
        return emitenteCep;
    }

    public void setEmitenteCep(String emitenteCep) {
        this.emitenteCep = emitenteCep;
    }

    public String getEmitenteTelefone() {
        return emitenteTelefone;
    }

    public void setEmitenteTelefone(String emitenteTelefone) {
        this.emitenteTelefone = emitenteTelefone;
    }

    public String getDestinatarioCpf() {
        return destinatarioCpf;
    }

    public void setDestinatarioCpf(String destinatarioCpf) {
        this.destinatarioCpf = destinatarioCpf;
    }

    public String getDestinatarioIdEstrangeiro() {
        return destinatarioIdEstrangeiro;
    }

    public void setDestinatarioIdEstrangeiro(String destinatarioIdEstrangeiro) {
        this.destinatarioIdEstrangeiro = destinatarioIdEstrangeiro;
    }

    public String getDestinatarioNome() {
        return destinatarioNome;
    }

    public void setDestinatarioNome(String destinatarioNome) {
        this.destinatarioNome = destinatarioNome;
    }

    public String getDestinatarioInscricaoEstadual() {
        return destinatarioInscricaoEstadual;
    }

    public void setDestinatarioInscricaoEstadual(String destinatarioInscricaoEstadual) {
        this.destinatarioInscricaoEstadual = destinatarioInscricaoEstadual;
    }

    public String getDestinatarioIndicadorIe() {
        return destinatarioIndicadorIe;
    }

    public void setDestinatarioIndicadorIe(String destinatarioIndicadorIe) {
        this.destinatarioIndicadorIe = destinatarioIndicadorIe;
    }

    public String getDestinatarioEmail() {
        return destinatarioEmail;
    }

    public void setDestinatarioEmail(String destinatarioEmail) {
        this.destinatarioEmail = destinatarioEmail;
    }

    public String getDestinatarioLogradouro() {
        return destinatarioLogradouro;
    }

    public void setDestinatarioLogradouro(String destinatarioLogradouro) {
        this.destinatarioLogradouro = destinatarioLogradouro;
    }

    public String getDestinatarioEnderecoNumero() {
        return destinatarioEnderecoNumero;
    }

    public void setDestinatarioEnderecoNumero(String destinatarioEnderecoNumero) {
        this.destinatarioEnderecoNumero = destinatarioEnderecoNumero;
    }

    public String getDestinatarioComplemento() {
        return destinatarioComplemento;
    }

    public void setDestinatarioComplemento(String destinatarioComplemento) {
        this.destinatarioComplemento = destinatarioComplemento;
    }

    public String getDestinatarioBairro() {
        return destinatarioBairro;
    }

    public void setDestinatarioBairro(String destinatarioBairro) {
        this.destinatarioBairro = destinatarioBairro;
    }

    public String getDestinatarioCodigoMunicipio() {
        return destinatarioCodigoMunicipio;
    }

    public void setDestinatarioCodigoMunicipio(String destinatarioCodigoMunicipio) {
        this.destinatarioCodigoMunicipio = destinatarioCodigoMunicipio;
    }

    public String getDestinatarioMunicipio() {
        return destinatarioMunicipio;
    }

    public void setDestinatarioMunicipio(String destinatarioMunicipio) {
        this.destinatarioMunicipio = destinatarioMunicipio;
    }

    public String getDestinatarioUf() {
        return destinatarioUf;
    }

    public void setDestinatarioUf(String destinatarioUf) {
        this.destinatarioUf = destinatarioUf;
    }

    public String getDestinatarioCep() {
        return destinatarioCep;
    }

    public void setDestinatarioCep(String destinatarioCep) {
        this.destinatarioCep = destinatarioCep;
    }

    public String getDestinatarioTelefone() {
        return destinatarioTelefone;
    }

    public void setDestinatarioTelefone(String destinatarioTelefone) {
        this.destinatarioTelefone = destinatarioTelefone;
    }

    public BigDecimal getBaseCalculoIcms() {
        return baseCalculoIcms;
    }

    public void setBaseCalculoIcms(BigDecimal baseCalculoIcms) {
        this.baseCalculoIcms = baseCalculoIcms;
    }

    public BigDecimal getValorIcms() {
        return valorIcms;
    }

    public void setValorIcms(BigDecimal valorIcms) {
        this.valorIcms = valorIcms;
    }

    public BigDecimal getValorIcmsDesonerado() {
        return valorIcmsDesonerado;
    }

    public void setValorIcmsDesonerado(BigDecimal valorIcmsDesonerado) {
        this.valorIcmsDesonerado = valorIcmsDesonerado;
    }

    public BigDecimal getValorFcp() {
        return valorFcp;
    }

    public void setValorFcp(BigDecimal valorFcp) {
        this.valorFcp = valorFcp;
    }

    public BigDecimal getBaseCalculoIcmsSt() {
        return baseCalculoIcmsSt;
    }

    public void setBaseCalculoIcmsSt(BigDecimal baseCalculoIcmsSt) {
        this.baseCalculoIcmsSt = baseCalculoIcmsSt;
    }

    public BigDecimal getValorIcmsSt() {
        return valorIcmsSt;
    }

    public void setValorIcmsSt(BigDecimal valorIcmsSt) {
        this.valorIcmsSt = valorIcmsSt;
    }

    public BigDecimal getValorFcpSt() {
        return valorFcpSt;
    }

    public void setValorFcpSt(BigDecimal valorFcpSt) {
        this.valorFcpSt = valorFcpSt;
    }

    public BigDecimal getValorProdutos() {
        return valorProdutos;
    }

    public void setValorProdutos(BigDecimal valorProdutos) {
        this.valorProdutos = valorProdutos;
    }

    public BigDecimal getValorFrete() {
        return valorFrete;
    }

    public void setValorFrete(BigDecimal valorFrete) {
        this.valorFrete = valorFrete;
    }

    public BigDecimal getValorSeguro() {
        return valorSeguro;
    }

    public void setValorSeguro(BigDecimal valorSeguro) {
        this.valorSeguro = valorSeguro;
    }

    public BigDecimal getValorDesconto() {
        return valorDesconto;
    }

    public void setValorDesconto(BigDecimal valorDesconto) {
        this.valorDesconto = valorDesconto;
    }

    public BigDecimal getValorImportacao() {
        return valorImportacao;
    }

    public void setValorImportacao(BigDecimal valorImportacao) {
        this.valorImportacao = valorImportacao;
    }

    public BigDecimal getValorIpi() {
        return valorIpi;
    }

    public void setValorIpi(BigDecimal valorIpi) {
        this.valorIpi = valorIpi;
    }

    public BigDecimal getValorPis() {
        return valorPis;
    }

    public void setValorPis(BigDecimal valorPis) {
        this.valorPis = valorPis;
    }

    public BigDecimal getValorCofins() {
        return valorCofins;
    }

    public void setValorCofins(BigDecimal valorCofins) {
        this.valorCofins = valorCofins;
    }

    public BigDecimal getValorOutrasDespesas() {
        return valorOutrasDespesas;
    }

    public void setValorOutrasDespesas(BigDecimal valorOutrasDespesas) {
        this.valorOutrasDespesas = valorOutrasDespesas;
    }

    public BigDecimal getValorTotalNota() {
        return valorTotalNota;
    }

    public void setValorTotalNota(BigDecimal valorTotalNota) {
        this.valorTotalNota = valorTotalNota;
    }

    public BigDecimal getValorAproximadoTributos() {
        return valorAproximadoTributos;
    }

    public void setValorAproximadoTributos(BigDecimal valorAproximadoTributos) {
        this.valorAproximadoTributos = valorAproximadoTributos;
    }

    public List<FiscalXmlDocumentItem> getItens() {
        return itens;
    }

    public void setItens(List<FiscalXmlDocumentItem> itens) {
        this.itens = itens;
    }
}
