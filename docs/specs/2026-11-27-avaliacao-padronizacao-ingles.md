# Avaliação: nomes em inglês no backend do Taxify

## Status

**Avaliação técnica** (2026-11-27). Não é spec de implementação. Foi feita só com leitura do código: nenhum arquivo foi alterado, o banco de produção não foi consultado e o `mvn test` não foi rodado. As contagens saem de varreduras por termos em português (Grep) com revisão manual dos achados, então são **aproximadas** (±10%).

Repositórios lidos: `C:\workspace\taxify` (609 classes em `src/main/java/br/...`, 81 migrations em `src/main/java/db/migration`, 179 classes de teste) e `C:\workspace\taxify-front\src\app` (só para medir o impacto no contrato). Ficaram de fora, como pedido: `docker-compose.aws.yml`, `application-default.yaml` e `scripts/sql/`.

## Resumo

| Camada | Ocorrências em pt (aprox.) | Onde se concentra | Recomendação |
|---|---|---|---|
| Banco: tabelas | 1 de 49 (`certidoes_estaduais`) | Certidões | Renomear numa janela (tabela pequena) |
| Banco: colunas | ~205 colunas em 12 tabelas | `fiscal_xml_documents` (~70), `fiscal_xml_document_items` (~47), `service_invoices` (25) | Tabelas grandes: **nunca**. Tabelas pequenas: janela |
| Banco: índices e FKs | 3 (`idx_certidoes_estaduais_company_validade`, `fk_certidoes_estaduais_company`, `idx_fxdi_document_cfop_valor`) | | Junto com a tabela, ou deixar como está |
| Banco: valores de enum gravados | ~12 colunas, ~45 valores | `pendencies`, `audit_rules`, `certidoes_estaduais`, `fronteira_rules`, `sefaz_note_summaries` | Java em inglês com conversor agora; troca do valor gravado depois e só onde for seguro |
| Entidades JPA (campos) | ~230 campos em 16 classes (12 entidades e 4 embeddables) | `FiscalXmlDocument`, `ServiceInvoice`, `FiscalXmlDocumentItem` e embeddables | Inglês já, mantendo `@Column(name = "<nome antigo>")` |
| Classes e pacotes | ~37 tipos e 6 pacotes | `Certidao*`, `Fronteira*`, `FiscalXml*DTO`, `SiegBaixar/Contar*` | Inglês já (`Certidao` muda, `Fronteira` fica) |
| Métodos | ~25 métodos de negócio, ~350 getters e setters, 8 derived queries | Seguem os campos | Inglês já |
| Enums (tipos e constantes) | 17 enums, ~110 constantes | `PendencyStatus/Severity`, `CaptureSituation`, `CertidaoSituacao`, `FronteiraRuleKind`, códigos de auditoria | Nome do tipo já; constante junto com o contrato ou o banco |
| DTOs e JSON da API | ~280 campos em 29 arquivos | `FronteiraDTOs` (38), `ServiceInvoiceDTOs` (28), DTOs de `fiscalxml` (~90) | Onda de contrato, com aliases |
| Rotas | ~88 de 139 endpoints com segmento em pt | `/fronteira`, `/certidoes`, `/pendencias`, `/auditoria`, `/notas-servico`, `/fiscal/apuracao`, `/importar` | Onda de contrato, com a rota antiga como alias |
| Query params | ~20 nomes, ~45 usos | `competencia` (10), `periodo` (7), `busca` (4), `situacao`, `canal` | Onda de contrato. Unificar `competencia`, `competence`, `periodo` e `month` |
| Propriedades e variáveis de ambiente | 2 chaves (`efisco.certidao.enabled/cron`) | | Inglês já, com fallback para a chave antiga |
| Logs e eventos | ~10 mensagens com termo pt; chaves estruturadas já em inglês | | Quase nada a fazer |
| Testes | 1.255 métodos já em inglês; 12 classes `Certidao*`/`Fronteira*`; ~12 testes com coluna ou rota pt em string | | Acompanham cada onda |

**Recomendação em uma linha:** começar já pela regra de que todo código novo nasce em inglês, o que inclui corrigir as 4 specs ainda não implementadas que propõem nomes em pt. Depois vem o refactor do Java sem tocar o banco nem o contrato. O contrato da API vai para inglês por recurso, com aliases de transição. O banco muda só nas tabelas pequenas, numa janela. **As colunas de `fiscal_xml_documents`, `fiscal_xml_document_items` e `service_invoices` não mudam**: o mapeamento `@Column` resolve o lado do Java sem custo.

---

## 1. Inventário por camada

### 1.1 Banco

As tabelas são declaradas em migrations Java (`TableMigration`, que cria o que falta e acrescenta colunas). O Hibernate roda com `ddl-auto: validate` (`application.yaml`, linha 40).

**Tabelas com nome em pt:** só `certidoes_estaduais` (V56). `fronteira_rules` usa `Fronteira` como nome próprio (ver a seção 2.2). `efisco_extracts`, `efisco_extract_notes` e `efisco_memories` já têm nome em inglês, mas as colunas estão em pt.

**Colunas em pt (ou mistas), por tabela:**

| Tabela (migration) | pt / total | Exemplos | Tamanho esperado |
|---|---|---|---|
| `fiscal_xml_documents` (V16, V23, V27) | ~70 / 109 | `ide_modelo`, `ide_natureza_operacao`, `emit_nome_fantasia`, `dest_inscricao_estadual`, `emit_end_logradouro`, `total_valor_total_nota`, `total_base_calculo_icms` | **Grande** (uma linha por nota) |
| `fiscal_xml_document_items` (V17, V22, V25, V48) | ~47 / 59 | `valor_unitario_comercial`, `quantidade_tributavel`, `aliquota_icms_st`, `base_calculo_pis`, `origem_mercadoria`, `codigo_beneficio`, `ibs_uf_reducao_aliquota` | **A maior** (uma linha por item) |
| `service_invoices` (V37) | 25 / 42 | `prestador_nome`, `tomador_documento`, `valor_iss_retido`, `codigo_tributacao_municipal`, `municipio_incidencia`, `pis_cofins_retidos` | **Grande** |
| `efisco_extracts` (V45, V52) | 16 / 28 | `numero_extrato`, `icms_a_recolher`, `icms_mandado_seguranca`, `credenciamento_antecipacao`, `data_vencimento`, `razao_social` | Pequena (empresa × mês) |
| `product_fiscal_rules` (V14, V26, V31) | 12 / 26 | `codigo_produto`, `descricao_produto`, `ncm_correto`, `cfop_correto`, `observacoes`, `documento_fornecedor` | Pequena |
| `fronteira_rules` (V47, V49, V50) | 10 / 28 | `descricao`, `aliquota`, `agregacao`, `aplica_em`, `regiao_origem`, `fornecedor_simples`, `fundamento`, `percentual` | Pequena |
| `efisco_extract_notes` (V46) | 7 / 15 | `numero_nota`, `valor_nota`, `cnpj_emitente`, `data_passagem`, `icms_devido`, `linha`, `registro` | Média |
| `certidoes_estaduais` (V56) | 7 / 19 | `tipo`, `numero`, `situacao`, `emitida_em`, `valida_ate`, `documento`, `origem` | Pequena |
| `fiscal_xml_sync_control` (V19, V38, V56_1, V79) | 4 / 25 | `ultima_verificacao_em`, `total_importado`, `data_emissao_inicio`, `data_emissao_fim`, que convivem com `issued_total_imported`, `last_attempt_at` e `issued_checked_at` | Pequena (1 por empresa) |
| `ibs_cbs_rates` (V24) | 4 / 4 | `ano`, `aliquota_cbs`, `aliquota_ibs_uf`, `aliquota_ibs_mun` | Mínima |
| `efisco_memories` (V53) | 3 / 11 | `chave_acesso`, `registro`, `texto` | Média |

