# Reforma Tributária: levantamento de quem pode ter crédito presumido de CBS sobre o estoque (F5a)

> Feature nova, Onda 1 da [avaliação do RECUPERAC RT33](2026-11-27-avaliacao-recuperac-rt33.md) (F5a). Escrita pelo
> po-taxify em 2026-11-27, com as decisões do Tony do mesmo dia (P-01, P-02 e as recomendações aceitas).
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base
> `br.com.cshub.taxify` (abreviado `…`).
>
> Depende de:
> - [Plataforma, acesso e empresas](2026-11-26-plataforma-acesso-e-empresas.md): perfis, permissões e carteira;
> - [Apuração do mês](2026-11-26-apuracao-do-mes.md): classificação de CFOP de compra (`RevenueCfops`) e histórico de
>   regime (`company_tax_regimes`);
> - [Taxify SaaS, fase 1](2026-11-26-taxify-saas-assinatura-do-escritorio.md): somente leitura no bloqueio (F1-RN-08),
>   rotinas paradas (F1-RN-10), módulos do Plano Taxify (P3-07, ainda não existe);
> - [Arquitetura do backend](2026-11-26-arquitetura-tecnica-backend-e-infra.md): formato único de erro.

## Status e decisões

**Pronta para implementação.** Prazo: no ar (com a flag ligada pelo Tony) até **~15/12/2026**, para dar tempo de avisar
os clientes antes do inventário de 31/12/2026.

| Decisão | Quem e quando | O que diz |
|---|---|---|
| P-01 | Tony, 2026-11-27 | **Confirmado:** LC 214/2025, art. 381, e Decreto 12.955/2026. Enquadramento: Lucro Presumido = hipótese I; Lucro Real = hipóteses II/III; Simples Nacional e MEI fora |
| P-02 | Tony, 2026-11-27 | **Confirmado:** inventário em 31/12/2026; crédito apropriado até 30/06/2027. O levantamento fica no ar até 30/06/2027 |
| P-03 | Recomendação aceita | Nenhum serviço novo no `PlanServiceEnum`. Leitura com `TAX_ASSESSMENT_VIEW`, escrita com `TAX_ASSESSMENT_CLOSE`. Módulo comercial futuro: "Reforma Tributária" (P3-07) |
| P-04 | Recomendação aceita | A CS Hub oferece o levantamento primeiro à própria carteira; preço depois do paralelo. Não muda o produto |
| P-21 | Recomendação aceita | A plataforma **não** vê nada deste levantamento (nem contagens por escritório) |

**O que é regra confirmada e o que continua "a validar".**
- Confirmado pelo Tony: o direito ao crédito presumido sobre o estoque de 31/12/2026 existe (LC 214/2025, art. 381;
  Decreto 12.955/2026), o enquadramento por regime da CE-RN-03 e as datas (31/12/2026 e 30/06/2027).
- **Continua a validar** (não entra nesta spec, é do F5b): a porcentagem do crédito (o material cita 9,25%, art. 381,
  § 3º, I), as parcelas, o prazo de uso (art. 383), quais bens entram em cada inciso, a vedação parcial, o critério de
  custo e os artigos 605 a 611 do decreto, um a um. **Esta feature não calcula crédito.**

## 1. Problema e objetivo

**Problema.** Quem estiver no Lucro Presumido ou no Lucro Real em 31/12/2026 pode ter direito ao crédito presumido de
CBS sobre o estoque, mas precisa fechar um inventário confiável naquela data. Hoje, para saber quem avisar, o Fiscal
abre empresa por empresa, confere o regime e estima o volume de compras do ano. Pela hipótese da avaliação (306
empresas × 10 min), são cerca de **51 h de trabalho uma vez**, em dezembro, o mês mais cheio do ano.

**Objetivo.** Uma lista de trabalho na carteira inteira, pronta no primeiro dia: quem pode ter direito (pelo regime) e
qual o peso das compras de 2026 de cada empresa, com a situação do contato ("avisada", "inventário recebido",
"descartada").

| Como medir | Meta |
|---|---|
| Tempo do Fiscal para montar a lista da campanha | ~3 h de revisão (contra ~51 h estimadas à mão) |
| Empresas `HIPOTESE_I` ou `HIPOTESE_II_III` com situação `NAO_AVALIADA` em 20/12/2026 | 0 |
| Paralelo (V-5a-2): lista conferida contra 3 empresas conhecidas do Fiscal | Regime e compras batem nas 3 |

## 2. O que já existe e será reaproveitado

| Item | Onde |
|---|---|
| Histórico de regime por mês, com consulta em lote | `domain/company/CompanyTaxRegime.java`; `repository/CompanyTaxRegimeRepository.historyOf(companyIds)` |
| Regime atual da empresa (fallback) | `Company.taxRegimeType` (`enumerated/TaxRegime.java`: MEI, SIMPLES_NACIONAL, LUCRO_PRESUMIDO, LUCRO_REAL, OTHER) |
| Classificação de CFOP de compra | `service/assessment/RevenueCfops.classify(INBOUND, cfop) == PURCHASE` |
| Itens com CFOP, CST de PIS, NCM e valor | `fiscal_xml_document_items` (`cfop`, `cst_pis`, `ncm`, `valor_total`) |
| Cabeçalho com sentido, situação, exclusão e data | `fiscal_xml_documents` (`direction`, `status`, `deleted`, `issue_date`); índice `idx_fxd_company_direction_issue` |
| Carteira | `common/TenantAccessService.accessibleCompanyIds`, `restrictToUserCompanies`, `assertCompanyAccess` |
| Bloqueio | `security/FirmAccessInterceptor` (POST/PUT respondem 403 no escritório bloqueado); `service/firmaccess/RoutineGate` |
| Relógio de Recife | `config/ClockConfig.RECIFE` |
| Erro único | `dto/ErrorDetailsDTO` (`extras` no topo do corpo) |

Não existe: utilitário de CSV com neutralização de fórmula (os CSV atuais em `AuditResource`, `FronteiraResource` e
`RetentionResource` não neutralizam), reserva de rotina entre instâncias (os agendadores usam `AtomicBoolean`) e
endpoint que diga ao front quais recursos da Reforma estão ligados. Os três entram aqui (B-CE-1, B-CE-2, B-CE-5).

