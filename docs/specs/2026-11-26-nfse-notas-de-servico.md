# NFS-e: captura e consulta das notas de serviço

> Spec retroativa (as-built). Descreve o que o código faz hoje. As divergências e dúvidas ficam em "Perguntas em aberto".

## Status

**Implementado.**

- Migrations:
  - `V37__ServiceInvoices`: tabela `service_invoices`, chave única `(company_id, access_key)`.
  - `V38__SyncControlServiceInvoices`: dia da última busca de NFS-e no SIEG.
  - `V41__NfseAdnControl`: NSU e situação da captura pelo Ambiente Nacional.
  - `V42__ServiceInvoiceSources`: `found_in_sieg_at` e `found_in_adn_at`.
- Para o histórico de commits: `git log -- src/main/java/br/com/cshub/taxify/integration/nfse src/main/java/br/com/cshub/taxify/service/ServiceInvoice* src/main/java/br/com/cshub/taxify/service/NfseAdn*`.

## Problema e objetivo

O analista precisa das NFS-e prestadas e tomadas por cada empresa, com o ISS e as retenções federais, para apurar e controlar as retenções (M1: a recolher × a compensar). Sem o Taxify, essas notas são baixadas prefeitura por prefeitura ou pelo portal nacional.

O Taxify reúne as NFS-e de três fontes:
- **SIEG** (`TipoXml 3`);
- **Ambiente Nacional da NFS-e (ADN)**, pela API de contribuintes, com o A1 da empresa;
- **upload manual**, pelo mesmo endpoint de importação de XML.

Os layouts aceitos são o **nacional** e o **ABRASF**. A nota é gravada uma vez por empresa.

Como medir hoje:
- totais por direção na tela;
- confronto SIEG × ADN (nota presente em uma fonte e não na outra há mais de 48 h);
- histórico de execuções do ADN em `capture_runs`.

## Usuários e cenário

- **Analista Fiscal**: abre "Notas de serviço" (`/notas-servico`), filtra a empresa e o período e vê os totais de prestadas e tomadas (ISS, ISS retido, federais retidos). Pode filtrar as notas com retenção, abrir o detalhe e baixar o XML. Também pode pedir ao SIEG as notas de um período.
- **Administrador**: dispara a captura ADN na tela da empresa (aba de certificado).
- **Rotinas**:
  - SIEG: uma vez por dia por empresa, dentro da sincronização SIEG que roda a cada minuto.
  - ADN: de hora em hora, aos 25 minutos.

## Histórias de usuário

- Como analista, quero ver as NFS-e prestadas e tomadas de uma empresa no período, para conferir o ISS e as retenções sem entrar no portal de cada prefeitura.
- Como analista, quero filtrar só as notas com retenção, para montar o controle de retenções.
- Como analista, quero ver as notas que estão só no SIEG ou só no Ambiente Nacional, para descobrir onde uma fonte falha.
- Como analista, quero buscar no SIEG as NFS-e de um período passado, para completar a base quando a rotina diária não alcança.
- Como administrador, quero disparar a captura pelo Ambiente Nacional na hora, para não esperar a próxima rodada.

## Regras de negócio

### Leitura do XML

**RN-01: Como uma NFS-e é reconhecida.** Os primeiros 4.096 bytes do XML precisam conter `infNFSe`, `InfNfse`, `CompNfse` ou `sped.fazenda.gov.br/nfse`. Isso vale no caminho comum de persistência, ou seja, para o upload e para qualquer página do SIEG.
`NfseXml.java:66-74`; `FiscalXmlIntegrationService.java:375-383`.

**RN-02: Layouts e chave.**

| Layout | Elemento | Chave |
|---|---|---|
| NACIONAL | `infNFSe` | dígitos do atributo `Id` (50 dígitos). Sem `Id` ou sem `infDPS`, o XML é inválido |
| ABRASF | `InfNfse` | sintética: `ABRASF-{município gerador}-{documento do prestador}-{número}`, com no máximo 80 caracteres. Município ou prestador ausente vira `0`. Sem `Numero`, o XML é inválido |

`NfseXml.java:76-104`, `:157-182`.

**RN-03: Campos e retenções no layout nacional.**

