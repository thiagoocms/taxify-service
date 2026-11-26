# Retenções de NF-e e NFS-e (a recolher × a compensar) — spec as-built

## Status

**Implementado** (roadmap M1). Spec retroativa: descreve o código atual e não propõe mudança.

- Commit de referência: `2268cfb` (retenções M1: a recolher, a compensar e informativo por competência).
- Migrations: `V37__ServiceInvoices` (NFS-e, origem dos valores retidos), `V39__FiscalXmlDocumentRetentions` (retTrib da NF-e: `ret_pis`, `ret_cofins`, `ret_csll`, `ret_irrf`, `ret_inss`) e `V40__RetentionReviews` (conferência por linha).
- Código: `rest/RetentionResource`, `service/retention/RetentionCalculator`, `service/retention/RetentionService`, `dto/retention/RetentionDTOs`, `integration/fiscalxml/NfeRetentions`, `integration/nfse/NfseXml` (leitura dos campos retidos), `service/pendency/PendencyFinder.retentions` (pendências).

## Problema e objetivo

O analista abria nota por nota (NFS-e tomadas e prestadas, NF-e com retTrib) para listar o que cada cliente reteve e precisa recolher (ISS ao município, IRRF, PIS/COFINS/CSLL, INSS) e o que sofreu de retenção e pode compensar. Também precisava identificar retenção provavelmente indevida em prestador do Simples.

A tela de Retenções calcula isso a partir das notas já importadas, por competência, para uma empresa ou para a carteira. Para cada linha mostra a ação sugerida e o vencimento federal, e permite marcar a retenção como conferida.

Indicadores possíveis (não medidos hoje): linhas conferidas sobre o total da competência e retenções indevidas abertas.

## Usuários e cenário

- **Analista fiscal** (`INVOICES_VIEW`): no fechamento da competência, abre a carteira, filtra "a recolher" e confere guias e DARFs. Depois filtra "a compensar" para orientar o PGDAS-D, a DCTFWeb ou a dedução no IRPJ.
- **Analista que confere** (`INVOICES_AUDIT`): marca e desmarca linhas como conferidas.
- Frequência: mensal. As pendências de retenção são recalculadas para o mês atual e o anterior (RN-12).

## Histórias de usuário

1. Como analista fiscal, quero ver por competência tudo que meus clientes retiveram como tomadores, com o tributo, o valor, a ação e o vencimento, para não deixar de recolher nenhuma guia.
2. Como analista fiscal, quero ver o que meus clientes sofreram de retenção como prestadores, com a forma de compensar conforme o regime, para aproveitar o crédito.
3. Como analista fiscal, quero que retenção federal sofrida por optante do Simples seja sinalizada como provavelmente indevida, para pedir o ajuste ao tomador.
4. Como analista que confere, quero marcar linhas como conferidas e ver quem conferiu e quando, para acompanhar o andamento.
5. Como analista fiscal, quero exportar a lista em CSV, para trabalhar no Excel ou enviar ao cliente.

## Regras de negócio

Dinheiro em `BigDecimal`. Os valores retidos são os que estão na nota: o sistema **não recalcula** nenhuma alíquota de retenção, só lê, classifica e soma.

**RN-01. Competência.** Vem do parâmetro `competencia` (`AAAA-MM`). Vazio significa o mês anterior. Formato inválido gera 400 "Competência inválida: use AAAA-MM." (`RetentionService.java:215-224`).

**RN-02. Fontes** (`ServiceInvoiceRepository.java:39-43`, `FiscalXmlDocumentRepository.java:106-116`).
- **NFS-e:** notas da empresa não excluídas, não canceladas e com `competence` dentro do mês.
- **NF-e:** notas não excluídas, não canceladas, com **data de emissão** entre o 1º dia 00:00:00 e o último dia 23:59:59 do mês, e com algum `ret_*` > 0 ou, quando a nota não é de emissão própria, `valor_icms_st` > 0.

**RN-03. Leitura dos valores retidos da NFS-e** (`NfseXml.java:25-28, 113-152, 171-211`).
- **Padrão nacional:**
  - ISS retido quando `tpRetISSQN` é 2 ou 3, com valor = `vISSQN`.
  - PIS/COFINS só contam como retidos quando `tpRetPisCofins = 1`.
  - IRRF = `vRetIRRF`, CSLL = `vRetCSLL` e INSS = `vRetCP`.
- **ABRASF:**
  - ISS retido quando `IssRetido = 1`, com valor = `ValorIssRetido` (ou `ValorIss` se aquele faltar).
  - PIS/COFINS contam como retidos quando há `ValorPis` ou `ValorCofins` > 0.
  - IRRF = `ValorIr`, CSLL = `ValorCsll` e INSS = `ValorInss`. O layout só preenche esses campos quando há retenção.