Total: **~205 colunas em 12 tabelas**, das 49 que existem. As outras 37 tabelas já estão em inglês. Os prefixos `ide_`, `emit_` e `dest_` vêm dos grupos do XML da NF-e, mas o resto do nome (`_modelo`, `_nome`, `_end_bairro`) é escolha nossa e está em pt.

Os tamanhos não foram medidos, porque o banco de produção não foi consultado. Antes de qualquer onda de banco, rode esta consulta (só leitura, com aprovação do Tony):
```sql
SELECT table_name, table_rows, ROUND(data_length/1024/1024) AS data_mb, ROUND(index_length/1024/1024) AS idx_mb
FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY data_length DESC;
```

**Valores de enum gravados em pt** (todos `EnumType.STRING` em `varchar`):

| Coluna | Valores | Origem no código |
|---|---|---|
| `pendencies.status` | `ABERTA`, `RESOLVIDA`, `DESCONSIDERADA` | `enumerated/PendencyStatus` |
| `pendencies.severity`, `audit_rules.severity` | `ALTA`, `MEDIA`, `BAIXA` (`INFO`) | `enumerated/PendencySeverity` |
| `pendencies.type` | `CERTIDAO_PE_POSITIVA`, `CERTIDAO_PE_VENCENDO`, `CERTIDAO_PE_VENCIDA` (os outros 13 já estão em inglês) | `enumerated/PendencyType` |
| `pendencies.natural_key` | `"<TYPE>:<id>"` e `"AUDIT:<code>:<mês>..."` | `PendencyFinder:290`, `CertidaoPendencies:81`, `AuditService:282` |
| `audit_rules.code` | `NUMERACAO_LACUNA`, `NUMERO_DUPLICADO`, `DEVOLUCAO_SEM_ORIGINAL`, `NOTA_SO_RESUMO`, `ALIQUOTA_INTERESTADUAL`, `FATURAMENTO_ATIPICO`... (14) | `service/audit/AuditRuleDefinition` |
| `audit_rules.rule_group` | `INTEGRIDADE`, `COERENCIA`, `ANOMALIA` | `AuditRuleDefinition.Group` |
| `certidoes_estaduais.situacao` | `NEGATIVA`, `POSITIVA`, `POSITIVA_EFEITO_NEGATIVA` | `enumerated/CertidaoSituacao` |
| `fronteira_rules.kind` | `ALIQUOTA_INTERNA`, `AGREGACAO` (`ST_MVA`) | `enumerated/FronteiraRuleKind` |
| `fronteira_rules.aplica_em` | `SEM_ENCERRAMENTO`, `CARGA_LIQUIDA`, `USO_CONSUMO`, `MERCADORIA` (`ST`) | `FronteiraCalculator.Treatment` |
| `fronteira_rules.regiao_origem` | `SUL_SUDESTE`, `DEMAIS` | `FronteiraRuleService:256` |
| `sefaz_note_summaries.ciencia_status` | `PENDENTE`, `ENVIADA`, `ERRO` | `SefazNoteSummary.CienciaStatus` |
| `sefaz_dist_control.last_status`, `nfse_adn_control.last_status` | `SEM_MOVIMENTO`, `ERRO` (`OK`) | `SefazDistControl.Status`, `NfseAdnControl.Status` |
| `fiscal_period_closings.snapshot` (JSON) | Chaves pt do `IbsCbsSummaryDTO`: `creditoCbs`, `debitoIbs`, `saldoCbs`, `notasEntrada`... | `TaxAssessmentService:170` lê o JSON de volta |
| `fronteira_rules.formula` (texto do usuário) | Variáveis `valor_produto`, `icms_origem`, `credito_sn`, `aliquota_origem`, `mva_ajustada`, `st_retido`, `agregacao`, `despesas`, `partida`... (17) | `service/fronteira/FormulaEvaluator` |

`TaxRegime` (`SIMPLES_NACIONAL`, `LUCRO_PRESUMIDO`, `LUCRO_REAL`), `SimplesAnnex` e `CertidaoTipo` (`CND`, `CRF`) também estão gravados, mas são nomes próprios (ver a seção 2).

### 1.2 Entidades JPA

As entidades têm `@Column(name = ...)` explícito em todos os campos. Por isso dá para renomear o campo Java sem tocar a coluna.

| Entidade | Campos pt | Exemplos |
|---|---|---|
| `domain/xml/FiscalXmlDocument` + `FiscalXmlHeaderExtras` + `FiscalIbsCbsTotals` | ~85 | `modelo`, `serie`, `numero`, `naturezaOperacao`, `emitenteNomeFantasia`, `destinatarioLogradouro`, `valorTotalNota`, `emitenteInscricaoEstadualSubstitutoTributario` |
| `domain/xml/FiscalXmlDocumentItem` + `FiscalItemTaxDetail` + `FiscalItemIbsCbs` | ~50 | `quantidadeComercial`, `valorUnitarioTributavel`, `situacaoTributariaIcms`, `baseCalculoIcmsSt`, `aliquotaCreditoSn`, `classificacaoTributaria` |
| `domain/xml/ServiceInvoice` | 25 | `prestadorNome`, `tomadorDocumento`, `valorIssRetido`, `codigoVerificacao` |
| `domain/fronteira/EfiscoExtract`, `EfiscoExtractNote`, `EfiscoMemory` | 25 | `numeroExtrato`, `icmsARecolher`, `periodo`, `dataPassagem`, `chaveAcesso`, `texto` |
| `domain/fronteira/FronteiraRule` | 10 | `descricao`, `aplicaEm`, `regiaoOrigem`, `fundamento` (`AuditRule` já usa `legalBasis` para o mesmo conceito) |
| `domain/certidao/CertidaoEstadual` | 7 | `tipo`, `situacao`, `emitidaEm`, `validaAte`, `origem` (e as constantes `ORIGEM_UPLOAD` e `ORIGEM_EFISCO`) |
| `domain/fiscalrule/ProductFiscalRule` | 6 | `codigoProduto`, `ncmCorreto`, `observacoes` |
| `domain/xml/FiscalXmlSyncControl` | 4 | `ultimaVerificacaoEm`, `totalImportado`, `dataEmissaoInicio` e `dataEmissaoFim`, ao lado de `issuedTotalImported` e `lastAttemptAt` (o caso misto mais visível) |
| `domain/reform/IbsCbsRate` | 4 | `ano`, `aliquotaCbs` |