| Campo | Origem no XML |
|---|---|
| Número | `nNFSe` |
| Emissão / competência | `dhEmi` / `dCompet` |
| Prestador | `emit`, ou `prest` se `emit` faltar |
| Tomador | `toma` |
| Município de incidência | `cLocIncid` |
| Serviço | `cTribNac`, `cTribMun`, `xDescServ` (até 1.000 caracteres) |
| Valor do serviço | `vServ` |
| Deduções | `vDR` |
| Base de cálculo | `vBC` |
| Alíquota | `pAliqAplic`, ou `pAliq` |
| ISS | `vISSQN` |
| Valor líquido | `vLiq` |

Retenções:
- ISS retido quando `tpRetISSQN` é `2` ou `3`; nesse caso o valor retido é o `vISSQN`.
- PIS/COFINS retidos só quando `tpRetPisCofins = 1`.
- IRRF = `vRetIRRF`, CSLL = `vRetCSLL`, INSS = `vRetCP`.
- O layout nacional nunca vem marcado como cancelado pelo próprio XML; o cancelamento chega por evento (RN-16).

`NfseXml.java:97-155`.
Fonte da regra: o código cita só "Retentions follow the roadmap (M1)" (`NfseXml.java:25-28`), sem norma.

**RN-04: Campos e retenções no layout ABRASF.**
- ISS retido quando `IssRetido = 1`. O valor retido é `ValorIssRetido` quando > 0; senão, `ValorIss`.
- PIS/COFINS contam como retidos quando algum dos dois é > 0. Segundo o comentário do código, o layout só preenche esses campos quando há retenção.
- IR (`ValorIr`), CSLL, INSS: os valores são gravados como vêm.
- A nota é cancelada se o XML contém `NfseCancelamento` ou `CancelamentoNfse`.

`NfseXml.java:88-92`, `:157-212`.

**RN-05: Datas.** Sem data de emissão, `issue_date` recebe o início do dia da competência. Sem competência, `competence` recebe a data de emissão.
`ServiceInvoiceService.java:189-192`.

**RN-06: Direção.** O CNPJ/CPF da empresa (só dígitos) é comparado com o prestador e o tomador:

| A empresa é... | Direção |
|---|---|
| o prestador | `PRESTADA` |
| o tomador | `TOMADA` |
| nenhum dos dois | `TERCEIRO` |

`ServiceInvoiceService.java:171-180`.

**RN-07: Gravação e duplicidade.** A unicidade é por `(empresa, chave)`. Uma nota já conhecida não é regravada: ela só ganha a marca da nova fonte (RN-17) e, se vier cancelada, passa a `canceled = true`. Ela nunca volta de cancelada para ativa.
`ServiceInvoiceService.java:117-142`; `V37__ServiceInvoices.java:53`.

**RN-08: Arquivo.** O arquivo vai para o mesmo storage das NF-e, em `{companyId}/NFSE-{chave}.xml` (ver a spec de gestão de documentos).
`ServiceInvoiceService.java:137`.

### Captura pelo SIEG

**RN-09: Consulta ao SIEG.**
- `TipoXml = 3`, nos dois lados: como prestador (`CnpjEmit`) e como tomador (`CnpjDest`). O código observa que o SIEG não documenta qual filtro é qual. Por isso a direção sai da própria nota (RN-06).
- O período é quebrado em janelas de no máximo 1 mês, porque o SIEG recusa intervalos acima de 2 meses.
- Páginas de 50 notas, até 20 páginas por lado e por janela. A leitura para quando uma página volta com menos de 50.
- Sem `BaixarEventos`.
- Datas em UTC: início às 00:00Z e fim às 23:59:59.999Z.

`ServiceInvoiceService.java:36-40`, `:71-115`, `:224-230`.