## 3. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| ADMIN do escritório | Vê o escritório inteiro, filtra `HIPOTESE_I` e `HIPOTESE_II_III`, exporta o CSV para a campanha | Algumas vezes em dezembro; depois, até 30/06/2027, para acompanhar |
| FISCAL (carteira) | Vê só a carteira; registra a situação do contato de cada empresa | Diário na segunda quinzena de dezembro |
| ACCOUNTING e MANAGEMENT | Só leem e exportam (têm `TAX_ASSESSMENT_VIEW`, não têm `TAX_ASSESSMENT_CLOSE`) | Eventual |
| Rotina diária | Recalcula o levantamento de cada empresa ativa | 06:15 de Recife |
| Plataforma (CS Hub) | **Nada.** Não há visão de plataforma (P-21, F1-RN-15) | — |

**Teste grátis.** Disponível para as até 5 empresas do teste. A cobertura de 2026 costuma ser baixa (a captura nova
traz o mês corrente pelo SIEG e cerca de 90 dias pela SEFAZ): a tela mostra a cobertura (CE-RN-06). Não há busca
retroativa.

**Escritório bloqueado.** Lista, detalhe e CSV continuam (GET), com o último cálculo e a data dele. "Atualizar agora" e
mudar a situação respondem 403 `error.firm.access.blocked` (interceptor existente). A rotina pula o escritório
(`RoutineGate`).

**Custo no SIEG: zero.** Só lê o que já está no banco. Nenhuma chamada externa.

## 4. Histórias

- H-CE-1. Como ADMIN, quero ver na carteira inteira quem estava no Presumido ou no Real em dezembro/2026, para saber
  quem precisa fechar inventário em 31/12.
- H-CE-2. Como FISCAL, quero ver o volume de compras de 2026 de cada empresa e a cobertura das notas, para priorizar
  quem tem mais a ganhar e desconfiar de número baixo por falta de nota.
- H-CE-3. Como FISCAL, quero registrar que avisei o cliente, que recebi o inventário ou que descartei a empresa, para a
  campanha não depender de planilha paralela.
- H-CE-4. Como ADMIN, quero exportar a lista em CSV, para montar a campanha de e-mail ou de ligação fora do Taxify.
- H-CE-5. Como CS Hub, quero que o levantamento diga claramente que não é cálculo de crédito, para não criar expectativa
  de valor no cliente.

## 5. Regras de negócio