Total: **~230 campos**. As consultas acompanham os campos: 88 referências a campos pt em JPQL (6 repositórios, entre eles `FiscalXmlDocumentItemRepository:80-94`) e 8 derived queries (`findByCompany_IdAndPeriodoAndDeletedIsFalse`, `findByCompany_IdAndUfAndDeletedIsFalseOrderByValidaAteDesc...`, `findByDocument_IdOrderByNumeroItemAsc`...).

### 1.3 Classes, pacotes e métodos

| Grupo | Ocorrências | Exemplos |
|---|---|---|
| Pacotes | 6 | `domain/certidao`, `dto/certidao`, `service/certidao`, `domain/fronteira`, `dto/fronteira`, `service/fronteira` (e `user_company` em snake_case, que não é pt mas foge do padrão) |
| `Certidao*` | 14 tipos | `CertidaoService`, `CertidaoResource`, `CertidaoScheduler`, `CertidaoPdfParser`, `CertidaoRenewalRule`, `CertidaoPendencies`, `CertidaoIssuer`, `CertidaoEstadual`, `EfiscoCertidaoClient`, `InvalidCertidaoException`, `CertidaoDTOs.CompanyCertidao` |
| `Fronteira*` | 11 tipos | `FronteiraService`, `FronteiraCalculator`, `FronteiraRule*`, `FronteiraReportPdf`, `FronteiraRevisionRequestPdf`, `FronteiraDTOs.Resultado` |
| DTOs do XML com sufixo pt | 7 | `FiscalXmlDestinatarioDTO`, `FiscalXmlEmitenteDTO`, `FiscalXmlEnderecoDTO`, `FiscalXmlIdentificacaoDTO`, `FiscalXmlImpostoItemDTO`, `FiscalXmlProdutoDTO`, `FiscalXmlTotaisDTO` (e já existe um `FiscalXmlTotalsDTO` em inglês ao lado) |
| SIEG | 3 | `SiegBaixarXmlsRequest`, `SiegContarXmlsResponse`, `SiegContarXmlsDataDTO` |
| Métodos de negócio | ~25 | `sincronizarComSieg`, `exportarXmls`, `exportarXmlsZip`, `baixarXmls`, `baixarPdf`, `buscarXmls`, `buscarEventos`, `buscarProdutos`, `contarXmlsNfe`, `conferir`, `conferirVarias`, `mapDestinatario`, `mapEmitente`, `mapTotais`, `findProdutos`, `storeCertidaoPdf`, `retryCiencia` |
| Getters e setters | ~350 | Seguem os campos da seção 1.2 |

`CalculoFronteira*` **não existe** no backend nem no front. O equivalente é `FronteiraCalculator`, que já está em inglês, e o campo `calculo` em `FronteiraDTOs.Line`/`Report` (`FronteiraDTOs.java:92,97`).

### 1.4 Enums

| Enum | Constantes pt | Exposto em |
|---|---|---|
| `PendencyStatus` | `ABERTA`, `RESOLVIDA`, `DESCONSIDERADA` | Banco, API, front (27 usos de `ABERTA`), link do e-mail diário |
| `PendencySeverity` | `ALTA`, `MEDIA`, `BAIXA` | Banco (2 tabelas), API, front (25 usos), link do e-mail |
| `PendencyType` | `CERTIDAO_PE_*` (3) | Banco e `natural_key`, `TAXIFY_PENDENCY_ALERT_TYPES` |
| `AuditRuleDefinition` (+ `Group`) | 14 códigos e 3 grupos | Banco, `natural_key`, rota `/auditoria/regras/{code}` |
| `CaptureSituation` | `EM_DIA`, `PENDENTE`, `ATRASADA`, `NUNCA_TENTADA`, `COM_ERRO`, `FORA_DO_PLANO`, `BLOQUEADA` | API e query param `situacao` (calculado, não gravado) |
| `CertidaoSituacao` / `CertidaoTipo` | `NEGATIVA`, `POSITIVA`, `POSITIVA_EFEITO_NEGATIVA` / nome do tipo | Banco, API |
| `CertidaoRenewalRule` (interno) | `SEM_CERTIDAO`, `VALIDA`, `A_VENCER`, `VENCIDA` | API (3 usos no front) |
| `FronteiraRuleKind` | `ALIQUOTA_INTERNA`, `AGREGACAO` | Banco, API |
| `FronteiraCalculator.Treatment` | `SEM_ENCERRAMENTO`, `CARGA_LIQUIDA`, `USO_CONSUMO`, `MERCADORIA`, `SEM_ANTECIPACAO`, `SEM_REGRA`, `ST_RETIDO`... (9) | Banco (`aplica_em`), API |
| `FronteiraDTOs.Flag` / `Resultado` | `SEM_XML`, `SO_RESUMO`, `VALOR_DIFERENTE`, `NOTA_CANCELADA`, `CONFERE`, `COBROU_A_MAIS`, `COBROU_A_MENOS`, `NAO_CALCULOU`... | API (6 a 8 usos no front) |
| `SefazNoteSummary.CienciaStatus` | `PENDENTE`, `ENVIADA`, `ERRO` | Banco, API |
| `SefazDistControl.Status`, `NfseAdnControl.Status` | `SEM_MOVIMENTO`, `ERRO` | Banco, API (6 usos no front) |
| `EfiscoException.Kind` | `BLOQUEADO`, `LOGIN_RECUSADO`, `PAGINA_INESPERADA`, `ERRO_HTTP` | `firm_certificates.last_sync_status` (texto) |
| `CapturePanelValidation.ItemType` | `NFE_RECEBIDAS`, `NFE_EMITIDAS`... | Query param `tipo` |

Total: **17 enums com ~110 constantes em pt**. Os outros 35 enums do projeto já estão em inglês.

### 1.5 DTOs e campos do JSON da API