**RN-04. Leitura da NF-e** (`NfeRetentions.java:9-53`). Os valores vêm de `total/retTrib`: `vRetPIS`, `vRetCOFINS`, `vRetCSLL`, `vIRRF` e `vRetPrev` (INSS). Valor zero ou ilegível vira nulo.

**RN-05. Natureza** (`RetentionCalculator.java:95-147`).
- NFS-e `TOMADA` gera `A_RECOLHER`: a empresa reteve como tomadora. NFS-e `PRESTADA` gera `A_COMPENSAR`: a empresa sofreu a retenção.
- NF-e de terceiro (`ownIssue = false`) gera `A_RECOLHER` (compra). NF-e de emissão própria (`ownIssue = true`) gera `A_COMPENSAR`.
- ICMS-ST em NF-e de terceiro com `valor_icms_st` > 0 gera `INFORMATIVO`, com a ação "entra na conferência da fronteira".
- NFS-e com outra direção é ignorada.

**RN-06. Quais tributos geram linha.** Só valores > 0 geram linha (`positive`, `:221-223`).
- Na NFS-e:
  - ISS só quando o ISS está marcado como retido.
  - IRRF sempre que houver valor.
  - PIS e COFINS só quando `pisCofinsRetidos`.
  - CSLL e INSS sempre que houver valor (`:108-126`).
- Na NF-e: IRRF, PIS, COFINS, CSLL e INSS (`:138-142`).

**RN-07. ISS retido sem valor** (`:108-118`). Uma NFS-e com ISS marcado como retido e valor nulo ou ≤ 0 não gera linha. Gera o issue `ISS_WITHOUT_VALUE`.

**RN-08. Ação a recolher** (`payAction`, `:170-177`):
- IRRF: "Recolher em DARF 1708."
- PIS, COFINS e CSLL: "Recolher em DARF 5952 (PIS/COFINS/CSLL)."
- INSS: "Informar na EFD-Reinf (R-2010) e recolher pela DCTFWeb."
- ISS tomado: "Guia de ISS do município de incidência (IBGE `<código>`) - vencimento conforme o município." O ISS **não tem vencimento** calculado.

**RN-09. Vencimento federal** (`federalDueDate`, `:200-210`). Para os tributos federais a recolher, o vencimento é o dia 20 do mês seguinte à competência. Se cair no sábado, vale a sexta anterior (−1 dia); no domingo, a sexta anterior (−2 dias). Feriados **não** são tratados. Linhas a compensar não têm vencimento.

**RN-10. Ação a compensar, pelo regime da empresa na competência** (`compensationAction`, `:179-198`). O regime vem do histórico; sem histórico, dos campos da empresa.
- Regime nulo: "Regime da empresa não informado - informe para saber como compensar."
- Simples ou MEI:
  - ISS: "Informar o ISS retido no PGDAS-D."
  - INSS: "Compensar na DCTFWeb (EFD-Reinf R-2020)."
  - Demais tributos: "Optante do Simples: conferir a retenção."
- Outros regimes:
  - ISS: "não recolher de novo sobre esta nota".
  - IRRF: "Deduzir do IRPJ do período".
  - CSLL: "Deduzir da CSLL do período".
  - PIS e COFINS: "Deduzir do PIS/COFINS do mês".
  - INSS: "Compensar na DCTFWeb (EFD-Reinf R-2020)".

**RN-11. Retenção provavelmente indevida** (`:149-168`). Na natureza `A_COMPENSAR`, se a empresa é Simples **ou MEI** e o tributo é IRRF, PIS, COFINS ou CSLL (não INSS e não ISS), o sistema faz duas coisas:
- marca `undue = true`, com a ação "Provável retenção indevida: optante do Simples Nacional não sofre retenção de `<tributo>` (salvo exceções). Confira e peça o ajuste ao tomador.";
- gera o issue `UNDUE`.

**RN-12. Pendências** (`PendencyFinder.java:237-257`). Para a competência atual e a anterior, cada issue vira uma pendência: `RETENTION_UNDUE` ("Retenção provavelmente indevida") ou `ISS_RETAINED_WITHOUT_VALUE` ("ISS retido sem valor"). O limite é `MAX_PER_TYPE` por mês.