**RN-10: Rotina diária do SIEG.** Roda dentro do tick do `FiscalXmlSyncScheduler`, para as empresas elegíveis ao SIEG (`FISCAL_XML_IMPORT` + `SIEG_INTEGRATION`), uma vez por dia por empresa.
- Na primeira vez, cobre do dia 1 de três meses atrás até hoje.
- Nas seguintes, cobre de hoje − 35 dias até hoje (NFS-e de prefeitura chega atrasada; a cobertura não muda).
- O dia (`service_invoices_checked_on`) só é marcado se a busca der certo.
- **Uso da contagem do SIEG (2026-11-27), `TAXIFY_SIEG_NFSE_COUNT_GATE`:**
  - `off`: baixa os 35 dias todo dia, como sempre;
  - `shadow` (**padrão**, decisão do Tony): baixa como `off` e, para o mês corrente, compara a contagem de NFS-e que o `contar-xmls` das NF-e já trouxe nesta tentativa (sem chamada a mais; filtro CnpjEmit + filtro CnpjDest) com as NFS-e do mês que o SIEG já tinha entregue (`found_in_sieg_at`, qualquer direção). Uma linha de log por empresa, só com ids e contagens, diz o que o modo `on` teria feito; "would have MISSED" (WARN) = o modo `on` teria pulado um mês que trouxe nota nova. Sem contagem nesta tentativa, a linha diz "not evaluated". **O modo sombra avalia só o mês corrente:** os meses anteriores que o modo `on` confere (com contagem própria) não aparecem no log, porque contá-los custaria 2 chamadas por mês por empresa além do download antigo; a conferência de 2 semanas valida a contagem do SIEG contra as entregas, não a cobertura dos meses anteriores. Conferir por 2 semanas antes de ligar;
  - `on`: os 35 dias em janelas de mês de calendário; cada janela só é baixada quando a contagem passa do que o SIEG já entregou. O mês corrente usa a contagem já paga; cada mês anterior alcançado por hoje − 35 tem `contar-xmls` próprio (2 chamadas, emitente e tomador). Contagem que falha = desconhecida: baixa a janela. As NFS-e "já entregues" são contadas na mesma fronteira da janela do SIEG (dias UTC, convertidos para Recife: 21:00 do dia anterior a 20:59:59 do último dia) - uma NFS-e de 30/11 22h de Recife conta em dezembro, como no SIEG, e não esconde uma NFS-e atrasada de novembro.
- O SIEG não documenta se a contagem de NFS-e respeita CnpjEmit/CnpjDest: só o total (soma dos dois filtros) é comparado, nunca a direção. Uma nota em que a empresa é prestadora e tomadora conta duas vezes no SIEG (o mês fica sempre "com diferença" e é baixado).

`FiscalXmlSyncScheduler.java:77-79`, `:198-230`; `V38__SyncControlServiceInvoices.java`.

**RN-11: Busca manual no SIEG.** `POST /notas-servico/sincronizar?companyId&inicio&fim` roda em segundo plano, numa thread virtual, e responde na hora `{"iniciado": true}`. Se já existe uma busca da mesma empresa em andamento nesta instância, responde `{"iniciado": false}`. O resultado não é devolvido nem guardado; falhas só vão para o log.
`ServiceInvoiceResource.java:70-93`.

### Captura pelo Ambiente Nacional (ADN)

**RN-12: Quem entra na captura ADN.** A empresa entra quando atende às duas condições:
- o escritório tem um certificado A1 com a mesma raiz de CNPJ da empresa;
- o plano vigente tem `FISCAL_XML_IMPORT` **e** `NFSE_NATIONAL_INTEGRATION`.

Não há filtro de UF.
`NfseAdnScheduler.java:76-102`; `PlanServiceEnum.java:21-27`.

**RN-13: Execução.** Cron `nfse.adn.cron` (padrão `0 25 * * * *`), numa thread virtual. Se a execução anterior ainda roda, a nova não começa. Uma empresa que falha não para as demais.
`NfseAdnScheduler.java:50-74`.

**RN-14: Leitura por NSU.**
- Chamada: `GET {endpoint}/DFe/{ult_nsu}?cnpjConsulta={cnpj}&lote=true`, com mTLS, HTTP/1.1 e timeout de 60 s.
- Cada documento vem em `ArquivoXml`, como base64 de gzip; base64 puro também é aceito.
- Até `max-batches-per-company` lotes (padrão 20), com pausa de 1 s entre eles.
- Depois de cada lote, `ult_nsu = max(anterior, maior NSU do lote)`.
- Lote com menos de 50 documentos, ou NSU que não avançou, significa empresa em dia. Nesse caso, `next_query_at` = agora + `wait-minutes-when-caught-up` (padrão 60).
- Pré-condições, com ERRO e mensagem: CNPJ presente, certificado presente, certificado não vencido, certificado que abre.

`NfseAdnService.java:38-39`, `:107-162`; `AdnClient.java:41-63`, `:108-117`; `AdnProperties.java:15-27`.

**RN-15: Respostas do ADN.**