| DTO | Campos pt | Exemplos |
|---|---|---|
| `dto/fronteira/FronteiraDTOs` | 38 | `cnpjEmitente`, `valorTotalNota`, `codigoReceita`, `credenciamentoInterno`, `icmsRegDebito`, `divergencias`, `calculo`, `canceladas` |
| `dto/fiscalxml/ServiceInvoiceDTOs` | 28 | `aliquotaIss`, `codigoServico`, `issRetido`, `municipioIncidencia`, `federaisRetidos`, `somenteComRetencao` |
| `FiscalXmlTotaisDTO`, `FiscalXmlIdentificacaoDTO`, `FiscalXmlProdutoDTO`, `FiscalXmlImpostoItemDTO`, `FiscalXmlEnderecoDTO`, `FiscalXmlEmitenteDTO`, `FiscalXmlDestinatarioDTO`, `FiscalXmlSummaryDTO`, `FiscalXmlDetailDTO`, `FiscalXmlItemDTO` | ~90 | `valorAproximadoTributos`, `naturezaOperacao`, `digitoVerificador`, `quantidadeComercial`, `situacaoTributariaIcms`, `logradouro`, `nomeFantasia`, `quantidadeItens`, `totais`, `identificacao` |
| `dto/fronteira/FronteiraRuleDTOs` | 18 | `descricao`, `aliquota`, `agregacao`, `aplicaEm`, `regiaoOrigem`, `semRegraMotivo` |
| `dto/company/SefazNotesDTO` | 14 | `aguardandoCiencia`, `cienciaTentativas`, `buscaDetalhe`, `comErro`, `notas` |
| `dto/fiscalrule/*` (5 DTOs) | ~35 | `codigoProduto`, `ncmCorreto`, `possuiRegra`, `valorEncontrado`, `valorEsperado`, `mensagem` |
| `dto/fiscalxml/ManifestationDTOs` | 12 | `chave`, `chaves`, `recusadas`, `mensagem` |
| `dto/certidao/CertidaoDTOs` | 12 | `situacao`, `situacaoLabel`, `validaAte`, `paginaEmissao`, `aplicavel` |
| `dto/assessment/IbsCbsSummaryDTO` | 9 | `creditoCbs`, `debitoIbs`, `saldoCbs`, `notasEntrada`, `notasSaida` (também gravado no `snapshot`) |
| `dto/sieg/XmlDownloadFilterDTO` | 2 | `dataEmissaoInicio`, `dataEmissaoFim` (ao lado de `xmlType`, `take` e `cnpjDest`) |
| `dto/fiscalxml/FiscalXmlSyncControlDTO` | 4 | `totalImportado`, `totalPendente`, `ultimaVerificacaoEm`, `dataEmissaoInicio` (endpoint `@Deprecated` que o front não usa) |
| Outros (`XmlSearchFilterDTO`, `RetentionDTOs`, `PlatformFirmDTO`, `CaptureCompanyDTO`...) | ~15 | `numero`, `origem`, `divergencia`, `clientes` |

Total: **~280 campos em 29 arquivos**. No front, os modelos de `core/models` declaram **218 propriedades pt distintas**, que aparecem em **~2.480 ocorrências em 144 arquivos `.ts`/`.html`**. Esse número é um teto, porque conta também variáveis locais com o mesmo nome. A concentração está em `fiscal-xml.model.ts` (86 de 174 propriedades), `fronteira.model.ts` (68/152), `service-invoice.model.ts` (31/51), `product-fiscal-rule.model.ts` (23/46), `company.model.ts` (15/74), `certidao.model.ts` (12/26) e `tax-assessment.model.ts` (12/55).

### 1.6 Rotas e query params

O prefixo é `/taxify-service/api/v1`. São **139 endpoints**, e **~88 têm pelo menos um segmento em pt**. No front, os services têm 89 segmentos de caminho pt.

| Recurso (`rest/`) | Base | Endpoints pt |
|---|---|---|
| `FronteiraResource` | `/fronteira` | 21: `/memoria`, `/memoria/buscar`, `/sincronizar`, `/relatorio.pdf`, `/pedido-revisao.pdf`, `/extrato/pdf`, `/importar`, `/regras`, `/regras/variaveis`, `/regras/testar`, `/regras/geral`, `/regras/{id}/conferencia`, `/regras/{id}/ativa` |
| `FiscalXmlResource` | `/integrations/xmls` | 12 de 16: `/importar`, `/sincronizar`, `/exportar`, `/exportar/zip`, `/manifestacao`, `/totais`, `/conferencia`, `/{id}/eventos`, `/{id}/produtos`, `/{id}/retificar` |
| `CompanyResource` | `/companies` | 12: `/{id}/certificado`, `/{id}/sefaz/capturar`, `/{id}/sefaz/resumos/{chave}/reenviar-ciencia`, `/{id}/regimes`, `/{id}/preencher-pelas-notas`, `/{id}/nfse-nacional/capturar` |
| `AuditResource` | `/auditoria` | 7: `/executar`, `/laudo.pdf`, `/regras/{code}/ativa` |
| `PendencyResource` | `/pendencias` | 7: `/resumo`, `/integracoes`, `/{id}/resolver`, `/{id}/desconsiderar`, `/{id}/reabrir`, `/atualizar` |
| `CertidaoResource` | `/certidoes` | 5: `/carteira`, `/empresa/{companyId}`, `/empresa/{companyId}/emitir` |
| `ServiceInvoiceResource` | `/notas-servico` | 5: `/buscar`, `/totais`, `/sincronizar` |
| `TaxAssessmentResource` | `/fiscal/apuracao` | 5: `/{companyId}/fechamento`, `/{companyId}/guia` |
| `ReportResource` | `/relatorios` | 4: `/mensal`, `/curva-abc`, `/simulacao` |
| `ProductFiscalRuleResource` | `/product-fiscal-rules` | 4: `/produtos`, `/divergencias`, `/conferencia`, `/sugestoes` |
| `RetentionResource` | `/retencoes` | 3: `/conferencia` |
| `FirmCertificateResource` | `/integrations/efisco/certificado` | 3 |

**Query params pt:** `competencia` (10 usos), `periodo` (7), `busca` (4), `situacao`, `canal`, `tipo`, `registro`, `natureza`, `conferida`, `ativa`, `incluirInativas` (2 cada), `somenteSemRegra`, `retificado`, `por`, `meses`, `margem`, `fornecedor` e `codigoProduto` (1 cada). Os três do painel (`situacao`, `canal`, `busca`) aparecem em `capture-panel.service.ts:31-40`, no front. O mesmo conceito tem hoje **quatro nomes**: `competencia` (`AuditResource`), `competence` (colunas e um param), `periodo` (`FronteiraResource`) e `month` (5 params). Isso precisa ser unificado de qualquer forma.

### 1.7 Propriedades e variáveis de ambiente

Quase tudo já está em inglês. Os casos em pt:

| Chave | Onde | Observação |
|---|---|---|
| `efisco.certidao.enabled`, `efisco.certidao.cron` | `@Value` em `service/certidao/CertidaoScheduler` | Única chave pt de verdade |
| `sefaz.dist.auto-ciencia`, `ciencia-per-run`, `ciencia-max-attempts`, env `SEFAZ_AUTO_CIENCIA` | `SefazProperties` | `ciência` é o nome do evento da SEFAZ e fica (ver a seção 2.1) |
| Valores de `TAXIFY_PENDENCY_ALERT_TYPES` | env | Recebe nomes de `PendencyType`, inclusive `CERTIDAO_PE_*` |