**RN-13. Chave da linha e conferência** (`RetentionCalculator.java:229-233`, `RetentionService.java:101-141`).
- A chave natural é `<NFSE|NFE>:<documentId>:<tributo>:<natureza>`. A chave exposta ao front é `<companyId>|<chave natural>`, porque a mesma nota pode aparecer em duas empresas da carteira.
- Marcar uma linha cria um `retention_reviews` com `reviewed_by` (auditor atual, ou "sistema") e `reviewed_at`. Desmarcar **apaga fisicamente** o registro.
- Chaves de empresas fora do escopo do usuário, ou mal formadas, são ignoradas em silêncio. A resposta é o número de linhas alteradas.
- A conferência não guarda o valor conferido: se o valor da nota mudar depois, a linha continua conferida.

**RN-14. Escopo** (`RetentionService.java:196-212`). Com `companyId` informado, vale só essa empresa, com acesso conferido por `CompanyValidation.checkExist`; UUID inválido gera 400. Sem `companyId`, valem as empresas ativas e não excluídas do escritório do usuário, filtradas pela carteira para quem não é ADMIN. Usuário sem escritório recebe 400 `error.pendency.firm.required`.

**RN-15. Cards** (`RetentionService.java:167-184`). Há um card por natureza, sempre sobre todas as linhas da competência, ignorando os filtros de natureza e de busca. Cada card traz:
- `total`: soma de `amount`;
- `notes`: notas distintas (empresa + documento);
- `byTax`: soma por tributo;
- `pendingReview`: linhas não conferidas, **incluindo as INFORMATIVO**.

**RN-16. Filtros e ordem** (`:70-84, 143-156`).
- `natureza` filtra no servidor.
- `busca` (sem diferenciar maiúsculas) casa com o nome da empresa ou da contraparte, com CNPJ/CPF da empresa ou da contraparte (pelos dígitos) ou com o número exato da nota.
- Ordem: nome da empresa, natureza e data de emissão.

**RN-17. CSV** (`:86-99`). UTF-8 com BOM, separador `;` e textos entre aspas. Valores com 2 casas `HALF_UP` e vírgula decimal; datas em `dd/MM/yyyy`. Colunas: Empresa, CNPJ, Competência, Natureza, Tributo, Documento, Número, Emissão, Contraparte, CNPJ/CPF contraparte, Valor do documento, Valor retido, Vencimento, Ação, Indevida, Conferida. São as mesmas linhas e filtros da tela. Nome do arquivo: `retencoes-<competencia>.csv`.

**RN-18. Valor do documento.** Na NFS-e é `valorServico`; na NF-e, `valorTotalNota`.

## Critérios de aceite (comportamento atual)

- **CA-01 (RN-05, RN-08, RN-09, RN-10, RN-11).** Dado o caso de aceite do roadmap (uma NFS-e tomada com ISS, IRRF, PIS/COFINS, CSLL e INSS retidos; uma NFS-e prestada por empresa do Simples com retenção federal; uma compra com ICMS-ST de 120,00), quando a competência é calculada, então: a recolher = 221,50, a compensar = 70,00, informativo = 120,00 e há 1 issue `UNDUE` na empresa prestadora (`RetentionCalculatorTest.theRoadmapAcceptanceCase`).
- **CA-02 (RN-08, RN-09).** Dado IRRF retido em NFS-e tomada na competência 2026-11, quando calculado, então a ação contém "DARF 1708" e o vencimento é 18/12/2026, porque 20/12/2026 é domingo.
- **CA-03 (RN-06).** Dado uma NFS-e nacional com `tpRetPisCofins` diferente de 1, quando calculada, então PIS e COFINS não geram linha e IRRF, CSLL e INSS geram.
- **CA-04 (RN-10, RN-11).** Dado uma prestadora do Lucro Real com IRRF sofrido, quando calculada, então a ação contém "IRPJ" e não há issue.
- **CA-05 (RN-07).** Dado uma NFS-e com ISS retido e sem valor, quando calculada, então não há linha de ISS e existe o issue `ISS_WITHOUT_VALUE`.
- **CA-06 (RN-05).** Dado uma NF-e própria com retIrrf de 15,00 e retPis de 6,50, quando calculada, então as duas linhas são `A_COMPENSAR`, com tipo NFE, somando 21,50.
- **CA-07 (RN-09).** Dado a competência 2026-05, quando o vencimento é calculado, então ele é 19/06/2026, porque 20/06 é sábado.
- **CA-08 (RN-02).** Dado uma NFS-e cancelada com IRRF retido, quando a competência é calculada, então ela não aparece.
- **CA-09 (RN-13).** Dado um usuário com `INVOICES_AUDIT` que marca 3 linhas, quando ele recarrega a tela, então as 3 vêm com `reviewed = true`, `reviewedBy` e `reviewedAt`. Ao desmarcar, voltam a `false`.
- **CA-10 (RN-13, RN-14).** Dado uma chave de empresa fora da carteira do usuário, quando ele tenta marcar, então nada é gravado e a resposta conta 0 para essa chave.
- **CA-11 (RN-15).** Dado o filtro natureza = A_RECOLHER, quando a tela carrega, então os 3 cards continuam com os totais de todas as naturezas.