| Resposta | Tratamento |
|---|---|
| HTTP 429 | `next_query_at` = agora + 60 min, com nota explicativa; não é erro |
| `NENHUM_DOCUMENTO_LOCALIZADO`, ou 2xx sem documentos e sem erros | em dia (RN-14) |
| `REJEICAO`, erros sem documentos, ou HTTP >= 400 que não seja "nada novo" | ERRO com os erros (`Codigo Descricao Complemento`); NSU mantido |

`AdnResponse.java:29-41`; `AdnClient.java:65-106`; `NfseAdnService.java:138-151`.

**RN-16: Eventos do ADN.** Só o cancelamento (101101) e o cancelamento por substituição (105102) são tratados. Eles são reconhecidos pelo `TipoEvento` ou pelo texto `e101101`/`e105102` no XML. A chave vem do campo `ChaveAcesso` (50 dígitos) ou do `chNFSe` dentro do XML, e a nota correspondente da empresa é marcada como cancelada. Os outros eventos são ignorados.
`NfseAdnService.java:40-42`, `:164-194`.

**RN-17: Fontes da nota.** O primeiro SIEG que traz a nota preenche `found_in_sieg_at`; o primeiro ADN preenche `found_in_adn_at`. `source_system` guarda a origem da gravação: `SIEG`, `ADN`, `UPLOAD`, `SEFAZ_DIST`.
`ServiceInvoiceService.java:157-169`; `NfseAdnService.java:37`.

**RN-18: Histórico de execuções.** Cada captura ADN grava uma linha em `capture_runs` (serviço `NFSE_ADN`): status OK, SEM_MOVIMENTO ou ERRO, quantidade de notas novas e detalhe de até 500 caracteres.
`NfseAdnService.java:96-105`, `:196-211`, `:225-251`.

**RN-19: Captura ADN manual.** `POST /companies/{id}/nfse-nacional/capturar` exige o plano (`error.nfse.adn.not.in.plan`) e respeita o `next_query_at`.
`CompanyResource.java:120-136`; `NfseAdnService.java:74-85`.

### Consulta

**RN-20: Busca.**
- Empresa obrigatória.
- Filtros:
  - período por `issue_date`, em dias inteiros; data ausente deixa o limite aberto;
  - direção: `PRESTADA`, `TOMADA`, `TERCEIRO`;
  - participante: parte do nome em minúsculas, ou os dígitos do documento, para prestador e tomador;
  - número exato;
  - "somente com retenção": ISS retido, ou IRRF/CSLL/INSS > 0, ou PIS/COFINS retidos com valor > 0;
  - fonte `SO_SIEG`/`SO_ADN`: a nota está em uma fonte há mais de 48 h e nunca apareceu na outra.
- Notas excluídas ficam fora. Ordem por emissão, da mais recente para a mais antiga.
- Página padrão de 50 notas, máximo de 200.
- A descrição na lista é cortada em 140 caracteres.

`ServiceInvoiceQueryService.java:29`, `:44-58`, `:148-166`; `ServiceInvoiceRepository.java:21-37`; `IssuePeriod.java:16-24`.

**RN-21: Totais.** Consideram só as notas **não canceladas** do período, por `issue_date`. Os demais filtros são ignorados. O cálculo é separado por `PRESTADA` e `TOMADA`; `TERCEIRO` não entra.

| Total | Fórmula |
|---|---|
| quantidade | `count` |
| valor | Σ `valor_servico` |
| ISS | Σ `valor_iss` |
| ISS retido | Σ `valor_iss_retido`, só das notas com `iss_retido` |
| federal | Σ (IRRF + CSLL + INSS + (PIS + COFINS, se `pis_cofins_retidos`)) |
| total retido | ISS retido + federal |

Valores nulos contam como zero. Não há arredondamento explícito: é soma de `BigDecimal` com a escala do banco, `decimal(15,2)`. O DTO também traz `onlySieg` e `onlyAdn`, contados nas mesmas notas com o corte de 48 h.
`ServiceInvoiceQueryService.java:60-72`, `:102-146`; `ServiceInvoiceRepository.java:45-49`.

**RN-22: Detalhe e XML.** O detalhe traz todos os campos e o total retido (RN-21 aplicada a uma nota). O XML é servido como `nfse-{id}.xml`. Nota inexistente ou excluída responde 400 `error.generic.bad.request`. Empresa de outro escritório ou sem vínculo é barrada pelo `companyValidation.checkExist`. Arquivo ilegível responde 400 `error.sieg.xml.storage.failed`.
`ServiceInvoiceQueryService.java:74-95`, `:120-125`; `ServiceInvoiceResource.java:57-68`.