| RN | Regra |
|---|---|
| **CE-RN-01 Escopo** | Empresas `deleted = false` e `active != false` do escritório do usuário. ADMIN vê o escritório; os demais perfis veem a carteira (`users_companies`, via `TenantAccessService`). Empresa de outro escritório ou fora da carteira: **404** `error.Company.not.found`, nunca 403. `ano` aceita **só 2026** nesta fase; outro valor: 400 `error.reform.stock.year.invalid` |
| **CE-RN-02 Regime de referência** | `regimeReferencia` = regime do período de `company_tax_regimes` vigente em **01/12/2026** (maior `valid_from` ≤ 2026-12-01, ou `valid_from` nulo). Como os períodos começam no dia 1 do mês, é o regime de dezembro/2026 e, portanto, o de 31/12/2026. Sem nenhum período: `Company.taxRegimeType`. `regimeAnoSeguinte` = regime do período com `valid_from = 2027-01-01`, se existir; senão, nulo (o período de 2026 que "continua" não conta como cadastro de 2027). A consulta usa `historyOf` por lote, nunca uma por empresa |
| **CE-RN-03 Classificação** (regra confirmada pelo Tony, P-01) | Aplicar na ordem; a primeira que casar vence. Ver a tabela "Classificação" abaixo. `motivoClassificacao` é o texto fixo da tabela |
| **CE-RN-04 Compras do ano** | `comprasAno` = soma de `fiscal_xml_document_items.valor_total` dos itens cuja nota tem `direction = 'INBOUND'`, `deleted = false`, `status` diferente de `CANCELED` (nulo conta), `issue_date` ≥ 2026-01-01 00:00:00 e < 2027-01-01 00:00:00 (a data gravada, que é a hora local do `dhEmi`), e cujo `cfop` está no conjunto `CFOPS_COMPRA`. `CFOPS_COMPRA` = todos os códigos de 4 dígitos `c` de 1000 a 7999 com `RevenueCfops.classify(INBOUND, c) == PURCHASE`, calculado uma vez no início da execução (mesmo critério da apuração, sem duplicar a lista em SQL). `valor_total` nulo conta zero. Soma em `BigDecimal` exata; exibida com 2 casas, `HALF_UP`. `itensCompra` = número desses itens; `notasEntrada` = número de notas distintas com ao menos um desses itens |
| **CE-RN-05 Quebras (fato, sem classificar)** | Mesma base da CE-RN-04, agrupada: (a) por mês de `issue_date` (`comprasPorMes`, sempre 12 posições, mês sem compra = 0,00 e 0 notas); (b) por `cst_pis` aparado (`comprasPorCstPis`; nulo ou vazio vira `"SEM_CST"`), ordenada por valor decrescente; (c) por `ncm` aparado (`topNcm`, os 10 de maior valor; empate por NCM crescente; nulo vira `"SEM_NCM"`). Nenhuma quebra diz o que entra ou não no crédito |
| **CE-RN-06 Cobertura** | Base: notas de entrada de 2026 (`direction = 'INBOUND'`, não excluídas, não canceladas), **com qualquer CFOP**. `mesesComEntrada` = meses de 2026 com ≥ 1 nota; `primeiraEntrada` e `ultimaEntrada` = menor e maior `issue_date` (data). `mesesAvaliados` = se a data de Recife do cálculo está em 2026, o número do mês corrente − 1 (o mês corrente ainda está aberto); a partir de 2027-01-01, 12. `cobertura` = `SEM_ENTRADAS` se não há nota; `COMPLETA` se todos os meses de 1 a `mesesAvaliados` têm ≥ 1 nota; senão `PARCIAL`. É indicador de captura, não regra fiscal |
| **CE-RN-07 Rotina diária** | Cron `TAXIFY_STOCK_SURVEY_CRON` (padrão `0 15 6 * * *`, zona `America/Recife`), só com `TAXIFY_STOCK_SURVEY_ENABLED=true`. Processa as empresas da CE-RN-01 (sem filtro de carteira, contexto de sistema) de escritórios liberados pelo `RoutineGate("stock-survey")`, **em lotes de 50 empresas** (`company_id IN (...)`), com as consultas da seção 8. Não roda a partir de 01/07/2027 (CE-RN-12). Erro numa empresa não para o lote: registra no log e segue |
| **CE-RN-08 Duas instâncias** | (a) **Reserva do dia:** antes de começar, a instância insere `routine_runs(routine='STOCK_SURVEY', run_date=<data de Recife>)`; a chave única garante que só uma insere. A outra vê a duplicidade e não roda. Se a linha existe com `finished_at` nulo e `started_at` há mais de `TAXIFY_STOCK_SURVEY_STALE_MINUTES` (padrão 120), a instância pode assumir com um `UPDATE ... WHERE id = ? AND finished_at IS NULL AND started_at < ?` (1 linha afetada = assumiu). (b) **Gravação idempotente:** cada empresa é gravada com `INSERT ... ON DUPLICATE KEY UPDATE` na chave única (`company_id`, `ano`), atualizando **só** as colunas de cálculo; as colunas de situação e `version` nunca são tocadas pelo cálculo. Duas gravações do mesmo dia produzem os mesmos números |
| **CE-RN-09 Atualizar agora** | `POST /{companyId}/atualizar?ano=2026` recalcula a empresa na hora (mesmas consultas, lote de 1) e devolve o detalhe. É escrita: no escritório bloqueado, 403 `error.firm.access.blocked`. Exige `TAX_ASSESSMENT_CLOSE`. **Cooldown por empresa:** 1 a cada `TAXIFY_STOCK_SURVEY_REFRESH_COOLDOWN_MINUTES` (padrão 10), reservado de forma atômica: garante a linha (`INSERT IGNORE` com os campos de cálculo vazios) e faz `UPDATE ... SET refresh_requested_at = :agora WHERE company_id = ? AND ano = ? AND (refresh_requested_at IS NULL OR refresh_requested_at <= :agora - cooldown)`. 0 linhas afetadas: 409 `error.reform.stock.refresh.cooldown` com `retryAt` (ISO 8601 com fuso de Recife) no topo do corpo. Dois cliques simultâneos, em instâncias diferentes: só um recalcula |
| **CE-RN-10 Situação** | Valores: `NAO_AVALIADA` (padrão), `AVISADA`, `INVENTARIO_RECEBIDO`, `DESCARTADA`. Qualquer valor pode ir para qualquer outro. `observacao` opcional, até 500 caracteres (aparada; vazia vira nula); sem validação de conteúdo. Exige `TAX_ASSESSMENT_CLOSE`. Concorrência otimista por `version`: corpo com `version` diferente do gravado: 409 `error.reform.stock.status.conflict`. Cada mudança grava um evento em `stock_credit_survey_events` (de, para, observação, quem, quando em Recife). Empresa sem levantamento calculado: 404 `error.reform.stock.survey.not.found` |
| **CE-RN-11 Ordem padrão** | `classificacao` na ordem `HIPOTESE_I`, `HIPOTESE_II_III`, `SEM_REGIME`, `FORA`; depois `comprasAno` decrescente; depois `razaoSocial` crescente (sem acento e sem caixa). Outras ordens em `sort` (seção 7) sempre terminam no desempate por `razaoSocial` |
| **CE-RN-12 Encerramento** (P-02) | O levantamento fica no ar até **30/06/2027 23:59:59 de Recife**. A partir de 01/07/2027: a rotina não roda; `POST /atualizar` e `PUT /situacao` respondem 409 `error.reform.stock.survey.closed`; lista, detalhe e CSV continuam como histórico; `GET /reforma/recursos` devolve `creditoEstoque.encerrado = true`. A data é constante da regra (`2027-06-30`), não variável de ambiente |
| **CE-RN-13 Texto fixo** | Toda tela, detalhe e CSV exibem: "Levantamento de quem pode ter direito ao crédito presumido de CBS sobre o estoque de 31/12/2026 (LC 214/2025, art. 381; Decreto 12.955/2026), pelo regime cadastrado. Não é cálculo de crédito: a elegibilidade de cada bem e o valor dependem do inventário e da análise do responsável técnico." |
| **CE-RN-14 Memória** | O detalhe traz `memoria` (lista de linhas de texto, gerada pelo backend) com: o regime de referência e o período de onde veio (`valid_from` ou "cadastro da empresa"); a regra da CE-RN-03 que casou; o critério da CE-RN-04 em palavras; a lista de `CFOPS_COMPRA` usada (compactada em faixas, ex.: "1101-1102, 1126, 1401, 1403, 2101-2102, ..., 5101-5106, ..."); e a data e hora do cálculo |
| **CE-RN-15 Recursos ligados** | `GET /api/v1/reforma/recursos` (qualquer usuário autenticado de escritório) devolve o que o front deve mostrar no menu "Reforma Tributária". Esta spec cria o endpoint com o campo `creditoEstoque`; F1a-0, F1a e F2 acrescentam os seus. Com `TAXIFY_STOCK_SURVEY_ENABLED=false`, `creditoEstoque.ativo = false` e **todas** as rotas `/api/v1/reforma/credito-estoque/**` respondem 404 `error.reform.stock.disabled` |

### Classificação (CE-RN-03)

| Ordem | Condição | `classificacao` | `motivoClassificacao` |
|---|---|---|---|
| 1 | `regimeAnoSeguinte` ∈ {`SIMPLES_NACIONAL`, `MEI`} | `FORA` | "Cadastrada no Simples Nacional/MEI a partir de jan/2027: fora do crédito pelo enquadramento adotado (LC 214/2025, art. 381)." |
| 2 | `regimeReferencia` = `LUCRO_PRESUMIDO` | `HIPOTESE_I` | "Lucro Presumido em dez/2026: hipótese I (LC 214/2025, art. 381, I)." |
| 3 | `regimeReferencia` = `LUCRO_REAL` | `HIPOTESE_II_III` | "Lucro Real em dez/2026: hipóteses II e III (LC 214/2025, art. 381, II e III); só parte dos bens pode entrar." |
| 4 | `regimeReferencia` ∈ {`SIMPLES_NACIONAL`, `MEI`} | `FORA` | "Simples Nacional/MEI em dez/2026: fora do crédito pelo enquadramento adotado." |
| 5 | `regimeReferencia` nulo ou `OTHER` | `SEM_REGIME` | "Regime não informado: cadastre o regime da empresa para classificar." |