## Casos de teste com números (fictícios)

| # | Entrada | Esperado |
|---|---|---|
| T1 | NFS-e TOMADA, competência 2026-10, serviço de 10.000,00, ISS retido 200,00, IRRF 150,00, `tpRetPisCofins=1` com PIS 65,00 e COFINS 300,00, CSLL 100,00, INSS 1.100,00 | 6 linhas A_RECOLHER. Federais vencem em 20/11/2026 (sexta). ISS sem vencimento. Card A_RECOLHER = 1.915,00 em 1 nota |
| T2 | O mesmo do T1 com `tpRetPisCofins=2` | Sem PIS/COFINS. A_RECOLHER = 1.550,00 |
| T3 | NFS-e PRESTADA por empresa do Simples, IRRF 15,00 e INSS 110,00 | IRRF: A_COMPENSAR com `undue=true` + issue UNDUE. INSS: A_COMPENSAR "Compensar na DCTFWeb (EFD-Reinf R-2020)." com `undue=false` |
| T4 | O mesmo do T3 com a empresa no Lucro Presumido | IRRF "Deduzir do IRPJ do período." Sem issue |
| T5 | O mesmo do T3 com a empresa sem regime | Ação "Regime da empresa não informado..." Sem `undue` (só Simples/MEI marcam) |
| T6 | NFS-e com ISS retido e `valor_iss_retido` nulo | Sem linha ISS. Issue ISS_WITHOUT_VALUE e pendência ISS_RETAINED_WITHOUT_VALUE |
| T7 | NF-e de compra (terceiro) com ICMS-ST de 120,00 e sem retTrib | 1 linha INFORMATIVO ICMS_ST 120,00 |
| T8 | NF-e própria com vIRRF 15,00 e vRetPIS 6,50 | 2 linhas A_COMPENSAR, total 21,50 |
| T9 | NF-e de compra com vRetPrev 0,00 | Sem linha (zero vira nulo) |
| T10 | NFS-e cancelada, ou nota com `deleted=true` | Fora da tela |
| T11 | Competência 2026-11 (20/12 é domingo) | Vencimento 18/12/2026 |
| T12 | `competencia=11/2026` | 400 "Competência inválida: use AAAA-MM." |
| T13 | Busca "12.345.678" (CNPJ fictício formatado) | Casa pelos dígitos com o CNPJ da empresa ou da contraparte |

## Endpoints da API

Base: `/api/v1/retencoes`. A permissão da classe é `INVOICES_VIEW`.

| Método | Caminho | Permissão | Descrição |
|---|---|---|---|
| GET | `/retencoes?companyId=&competencia=&natureza=&busca=` | INVOICES_VIEW | Tela: competência, cards e linhas |
| GET | `/retencoes/csv?...` (mesmos parâmetros) | INVOICES_VIEW | CSV (`text/csv; charset=UTF-8`) |
| POST | `/retencoes/conferencia` body `{companyId?, keys[] (não vazio), reviewed}` | INVOICES_AUDIT | Marca ou desmarca; retorna a quantidade alterada |

Telas (taxify-front):
- `/retencoes` (`features/retentions/retentions.component`), protegida por `permissionGuard('INVOICES_VIEW')`.
- A tela tem navegação de mês, seleção de empresa (autocomplete) ou carteira, busca, cards por natureza (alternam o filtro no servidor) e atalhos locais "indevidas" e "pendentes". O front conta como pendentes as não conferidas **excluindo** as INFORMATIVO.
- Seleção múltipla e os botões "Marcar como conferidas" e "Desfazer conferência" só aparecem com `INVOICES_AUDIT`. Há também "Exportar CSV".
- Clicar na nota abre `/notas-fiscais/:id` (NF-e) ou `/notas-servico` (NFS-e, sem abrir a nota).
- Serviço: `core/services/retention.service.ts`.

A tela de NF-e (`invoice-detail.component`) e o relatório mensal (spec `2026-11-26-relatorios.md`) também mostram retenções.

## Dados