**RN-23: Uso pelas Retenções (M1), só como referência.** O módulo de retenções lê as NFS-e não canceladas por **competência**, e não por data de emissão.
`ServiceInvoiceRepository.java:39-43`.

## Critérios de aceite

1. **(RN-01/RN-02)** Dado um XML nacional com `infNFSe Id="NFS{50 dígitos}"`, quando ele é importado, então é gravado em `service_invoices` com layout `NACIONAL` e chave igual aos 50 dígitos.
2. **(RN-02)** Dado um XML ABRASF do município 2604106 (fictício), prestador 12345678000190 (fictício) e número 1500, quando ele é importado, então a chave é `ABRASF-2604106-12345678000190-1500`.
3. **(RN-02)** Dado um XML ABRASF sem `Numero`, quando ele é importado, então conta como inválido e não é gravado.
4. **(RN-03)** Dado uma NFS-e nacional com `vServ = 1000.00`, `vISSQN = 50.00`, `tpRetISSQN = 2`, `vRetIRRF = 15.00`, `vRetCSLL = 10.00`, `vRetCP = 110.00`, `tpRetPisCofins = 1`, `vPis = 6.50` e `vCofins = 30.00`, quando ela é gravada, então ISS retido = 50,00, federal = 15,00 + 10,00 + 110,00 + 6,50 + 30,00 = 171,50 e total retido = 221,50.
5. **(RN-03)** Dado a mesma nota com `tpRetPisCofins = 2`, então PIS/COFINS ficam fora do federal: federal = 135,00 e total retido = 185,00.
6. **(RN-04)** Dado uma NFS-e ABRASF com `IssRetido = 1`, `ValorIss = 40.00` e sem `ValorIssRetido`, então o ISS retido = 40,00.
7. **(RN-04)** Dado uma ABRASF com `NfseCancelamento`, quando ela é gravada, então `canceled = true` e ela fica fora dos totais.
8. **(RN-06)** Dado uma empresa de CNPJ igual ao do tomador, quando a nota é gravada, então a direção é `TOMADA`.
9. **(RN-07)** Dado uma nota já gravada e ativa, quando ela chega de novo cancelada, então passa a `canceled = true` e conta como duplicada.
10. **(RN-09)** Dado o período de 2026-08-15 a 2026-11-20, quando o SIEG é consultado, então as janelas são [08-15, 09-14], [09-15, 10-14], [10-15, 11-14], [11-15, 11-20], cada uma consultada como prestador e como tomador.
11. **(RN-10)** Dado uma empresa nunca verificada e o dia 2026-11-26, quando o tick roda, então a busca vai de 2026-08-01 a 2026-11-26. No dia seguinte, a busca vai de 2026-10-23 a 2026-11-27.
12. **(RN-10)** Dado uma falha na busca de NFS-e, então `service_invoices_checked_on` não muda e o próximo tick tenta de novo.
13. **(RN-11)** Dado uma busca manual em andamento para a empresa, quando o usuário clica de novo, então a resposta é `{"iniciado": false}`.
14. **(RN-12)** Dado uma empresa com A1 e plano sem `NFSE_NATIONAL_INTEGRATION`, quando o agendador ADN roda, então ela não é capturada. Quando o administrador chama "Capturar", então a resposta é 400 `error.nfse.adn.not.in.plan`.
15. **(RN-14)** Dado `ult_nsu = 100` e um lote de 50 documentos com NSU de 101 a 150, quando a captura roda, então `ult_nsu = 150` e um novo lote é pedido. Com um lote de 12 documentos, então `next_query_at` = agora + 60 min.
16. **(RN-15)** Dado HTTP 429, então o status não é ERRO, o detalhe explica a espera e `next_query_at` = agora + 60 min.
17. **(RN-15)** Dado `StatusProcessamento = REJEICAO` com erros, então o status é ERRO, o detalhe traz os erros e o NSU não muda.
18. **(RN-16)** Dado um evento 101101 de uma chave que a empresa tem, quando o lote é lido, então a nota fica cancelada e o detalhe da execução conta "1 NFS-e cancelada(s)".
19. **(RN-20)** Dado "somente com retenção", então uma nota sem ISS retido, com IRRF, CSLL e INSS zerados e PIS/COFINS não retidos fica fora.
20. **(RN-21)** Dado uma nota `TERCEIRO` no período, então ela não entra nos totais de prestadas nem de tomadas.
21. **(RN-22)** Dado o id de uma nota de outro escritório, quando o detalhe é pedido, então o acesso é negado pela checagem da empresa.