### 1.8 Logs e eventos

Há 242 chamadas de log. As mensagens e as chaves estruturadas já estão em inglês (`firm={}`, `company={}`, `password.reset.failed`, `pendency.alert.failed`, `auth.session.check.failed`). Umas 10 mensagens citam termos pt como palavra ("Certidão SEFAZ-PE run failed", "SEFAZ ciência / fetch failed"), e uma thread se chama `certidao-pe`. O único alarme que lê log (`monitoring/README.md:82-85`) filtra por `"ERROR"`. **Nenhum alarme depende de nome**.

### 1.9 Testes

Os 1.255 métodos de teste já têm nome em inglês, e uns 19 usam termo pt de domínio (`refusesWhatIsNotACertidao`, `consumoIndevidoIsNotAnError`). 12 classes seguem o nome da classe de produção (`CertidaoSchedulerTest`, `FronteiraCalculatorTest`...). Cerca de 12 testes têm coluna, rota ou valor pt em string e quebram em cada onda de contrato ou de banco: `CapturePanelIntegrationTest`, `CapturePanelQaHttpTest`, `CapturePanelQaPerfTest`, `PendencyServiceTest`, `PasswordAndAlertsQaHttpTest`, `CapturePanelMigrationsOnExistingDataTest` e `PasswordAlertsMigrationsOnExistingDataTest`.

### 1.10 Fora do pedido, mas relevante

- **Texto para o usuário fora do i18n:** há ~467 literais com acento no Java, sobretudo em PDFs (`FronteiraReportPdf`, `FronteiraRevisionRequestPdf`), mensagens de validação (`FronteiraRuleService:283`: `invalid("A fórmula pode substituir: ...")`) e `AuditRuleDefinition`. Não é problema de nome, mas contraria a regra de "texto para o usuário em i18n pt/en". Vale um item de backlog próprio.
- **Specs ainda não implementadas que propõem nomes em pt** (ver a Onda 0): `2026-11-27-aliquotas-ibs-cbs-por-ano.md` (`alterado_em`, `status_antes`, `status_depois`, `PENDENTE`, `ESTIMATIVA`, `SEM_LEI`), `2026-11-27-credito-estoque-cbs-levantamento.md` (`calculado_em`, `situacao_alterada_em`, `situacao_alterada_por`, `INVENTARIO_RECEBIDO`, `NAO_AVALIADA`, `/reforma/credito-estoque`), `2026-11-27-validador-ibs-cbs-tabela-oficial.md` (`ativada_em`, `conferida_em`, `importada_em`, `publicada_em`, `primeira_ativacao_em`, `hash_arquivo_oficial`, `vigencia_inicio`, `vigencia_fim`, `FORA_DE_VIGENCIA`, `SEM_TABELA`, `MODELO_NAO_PERMITIDO`, `/reforma/prontidao`) e `2026-11-27-ncm-inexistente-ou-fora-de-vigencia.md`. Corrigir agora custa só editar texto.

---

## 2. O que NÃO deve mudar

### 2.1 Nomes impostos por terceiros ou termos fiscais sem tradução fiel

| Item | Onde | Por quê |
|---|---|---|
| Tags do XML (`ide`, `emit`, `dest`, `vProd`, `infNFSe`, `cClassTrib`, `pCredSN`...) e nomes de nó no parser | `FiscalXmlDomParser`, `FiscalXmlMetadataParser`, `integration/nfse/NfseXml` (`ValorCofins`, `IssRetido`...) | Leiaute da NF-e e da NFS-e |
| Contrato do SIEG: `TipoXml`, `Take`, `Skip`, `DataEmissaoInicio`, `CNPJemit`, `BaixarEventos`, os caminhos `baixar-xmls` e `contar-xmls` | `integration/sieg/*` | API do SIEG. **O `@JsonProperty` fica, e o nome Java e o da classe vão para inglês** (`SiegDownloadXmlsRequest(@JsonProperty("TipoXml") Integer xmlType, ...)`) |
| e-Fisco: `DtPeriodoFiscal`, `NuDocumentoIdentificacao`, `id_contexto_sessao`, `PRConsultarNotaFiscalMemoria`, `PREmitirCertidaoRegularidadeFiscal` | `integration/efisco/EfiscoClient` | Campos do formulário e das rotinas do e-Fisco |
| Acessórias e Conta Azul: `RespNome`, `GrupoDeEmpresas`, `ClienteDesde`, `ids_clientes`, `listCategoriasReceita`, `data_vencimento`, `nao_pago`... | `integration/acessorias`, `integration/contaazul` | API de terceiros |
| SEFAZ: `ult_nsu`, `max_nsu` e os eventos `CIENCIA`, `CONFIRMACAO`, `DESCONHECIMENTO`, `NAO_REALIZADA` | `SefazDistControl`, `NfseAdnControl`, `SefazEventClient` | NSU e os nomes dos eventos de manifestação do destinatário |
| `ciencia` como termo (`ciencia_status`, `cienciaAttempts`, `auto-ciencia`) | `SefazNoteSummary`, `SefazProperties` | "Ciência da Operação" é o nome oficial do evento, e o resto do nome já está em inglês. **Os valores `PENDENTE`, `ENVIADA` e `ERRO` mudam** |
| Siglas e regimes: ICMS, ICMS-ST, DIFAL, FCP, IPI, PIS, COFINS, ISS, IBS, CBS, CFOP, NCM, CEST, CST, CSOSN, cClassTrib, MVA, CNAE, IE, DT-e, PGDAS, RBT12, CND e CRF, além de `SIMPLES_NACIONAL`, `LUCRO_PRESUMIDO`, `LUCRO_REAL`, `MEI` e os anexos `II`, `III`, `IV` | Todo o código | Não existe tradução fiel, e são esses os nomes que aparecem nos documentos oficiais |
| Rota de callback `integracoes/conta-azul/callback` | `CONTA_AZUL_REDIRECT_URI` | Está cadastrada na Conta Azul |
| Textos de i18n (`i18n/*_pt.properties`, `*_en.properties`) | | São texto. As chaves (`error.fronteira.*`, `error.certidao.*`, 11 no total) seguem a decisão de nome da seção 2.2 |

### 2.2 Fronteira e certidões