- `fiscal_xml_documents` (V39): `ret_pis`, `ret_cofins`, `ret_csll`, `ret_irrf`, `ret_inss` em `decimal(15,2)`. São preenchidos na importação e, para as notas antigas, pela releitura dos XMLs (versão 5 do leitor, `FiscalXmlTaxDetailBackfill`).
- `service_invoices` (V37): `direction` (PRESTADA/TOMADA), `competence`, `valor_servico`, `iss_retido`, `valor_iss_retido`, `valor_irrf`, `valor_pis`, `valor_cofins`, `pis_cofins_retidos`, `valor_csll`, `valor_inss`, `municipio_incidencia`, `canceled`.
- `retention_reviews` (V40): `id`, `company_id` (FK), `natural_key` (120), `reviewed_by`, `reviewed_at`, `deleted` e auditoria. Chave única `(company_id, natural_key)`. A coluna `deleted` existe, mas a desmarcação faz delete físico.
- As retenções **não são persistidas**: são recalculadas a cada requisição a partir das notas.
- Os dados são sigilosos (CNPJ/CPF de contrapartes, valores). O acesso segue a carteira do usuário (RN-14).

## Configuração

Nenhuma. Códigos de DARF, textos de ação e a regra do dia 20 estão fixos em `RetentionCalculator`.

## Testes existentes

- `service/retention/RetentionCalculatorTest`: caso de aceite do roadmap, ação e vencimento por tributo, PIS/COFINS só informados, prestadora fora do Simples, ISS sem valor, NF-e de venda com retenção, vencimento no fim de semana.
- `integration/nfse/NfseXmlTest`: leitura dos campos retidos (nacional e ABRASF).
- `service/pendency/PendencyFinderTest`: cita o `RetentionCalculator` (mock).

**Lacunas de teste:**
- Nenhum teste de `RetentionService`: cards, filtro de natureza e busca, ordenação, CSV (BOM, vírgula, aspas), conferência (marcar, desmarcar, chave de outra empresa, chave mal formada), escopo da carteira.
- Nenhum teste de `NfeRetentions` (retTrib ausente, valor zero).
- Nenhum teste de MEI como indevido, de ISS sofrido por empresa do Simples ("Informar o ISS retido no PGDAS-D") nem de nota de emissão própria de **entrada** com retTrib.
- Nenhum teste de permissão (`INVOICES_AUDIT` em `/conferencia`).
- Nenhum teste de front.

## Fora do escopo / limitações conhecidas

- O sistema não valida a alíquota nem a base da retenção; usa o valor informado na nota.
- Não calcula o vencimento do ISS (depende do município) e não considera feriados no vencimento federal.
- Não gera DARF, não transmite a EFD-Reinf e não concilia com o que foi pago.
- A sinalização de indevida só olha o regime da própria empresa quando ela sofreu a retenção. Não verifica se o **prestador** de uma NFS-e tomada é do Simples (caso em que a empresa talvez não devesse ter retido).
- ICMS-ST é só informativo: o cálculo está na Fronteira PE.
- A NFS-e é recortada por competência e a NF-e por data de emissão.

## Perguntas em aberto

1. **NF-e própria de entrada.** `ownIssue = true` gera A_COMPENSAR em qualquer direção. Uma nota de entrada emitida pela própria empresa com retTrib seria classificada errado. Precisa considerar `direction`?
2. **CSLL sem flag.** Na NFS-e nacional, PIS/COFINS dependem de `tpRetPisCofins = 1`, mas a CSLL (`vRetCSLL`) entra sempre que tem valor. Está certo?
3. **MEI como indevido.** A regra trata o MEI igual ao Simples para retenção indevida de IRRF, PIS, COFINS e CSLL. Tony, confirma? E as "exceções" citadas na mensagem: quais são? Devem ser tratadas?
4. **Tomada de prestador do Simples.** Devemos alertar quando a empresa reteve IRRF, PIS, COFINS ou CSLL de um prestador optante do Simples? Hoje o sistema não alerta.
5. **Vencimento federal.** Antecipar para o dia útil anterior no fim de semana vale para DARF 1708, 5952 e INSS/DCTFWeb? Feriados nacionais devem entrar?
6. **Pendentes de conferência.** O card do backend conta as INFORMATIVO como pendentes e o atalho do front não conta. Qual das duas contagens vale?
7. **Conferência e mudança de valor.** A conferência sobrevive se a nota for reimportada com outro valor. Devemos guardar o valor conferido e invalidar a conferência quando ele mudar?
8. **Desfazer conferência** apaga o registro, e assim se perde quem conferiu antes. Para a fase de auditoria em paralelo, devemos usar exclusão lógica (a coluna `deleted` já existe)?
9. **Limite de 23:59:59** na consulta de NF-e: uma nota emitida às 23:59:59,5 do último dia fica de fora. Usar `< 1º dia do mês seguinte`, como na apuração?