A pendência `REGIME_MISSING` já existe e não é duplicada aqui. As compras (CE-RN-04 a 06) são calculadas em todas as
classificações.

## 6. Modelo de dados

Migrations reservadas (a última hoje é `V79__SyncControlPreviousMonth`; conferir na hora de implementar e renumerar se
outra spec entrar antes). Números das outras specs da Onda 1: V83 e V84 (F1a-0), V85 a V87 (F1a), V88 (F2).

### V80__RoutineRuns (genérica, reaproveitável por outras rotinas)

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | varchar(36) not null, PK | |
| `routine` | varchar(40) not null | ex.: `STOCK_SURVEY` |
| `run_date` | date not null | data de Recife |
| `instance_id` | varchar(60) | hostname ou id aleatório da instância; só diagnóstico |
| `started_at` | datetime(6) not null | Recife |
| `finished_at` | datetime(6) | Recife |
| `processed` | int | empresas processadas |
| `failed` | int | empresas com erro |
| + colunas de auditoria do projeto (`deleted`, `created_by`, `created_date`, `last_modified_by`, `last_modified_date`) | | |

Único `uk_routine_runs_routine_date (routine, run_date)`. Volume: 1 linha por rotina por dia.

### V81__StockCreditSurveys

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | varchar(36) not null, PK | |
| `company_id` | varchar(36) not null, FK `companies` | |
| `ano` | int not null | 2026 |
| `regime_referencia` | varchar(30) | `TaxRegime` |
| `regime_referencia_desde` | date | `valid_from` do período usado; nulo = "desde sempre" ou cadastro |
| `regime_referencia_origem` | varchar(12) | `HISTORICO` ou `CADASTRO` |
| `regime_ano_seguinte` | varchar(30) | |
| `classificacao` | varchar(20) | CE-RN-03 |
| `compras_ano` | decimal(19,2) | |
| `itens_compra`, `notas_entrada`, `meses_com_entrada`, `meses_avaliados` | int | |
| `cobertura` | varchar(15) | CE-RN-06 |
| `primeira_entrada`, `ultima_entrada` | date | |
| `compras_por_mes_json`, `compras_por_cst_json`, `top_ncm_json` | text | arrays da CE-RN-05 (valores como texto decimal, ex. `"120000.00"`) |
| `calculado_em` | datetime(6) | Recife; nulo enquanto a linha só foi reservada pelo cooldown |
| `refresh_requested_at` | datetime(6) | CE-RN-09 |
| `situacao` | varchar(25) not null | padrão `NAO_AVALIADA` |
| `situacao_alterada_por` | varchar(50) | login |
| `situacao_alterada_em` | datetime(6) | Recife |
| `observacao` | varchar(500) | |
| `version` | int not null | só a CE-RN-10 incrementa |
| + colunas de auditoria do projeto | | |

Único `uk_stock_credit_surveys_company_year (company_id, ano)`; índice `idx_scs_year_class (ano, classificacao)`.
Volume: 1 linha por empresa (até ~5.000).

### V82__StockCreditSurveyEvents

`id`, `survey_id` (FK), `de` varchar(25), `para` varchar(25) not null, `observacao` varchar(500), `alterado_por`
varchar(50) not null, `alterado_em` datetime(6) not null (Recife) + auditoria. Índice (`survey_id`, `alterado_em`).

### Impacto nas tabelas grandes

Nenhuma coluna nova em `fiscal_xml_documents` nem em `fiscal_xml_document_items`. As consultas (seção 8) partem de
`idx_fxd_company_direction_issue (company_id, direction, issue_date)` e juntam os itens pelo índice da FK
`document_id`. **Não criar índice novo** sem o `EXPLAIN` da massa sintética do Q-CE-3 pedir.

## 7. Contrato da API

Base `/api/v1/reforma/credito-estoque`. Todas exigem usuário de escritório autenticado. Com a flag desligada, todas
respondem 404 `error.reform.stock.disabled` (CE-RN-15). Datas `AAAA-MM-DD`; data e hora em ISO 8601 com o fuso de
Recife (`-03:00`); dinheiro como número decimal com 2 casas no JSON (ex.: `160000.00`).

| Método e rota | Permissão | Parâmetros | Respostas |
|---|---|---|---|
| `GET /` | `TAX_ASSESSMENT_VIEW` | `ano` (obrigatório, 2026); `classificacao` (lista, separada por vírgula); `situacao` (lista); `cobertura` (lista); `busca` (razão social ou CNPJ, só dígitos ou com máscara, mínimo 3 caracteres); `page` (padrão 0); `size` (padrão 50, máximo 200); `sort` (`classificacao`, `comprasAno`, `razaoSocial`, `situacaoAlteradaEm`, com `,asc` ou `,desc`; padrão CE-RN-11) | 200 corpo da lista; 400 `error.reform.stock.year.invalid`; 400 `error.reform.stock.filter.invalid` (valor fora do enum, `size` > 200, `sort` desconhecido; `invalidField` no topo) |
| `GET /{companyId}` | `TAX_ASSESSMENT_VIEW` | `ano` | 200 detalhe; 404 `error.Company.not.found` (outro escritório, fora da carteira, excluída); 404 `error.reform.stock.survey.not.found` (ainda não calculada) |
| `POST /{companyId}/atualizar` | `TAX_ASSESSMENT_CLOSE` | `ano` | 200 detalhe recalculado; 403 `error.firm.access.blocked`; 404 como acima; 409 `error.reform.stock.refresh.cooldown` (`retryAt`); 409 `error.reform.stock.survey.closed` |
| `PUT /{companyId}/situacao` | `TAX_ASSESSMENT_CLOSE` | corpo `{ano, situacao, observacao, version}` | 200 linha atualizada; 400 `error.reform.stock.status.invalid`; 400 `error.reform.stock.note.too.long`; 403 bloqueado; 404; 409 `error.reform.stock.status.conflict` (`currentVersion` no topo); 409 `error.reform.stock.survey.closed` |
| `GET /{companyId}/historico` | `TAX_ASSESSMENT_VIEW` | `ano` | 200 lista de eventos (mais recente primeiro, sem paginação: poucos por empresa) |
| `GET /export.csv` | `TAX_ASSESSMENT_VIEW` | os filtros e o `sort` do `GET /`, sem paginação (todas as linhas acessíveis) | 200 `text/csv; charset=UTF-8`, nome `credito-estoque-2026-AAAAMMDD.csv` |
| `GET /api/v1/reforma/recursos` | autenticado | — | 200 `{creditoEstoque: {ativo, encerrado, encerraEm: "2027-06-30"}}` (outras specs acrescentam campos) |