## Endpoints da API

Base: `/taxify-service/api/v1`.

| Método | Caminho | Permissão | O que faz | Front |
|---|---|---|---|---|
| POST | `/notas-servico/buscar` | `INVOICES_VIEW` | Lista paginada (RN-20) | `/notas-servico` (`features/service-invoices`) |
| POST | `/notas-servico/totais` | `INVOICES_VIEW` | Totais por direção e confronto (RN-21) | `/notas-servico` |
| GET | `/notas-servico/{id}` | `INVOICES_VIEW` | Detalhe (RN-22) | `/notas-servico` |
| GET | `/notas-servico/{id}/xml` | `INVOICES_VIEW` | XML bruto | `/notas-servico` |
| POST | `/notas-servico/sincronizar?companyId&inicio&fim` | `INVOICES_SYNC` | Busca no SIEG em segundo plano (RN-11) | `service-invoices.component.ts:225-232` |
| GET | `/companies/{id}/nfse-nacional` | `COMPANIES_VIEW` | Última execução ADN e se o plano inclui | `company-certificate.component.ts:249` |
| POST | `/companies/{id}/nfse-nacional/capturar` | ADMIN (`@AdminOnly(writeOnly)` da `CompanyResource`) | Captura ADN agora (RN-19) | `company-certificate.component.ts:257` |
| POST | `/integrations/xmls/importar` | `INVOICES_SYNC` | Upload manual; uma NFS-e vai para `service_invoices` (RN-01) | `/notas-fiscais` (botão "Importar XML") |

A tela também é acessada pelos atalhos de `/pendencias` e `/retencoes` (`pendencies.component.ts:267`, `retentions.component.ts:204`).

## Dados e integrações

**Tabelas**
- `service_invoices` (V37 + V42):
  - identificação: chave (até 80 caracteres), layout, número, código de verificação;
  - datas: emissão e competência;
  - partes: direção, prestador (documento, nome, município) e tomador;
  - serviço: município de incidência, códigos e descrição;
  - valores, todos `decimal(15,2)`, exceto a alíquota (`decimal(9,4)`);
  - indicadores de ISS retido e de PIS/COFINS retidos;
  - situação: cancelada, caminho do XML, origem e datas por fonte.
  - Chave única `(company_id, access_key)`; índice `(company_id, issue_date)`.
- `nfse_adn_control` (V41): `ult_nsu` (bigint), `next_query_at`, última execução.
- `fiscal_xml_sync_control.service_invoices_checked_on` (V38).
- `capture_runs` (serviço `NFSE_ADN`).

**Integrações externas**
- **SIEG** `POST /api/v1/baixar-xmls` com `TipoXml 3`. Mesmo cliente, mesma chave do escritório e mesmo limitador de 2 req/min da captura de NF-e.
- **ADN**, produção `https://adn.nfse.gov.br/contribuintes`; produção restrita `https://adn.producaorestrita.nfse.gov.br/contribuintes`. Autenticação por mTLS com o A1.

## Configuração

| Variável | Propriedade | Padrão |
|---|---|---|
| `NFSE_ADN_ENABLED` | `nfse.adn.enabled` | true |
| `NFSE_ADN_CRON` | `nfse.adn.cron` | `0 25 * * * *` |
| `NFSE_ADN_PRODUCTION` | `nfse.adn.production` | true (false = produção restrita) |
| (sem variável) | `nfse.adn.max-batches-per-company` / `pause-between-batches-millis` / `timeout-seconds` / `wait-minutes-when-caught-up` | 20 / 1000 / 60 / 60 |
| ver spec de captura | `fiscal-xml.sync.*`, `sieg.*` | a rotina diária de NFS-e do SIEG segue o agendador SIEG |

Constantes no código, sem configuração:
- 35 dias de janela diária e 3 meses na primeira busca (`FiscalXmlSyncScheduler.SERVICE_INVOICE_LOOKBACK_DAYS` / `SERVICE_INVOICE_FIRST_MONTHS`); o modo de uso da contagem é `TAXIFY_SIEG_NFSE_COUNT_GATE` (`off` | `shadow` | `on`, padrão `shadow`);
- 50 por página e 20 páginas no SIEG (`ServiceInvoiceService.java:38-40`);
- 48 h no confronto (`FiscalXmlQueryService.java:145`).