| Termo | Opções | Decisão | Justificativa |
|---|---|---|---|
| **Fronteira** (`FronteiraRule`, `fronteira_rules`, `/fronteira`) | `BorderTax`, `StateBorderTax`, `IcmsAdvanceCollection`, ou manter | **Manter `Fronteira` como nome próprio**, no mesmo nível de `DIFAL` | É o nome do regime de cobrança na entrada em PE (o "extrato de fronteira" do e-Fisco). `BorderTax` seria um termo inventado que nenhum fiscal reconhece e nenhum documento usa. "Antecipação" não serve porque é outra coisa, e já aparece em `credenciamento_antecipacao` e `SEM_ANTECIPACAO`. `FronteiraCalculator` e `FronteiraRule` já seguem o padrão "nome próprio + inglês". **O que fica ao redor vai para inglês**: `descricao` → `description`, `aplicaEm` → `appliesTo`, `regiaoOrigem` → `originRegion`, `fornecedorSimples` → `simplesSupplier`, `fundamento` → `legalBasis` (igual a `audit_rules.legal_basis`), `agregacao` → `aggregationRate` |
| **Certidão** (`CertidaoService`, `certidoes_estaduais`, `/certidoes`) | `TaxClearanceCertificate`, `TaxClearance`, `Certificate`, ou manter | **`TaxClearance`**: `StateTaxClearance`, `TaxClearanceService`, tabela `state_tax_clearances`, rota `/tax-clearances` | "Tax clearance certificate" é a tradução usual de certidão de regularidade fiscal. **Não usar `Certificate`**: o projeto já usa esse nome para o certificado digital A1 (`company_certificates`, `firm_certificates`, `CERTIFICATE_EXPIRED`), e a mistura geraria ambiguidade real. As situações vão para `NEGATIVE`, `POSITIVE` e `POSITIVE_WITH_NEGATIVE_EFFECT`, uma tradução literal 1:1 das categorias do CTN (arts. 205 e 206), sem reinterpretar. `CND` e `CRF` ficam como siglas |

### 2.3 Glossário para o resto (proposta)

| pt | en | Já usado no projeto? |
|---|---|---|
| apuração | `tax assessment` | Sim (`TaxAssessmentResource`, mas na rota `/fiscal/apuracao`) |
| competência / período / mês | **um nome só**: `period` (`yyyy-MM`) | Hoje há 4 nomes. `competence` está em 3 colunas, e lá fica (ver a Onda 3) |
| pendência | `pendency` | Sim |
| retenção | `retention` | Sim |
| nota (fiscal) | `invoice` (API) e `note` (interno, onde já existe) | Misto |
| extrato e memória do e-Fisco | `extract`, `memory` | Sim |
| prestador / tomador (NFS-e) | `provider` / `taker` | Não |
| emitente / destinatário | `issuer` / `recipient` | Sim (`issuer_cnpj`, `recipient_cnpj`) |
| alíquota / base de cálculo / valor | `rate` / `taxBase` / `amount` | Parcial |
| conferência | `check` | Sim (`fiscalcheck`) |
| situação | `status` | Sim |

Termo novo entra no glossário com a aprovação do Tony quando tiver peso fiscal, como "anistiado", "autuado" e "mandado de segurança" no extrato.

---

## 3. Avaliação por camada

Os pontos de impacto que valem para várias camadas:

- **Hibernate `ddl-auto: validate`.** Na subida, a aplicação confere entidade contra banco. Qualquer rename no banco tem de sair **na mesma imagem** que o código, e **voltar para a imagem anterior não funciona** sem um SQL de volta pronto: a imagem antiga não sobe com as colunas novas. O Flyway Community não tem *undo*.
- **Um único container de backend** (`docker-compose.yml`). Não há duas versões do backend rodando juntas. Mas **o front fica em cache no navegador**: uma aba aberta continua chamando rotas e lendo campos antigos até recarregar. Além disso, front e back são imagens diferentes, publicadas em momentos diferentes. Mudar o contrato sem alias quebra telas abertas e qualquer deploy fora de ordem. Desde o SaaS isso atinge clientes de fora, não só o time Fiscal.
- **MySQL 8.4.** `ALTER TABLE ... RENAME COLUMN` sem mudança de tipo só altera metadado (`ALGORITHM=INSTANT`, sem copiar a tabela), e `RENAME TABLE` também. O custo não está na cópia, está no **lock de metadado (MDL) exclusivo**. Se houver uma transação longa aberta na tabela, como uma exportação em ZIP, um backfill ou a rotina de captura, o `ALTER` fica esperando, e todas as consultas seguintes ficam enfileiradas atrás dele. Em `fiscal_xml_documents` e `fiscal_xml_document_items`, isso trava o sistema inteiro. Se alguém usar `CHANGE COLUMN` com uma definição diferente, vira cópia completa da tabela. Para mitigar: `ALGORITHM=INSTANT` explícito (falha em vez de copiar), `SET SESSION lock_wait_timeout = 5` com retentativa, todos os renames da tabela num único `ALTER`, rotinas desligadas e uma janela de manutenção.
- **`TableMigration` não sabe renomear.** Hoje ele só acrescenta o que falta. Seria preciso um `renameColumn(old, new)` idempotente que consulte `information_schema`, no estilo do projeto. As migrations antigas (V19, V56...) continuam criando o nome antigo num banco novo, e a migration nova renomeia em seguida. As migrations futuras dessas tabelas têm de usar o nome novo.
- **SQL em string:** `CapturePanelRepository` (JDBC: `x.ultima_verificacao_em`, `x.total_importado`, `x.data_emissao_inicio` nas linhas 71-128) e 23 `nativeQuery` em 8 repositórios, nenhum deles com coluna pt. As 154 `@Query` JPQL usam nome de campo, não de coluna.
- **Chaves naturais:** `pendencies.natural_key` embute `PendencyType` e o código da regra de auditoria. Se um desses mudar, a deduplicação quebra: o achador abre pendências "novas", fecha as antigas como ausentes e dispara um novo episódio no resumo por e-mail das pendências ALTA. É um efeito visível para o cliente.
- **JSON gravado:** `fiscal_period_closings.snapshot` guarda o `TaxAssessmentDTO`, inclusive o `IbsCbsSummaryDTO` com campos pt. O Spring Boot ignora propriedade desconhecida por padrão. Se um campo for renomeado sem `@JsonAlias`, **os meses fechados passam a mostrar nulo, sem erro nenhum**.
- **Fórmulas do usuário:** `fronteira_rules.formula` guarda texto digitado com as variáveis `valor_produto`, `icms_origem`... Renomear a variável quebra as fórmulas salvas.
- **URLs salvas:** o e-mail diário leva `.../pendencias?severidade=ALTA&status=ABERTA` (`PendencyAlertMailTemplate:51`), e esses links já estão na caixa de entrada das pessoas. O painel guarda os filtros na URL (`capture-panel.component.ts:284`). Os arquivos no S3 e no disco têm os prefixos `fronteira/` e `certidoes/` (`FiscalXmlFileStorage:90,95`), e o caminho completo está gravado em `pdf_path`.