**Corpo da lista.**

```json
{
  "aviso": "<texto da CE-RN-13>",
  "resumo": {
    "empresas": 4,
    "semCalculo": 1,
    "porClassificacao": {"HIPOTESE_I": 2, "HIPOTESE_II_III": 1, "SEM_REGIME": 0, "FORA": 1},
    "porSituacao": {"NAO_AVALIADA": 3, "AVISADA": 1, "INVENTARIO_RECEBIDO": 0, "DESCARTADA": 0},
    "comprasAnoTotal": 185000.00,
    "calculadoEmMaisAntigo": "2026-12-10T06:15:42-03:00"
  },
  "page": {"content": [ /* linhas */ ], "totalElements": 4, "totalPages": 1, "number": 0, "size": 50}
}
```

`resumo` considera **todas** as linhas que passam nos filtros (não só a página). `semCalculo` = empresas da CE-RN-01
acessíveis ao usuário sem linha calculada (não entram em `page`).

**Linha (`content[]`).** `companyId`, `razaoSocial`, `cnpj` (14 dígitos, sem máscara), `regimeReferencia`,
`regimeAnoSeguinte`, `classificacao`, `motivoClassificacao`, `comprasAno`, `itensCompra`, `notasEntrada`,
`mesesComEntrada`, `mesesAvaliados`, `cobertura`, `primeiraEntrada`, `ultimaEntrada`, `situacao`,
`situacaoAlteradaPor`, `situacaoAlteradaEm`, `version`, `calculadoEm`.

**Detalhe.** Os campos da linha mais `aviso`, `regimeReferenciaDesde`, `regimeReferenciaOrigem`, `observacao`,
`comprasPorMes[12]{mes: "2026-01", valor, notas}`, `comprasPorCstPis[]{cst, valor, itens}`,
`topNcm[≤10]{ncm, valor, itens}`, `memoria[]` (CE-RN-14), `podeAtualizarEm` (data e hora em que o cooldown libera;
nulo se já liberado), `encerrado`.

**Evento (`/historico`).** `de`, `para`, `observacao`, `alteradoPor`, `alteradoEm`.

**CSV.** Separador `;`, UTF-8 com BOM, quebra `\r\n`, decimal com vírgula e sem separador de milhar (`160000,00`),
datas `dd/MM/aaaa`. Colunas: Razão social; CNPJ (com máscara); Regime dez/2026; Regime jan/2027; Classificação;
Motivo; Compras 2026 (R$); Itens de compra; Notas de entrada; Meses com entrada; Meses avaliados; Cobertura; Primeira
entrada; Última entrada; Situação; Alterada por; Alterada em; Calculado em. Última linha: `Observação;<texto da
CE-RN-13>`. **Toda célula de texto** que começa com `=`, `+`, `-`, `@`, tabulação ou `\r` recebe o prefixo `'`
(utilitário novo `CsvCells.safe`, B-CE-1).

**Textos de erro (pt).**

| Code | Status | Texto |
|---|---|---|
| `error.reform.stock.disabled` | 404 | "Recurso não disponível." |
| `error.reform.stock.year.invalid` | 400 | "Ano de referência inválido. Use 2026." |
| `error.reform.stock.filter.invalid` | 400 | "Filtro inválido." |
| `error.reform.stock.survey.not.found` | 404 | "O levantamento desta empresa ainda não foi calculado. Use Atualizar agora." |
| `error.reform.stock.refresh.cooldown` | 409 | "Aguarde alguns minutos para atualizar de novo." |
| `error.reform.stock.status.invalid` | 400 | "Situação inválida." |
| `error.reform.stock.note.too.long` | 400 | "A observação pode ter até 500 caracteres." |
| `error.reform.stock.status.conflict` | 409 | "Outra pessoa alterou esta empresa. Recarregue e tente de novo." |
| `error.reform.stock.survey.closed` | 409 | "O levantamento foi encerrado em 30/06/2027. Os dados continuam disponíveis para consulta." |

## 8. Consultas (para o dev; sem N+1)

Por lote de até 50 empresas, numa transação só de leitura:
1. `historyOf(companyIds)` (regimes) e as empresas do lote (já carregadas pela rotina).
2. Compras por empresa e mês:
   `SELECT d.company_id, MONTH(d.issue_date), SUM(i.valor_total), COUNT(i.id), COUNT(DISTINCT d.id) FROM fiscal_xml_documents d JOIN fiscal_xml_document_items i ON i.document_id = d.id WHERE d.company_id IN (:ids) AND d.direction = 'INBOUND' AND d.deleted = 0 AND (d.status IS NULL OR d.status <> 'CANCELED') AND d.issue_date >= :inicio AND d.issue_date < :fim AND i.cfop IN (:cfopsCompra) GROUP BY d.company_id, MONTH(d.issue_date)`.
   `notasEntrada` do ano sai de uma variante sem o mês (`COUNT(DISTINCT d.id)` por empresa).
3. A mesma base agrupada por `(company_id, TRIM(i.cst_pis))` e por `(company_id, TRIM(i.ncm))`; o top 10 é cortado em Java.
4. Cobertura: `SELECT company_id, MONTH(issue_date), MIN(issue_date), MAX(issue_date) FROM fiscal_xml_documents WHERE ... (sem o filtro de CFOP, sem junção) GROUP BY company_id, MONTH(issue_date)`.
5. Upsert das linhas do lote (CE-RN-08 b), em batch.