## Testes existentes

- `integration/nfse/NfseXmlTest`: leitura dos layouts nacional e ABRASF.
- `integration/nfse/AdnClientTest`: parse da resposta JSON e decodificação.
- `service/NfseAdnServiceTest`: lê até ficar em dia, cancelamentos, nada novo, rejeição, 429, espera e certificado.
- `service/ServiceInvoiceServiceTest`: direção, duplicada com cancelamento, marca de fonte, evento de cancelamento, inválido, SIEG como prestador e tomador paginado, janelas de 1 mês.
- `service/FiscalXmlSyncSchedulerTest#serviceInvoicesOnceADayThreeMonthsBackTheFirstTime`.
- `service/retention/RetentionCalculatorTest`: consumidor das NFS-e (M1).

**Lacunas**
- `NfseAdnScheduler` (elegibilidade e não sobreposição) não tem teste.
- `ServiceInvoiceQueryService` (filtros, totais, `onlySieg`/`onlyAdn`, acesso de outro escritório) não tem teste.
- `ServiceInvoiceResource` (concorrência do `/sincronizar` e permissões) não tem teste.
- Não há teste de valores com vírgula de milhar (`1.234,56`) nem de chave ABRASF com caracteres especiais.

## Fora do escopo / limitações conhecidas

- Não existe exportação em lote, ZIP nem PDF (DANFSe) de NFS-e.
- A busca manual no SIEG não devolve resultado nem progresso, e não usa o `IntegrationJobRunner`. O controle de concorrência vale só para a instância atual, em memória.
- O expurgo (`FiscalXmlPurgeScheduler`) e a migração para o S3 (`FiscalXmlStorageMigration`) tratam só `fiscal_xml_documents`. Os arquivos de NFS-e não são expurgados nem copiados para o S3.
- Outros eventos do ADN, além do cancelamento, são descartados.
- A tela não mostra o histórico de execuções do ADN; ela aparece só como saúde em `/pendencias`.

## Perguntas em aberto

1. **Emissão × competência.** A tela de NFS-e (busca e totais) filtra pela **data de emissão**. As Retenções (M1) usam a **competência** (RN-23). No mesmo "mês", os totais da tela podem não bater com o módulo de retenções. Qual data deve valer na tela?
2. **Retenção no ABRASF.** PIS/COFINS são tratados como retidos sempre que têm valor > 0, e IR, CSLL e INSS sempre somam no federal (RN-04 e RN-21). A premissa do código é "o layout só preenche quando retido". O Tony valida?
3. **Códigos do layout nacional.** Validar os códigos que o código usa no layout nacional: ISS retido com `tpRetISSQN` 2 ou 3, PIS/COFINS retidos só com `tpRetPisCofins = 1`, e IRRF, CSLL e INSS sempre como retidos (`vRet*`) (RN-03).
4. **Números com separador de milhar.** O parse de valores troca `,` por `.` (`NfseXml.java:274-280`). Um valor como `1.234,56` vira nulo sem aviso e conta como zero nos totais. Algum município manda nesse formato?
5. **Chave ABRASF sem sanitização.** A chave sintética do ABRASF usa número e município vindos do XML sem sanitizar e entra no nome do arquivo (`NFSE-{chave}.xml`). No storage local, um XML malicioso no upload poderia gerar um caminho inválido ou fora da pasta. Vale sanitizar?
6. **Notas TERCEIRO.** Uma NFS-e em que a empresa não é prestadora nem tomadora (`TERCEIRO`) é gravada, mas fica fora dos totais. O correto é rejeitar no upload?
7. **ADN 404 sem corpo.** Hoje uma resposta 404 sem corpo do ADN vira ERRO (`AdnResponse.failed`), enquanto 404 com `NENHUM_DOCUMENTO_LOCALIZADO` vira "em dia". Esse é o comportamento esperado?
8. **Arquivos de NFS-e fora do expurgo e do S3.** Os arquivos de NFS-e gravados em disco antes da mudança para o S3 continuam no volume, porque a migração não os copia. Eles também não são expurgados. É preciso decidir a retenção e a migração dos arquivos de NFS-e.
9. **Inexistente responde 400.** O detalhe e o XML de uma NFS-e inexistente respondem 400. As NF-e respondem 404 para não revelar se o recurso existe (padrão do README). Padronizar?