| Camada | Impacto | Esforço | Risco | Benefício |
|---|---|---|---|---|
| **Java interno** (classes, pacotes, métodos, variáveis, campos de entidade com `@Column` antigo, JPQL, derived queries, nomes de tipo de enum, testes) | Nenhum fora do processo: o banco e o JSON não mudam. Os mappers são escritos à mão, então o compilador pega tudo. O `validate` pega mapeamento errado na subida | **G** em volume (~600 identificadores, ~350 deles getter e setter), mas mecânico com o refactor da IDE. Em lotes por pacote, vira **M** cada | **Baixo**. Atenção para não renomear sem querer um componente de record de DTO, que **é** contrato | Alto para quem mantém o código: acaba a mistura `totalSieg` + `totalImportado` + `issuedTotalImported`, e o grep e o code review ficam previsíveis |
| **Constantes de enum gravadas** (feitas no Java, valor no banco intacto) | Um `AttributeConverter` por enum mapeia `PendencyStatus.OPEN` para `"ABERTA"`. O JSON preserva o valor antigo com `@JsonProperty` na constante. **Mas** o Spring converte query param com `valueOf` e ignora o `@JsonProperty`, então os enums usados em query param (`situacao`, `severity`, `tipo`) precisam de `Converter<String, Enum>` que aceite os dois | **M** | Baixo a médio (é fácil esquecer um caminho de conversão; o teste HTTP pega) | Médio |
| **Contrato da API** (rotas, params, JSON, valores de enum no JSON) | Quebra o front: 218 propriedades, 89 segmentos de rota e 3 params do painel. Quebra também abas abertas, deploy fora de ordem, e-mails já enviados e snapshots | **G** (back + front + testes HTTP) | **Médio**. Com alias de transição e lotes por recurso, cai para baixo | Médio. Ganha consistência, mas o único cliente da API é o nosso front |
| **Banco: tabelas pequenas** (`certidoes_estaduais`, `fronteira_rules`, `fiscal_xml_sync_control`, `ibs_cbs_rates`, `product_fiscal_rules`, `efisco_*`) | Rename instantâneo. Atualizar `CapturePanelRepository` e os testes de migration. Precisa de janela por causa do MDL e da volta de imagem | **P-M** (~65 colunas, 1 tabela, 3 índices/FKs) | Baixo a médio | Baixo a médio: banco coerente nas tabelas que mais vamos mexer (fronteira, certidões, IBS/CBS) |
| **Banco: tabelas grandes** (`fiscal_xml_documents`, `fiscal_xml_document_items`, `service_invoices`, ~142 colunas) | MDL nas tabelas mais quentes, scripts e consultas ad hoc com nome de coluna, volta de imagem impossível sem SQL pronto, e o backup e restore precisam bater | **M** em código, **G** em operação | **Alto** | **Quase nenhum**. Com o `@Column`, o Java já fica em inglês, e ninguém fora do time lê essas colunas |
| **Valores de enum no banco** | `UPDATE` em `pendencies`, `audit_rules`, `certidoes_estaduais`, `fronteira_rules`, `sefaz_*` (lock de linha e binlog, em lotes). Quebra o `natural_key` se tocar em `PendencyType` ou nos códigos de auditoria | **M** | **Alto** para tipos e códigos (natural_key, episódios, e-mails), médio para status e severidade | Baixo: com o conversor, só se vê o valor ao olhar o banco |
| **Propriedades** | `efisco.certidao.*` → `efisco.tax-clearance.*` com fallback (`${efisco.tax-clearance.enabled:${efisco.certidao.enabled:true}}`) | **P** | Baixo | Baixo |
| **Logs** | Trocar ~10 mensagens e o nome da thread | **P** | Nenhum (o alarme só lê `ERROR`) | Baixo |

---

## 4. Estratégia recomendada: ondas

### Onda 0: parar de criar dívida (agora; esforço P)
1. **Regra no `CLAUDE.md` e nos agentes** (`po-taxify`, `dev-backend-taxify`, `dev-frontend-taxify`, `reviewer-taxify`): todo identificador novo nasce em inglês. Isso vale para tabela, coluna, índice, valor de enum gravado, classe, campo, rota, query param, chave JSON e propriedade. As exceções são as da seção 2, e o glossário da seção 2.3 é a referência. O reviewer **barra como bloqueante** um nome pt novo fora das exceções. Hoje nenhum agente tem essa regra.
2. **Corrigir as 4 specs não implementadas** antes do dev começar (lista na seção 1.10). Exemplos: `alterado_em` → `changed_at`, `situacao_alterada_em` → `status_changed_at`, `vigencia_inicio` → `valid_from` (já é o padrão de `audit_rules` e `fronteira_rules`), `PENDENTE`/`ESTIMATIVA`/`SEM_LEI` → `PENDING`/`ESTIMATE`/`NO_LAW`, `/reforma/prontidao` → `/reform/readiness`. A rota `/platform/official-tables` já está certa. Esse é o item de maior retorno: são tabelas novas, sem dado nem front.
3. **Remover o endpoint obsoleto** `FiscalXmlSyncControlResource` (`@Deprecated`, sem uso no front). Com ele saem `ultimaVerificacaoEm` e `totalImportado` do contrato.

### Onda 1: Java em inglês sem tocar o banco nem o contrato (esforço M por lote; risco baixo)
O que muda, em PRs por pacote (cada PR com `mvn test` no MySQL local e o reviewer olhando "nenhum JSON nem coluna mudou"):
- Pacotes `certidao` → `taxclearance`, os tipos `Certidao*` → `TaxClearance*` e `CertidaoEstadual` → `StateTaxClearance`, mantendo `@Table(name = "certidoes_estaduais")`.
- `FiscalXml*DTO` com sufixo pt → `FiscalXmlRecipientDTO`, `IssuerDTO`, `AddressDTO`, `IdentificationDTO`, `ItemTaxDTO`, `ProductDTO`, juntando `Totais` com `Totals`. **Os componentes desses records não mudam nesta onda.**
- `SiegBaixarXmlsRequest` → `SiegDownloadXmlsRequest` e `SiegContar*` → `SiegCount*`, com os campos Java em inglês e os `@JsonProperty("TipoXml")` mantidos.
- Os campos das entidades vão para inglês e o `@Column(name = "<antigo>")` fica: `ultimaVerificacaoEm` → `lastCheckedAt`, `totalImportado` → `totalImported`, `dataEmissaoInicio` → `issueDateFrom`, `modelo` → `model`, `valorTotalNota` → `invoiceTotal`, `prestadorNome` → `providerName`... Junto com eles vão os getters e setters, o JPQL e as derived queries.
- Os métodos de negócio (`sincronizarComSieg` → `syncFromSieg`, `exportarXmls` → `exportXmls`, `mapDestinatario` → `mapRecipient`...).
- Os nomes de tipo de enum (`CertidaoSituacao` → `TaxClearanceStatus`, `CertidaoTipo` → `TaxClearanceType`) e as constantes de enums que **não** saem no banco, no JSON nem em query param.
- As constantes de enum gravadas ou expostas vão para inglês **com** conversor JPA para o valor antigo, `@JsonProperty` com o valor antigo e conversor de query param. Se o time preferir, isso pode ficar para a Onda 2, que é a escolha mais conservadora.
- A propriedade `efisco.certidao.*` com fallback, as mensagens de log e os nomes de classe de teste.
- Ordem: `certidao` → `fronteira` → `fiscalrule` → `xml` (entidades grandes) → `fiscalxml` (DTOs internos).