Hipótese de volume: 5.000 empresas × ~2.000 itens de entrada por ano = ~10 milhões de linhas de item lidas por dia,
em 100 lotes. Meta: rotina inteira em menos de 30 min na EC2. Medir no Q-CE-3.

A lista (`GET /`) lê só `stock_credit_surveys` + `companies` (filtro de carteira por `restrictToUserCompanies`), com
paginação e ordenação no banco. O `resumo` é uma consulta agregada sobre os mesmos filtros.

## 9. Variáveis de ambiente

| Variável | Padrão | Quem liga | O que faz |
|---|---|---|---|
| `TAXIFY_STOCK_SURVEY_ENABLED` | `false` | Tony, na EC2, depois do Q-CE e do paralelo V-5a-2 | Liga a rotina e as rotas (CE-RN-15) |
| `TAXIFY_STOCK_SURVEY_CRON` | `0 15 6 * * *` | — | Horário da rotina (Recife) |
| `TAXIFY_STOCK_SURVEY_REFRESH_COOLDOWN_MINUTES` | `10` | — | CE-RN-09 |
| `TAXIFY_STOCK_SURVEY_STALE_MINUTES` | `120` | — | CE-RN-08 (a) |
| `TAXIFY_STOCK_SURVEY_BATCH_SIZE` | `50` | — | Tamanho do lote (CE-RN-07) |

Entram em `application.yaml` como `taxify.reform.stock-survey.*` e no `docker-compose.aws.yml` com o padrão seguro.

## 10. Logs

Só ids e contagens. **Nunca** CNPJ, razão social, valor, NCM nem observação.

| Evento | Nível | Campos |
|---|---|---|
| `stock.survey.run.start` | INFO | `runDate`, `instanceId` |
| `stock.survey.run.skipped` | INFO | `runDate`, `reason` (`ALREADY_RESERVED`, `DISABLED`, `CLOSED`) |
| `stock.survey.run.end` | INFO | `runDate`, `firmCount`, `companyCount`, `failed`, `durationMs`, `skippedFirms` (do `RoutineGate`) |
| `stock.survey.company.failed` | WARN | `companyId`, classe da exceção (sem a mensagem, que pode ter dado) |
| `stock.survey.refresh` | INFO | `companyId`, `userId`, `durationMs` |
| `stock.survey.status.changed` | INFO | `companyId`, `userId`, `de`, `para` |

## 11. Critérios de aceite

- **CA-CE-01 (CE-RN-02, 03)** Dado a empresa A com Lucro Presumido desde 2025-01 e nenhum período de 2027, quando a
  rotina roda, então `classificacao = HIPOTESE_I`, `regimeAnoSeguinte = null` e o motivo cita o art. 381, I.
- **CA-CE-02 (CE-RN-03)** Dado a empresa B com Presumido até 2026-12 e Simples a partir de 2027-01, então
  `classificacao = FORA` com o motivo de jan/2027.
- **CA-CE-03 (CE-RN-03)** Dado a empresa C com Lucro Real em dez/2026, então `HIPOTESE_II_III`.
- **CA-CE-04 (CE-RN-04)** Dado itens de nota cancelada, de nota excluída, com CFOP de remessa (5949) e de nota de saída,
  então nenhum deles entra em `comprasAno`.
- **CA-CE-05 (CE-RN-01)** Dado um FISCAL sem a empresa D na carteira, quando pede `GET /{D}`, então 404
  `error.Company.not.found`, e D não aparece na lista, no `resumo` nem no CSV. Empresa de outro escritório: 404.
- **CA-CE-06 (CE-RN-07, bloqueio)** Dado um escritório bloqueado, quando a rotina roda, então as empresas dele não são
  recalculadas, a lista mostra o `calculadoEm` anterior, e `PUT /situacao` e `POST /atualizar` respondem 403
  `error.firm.access.blocked`.
- **CA-CE-07 (CE-RN-10)** Dado dois usuários editando a situação com o mesmo `version`, então o primeiro grava e o
  segundo recebe 409 `error.reform.stock.status.conflict` com `currentVersion`.
- **CA-CE-08 (CE-RN-08)** Dado duas instâncias com a rotina no mesmo minuto, então existe uma só linha em
  `routine_runs` para o dia, uma só linha por (empresa, 2026) e os números são os mesmos de uma execução única.
- **CA-CE-09 (CE-RN-08 b)** Dado uma empresa com situação `AVISADA` e `version = 3`, quando a rotina recalcula, então
  situação, observação e `version` continuam iguais.
- **CA-CE-10 (CE-RN-09)** Dado um "Atualizar agora" há 4 minutos, quando o usuário pede de novo, então 409 com
  `retryAt` = hora do primeiro + 10 min.
- **CA-CE-11 (CE-RN-12)** Dado a data de Recife 2027-07-01, então a rotina não roda, `PUT /situacao` responde 409
  `error.reform.stock.survey.closed` e a lista continua respondendo 200.
- **CA-CE-12 (CE-RN-15)** Dado `TAXIFY_STOCK_SURVEY_ENABLED=false`, então `GET /reforma/recursos` traz
  `creditoEstoque.ativo = false`, as rotas respondem 404 `error.reform.stock.disabled` e o menu não mostra o item.
- **CA-CE-13 (CE-RN-13)** Dado qualquer tela, detalhe ou CSV do levantamento, então o texto fixo aparece.
- **CA-CE-14 (CE-RN-06)** Dado uma empresa com entradas só em outubro e novembro e o cálculo em 2026-12-10, então
  `mesesComEntrada = 2`, `mesesAvaliados = 11` e `cobertura = PARCIAL`.
- **CA-CE-15 (permissão)** Dado um usuário ACCOUNTING, quando tenta `PUT /situacao`, então 403 (sem
  `TAX_ASSESSMENT_CLOSE`); o `GET /` responde 200.

## 12. Casos de teste (números fictícios)