### Onda 2: contrato da API em inglês, com transição (esforço G; risco médio → baixo)
Um recurso por vez, cada um num ciclo completo da esteira (spec curta → back → front → reviewer → QA com teste HTTP real):
1. **Backend, release N:** aceita as duas formas. Rota nova e rota antiga no mesmo handler (`@GetMapping({"/tax-clearances", "/certidoes"})`); query params com os dois nomes (`period` e `competencia`); corpo com `@JsonAlias("nomeAntigo")`; valor de enum aceito nas duas grafias. A resposta sai com o nome novo **e** o antigo, este último num acessor `@JsonProperty("nomeAntigo")` marcado `@Deprecated`.
2. **Front, release N:** passa a usar só a forma nova.
3. **Backend, release N+1** (uns 15 dias depois, quando nenhum bundle antigo estiver em uso): remove as rotas antigas e os campos duplicados.
4. Ficam **para sempre**: o `@JsonAlias` nos DTOs gravados em `fiscal_period_closings.snapshot` (ou uma migração de dados do JSON, que é mais arriscada), e no front a leitura de `severidade=ALTA&status=ABERTA` vindos dos links de e-mail já enviados.

`/api/v2` não vale a pena: o único consumidor é o nosso front, e duplicar o prefixo inteiro custa mais que os aliases.

Ordem dos recursos, do mais isolado para o mais usado: certidões → regras da fronteira → fronteira → retenções e relatórios → auditoria → pendências → apuração → notas de serviço → painel de captura (`situacao`/`canal`/`busca` → `status`/`channel`/`search`) → XMLs (`/integrations/xmls/importar` → `/import`, `XmlDownloadFilterDTO.dataEmissaoInicio` → `issueDateFrom`, os 86 campos de `fiscal-xml.model.ts`). Unificar `competencia`/`periodo`/`month` em `period` nesta onda.

### Onda 3: banco, só nas tabelas pequenas, em janela de manutenção (esforço P-M; risco baixo a médio)
Com aprovação do Tony, porque roda em produção:
- **Antes:** medir os tamanhos (consulta da seção 1.1), desligar as rotinas (captura, e-Fisco, certidões, pendências) e escrever e testar o SQL de volta.
- **Uma migration por tabela**, com o helper idempotente `renameColumn` e `ALGORITHM=INSTANT` explícito:
  - `certidoes_estaduais` → `state_tax_clearances` (mais 7 colunas, o índice e a FK);
  - `fronteira_rules` (10 colunas);
  - `fiscal_xml_sync_control` (4 colunas, atualizando `CapturePanelRepository`);
  - `ibs_cbs_rates`, `product_fiscal_rules`, `efisco_extracts`, `efisco_extract_notes`, `efisco_memories`.
- **Valores de enum** em `UPDATE` por lotes, removendo depois o conversor: `pendencies.status` e `severity`, `audit_rules.severity`, `certidoes_estaduais.situacao`, `fronteira_rules.kind`, `aplica_em` e `regiao_origem`, `sefaz_note_summaries.ciencia_status` e os `last_status`. Nenhum desses entra no `natural_key`.
- Na mesma imagem vão o código sem `@Column` antigo nessas tabelas e os testes de migration sobre dados existentes. O QA verifica no banco local com uma cópia anonimizada.

### Nunca (ou só se houver outra razão para mexer)
- **As colunas de `fiscal_xml_documents`, `fiscal_xml_document_items` e `service_invoices`.** O `@Column` da Onda 1 entrega o Java em inglês sem custo, e o rename só traria risco operacional. Reavaliar só se uma dessas tabelas for reestruturada por outro motivo (particionamento, troca de modelo).
- **`PendencyType.CERTIDAO_PE_*`, os códigos de `AuditRuleDefinition` e seus grupos, no banco.** Estão no `natural_key`, nos episódios do resumo por e-mail e em `TAXIFY_PENDENCY_ALERT_TYPES`. As constantes Java vão para inglês com conversor, e o valor gravado fica. Migrar o `natural_key` na mesma transação é possível, mas custa caro e expõe o cliente a um surto de e-mails. Fica como item separado, se um dia valer.

### Ordem e dependências

| Onda | Depende de | Quem | Deploy |
|---|---|---|---|
| 0 | Nada | PO (specs), Tony (regra e glossário) | Não tem |
| 1 | Onda 0 (glossário) | dev-backend, reviewer, QA | Imagem normal, sem migration |
| 2 | Onda 1 do recurso | dev-backend → dev-frontend → reviewer → QA, por recurso | Duas releases por recurso (com alias, sem alias) |
| 3 | Onda 2 dos recursos afetados (certidões, fronteira, painel) | dev-backend, QA, Tony | Janela de manutenção, com aprovação |

---

## 5. O que fica de fora e por quê

| Fica de fora | Por quê |
|---|---|
| Colunas das 3 tabelas grandes | O custo e o risco operacional (MDL nas tabelas mais quentes, volta de imagem com `validate`) não compensam. O `@Column` resolve o Java |
| `natural_key`, `PendencyType.CERTIDAO_PE_*` e códigos de auditoria gravados | A deduplicação de pendências e os episódios do e-mail dependem deles. O conversor no Java basta |
| Variáveis das fórmulas da fronteira (`valor_produto`, `icms_origem`...) | São a linguagem que o usuário digita e estão gravadas em `fronteira_rules.formula`. Valem como texto de produto, igual ao i18n |
| Prefixos `fronteira/` e `certidoes/` no S3 e no disco | O caminho completo está gravado em `pdf_path`. Mover objetos não traz nada. Arquivos novos podem até usar outro prefixo, mas não há ganho |
| Rotas de tela do front (`/pendencias`, `/notas-fiscais`, `/apuracao`, `?mes=`, `?empresa=`, `?severidade=`) | São URLs que o usuário vê e que estão em e-mails. São texto de produto, não código. Se for mudar, é uma decisão do front, com redirecionamento |
| Contratos de terceiros e termos fiscais (seção 2.1) | Não são nossos, ou não têm tradução fiel |
| Textos de i18n | São texto |
| `competence` nas colunas de `audit_runs`, `pendencies` e `service_invoices` | Já está em inglês. O falso cognato não justifica um rename. A API usa `period` (Onda 2) |
| Texto para o usuário fixo no Java (~467 literais) | Não é problema de nome. Vale um item próprio de i18n (PDFs e mensagens de validação) |
| Pacote `user_company` (snake_case) | Não é pt. Pode entrar de carona na Onda 1 se o time quiser |