Empresas: E1 = 11.222.333/0001-81, E2 = 12.345.678/0001-95, E3 = 33.444.555/0001-81, E4 = 44.555.666/0001-81. Usuários:
`admin@exemplo.test` (ADMIN), `fiscal1@exemplo.test` (FISCAL com E1 e E2 na carteira), `contabil@exemplo.test`
(ACCOUNTING).

| CT | Dado | Esperado | RN |
|---|---|---|---|
| CT-CE-01 | E1 Presumido. Entradas 2026 (notas recebidas, `direction = INBOUND`): item CFOP 5102 R$ 120.000,00 (CST PIS 01, NCM 22030000); item CFOP 5405 R$ 30.000,00 (CST 04, NCM 22021000); item CFOP 6102 R$ 10.000,00 (CST 06, NCM 22030000); nota **cancelada** com item 5102 de R$ 5.000,00; item CFOP 5949 R$ 2.000,00 | `comprasAno = 160000.00` (120.000 + 30.000 + 10.000); `itensCompra = 3`; quebra por CST: 01 = 120.000,00, 04 = 30.000,00, 06 = 10.000,00; `topNcm`: 22030000 = 130.000,00 (2 itens), 22021000 = 30.000,00; cancelada e 5949 fora; `HIPOTESE_I` | 03, 04, 05 |
| CT-CE-02 | E1: nota própria de entrada com CFOP 1102 de R$ 8.000,00 | Entra (1102 é compra pela `RevenueCfops`); `comprasAno` do CT-CE-01 passa a 168000.00 | 04 |
| CT-CE-03 | Nota com `dhEmi` 2026-12-31T23:30:00-04:00 (gravada 2026-12-31 23:30) | Entra em 2026 e em `comprasPorMes[12]` | 04 |
| CT-CE-04 | Nota com `dhEmi` 2027-01-01T00:10:00-03:00 | Fora | 04 |
| CT-CE-05 | E2 sem período de regime e `taxRegimeType` nulo | `SEM_REGIME`, compras calculadas, `regimeReferenciaOrigem = CADASTRO` | 02, 03 |
| CT-CE-06 | E3: períodos Simples (`valid_from` nulo) e Presumido a partir de 2026-07-01 | `regimeReferencia = LUCRO_PRESUMIDO`, `regimeReferenciaDesde = 2026-07-01`, `HIPOTESE_I` | 02 |
| CT-CE-07 | E4: Real a partir de 2025-01-01 e Simples a partir de 2027-01-01 | `regimeAnoSeguinte = SIMPLES_NACIONAL`, `FORA` (regra 1 vence a 3) | 03 |
| CT-CE-08 | E3 com período Presumido desde 2026-07-01 e nada de 2027 | `regimeAnoSeguinte = null` (o período de 2026 não conta como cadastro de 2027) | 02 |
| CT-CE-09 | Entradas só em out, nov e dez; cálculo em 2026-12-10 | `mesesComEntrada = 3`, `mesesAvaliados = 11`, `PARCIAL` | 06 |
| CT-CE-10 | Entradas em todos os meses de jan a nov; cálculo em 2026-12-10 | `COMPLETA` | 06 |
| CT-CE-11 | Mesma empresa do CT-CE-09, cálculo em 2027-01-02 | `mesesAvaliados = 12`, `PARCIAL` | 06 |
| CT-CE-12 | Empresa sem nenhuma entrada em 2026 | `comprasAno = 0.00`, `SEM_ENTRADAS`, 12 meses zerados | 05, 06 |
| CT-CE-13 | 51 empresas ativas | 2 lotes (50 + 1); log `companyCount = 51`; nenhuma consulta por empresa (contar as consultas no teste) | 07 |
| CT-CE-14 | Duas instâncias disparam a rotina às 06:15:00 | 1 linha em `routine_runs`; a segunda registra `stock.survey.run.skipped reason=ALREADY_RESERVED` | 08 |
| CT-CE-15 | Linha de `routine_runs` do dia com `started_at` 06:15 e `finished_at` nulo; outra instância às 08:30 | Assume (130 min > 120) e roda | 08 |
| CT-CE-16 | Empresa `AVISADA`, `version = 3`; rotina recalcula | situação, observação e `version` inalterados; `calculado_em` novo | 08 |
| CT-CE-17 | `POST /atualizar` às 10:00 e de novo às 10:04 | Segundo: 409, `retryAt = ...T10:10:00-03:00` | 09 |
| CT-CE-18 | Dois `POST /atualizar` simultâneos em instâncias diferentes | Um 200 e um 409 | 09 |
| CT-CE-19 | `PUT /situacao` com `version = 2` quando o gravado é 3 | 409, `currentVersion = 3` | 10 |
| CT-CE-20 | `PUT /situacao` com observação de 501 caracteres | 400 `error.reform.stock.note.too.long` | 10 |
| CT-CE-21 | `PUT /situacao` para `INVENTARIO_RECEBIDO` | 200; `version` +1; evento com `de = NAO_AVALIADA`, `para = INVENTARIO_RECEBIDO`, login e hora de Recife | 10 |
| CT-CE-22 | `fiscal1@exemplo.test` pede `GET /{E3}` | 404 `error.Company.not.found`; E3 fora da lista e do CSV | 01 |
| CT-CE-23 | Usuário de outro escritório pede `GET /{E1}` e `PUT /{E1}/situacao` | 404 nos dois | 01 |
| CT-CE-24 | Escritório bloqueado: `GET /`, `GET /export.csv`, `POST /atualizar`, `PUT /situacao` | 200, 200, 403, 403 | bloqueio |
| CT-CE-25 | CSV com razão social fictícia "=SOMA(A1) Comércio Ltda" | Célula `'=SOMA(A1) Comércio Ltda`; BOM presente; decimal com vírgula; última linha com o texto fixo | CSV |
| CT-CE-26 | `ano=2025` | 400 `error.reform.stock.year.invalid` | 01 |
| CT-CE-27 | Data de Recife 2027-06-30 23:59 e 2027-07-01 00:00 (Clock fixo) | 23:59: `PUT` 200; 00:00: `PUT` 409 `error.reform.stock.survey.closed`; rotina não roda | 12 |
| CT-CE-28 | Rotina com o Clock em 2026-12-10 06:15 de Recife e a JVM em UTC | `run_date = 2026-12-10`; `calculado_em` em Recife | 07 |
| CT-CE-29 | Ordem padrão com E1 (I, 160.000), E3 (I, 90.000), E4 (FORA, 500.000), E2 (SEM_REGIME, 10.000) | E1, E3, E2, E4 | 11 |
| CT-CE-30 | `contabil@exemplo.test`: `GET /` e `PUT /situacao` | 200 e 403 | permissão |
| CT-CE-31 | Flag desligada | `GET /reforma/recursos` → `creditoEstoque.ativo = false`; `GET /` → 404 `error.reform.stock.disabled`; rotina não roda | 15 |
| CT-CE-32 | Item com `valor_total` nulo e CFOP 5102 | Conta 0,00 no valor e 1 em `itensCompra` | 04 |

## 13. Tarefas por agente

O contrato está fechado nesta spec: backend e frontend podem rodar em paralelo.

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| B-CE-1 | backend | Utilitário `CsvCells.safe` (neutralização de fórmula) com teste; **não** trocar os CSV antigos nesta entrega (fica registrado como melhoria) | — |
| B-CE-2 | backend | V80 `routine_runs` + serviço `RoutineRunReservation` (reservar, assumir vencida, finalizar), com teste de concorrência | — |
| B-CE-3 | backend | V81 e V82; entidades, repositórios e o cálculo por lote (seção 8), com o conjunto `CFOPS_COMPRA` derivado da `RevenueCfops` | B-CE-2 |
| B-CE-4 | backend | Rotina diária (flag, cron, `RoutineGate`, encerramento) e `POST /atualizar` com cooldown atômico | B-CE-3 |
| B-CE-5 | backend | Rotas de lista, detalhe, histórico, situação e CSV; `GET /reforma/recursos`; mensagens pt em `messages`; variáveis no `application.yaml` e no `docker-compose.aws.yml` | B-CE-3, B-CE-1 |
| F-CE-1 | frontend | Menu "Reforma Tributária" guiado por `GET /reforma/recursos` (o item some com `ativo = false`) | contrato |
| F-CE-2 | frontend | Tela "Reforma > Crédito de estoque" (`/reforma/credito-estoque`): texto fixo no topo, cartões do `resumo`, filtros, tabela paginada com cobertura (selo "Parcial" ou "Sem entradas"), exportar CSV, faixa "encerrado" quando `encerrado = true` | F-CE-1 |
| F-CE-3 | frontend | Detalhe (painel lateral): regime e origem, motivo, compras por mês (tabela simples), quebra por CST de PIS, top NCM, memória, histórico; "Atualizar agora" (desabilitado até `podeAtualizarEm`) e troca de situação com observação; botões de escrita com `[write]="true"` (somem no bloqueio) e escondidos sem `TAX_ASSESSMENT_CLOSE`; 409 de conflito recarrega a linha e avisa | F-CE-2 |
| Q-CE-1 | QA | CT-CE-01 a 32 por HTTP real, no MySQL local | B-CE-5, F-CE-3 |
| Q-CE-2 | QA | Isolamento: dois escritórios e um FISCAL com carteira parcial (lista, `resumo`, detalhe, CSV) | Q-CE-1 |
| Q-CE-3 | QA | Massa sintética: 1.000 empresas × 2.000 itens de entrada; medir a rotina e o `EXPLAIN` das consultas da seção 8; registrar o tempo | B-CE-4 |
| V-5a-2 | Fiscal | Paralelo: conferir regime, classificação e compras de 3 empresas conhecidas contra a conferência manual antes de usar a lista na campanha | Q-CE-1 |

## 14. Riscos

- **Leitura errada do volume.** O CFOP da nota de compra é o do fornecedor (5102 serve para revenda e para uso e consumo
  do comprador). `comprasAno` é ordem de grandeza para priorizar, nunca base de crédito. A tela e o CSV dizem isso
  (CE-RN-13).
- **Regime desatualizado no cadastro** classifica errado. Mitigação: a memória mostra de onde veio o regime e a lista
  filtra `SEM_REGIME`.
- **Cobertura baixa** (empresa nova no Taxify, teste grátis) subestima as compras. Mitigação: `cobertura` visível e
  filtrável.
- **Expectativa do cliente.** A lista é interna do escritório; não há envio ao cliente pelo sistema.
- **Desempenho** na carteira grande: lotes, sem N+1, medição no Q-CE-3.
- **LGPD:** dado do próprio escritório; nada sai do Taxify; logs só com ids.

## 15. Pré-requisitos de go-live

1. Q-CE-1 a Q-CE-3 aprovados e o paralelo V-5a-2 feito.
2. Tony aprova ligar `TAXIFY_STOCK_SURVEY_ENABLED=true` na EC2 (deploy só com a aprovação dele).
3. Regimes de dez/2026 conferidos no cadastro da carteira da CS Hub (filtrar `SEM_REGIME` no primeiro dia).
4. Jurídico: não há exigência nova (sem e-mail, sem dado para fora, sem visão da plataforma).

## 16. Fora do escopo

- Cálculo do crédito, importação de inventário, parcelas e casamento item × aquisição (F5b).
- Busca retroativa de 2026 no SIEG para quem tem cobertura baixa (F5b, P-13).
- E-mail ou aviso automático ao cliente.
- Visão da plataforma e contagens por escritório (P-21).
- Anos diferentes de 2026.

## 17. Perguntas em aberto

Nenhuma bloqueia.

| P | Pergunta | Opções | Recomendação | Impacto se for diferente |
|---|---|---|---|---|
| P-CE-01 | Depois de 30/06/2027, o item some do menu? | (a) fica como histórico somente leitura; (b) some do menu em 01/07/2027 | (a) até 31/12/2027; decidir a remoção depois | (b) exige só esconder o item no front |
| P-CE-02 | Situações extras (ex.: "inventário pendente", "cliente recusou")? | manter as 4; acrescentar | Manter as 4 no paralelo e acrescentar se o Fiscal pedir | Enum e filtro novos, sem migration de dados |
| P-CE-03 | Trocar os CSV antigos (auditoria, Fronteira, retenções) para o `CsvCells.safe`? | agora; depois | Depois, numa tarefa separada | Agora aumenta o diff desta entrega |
