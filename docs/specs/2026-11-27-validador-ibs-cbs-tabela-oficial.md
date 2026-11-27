# Reforma Tributária: validador de IBS/CBS pela tabela oficial de cClassTrib e painel de prontidão da carteira (F1a)

> Feature nova, Onda 1 da [avaliação do RECUPERAC RT33](2026-11-27-avaliacao-recuperac-rt33.md) (F1a). Escrita pelo
> po-taxify em 2026-11-27 (revisão 2 do mesmo dia: modelo novo de documentos, nomes em inglês, migrations depois da
> V89, perguntas fechadas).
>
> **Esta spec também define a importação de tabelas oficiais pelo console da plataforma (Parte A, regras `TO-RN`)**,
> que o [F2 (NCM)](2026-11-27-ncm-inexistente-ou-fora-de-vigencia.md) reaproveita sem duplicar.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base
> `br.com.cshub.taxify` (abreviado `…`). Identificadores (classes, tabelas, colunas, enums, rotas, JSON, variáveis) em
> inglês; textos para o usuário em português, como a Onda 1 (F1a-0 e F5a) já fez.
>
> Depende de:
> - [Remodelagem dos documentos fiscais](2026-11-27-remodelagem-documentos-fiscais.md): `fiscal_documents`
>   (`SINGLE_TABLE`, `Nfe`/`Nfce`/`Nfse`/`Cte`), `fiscal_document_items`, motor migrado (B-FD-05). **As Partes B e C só
>   são implementadas sobre o modelo novo**;
> - [Motor de conferência fiscal](2026-11-26-motor-de-conferencia-fiscal.md): RN-01 (bean por checagem, contexto carregado
>   uma vez), RN-02 (nota cancelada ou excluída não é conferida), RN-03 (formato do achado), RN-12 a RN-14 (IBS/CBS);
> - [Auditoria da carteira](2026-11-26-auditoria-da-carteira.md): `AuditEngine.CHECK_RULES` (o que vira pendência);
> - [Taxify SaaS, fase 1](2026-11-26-taxify-saas-assinatura-do-escritorio.md): console da plataforma e
>   `@PlatformAdminOnly`;
> - [Alíquotas por ano (F1a-0)](2026-11-27-aliquotas-ibs-cbs-por-ano.md) e [crédito de estoque (F5a)](2026-11-27-credito-estoque-cbs-levantamento.md):
>   `GET /api/v1/reform/features` (`ReformFeaturesDTO`), padrão de console `/platform/ibs-cbs-rates`.

## Status e decisões

**Pronta para implementação.** Nada bloqueia o código. O **primeiro arquivo real** (go-live) depende do D-01.

| Decisão | Quem e quando | O que diz |
|---|---|---|
| P-06 | Tony, 2026-11-27 | Achados de IBS/CBS são **alerta (`WARNING`) até o fim do paralelo**: aparecem no painel de prontidão e na nota, **sem pendência e sem e-mail** |
| P-07 | Tony, 2026-11-27 | Tabelas oficiais importadas pelo **admin da plataforma** no console, valem para **todos os escritórios**, com **aviso quando passam de 60 dias** sem atualização |
| AL-RN-02 (F1a-0) | Tony, 2026-11-27 | **Só alíquota `LAW` gera achado.** Esta spec não cria checagem de alíquota e não muda o `IBSCBS_RATE` (que já lê só `LAW` em `IbsCbsRateRepository.findFindingRates`). O painel de prontidão não usa alíquota |
| P-03, P-21 | Recomendação aceita | Nenhum serviço novo no `PlanServiceEnum`; a plataforma não vê contagens por escritório |
| P-VT-02 a P-VT-04 | Fechadas nesta revisão pela recomendação | CSV com mapeamento configurável (sem XLSX); checagens na conferência básica e painel no futuro módulo "Reforma"; painel só interno (seção 14) |

**Como a P-06 foi aplicada.** Os 4 códigos novos são `WARNING` em qualquer ano. **Nenhum** entra no
`AuditEngine.CHECK_RULES`: sem `AUDIT_FINDING`, sem pendência de qualquer severidade, sem item no resumo por e-mail
(que só leva ALTA). O fim do paralelo é uma decisão futura do Tony (seção 14, nota).

**Regra fiscal.** A spec não cria regra fiscal: confere o código do XML contra a tabela oficial, na versão importada. A
versão citada pelo material (IT 2025.002 v1.71, de 30/10/2026, com 173 cClassTrib) **continua a validar** até o D-01.
As regras da NT 2025.002 com código de rejeição (grupos exigidos e vedados) são do F1b, fora daqui.

**Formato provisório.** A implementação começa já, com o layout CSV da seção A.3 e o **mapeamento de colunas
configurável** da TO-RN-14. Se o D-01 mostrar nomes de coluna diferentes, muda a configuração (ou o CSV convertido), não
o código.

## 1. Problema e objetivo

**Problema.** A conferência confere só o **formato** do cClassTrib (6 dígitos, começa pelo CST), não se o código existe,
está vigente na data da nota ou é permitido no modelo (`IbsCbsItemCheck`, código `IBSCBS_CLASS_TRIB`). Em 2026, ano-teste,
o erro de cadastro do cliente ou do fornecedor passa batido e vira rejeição ou crédito glosado quando as regras
endurecerem. O Fiscal não tem como saber quanto da carteira já emite certo sem abrir nota por nota.

**Objetivo.**
1. Tabela oficial versionada, importada uma vez pela CS Hub e usada por todos os escritórios.
2. Quatro checagens novas no motor, em nota emitida e recebida, como alerta.
3. Painel de prontidão por competência: quanto da carteira emite (e recebe) o grupo IBS/CBS com código válido, e quais
   fornecedores mais erram.

| Como medir | Meta |
|---|---|
| Tempo do Fiscal na conferência de IBS/CBS das empresas do regime normal (hipótese: 20 min por empresa por mês, ~45 empresas = 15 h/mês) | ~4 h/mês tratando a lista de exceções, medido no paralelo |
| Paralelo (V-VT-1): achados conferidos à mão em 5 empresas do regime normal por 1 mês | ≥ 95% dos achados confirmados; falso positivo explicado |
| Testes grátis que abrem o painel de prontidão (gancho de conversão) | Medir; sem meta nesta fase |

## 2. O que já existe (ou existirá depois da remodelagem) e será reaproveitado

| Item | Onde |
|---|---|
| Grupo IBS/CBS do item: `ibs_cbs_cst` varchar(3), `ibs_cbs_class_code` varchar(6) (objeto de valor `ItemIbsCbs`) | Remodelagem, seção 3.6 (`fiscal_document_items`, `FiscalDocumentItem.ibsCbs`) |
| Tipo do documento pelo subtipo: `Nfe` (`document_type = 'NFE'`, modelo 55), `Nfce` (`'NFCE'`, 65), ambos `GoodsInvoice`; `Nfse` e `Cte` não têm itens (I-16) | Remodelagem, FD-RN-01, FD-RN-06, FD-RN-14 |
| Cabeçalho: `issue_date`, `company_role` (`ISSUER`/`RECIPIENT` em `GoodsInvoice`; `isOwnIssue()`), `status` (nunca nulo; `CANCELED`), `deleted`, `issuer_document` + `issuer_document_type` (`CNPJ`/`CPF`), `issuer_name`, `issuer_tax_regime_code` (CRT) | Remodelagem, seção 3.4 e FD-RN-08 a FD-RN-10 |
| Índices: `idx_fd_company_type_issue (company_id, document_type, issue_date)`; itens por `idx_fdi_document_cfop_amount (document_id, …)` | Remodelagem, 3.6 e 3.8 |
| Checagens atuais de IBS/CBS (formato, alíquota só `LAW`, cálculo, grupo ausente, totais) | `service/fiscalcheck/IbsCbsItemCheck.java`, `IbsCbsDocumentCheck.java` (migradas na B-FD-05) |
| Motor e contexto por empresa (hoje 2 consultas: regras e alíquotas `LAW`) | `FiscalCheckEngine.contextFor`, `FiscalCheckContext(companyId, rules, ibsCbsRatesByYear)` |
| O que vira pendência na auditoria | `service/audit/AuditEngine.CHECK_RULES` (hoje só `IBSCBS_MISSING` de IBS/CBS) |
| Agrupador IBS/CBS no front (pega qualquer código `IBSCBS_*`) | `company-fiscal-conference/finding-groups.ts` |
| Detalhe da nota com aba de divergências | `features/invoices/invoice-detail/` |
| Console da plataforma e padrão de recurso global | `security/PlatformAdminOnly`, `security/AllowedWhenBlocked`, `rest/PlatformIbsCbsRateResource` (`/platform/ibs-cbs-rates`), front `features/platform/ibs-cbs-rates/` |
| Rotas da Reforma para o escritório | `rest/ReformResource` (`/reform/features`, `/reform/ibs-cbs-rates`), `ReformFeaturesService`, `dto/reform/ReformFeaturesDTO` (hoje só `stockCredit`) |
| Migration por classe | `db/migration/TableMigration` (`column`, `index`, `foreignKey`), ex.: `V84__StockCreditSurveyEvents` |
| Carteira e 404 | `common/TenantAccessService` |
| Erro único com campos extras no topo | `dto/ErrorDetailsDTO` (`details` é texto; listas vão em campo extra) |
| Upload multipart até 50 MB | `application.yaml` (`max-file-size: 50MB`) |

Não existe: tabela de cClassTrib, versionamento de tabela oficial, cache de catálogo global, painel de prontidão e
biblioteca de XLSX no `pom.xml` (por isso o layout é CSV; P-VT-02 fechada).

## 3. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| Admin da plataforma (CS Hub) | Baixa a tabela oficial, importa, confere as contagens, ativa; marca "conferida" quando o portal não tem versão nova | A cada publicação; checagem mensal (aviso aos 60 dias) |
| ADMIN do escritório | Vê o painel de prontidão do escritório inteiro | Mensal, depois do fechamento |
| FISCAL (carteira) | Vê o painel da carteira, os fornecedores que mais erram e os alertas na nota e na conferência | Ao conferir cada empresa |
| ACCOUNTING e MANAGEMENT | Leem o painel (têm `INVOICES_VIEW`) | Eventual |

**Teste grátis.** Tudo disponível para as até 5 empresas; o painel aparece assim que as primeiras notas chegam.
**Escritório bloqueado.** Painel, conferência, consultas e detalhe da nota continuam (só GET). A importação é da
plataforma (`@AllowedWhenBlocked`, como o `PlatformIbsCbsRateResource`) e não é afetada pelo bloqueio de nenhum
escritório.
**Serviço do plano.** Nenhum novo (P-03). As checagens do motor fazem parte da conferência básica (sem trava). O painel
fica liberado para todos até existir o módulo "Reforma Tributária" do Plano Taxify (P3-07); quando existir, as rotas
`/api/v1/reform/readiness/**` passam a exigir o módulo (P-VT-03 fechada) e as checagens continuam na conferência básica.
**Custo no SIEG: zero.** Usa só as colunas já gravadas; não relê XML (o expurgo dos arquivos não atrapalha).

## 4. Histórias

- H-VT-1. Como admin da plataforma, quero importar a tabela oficial de cClassTrib com versão, data, link e hash, para
  todos os escritórios conferirem pela mesma base rastreável.
- H-VT-2. Como admin da plataforma, quero ver as contagens antes de ativar e poder voltar para a versão anterior, para
  não publicar uma tabela errada para a carteira inteira.
- H-VT-3. Como admin da plataforma, quero ser avisado quando a tabela ativa passa de 60 dias sem atualização, para não
  conferir notas com base velha.
- H-VT-4. Como Fiscal, quero ver na nota e na conferência quando o cClassTrib não existe, está fora de vigência ou não é
  permitido no modelo, com a versão da tabela, para corrigir o cadastro do cliente ou cobrar o fornecedor.
- H-VT-5. Como ADMIN, quero um painel por competência com o percentual de notas e itens corretos de cada empresa, para
  saber onde agir primeiro.
- H-VT-6. Como Fiscal, quero ver os fornecedores que mais mandam nota sem grupo ou com código inválido, para avisar o
  cliente antes de o crédito ser glosado.

---

## Parte A. Tabelas oficiais no console da plataforma (compartilhada com o F2)

A Parte A **não lê documentos fiscais**: pode ser desenvolvida em paralelo com a remodelagem (seção 10, ordem).

### A.1 Enums

| Enum (`enumerated/`) | Valores | Uso |
|---|---|---|
| `OfficialTableType` | `IBS_CBS_CLASS_CODE` (esta spec), `NCM` (F2) | TO-RN-01 |
| `OfficialTableStatus` | `IMPORTED`, `ACTIVE`, `SUPERSEDED` | TO-RN-05 (descartada = `deleted = true`, sem status próprio) |
| `OfficialTableEventAction` | `IMPORTED`, `ACTIVATED`, `DISCARDED`, `CHECKED` | TO-RN-10 |
| `OfficialTableSituation` | `OK`, `STALE`, `NO_TABLE` | TO-RN-08 |

Rótulos em pt no front: `IMPORTED` "Importada", `ACTIVE` "Ativa", `SUPERSEDED` "Substituída"; `OK` "Em dia", `STALE`
"Desatualizada", `NO_TABLE` "Sem tabela"; tipos "cClassTrib (IBS/CBS)" e "NCM".

### A.2 Regras

| RN | Regra |
|---|---|
| **TO-RN-01 Tipos** | `type` ∈ `OfficialTableType`. Cada tipo tem o seu layout (parser) e a sua tabela de linhas; o cabeçalho, o ciclo de vida, a ativação, o aviso de desatualização, o cache e a consulta por histórico são comuns e estão aqui. Tipo novo entra acrescentando valor no enum, parser e tabela de linhas, sem mudar estas regras. `type` desconhecido: 400 `error.reform.table.type.invalid` |
| **TO-RN-02 Quem** (P-07) | Importar, ativar, descartar, marcar como conferida e ler as rotas `/platform/official-tables`: só `@PlatformAdminOnly` (outro usuário, inclusive ADMIN de escritório: 403, como todo o console). A tabela vale para todos os escritórios. Escritórios só leem pelas rotas `/reform/...` (TO-RN-12) |
| **TO-RN-03 Importação** | `POST /platform/official-tables` multipart: `type`, `file` (até `TAXIFY_OFFICIAL_TABLE_MAX_FILE_MB`, padrão 20), `versionLabel` (1 a 60 caracteres, aparado), `publishedOn` (`AAAA-MM-DD`, não futura na data de Recife), `sourceUrl` (obrigatória, começa com `https://`, até 300), `legalAct` (opcional, até 200), `officialFileHash` (opcional, 64 hexadecimais, guardado em minúsculas: SHA-256 do arquivo **original** do portal quando o enviado é uma conversão). O servidor calcula o SHA-256 do arquivo enviado (`fileHash`). O arquivo é lido e validado **inteiro** antes de gravar qualquer coisa. Com erro de conteúdo: 400 `error.reform.table.file.invalid`, com `errors` (até 20 itens `{line, column, reason}`, `reason` em pt) e `totalErrors` no topo do corpo, e **nada** é gravado. Metadado inválido: 400 `error.reform.table.metadata.invalid` com `invalidField` (nome do campo multipart). Sem erro: grava o cabeçalho (`IMPORTED`) e as linhas numa transação só, em lotes de 1.000 (`saveAll` + `flush`/`clear`, ou `JdbcTemplate.batchUpdate`); 201 |
| **TO-RN-04 Duplicidade** | Única por (`table_type`, `version_label`) e por (`table_type`, `file_hash`), entre as não descartadas e as descartadas (a chave é do banco). Repetida: 409 `error.reform.table.duplicate` com `existingId`. Duas importações iguais ao mesmo tempo em instâncias diferentes: a chave única garante uma só; a outra recebe o mesmo 409 (a `DataIntegrityViolationException` da chave é traduzida) |
| **TO-RN-05 Ciclo de vida** | `IMPORTED` → (ativar) → `ACTIVE` → (outra é ativada) → `SUPERSEDED` → (ativar de novo) → `ACTIVE`. `IMPORTED` que nunca foi ativada pode ser descartada (`DELETE`, soft delete; as linhas ficam, não são lidas). Versão já ativada uma vez (`first_activated_at` preenchido) não é descartada: é a base de achados já mostrados |
| **TO-RN-06 Ativação atômica** | `POST /{id}/activate` numa transação: `UPDATE official_tables SET status='SUPERSEDED', active_type=NULL WHERE active_type=:type AND id<>:id` e `UPDATE official_tables SET status='ACTIVE', active_type=:type, activated_at=:now, activated_by=:login, first_activated_at=COALESCE(first_activated_at, :now) WHERE id=:id AND deleted=0 AND status IN ('IMPORTED','SUPERSEDED')`. A coluna `active_type` tem chave única: nunca há duas ativas do mesmo tipo, nem com duas instâncias ativando ao mesmo tempo (a segunda transação espera o lock de linha ou falha na chave e responde 409 `error.reform.table.activation.conflict`). Ativar a que já está ativa: 200 sem mudança e sem evento. Descartada: 409 `error.reform.table.status.invalid` |
| **TO-RN-07 Consulta por histórico** | Para saber se um código vale numa data `D`, o catálogo usa as versões **já ativadas alguma vez** (`first_activated_at` não nulo, não descartadas): (1) se o código está na versão ativa, valem as linhas dela; (2) se não está, procura nas versões anteriores, da mais recente para a mais antiga por `first_activated_at`, e usa as linhas da primeira que o tiver; nesse caso, linha sem fim de vigência ganha **fim implícito** = `published_on` da versão seguinte (na mesma ordem) que deixou de trazer o código, menos 1 dia; (3) se não está em nenhuma, o código é **desconhecido**. Achado o conjunto de linhas, o código está **vigente em D** se alguma linha tem `valid_from ≤ D` e (`valid_to` nulo ou `D ≤ valid_to`); senão, **fora de vigência**. `D` = `issue_date.toLocalDate()` (a data local de Recife gravada do `dhEmi`, sem conversão de fuso, FD-RN-29). `IMPORTED` nunca ativada não conta. Documento com `issue_date` nulo (legado, I-08): não é conferido nem contado |
| **TO-RN-08 Desatualizada** (P-07) | Com a data de Recife (`Clock` do projeto): `daysSinceUpdate` = dias corridos entre a data de `max(activated_at, checked_at)` da versão ativa e hoje. `stale = daysSinceUpdate > TAXIFY_OFFICIAL_TABLE_STALE_DAYS` (padrão **60**: 60 dias ainda é `OK`, 61 é `STALE`). Sem versão ativa: `NO_TABLE`. O aviso aparece no console (faixa no topo das páginas da plataforma) e nas telas dos escritórios que dependem da tabela (painel de prontidão, conferência, detalhe da nota). Os achados continuam sendo gerados, sempre citando a versão |
| **TO-RN-09 Conferida** | `POST /{id}/mark-checked` (só na `ACTIVE`) registra que o admin conferiu o portal e a versão continua vigente: grava `checked_at` e `checked_by` e zera o contador da TO-RN-08. Não muda as linhas. Em outra situação: 409 `error.reform.table.status.invalid` |
| **TO-RN-10 Eventos** | Importar, ativar, descartar e conferir gravam um evento em `official_table_events` (`action`, `performed_by`, `performed_at` em Recife), na mesma transação da ação. É a trilha de "qual tabela valia em qual dia" |
| **TO-RN-11 Cache do catálogo e duas instâncias** | O catálogo de cada tipo (`OfficialCatalog`: as linhas de todas as versões já ativadas, indexadas por código) fica em memória por instância. A cada `FiscalCheckContext` (e a cada cálculo do painel ou consulta), uma consulta leve lê a **assinatura** do tipo: `SELECT id da ativa, COUNT(*) das já ativadas, MAX(activated_at)` (uma linha, sobre `official_tables`, pequena). Se mudou, recarrega (sincronizado por tipo: uma recarga por vez na instância; as outras threads esperam a nova). Assim, a ativação feita na instância A vale na próxima conferência da instância B, sem reiniciar. Volume esperado: centenas de linhas (cClassTrib) a ~15 mil (NCM) por versão |
| **TO-RN-12 Leitura pelos escritórios** | `GET /api/v1/reform/official-tables` (qualquer usuário autenticado de escritório, também em teste e bloqueado) devolve, por tipo, a situação da TO-RN-08. Não expõe quem importou nem os hashes. É dado público oficial, sem dado de cliente |
| **TO-RN-13 Arquivo não fica guardado** | O arquivo enviado não é gravado em disco nem no S3: ficam o hash, o nome (aparado, até 200 caracteres) e as linhas. Para provar a origem, valem `source_url`, `file_hash` e `official_file_hash` |
| **TO-RN-14 Mapeamento configurável** | Cada parser lê os nomes de coluna (CSV) ou de campo (JSON) de uma configuração com **padrão no código**, em `application.yaml` (`taxify.official-tables.class-code.columns.*` e, no F2, `taxify.official-tables.ncm.fields.*`), sobrescrevível por variável de ambiente (binding do Spring). Cada chave aceita uma lista de nomes alternativos, comparados sem distinção de caixa e aparados. Os padrões são os das seções A.4 e F2 6.2. Mudar o mapeamento não exige código, mas exige restart e vale só para importações seguintes; a versão importada não muda. É o "formato provisório configurável" enquanto o D-01/D-03 não confirma o layout oficial |

### A.3 Contrato da API (comum)

| Método e rota | Quem | Parâmetros e corpo | Respostas |
|---|---|---|---|
| `GET /api/v1/platform/official-tables` | `@PlatformAdminOnly` | `type` (obrigatório) | 200 `{situation, versions: [Version]}`; `versions` por `importedAt` decrescente, sem as descartadas, sem paginação (poucas versões); 400 `error.reform.table.type.invalid` |
| `POST /api/v1/platform/official-tables` | `@PlatformAdminOnly` | multipart da TO-RN-03 | 201 `Version`; 400 `error.reform.table.file.invalid` (`errors`, `totalErrors`), `error.reform.table.metadata.invalid` (`invalidField`), `error.reform.table.file.too.large`, `error.reform.table.type.invalid`; 409 `error.reform.table.duplicate` (`existingId`) |
| `GET /api/v1/platform/official-tables/{id}` | `@PlatformAdminOnly` | — | 200 `Version` + `rowSummary` (por tipo: A.4 e F2) + `events[]` (por `performedAt` decrescente); 404 `error.reform.table.not.found` (também descartada) |
| `GET /api/v1/platform/official-tables/{id}/rows` | `@PlatformAdminOnly` | `search` (código ou trecho da descrição, opcional), `page` (0), `size` (50, máx. 200) | 200 página Spring (`content`, `totalElements`, `totalPages`, `number`, `size`) das linhas do tipo, ordenada por `code` e `validFrom`; 400 `error.reform.table.filter.invalid` (`invalidField`); 404 |
| `POST /api/v1/platform/official-tables/{id}/activate` | `@PlatformAdminOnly` | — | 200 `Version`; 404; 409 `error.reform.table.activation.conflict`; 409 `error.reform.table.status.invalid` |
| `POST /api/v1/platform/official-tables/{id}/mark-checked` | `@PlatformAdminOnly` | — | 200 `Version`; 404; 409 `error.reform.table.status.invalid` (não é a ativa) |
| `DELETE /api/v1/platform/official-tables/{id}` | `@PlatformAdminOnly` | — | 204; 404; 409 `error.reform.table.status.invalid` (já foi ativada) |
| `GET /api/v1/reform/official-tables` | autenticado de escritório | — | 200 `[Situation]`, um por tipo, na ordem do enum |

**Version.** `id`, `type`, `versionLabel`, `legalAct`, `publishedOn`, `sourceUrl`, `fileHash`, `officialFileHash`,
`fileName`, `rowCount`, `status`, `importedBy`, `importedAt`, `activatedBy`, `activatedAt`, `firstActivatedAt`,
`checkedBy`, `checkedAt`. Datas-hora em ISO com o deslocamento de Recife (`2026-12-05T09:12:03-03:00`), datas em
`AAAA-MM-DD`.
**Situation** (TO-RN-08). `{type, situation: "OK"|"STALE"|"NO_TABLE", activeVersionLabel, publishedOn, activatedAt,
checkedAt, daysSinceUpdate, limitDays}`; em `NO_TABLE`, só `type`, `situation` e `limitDays`.
**Event.** `action` (`OfficialTableEventAction`), `performedBy`, `performedAt`.

| Code | Status | Texto (pt) |
|---|---|---|
| `error.reform.table.type.invalid` | 400 | "Tipo de tabela inválido." |
| `error.reform.table.metadata.invalid` | 400 | "Dados da versão inválidos: confira versão, data de publicação e link." |
| `error.reform.table.file.too.large` | 400 | "Arquivo acima do tamanho permitido." |
| `error.reform.table.file.invalid` | 400 | "O arquivo não está no layout esperado. Nada foi importado." |
| `error.reform.table.filter.invalid` | 400 | "Filtro inválido." |
| `error.reform.table.duplicate` | 409 | "Esta versão (ou este mesmo arquivo) já foi importada." |
| `error.reform.table.not.found` | 404 | "Versão de tabela não encontrada." |
| `error.reform.table.activation.conflict` | 409 | "Outra ativação está em andamento. Recarregue e tente de novo." |
| `error.reform.table.status.invalid` | 409 | "Esta ação não vale para a situação atual da versão." |

Motivos das linhas (`errors[].reason`, pt): "coluna obrigatória ausente", "coluna desconhecida", "valor obrigatório
vazio", "formato inválido", "data inválida", "fim de vigência anterior ao início", "linha repetida", "vigências
sobrepostas", "texto acima do tamanho", "arquivo vazio", "linhas acima do limite".

### A.4 Layout de importação do cClassTrib (`type = IBS_CBS_CLASS_CODE`)

Arquivo CSV, UTF-8 (com ou sem BOM), separador `;`, primeira linha com o cabeçalho. Nomes de coluna sem distinção de
caixa, aparados, em qualquer ordem, resolvidos pelo mapeamento da TO-RN-14 (padrões abaixo). Linhas totalmente vazias
são ignoradas. Até 5.000 linhas de dados. `line` nos erros = número da linha no arquivo (cabeçalho = 1).

| Chave do mapeamento | Coluna padrão | Obrigatória | Regra |
|---|---|---|---|
| `cst` | `CST` | sim | 3 dígitos |
| `cst-description` | `DESCRICAO_CST` | não | até 500 caracteres |
| `class-code` | `CCLASSTRIB` | sim | 6 dígitos |
| `description` | `DESCRICAO` | sim | 1 a 1.000 caracteres |
| `valid-from` | `INICIO_VIGENCIA` | sim | `dd/MM/aaaa` ou `AAAA-MM-DD` |
| `valid-to` | `FIM_VIGENCIA` | não | mesmo formato; vazio = sem fim; não pode ser anterior ao início |
| `nfe` | `NFE` | sim | `1`/`0` ou `S`/`N`: permitido no modelo 55 |
| `nfce` | `NFCE` | sim | idem, modelo 65 |
| `cte`, `nfse` | `CTE`, `NFSE` | não | idem (guardados para o F7; não usados nesta spec) |
| `indicator-prefix` | `IND_` | não | toda coluna que começa com o prefixo vai para `indicators_json` (`{"IND_X": "valor"}`, texto até 50 caracteres por valor); usados pelo F1b |
| — | outras | — | recusadas: 400 com o motivo "coluna desconhecida" na linha 1 (evita importar uma planilha errada em silêncio) |

Validações de conjunto: (`class-code`, `valid-from`) não se repete; duas linhas do mesmo cClassTrib não podem ter
vigências sobrepostas. **Não** se exige que o cClassTrib comece pelo CST (a tabela oficial é a fonte; a checagem de
prefixo do motor continua valendo sobre o XML).

`rowSummary` (no `GET /{id}`): `{rowCount, csts: [{cst, description, classCodes}], inForceToday, withEndDate}`, para o
admin conferir com a planilha oficial antes de ativar (`classCodes` = quantidade de cClassTrib do CST).

**Mapeamento da planilha oficial (preenchido no D-01).** O D-01 registra aqui a URL de download, a versão, a data de
publicação, o SHA-256 do XLSX original e, para cada chave acima, o nome da coluna da planilha oficial. Se os nomes
forem outros, o admin converte o XLSX para CSV **com os nomes padrão** (troca de formato e de cabeçalho, **sem editar
valores**) ou o dev acrescenta os nomes oficiais como alternativos na configuração (TO-RN-14).

| Chave | Coluna da planilha oficial (D-01) |
|---|---|
| `cst`, `cst-description` | a preencher |
| `class-code`, `description` | a preencher |
| `valid-from`, `valid-to` | a preencher |
| `nfe`, `nfce`, `cte`, `nfse` | a preencher |
| `indicator-prefix` | a preencher (indicadores de grupo que a planilha trouxer) |

---

## Parte B. Checagens novas no motor

### B.1 Regras

| RN | Regra |
|---|---|
| **VT-RN-01 Flag** | Com `TAXIFY_IBSCBS_OFFICIAL_TABLE_CHECK_ENABLED=false` (padrão), nenhuma checagem desta parte roda e o `contextFor` não lê o catálogo. É variável de ambiente: muda com o restart do container. `GET /reform/features` traz `ibsCbsOfficialTableCheck.enabled` |
| **VT-RN-02 Onde roda** | Bean novo `IbsCbsOfficialTableCheck implements FiscalCheck`, sobre o modelo novo: itens (`FiscalDocumentItem`) com grupo IBS/CBS (`getIbsCbs() != null`) de `GoodsInvoice` conferíveis (motor RN-02: `status <> CANCELED`, `deleted = false`), ou seja `Nfe` e `Nfce`, **emitidas** (`company_role = ISSUER`) **e recebidas** (`RECIPIENT`). O modelo vem do subtipo (`Nfe` = 55, `Nfce` = 65), nunca de coluna de texto. `Nfse` e `Cte` não têm itens e ficam de fora (F7). Sem versão já ativada do tipo `IBS_CBS_CLASS_CODE`: não roda (e as telas mostram `NO_TABLE`) |
| **VT-RN-03 Data** | `D` = `issue_date.toLocalDate()` (TO-RN-07). `issue_date` nulo: não roda |
| **VT-RN-04 `IBSCBS_CST_UNKNOWN`** | `ibs_cbs_cst` aparado com 3 dígitos que não aparece em nenhuma linha do catálogo, em nenhuma versão já ativada (TO-RN-07, passo 3, aplicado ao CST). CST nulo ou fora do formato: não roda (a checagem atual cuida) |
| **VT-RN-05 `IBSCBS_CLASS_TRIB_UNKNOWN`** | `ibs_cbs_class_code` aparado com 6 dígitos e desconhecido pela TO-RN-07. Fora do formato: não roda (o `IBSCBS_CLASS_TRIB` atual cuida) |
| **VT-RN-06 `IBSCBS_CLASS_TRIB_NOT_IN_FORCE`** | Conhecido, mas fora de vigência em `D` (TO-RN-07) |
| **VT-RN-07 `IBSCBS_CLASS_TRIB_MODEL`** | Vigente em `D`, mas a linha vigente tem `allowed_nfe = false` (documento `Nfe`) ou `allowed_nfce = false` (documento `Nfce`) |
| **VT-RN-08 Severidade e destino** (P-06) | Todos `WARNING`, em qualquer ano de emissão, até nova decisão do Tony. Aparecem na conferência da empresa (`finding-groups.ts` já agrupa `IBSCBS_*`) e na aba de divergências da nota. **Não** entram no `AuditEngine.CHECK_RULES`: sem `AUDIT_FINDING`, sem pendência, sem resumo por e-mail. As checagens atuais de IBS/CBS não mudam (`IBSCBS_RATE` continua só com alíquota `LAW`) |
| **VT-RN-09 Ordem e unicidade** | Por item, no máximo um achado de cClassTrib (o primeiro que casar: desconhecido, fora de vigência, modelo) e no máximo um de CST. Os dois podem sair juntos |
| **VT-RN-10 Formato do achado** | `FiscalDivergence(code, field, found, expected, WARNING, message)`. `field` = `CST` ou `cClassTrib` (rótulo do campo do XML, como os achados atuais). `found` = o código do XML. `expected` = descrição oficial da linha (até 200 caracteres, cortada com "…") ou, no desconhecido, `null`. Mensagens (pt): `"CST {cst} do IBS/CBS não existe na tabela {versao}"`; `"cClassTrib {c} não existe na tabela {versao}"`; `"cClassTrib {c} fora de vigência em {dd/MM/aaaa} (vigente de {início} a {fim ou 'sem fim'}, tabela {versao})"`; `"cClassTrib {c} não é permitido em {NF-e\|NFC-e} (tabela {versao})"`. `{versao}` = `version_label` da versão de onde veio a linha usada (no desconhecido, a ativa). Na fora de vigência com várias linhas, `{início}`/`{fim}` são os da linha mais recente anterior a `D` (ou da primeira posterior, se não houver anterior) |
| **VT-RN-11 Contexto** | `FiscalCheckContext` ganha o catálogo (`OfficialCatalog`, possivelmente vazio). Com a flag ligada, `contextFor` faz **no máximo 1 consulta a mais** por chamada (a assinatura da TO-RN-11) quando o cache está válido. Nenhuma consulta por item ou por documento |

---

## Parte C. Painel de prontidão da carteira

### C.1 Enums

| Enum | Valores | Rótulo (pt) |
|---|---|---|
| `ReadinessSide` | `ISSUED`, `RECEIVED` | "Emitidas", "Recebidas" |
| `ReadinessReason` | `FORMAT`, `CST_UNKNOWN`, `CLASS_CODE_UNKNOWN`, `NOT_IN_FORCE`, `MODEL_NOT_ALLOWED` | "Formato", "CST inexistente", "cClassTrib inexistente", "Fora de vigência", "Não permitido no modelo" |
| `ClassCodeSituation` | `IN_FORCE`, `NOT_IN_FORCE`, `UNKNOWN` | "Vigente", "Fora de vigência", "Desconhecido" |

### C.2 Regras

| RN | Regra |
|---|---|
| **PR-RN-01 Flag** | `TAXIFY_REFORM_READINESS_ENABLED=false` (padrão): rotas `/api/v1/reform/readiness/**` respondem 404 `error.reform.readiness.disabled`; `GET /reform/features` traz `readiness.enabled = false`. Independente da flag da Parte B |
| **PR-RN-02 Escopo e acesso** | Empresas `deleted = false`, `active != false`, do escritório, filtradas pela carteira (`TenantAccessService`). Permissão `INVOICES_VIEW` (`@RequiresPermission`). Empresa de outro escritório ou fora da carteira: 404 `error.Company.not.found` |
| **PR-RN-03 Competência** | `period` = `AAAA-MM`, de `2026-01` até o mês corrente de Recife; senão 400 `error.reform.readiness.period.invalid`. Documentos com `issue_date` em `[AAAA-MM-01 00:00, primeiro dia do mês seguinte 00:00)` |
| **PR-RN-04 Lado e universo** | `GoodsInvoice` (`document_type IN ('NFE','NFCE')`, **explícito** no SQL nativo, FD-RN-04), `deleted = false`, `status <> 'CANCELED'`, `issue_date` não nulo. `ISSUED` = `company_role = 'ISSUER'`; `RECEIVED` = `company_role = 'RECIPIENT'` |
| **PR-RN-05 Contadores por empresa** | `documents` = documentos do lado. `regularRegimeDocuments` = documentos com `issuer_tax_regime_code = '3'` (no lado recebido, é o CRT do fornecedor). `regularRegimeDocumentsWithGroup` = dessas, as que têm ≥ 1 item com `ibs_cbs_cst` preenchido. `itemsWithGroup` = itens (de todos os documentos do lado) com `ibs_cbs_cst` ou `ibs_cbs_class_code` preenchido. `validItems` = itens com grupo que passam em **todas**: cClassTrib com 6 dígitos começando pelo CST (regra atual), CST conhecido, cClassTrib conhecido, vigente em `D` e permitido no modelo (VT-RN-04 a 07). `documentsWithGroupPct = regularRegimeDocumentsWithGroup / regularRegimeDocuments × 100`; `validItemsPct = validItems / itemsWithGroup × 100`; ambos `BigDecimal` com 2 casas, `RoundingMode.HALF_UP`, **nulos** quando o denominador é 0. Sem tabela já ativada: `validItems`, `validItemsPct` e `topReasons` nulos. Não depende das flags da Parte B |
| **PR-RN-06 Motivos** | Cada item com grupo e inválido recebe **um** motivo, o primeiro que falhar nesta ordem: `FORMAT`, `CST_UNKNOWN`, `CLASS_CODE_UNKNOWN`, `NOT_IN_FORCE`, `MODEL_NOT_ALLOWED`. `topReasons` = até 3, por quantidade decrescente; empate pela ordem acima |
| **PR-RN-07 Cálculo agregado** | Sem rodar o motor documento a documento. Por lote de até 200 empresas, em SQL nativo sobre `fiscal_documents d JOIN fiscal_document_items i ON i.document_id = d.id`: (a) contagem dos documentos por empresa e CRT, com `EXISTS` de item com grupo; (b) itens com grupo agrupados por (`d.company_id`, `d.document_type`, `i.ibs_cbs_cst`, `i.ibs_cbs_class_code`) com `COUNT`, `MIN(d.issue_date)` e `MAX(d.issue_date)`; cada grupo é avaliado em Java pelo **mesmo catálogo e o mesmo avaliador** do motor (TO-RN-07; uma classe só, `IbsCbsClassCodeEvaluator`, usada pelo bean e pelo painel); se a vigência do código muda entre o mínimo e o máximo do grupo (fronteira dentro do mês), esse grupo é recontado por dia (`DATE(d.issue_date)`) numa consulta só para os grupos nessa situação. Resultado idêntico ao do motor para os mesmos itens (CT-PR-12). Plano de acesso: `idx_fd_company_type_issue` para os documentos e o prefixo `document_id` de `idx_fdi_document_cfop_amount` para os itens |
| **PR-RN-08 Cache** | O resultado do escritório inteiro por (`firmId`, `period`, `side`, assinatura do catálogo) fica em memória por `TAXIFY_REFORM_READINESS_CACHE_MINUTES` (padrão 10); a carteira do usuário e os filtros são aplicados depois, sobre o resultado em cache. Cada instância tem o seu cache (é só leitura; não há escrita a coordenar entre instâncias). A resposta traz `calculatedAt` |
| **PR-RN-09 Ordem e página** | `sort` ∈ {`validItemsPct`, `documentsWithGroupPct`, `documents`, `companyName`} com `,asc`/`,desc`. Padrão: `validItemsPct` crescente com nulos no fim, depois `documents` decrescente, depois `companyName`. `page` padrão 0, `size` padrão 50, máximo 200. Filtros: `search` (razão social ou CNPJ com ou sem máscara, mínimo 3 caracteres), `onlyWithDocuments` (padrão `true`: esconde empresa com `documents = 0`). Valor fora disso: 400 `error.reform.readiness.filter.invalid` com `invalidField` |
| **PR-RN-10 Resumo da carteira** | Somas dos contadores de todas as empresas que passam nos filtros (não só a página) e os percentuais calculados sobre as somas (não média de percentuais), com o mesmo arredondamento da PR-RN-05 |
| **PR-RN-11 Fornecedores** | `GET /reform/readiness/{companyId}/suppliers?period` usa o lado `RECEIVED` da empresa. Agrupa por `issuer_document` quando `issuer_document_type = 'CNPJ'`; documento de emitente pessoa física (`issuer_document_type = 'CPF'`) vai para uma linha só, `document = null`, `name = "Pessoa física"` (**CPF nunca aparece**). `name` = `issuer_name` do documento mais recente do fornecedor; `taxRegimeCode` = o CRT mais frequente (empate: o do mais recente). Campos: `documents`, `itemsWithGroup`, `itemsWithoutGroup` (itens de documentos com CRT 3 sem `ibs_cbs_cst`), `invalidItems` (PR-RN-05/06), `topReason`. Até 50, por (`itemsWithoutGroup + invalidItems`) decrescente, depois `documents` decrescente, depois `name`. Sem paginação |
| **PR-RN-12 Ligação com a conferência** | Cada linha do painel leva à conferência da empresa com o período da competência e o filtro do grupo IBS/CBS; o fornecedor leva à lista de notas recebidas filtrada pelo CNPJ (rotas e filtros que já existem no front) |

### C.3 Contrato da API do painel e das consultas

| Método e rota | Quem | Parâmetros | Respostas |
|---|---|---|---|
| `GET /api/v1/reform/readiness` | `INVOICES_VIEW` | `period` (obrigatório), `side` (`ISSUED` padrão, `RECEIVED`), `search`, `onlyWithDocuments`, `page`, `size`, `sort` | 200 corpo abaixo; 400 `error.reform.readiness.period.invalid`, `error.reform.readiness.filter.invalid` (`invalidField`); 404 `error.reform.readiness.disabled` |
| `GET /api/v1/reform/readiness/{companyId}/suppliers` | `INVOICES_VIEW` | `period` | 200 `{period, table, calculatedAt, suppliers: [Supplier]}`; 404 `error.Company.not.found`, `error.reform.readiness.disabled`; 400 período |
| `GET /api/v1/reform/class-codes` | autenticado de escritório | `search` (código ou trecho da descrição, mínimo 2 caracteres, opcional), `page`, `size` (padrão 50, máx. 100) | 200 `{versionLabel, page}` com as linhas da versão ativa (`cst`, `classCode`, `description`, `validFrom`, `validTo`, `allowedNfe`, `allowedNfce`), por `classCode` e `validFrom`; sem versão ativa: página vazia e `versionLabel = null`; 400 `error.reform.table.filter.invalid` |
| `GET /api/v1/reform/class-codes/{code}` | autenticado de escritório | — | 200 `{code, situationToday: ClassCodeSituation, rows: [...], versionLabel}` pela TO-RN-07 com a data de hoje em Recife; sem tabela: `situationToday = null`, `rows = []`, `versionLabel = null`; 400 `error.reform.table.code.invalid` se não tiver 6 dígitos |

As consultas `class-codes` não dependem de flag (dado público; o front só mostra o link quando há tabela).

**Corpo do painel.**

```json
{
  "period": "2026-11",
  "side": "ISSUED",
  "table": {"versionLabel": "TESTE v1", "publishedOn": "2026-10-30", "situation": "OK", "daysSinceUpdate": 12},
  "calculatedAt": "2026-12-05T09:12:03-03:00",
  "summary": {"companies": 2, "documents": 30, "regularRegimeDocuments": 10, "regularRegimeDocumentsWithGroup": 8,
              "documentsWithGroupPct": 80.00, "itemsWithGroup": 40, "validItems": 30, "validItemsPct": 75.00},
  "page": {"content": [ /* Row */ ], "totalElements": 2, "totalPages": 1, "number": 0, "size": 50}
}
```

`table` é `null` sem tabela já ativada.

**Row.** `companyId`, `companyName`, `cnpj` (14 dígitos), `regime` (regime da empresa vigente no mês, de
`company_tax_regimes`; nulo se não houver), `documents`, `regularRegimeDocuments`, `regularRegimeDocumentsWithGroup`,
`documentsWithGroupPct`, `itemsWithGroup`, `validItems`, `validItemsPct`, `topReasons[≤3]{reason, items}`.

**Supplier.** `document` (14 dígitos ou `null`), `name`, `taxRegimeCode`, `documents`, `itemsWithGroup`,
`itemsWithoutGroup`, `invalidItems`, `topReason` (`ReadinessReason` ou `null`).

| Code | Status | Texto (pt) |
|---|---|---|
| `error.reform.readiness.disabled` | 404 | "Recurso não disponível." |
| `error.reform.readiness.period.invalid` | 400 | "Competência inválida. Use de 2026-01 até o mês atual." |
| `error.reform.readiness.filter.invalid` | 400 | "Filtro inválido." |
| `error.reform.table.code.invalid` | 400 | "Código inválido." |

**`GET /api/v1/reform/features`** (`ReformFeaturesDTO`, hoje `{stockCredit}`) ganha `readiness: {enabled}` e
`ibsCbsOfficialTableCheck: {enabled}`. O F2 acrescenta `ncmCheck: {enabled}`.

---

## 5. Modelo de dados

**Numeração.** V80–V84 são da Onda 1, V85 das correções do SIEG e V86–V89 da remodelagem. Esta spec usa os **três
próximos números livres depois da V89** no momento do merge; reserva sugerida **V90, V91 e V92** (o F2 fica com a V93;
a captura ampliada, com as seguintes). Como o Flyway roda em ordem e não aceita migration com número menor que a última
aplicada, **nenhuma migration desta spec vai para o master antes da V89** (seção 10). Todas são `TableMigration`
(tabelas novas, sem dado de cliente, sem impacto em tabela grande).

**V90__OfficialTables**

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | varchar(36) not null, PK | |
| `table_type` | varchar(30) not null | `OfficialTableType` |
| `version_label` | varchar(60) not null | |
| `legal_act` | varchar(200) | |
| `published_on` | date not null | |
| `source_url` | varchar(300) not null | |
| `file_hash` | char(64) not null | SHA-256 em minúsculas |
| `official_file_hash` | char(64) | |
| `file_name` | varchar(200) | |
| `row_count` | int not null | |
| `status` | varchar(12) not null | `OfficialTableStatus` |
| `active_type` | varchar(30) | = `table_type` só na ativa; nulo nas outras |
| `imported_by`, `activated_by`, `checked_by` | varchar(50) | logins |
| `imported_at`, `activated_at`, `first_activated_at`, `checked_at` | datetime(6) | Recife |
| `deleted`, `created_by`, `created_date`, `last_modified_by`, `last_modified_date` | padrão do projeto | |

Únicos: `uk_official_tables_type_version (table_type, version_label)`, `uk_official_tables_type_hash (table_type,
file_hash)`, `uk_official_tables_active (active_type)` (o MySQL aceita vários nulos numa chave única).

**V91__OfficialTableEvents.** `id`, `table_id` varchar(36) not null (FK `fk_official_table_events_table`), `action`
varchar(12) not null, `performed_by` varchar(50) not null, `performed_at` datetime(6) not null + auditoria e `deleted`.
Índice `idx_ote_table_at (table_id, performed_at)`.

**V92__IbsCbsClassCodes.** `id`, `table_id` varchar(36) not null (FK `fk_ibs_cbs_class_codes_table`), `cst` char(3) not
null, `cst_description` varchar(500), `class_code` char(6) not null, `description` varchar(1000) not null, `valid_from`
date not null, `valid_to` date, `allowed_nfe` bit not null, `allowed_nfce` bit not null, `allowed_cte` bit,
`allowed_nfse` bit, `indicators_json` text. Sem colunas de auditoria (a linha é parte do agregado da versão, como o
item do documento). Único `uk_ibs_cbs_class_codes (table_id, class_code, valid_from)`; índice `idx_icc_table_cst
(table_id, cst)`. Algumas centenas de linhas por versão.

Entidades em `domain/reform/officialtable/`: `OfficialTable` (estende `AbstractAuditingEntity`), `OfficialTableEvent`,
`IbsCbsClassCode`. Repositórios estendem `AbstractJpaRepository` (padrão CRUD de `common/`); a ativação usa as duas
`@Modifying` da TO-RN-06.

**Tabelas grandes.** Nenhuma coluna nova em `fiscal_documents` ou `fiscal_document_items`. Índice novo em itens **só**
se o `EXPLAIN` da massa sintética (Q-VT-3) pedir, e com aprovação do Tony para a migration.

## 6. Variáveis de ambiente

| Variável | Padrão | Quem liga | O que faz |
|---|---|---|---|
| `TAXIFY_IBSCBS_OFFICIAL_TABLE_CHECK_ENABLED` | `false` | Tony, depois da primeira tabela ativa e do Q-VT | Liga as checagens da Parte B |
| `TAXIFY_REFORM_READINESS_ENABLED` | `false` | Tony, depois do Q-VT-3 (desempenho) | Liga o painel da Parte C |
| `TAXIFY_REFORM_READINESS_CACHE_MINUTES` | `10` | — | PR-RN-08 |
| `TAXIFY_OFFICIAL_TABLE_STALE_DAYS` | `60` (P-07) | — | TO-RN-08; vale para todos os tipos |
| `TAXIFY_OFFICIAL_TABLE_MAX_FILE_MB` | `20` | — | TO-RN-03 |
| `taxify.official-tables.class-code.columns.*` (yaml; env pelo binding do Spring) | nomes da A.4 | dev, depois do D-01 | TO-RN-14 |

A importação pelo console não tem flag: sem tabela ativa e com as duas flags desligadas, nada muda para os escritórios.
Todas entram em `application.yaml` e nos dois compose (`docker-compose.yml`, `docker-compose.aws.yml`) com o padrão
seguro.

## 7. Logs

Só ids, códigos de tabela e contagens. **Nunca** conteúdo do arquivo importado, CNPJ, razão social, nome de fornecedor
nem valor.

| Evento | Nível | Campos |
|---|---|---|
| `official.table.imported` | INFO | `type`, `tableId`, `rows`, `userId`, `durationMs` |
| `official.table.rejected` | INFO | `type`, `totalErrors`, `userId` (sem as linhas) |
| `official.table.activated` | INFO | `type`, `tableId`, `previousId`, `userId` |
| `official.table.checked` / `official.table.discarded` | INFO | `type`, `tableId`, `userId` |
| `official.catalog.reloaded` | INFO | `type`, `versions`, `rows`, `durationMs` |
| `reform.readiness.computed` | INFO | `firmId`, `period`, `side`, `companyCount`, `durationMs`, `cacheHit` |

## 8. Critérios de aceite

- **CA-TO-01 (TO-RN-03)** Dado um CSV com uma linha de data inválida, quando o admin importa, então 400 com `errors`
  citando linha e coluna, `totalErrors = 1`, e nenhuma linha gravada.
- **CA-TO-02 (TO-RN-04)** Dado uma versão já importada, quando o mesmo arquivo é enviado com outro `versionLabel`,
  então 409 `error.reform.table.duplicate` com `existingId`.
- **CA-TO-03 (TO-RN-06)** Dado duas instâncias ativando versões diferentes do mesmo tipo ao mesmo tempo, então fica
  exatamente uma `ACTIVE`.
- **CA-TO-04 (TO-RN-07)** Dado um código que saiu da versão nova (publicada em 2026-12-01) e uma nota de 2026-11-20 que
  o usa, então não há achado; numa nota de 2026-12-02, há `IBSCBS_CLASS_TRIB_NOT_IN_FORCE`.
- **CA-TO-05 (TO-RN-08)** Dado a versão ativada há 61 dias, então o console e o painel mostram `STALE`; depois de
  `mark-checked`, a situação volta a `OK`.
- **CA-TO-06 (TO-RN-02)** Dado o ADMIN de um escritório comum, quando chama qualquer rota `/platform/official-tables`,
  então 403.
- **CA-TO-07 (TO-RN-14)** Dado o mapeamento configurado com o nome alternativo `COD_CLASS_TRIB` para `class-code`,
  quando o admin importa um CSV com essa coluna, então a importação passa; sem a configuração, 400 "coluna
  desconhecida".
- **CA-VT-01 (VT-RN-04 a 07)** Dado a flag ligada e a tabela de teste ativa, então cada caso dos CT-VT-01 a 08 gera
  exatamente o achado esperado, `WARNING`, com a versão na mensagem.
- **CA-VT-02 (VT-RN-08, P-06)** Dado uma nota emitida com `IBSCBS_CLASS_TRIB_UNKNOWN`, quando a auditoria roda, então
  nenhum `AUDIT_FINDING` é criado por esse código, nenhuma pendência é aberta e o resumo por e-mail não muda.
- **CA-VT-03 (VT-RN-01)** Dado a flag desligada, então nenhum dos 4 códigos aparece em conferência nenhuma.
- **CA-VT-04 (VT-RN-02)** Dado uma nota recebida (`company_role = RECIPIENT`) de fornecedor CRT 3 com cClassTrib
  inexistente, então o achado aparece na conferência da empresa destinatária.
- **CA-PR-01 (PR-RN-05)** Dado 10 notas emitidas CRT 3 na competência, 8 com grupo, 40 itens com grupo e 30 válidos,
  então `documentsWithGroupPct = 80.00` e `validItemsPct = 75.00`.
- **CA-PR-02 (PR-RN-02)** Dado um FISCAL sem a empresa X na carteira, então X não aparece no painel nem no `summary`, e
  `/readiness/{X}/suppliers` responde 404. Empresa de outro escritório: 404.
- **CA-PR-03 (PR-RN-11)** Dado notas recebidas de produtor pessoa física, então o fornecedor aparece como "Pessoa
  física", sem CPF, numa linha só.
- **CA-PR-04 (bloqueio)** Dado um escritório bloqueado, então o painel e as consultas respondem 200.
- **CA-PR-05 (PR-RN-07)** Dado a mesma massa, então `validItems` do painel é igual ao número de itens com grupo sem
  nenhum achado de cClassTrib/CST (formato, prefixo e VT-RN-04 a 07) na conferência do mesmo período.

## 9. Casos de teste

Tabela de teste montada no fixture, **com códigos, descrições e vigências fictícios** (não são os da tabela oficial):

| CST | cClassTrib | Vigência | NF-e | NFC-e | Descrição (fictícia) |
|---|---|---|---|---|---|
| 000 | 000001 | 2026-01-01, sem fim | 1 | 1 | "Teste: tributação integral" |
| 200 | 200010 | 2026-01-01 a 2026-06-30 | 1 | 1 | "Teste: redução A" |
| 200 | 200020 | 2026-01-01, sem fim | 1 | 0 | "Teste: redução só NF-e" |
| 200 | 200030 | 2026-01-01 a 2026-11-15 | 1 | 1 | "Teste: redução B" |
| 410 | 410001 | 2026-01-01, sem fim | 1 | 1 | "Teste: imune" |

Versão "TESTE v1", publicada em 2026-10-30. Empresas: E1 = 11.222.333/0001-81 (emite NF-e, CRT 3), E2 =
12.345.678/0001-95 (fornecedor de E1, CRT 3), E3 = 33.444.555/0001-81 (outro escritório). Usuários `csadmin@exemplo.test`
(admin da plataforma), `admin@exemplo.test`, `fiscal1@exemplo.test` (carteira: E1). Documentos criados como `Nfe`/`Nfce`
pelas fábricas do modelo novo (`Nfe.fromParsed`), nunca por SQL nas tabelas antigas.

| CT | Dado | Esperado | RN |
|---|---|---|---|
| CT-TO-01 | Importar o CSV da tabela de teste | 201, `rowCount = 5`, `status = IMPORTED`; `rowSummary.csts` com 000 (1), 200 (3), 410 (1) | TO-03 |
| CT-TO-02 | CSV com coluna `OBS` a mais | 400, `errors[0] = {line: 1, column: "OBS", reason: "coluna desconhecida"}` | TO-03 |
| CT-TO-03 | CSV com 30 linhas inválidas | 400, `errors` com 20 itens, `totalErrors = 30` | TO-03 |
| CT-TO-04 | Linhas 200010 de 2026-01-01 a 2026-06-30 e de 2026-06-01 sem fim | 400 "vigências sobrepostas" | TO-03 |
| CT-TO-05 | `publishedOn` amanhã (Recife) | 400 `error.reform.table.metadata.invalid`, `invalidField = publishedOn` | TO-03 |
| CT-TO-06 | Mesmo arquivo, `versionLabel` "TESTE v1b" | 409 `error.reform.table.duplicate`, `existingId` = id da v1 | TO-04 |
| CT-TO-07 | Ativar "TESTE v1"; importar "TESTE v2" e ativar | v1 `SUPERSEDED`, v2 `ACTIVE`; 2 eventos `ACTIVATED` | TO-05, 06 |
| CT-TO-08 | Ativar v1 de novo | v1 `ACTIVE`, v2 `SUPERSEDED` | TO-05 |
| CT-TO-09 | `DELETE` de v2 (já ativada) | 409 `error.reform.table.status.invalid` | TO-05 |
| CT-TO-10 | Duas ativações simultâneas (v1 e v2) em instâncias diferentes | Uma só `ACTIVE`; a outra chamada 200 ou 409, nunca duas ativas | TO-06 |
| CT-TO-11 | v2 (publicada 2026-12-01) sem o 410001, ativada; nota de 2026-11-20 com 410001; nota de 2026-12-02 com 410001 | Primeira sem achado (fim implícito 2026-11-30); segunda `IBSCBS_CLASS_TRIB_NOT_IN_FORCE` citando "TESTE v1" | TO-07 |
| CT-TO-12 | Ativada em 2026-10-01; `Clock` de Recife em 2026-11-30 (60 dias) e 2026-12-01 (61 dias) | `OK` e `STALE` | TO-08 |
| CT-TO-13 | `POST /mark-checked` em 2026-12-01 | `checkedAt` gravado; situação `OK`, `daysSinceUpdate = 0`; evento `CHECKED` | TO-09, 10 |
| CT-TO-14 | Ativar na instância A; conferência na instância B em seguida | B usa a nova versão (assinatura mudou); log `official.catalog.reloaded` em B | TO-11 |
| CT-TO-15 | `admin@exemplo.test` chama `POST /platform/official-tables` | 403 | TO-02 |
| CT-TO-16 | `GET /reform/official-tables` sem nenhuma versão ativa | `[{type: IBS_CBS_CLASS_CODE, situation: NO_TABLE, limitDays: 60}, {type: NCM, situation: NO_TABLE, limitDays: 60}]` | TO-12 |
| CT-TO-17 | Configuração com `class-code: [CCLASSTRIB, COD_CLASS_TRIB]`; CSV com `COD_CLASS_TRIB` | 201 | TO-14 |
| CT-VT-01 | `Nfe` de 2026-11-10 de E1, item CST 000, cClassTrib 000001 | Nenhum achado novo | VT |
| CT-VT-02 | Item CST 000, cClassTrib 000999 | `IBSCBS_CLASS_TRIB_UNKNOWN` `WARNING`: "cClassTrib 000999 não existe na tabela TESTE v1" | VT-05 |
| CT-VT-03 | Item CST 300, cClassTrib 300001 | `IBSCBS_CST_UNKNOWN` e `IBSCBS_CLASS_TRIB_UNKNOWN` (dois achados) | VT-04, 05, 09 |
| CT-VT-04 | Item 200010 em nota com `issue_date` 2026-06-30T23:50 | Sem achado | VT-06 |
| CT-VT-05 | Item 200010 em nota com `issue_date` 2026-07-01T00:05 | `IBSCBS_CLASS_TRIB_NOT_IN_FORCE`: "cClassTrib 200010 fora de vigência em 01/07/2026 (vigente de 01/01/2026 a 30/06/2026, tabela TESTE v1)" | VT-06 |
| CT-VT-06 | Item 200020 em `Nfce` enviada por upload | `IBSCBS_CLASS_TRIB_MODEL`: "cClassTrib 200020 não é permitido em NFC-e (tabela TESTE v1)" | VT-07 |
| CT-VT-07 | Nota `CANCELED` com 000999 | Sem achado (motor RN-02) | VT-02 |
| CT-VT-08 | Item com cClassTrib "12345" (5 dígitos) | Só o `IBSCBS_CLASS_TRIB` atual; nenhum código novo | VT-05 |
| CT-VT-09 | Nota recebida de E2 por E1 (`company_role = RECIPIENT`) com 000999 | Achado na conferência de E1 | VT-02 |
| CT-VT-10 | Auditoria de E1 com o achado do CT-VT-02 | Nenhum `AUDIT_FINDING` novo; pendências e resumo por e-mail inalterados | VT-08 |
| CT-VT-11 | Flag desligada, mesma massa | Nenhum dos 4 códigos; `contextFor` sem a consulta de assinatura | VT-01 |
| CT-VT-12 | Flag ligada, nenhuma versão ativa | Nenhum código novo; `GET /reform/official-tables` com `NO_TABLE` | VT-02 |
| CT-VT-13 | Conferência de 200 notas de E1, cache válido | Consultas do `contextFor`: no máximo 3 (regras, alíquotas `LAW`, assinatura); nenhuma por item | VT-11 |
| CT-VT-14 | Ano de 2027 com alíquota `PENDING` e item com cClassTrib 000999 | `IBSCBS_CLASS_TRIB_UNKNOWN` sai; nenhum `IBSCBS_RATE` (só `LAW` gera achado de alíquota) | VT-08, AL-RN-02 |
| CT-PR-01 | E1, 2026-11, `ISSUED`: 10 notas CRT 3, 8 com grupo, 40 itens com grupo, 30 válidos (10 com 000999) | `documentsWithGroupPct = 80.00` (8/10); `validItemsPct = 75.00` (30/40); `topReasons = [{CLASS_CODE_UNKNOWN, 10}]` | PR-05, 06 |
| CT-PR-02 | 3 itens com grupo, 2 válidos | `validItemsPct = 66.67` (2/3 = 66,666… → 66,67, HALF_UP) | PR-05 |
| CT-PR-03 | Empresa só com notas CRT 1 | `regularRegimeDocuments = 0`, `documentsWithGroupPct = null` | PR-05 |
| CT-PR-04 | Sem tabela ativa | `validItems`, `validItemsPct`, `topReasons` nulos; `table = null` | PR-05 |
| CT-PR-05 | Item com problema de formato e de cClassTrib desconhecido ao mesmo tempo | Conta só `FORMAT` | PR-06 |
| CT-PR-06 | `period=2025-12` e `period=2027-02` com o `Clock` em 2026-12-05 | 400 `error.reform.readiness.period.invalid` nos dois | PR-03 |
| CT-PR-07 | Nota de 2026-11-30T23:59 e de 2026-12-01T00:00 | A primeira em 2026-11, a segunda em 2026-12 | PR-03 |
| CT-PR-08 | `fiscal1@exemplo.test` sem E2 na carteira: painel e `/readiness/{E2}/suppliers` | E2 fora do painel e do `summary`; 404 | PR-02 |
| CT-PR-09 | Usuário de E3 (outro escritório) pede `/readiness/{E1}/suppliers` | 404 | PR-02 |
| CT-PR-10 | E1 recebe 3 notas de produtores pessoa física (`issuer_document_type = CPF`, CPFs fictícios diferentes) | Uma linha "Pessoa física", `document = null`, `documents = 3`; nenhum CPF no corpo | PR-11 |
| CT-PR-11 | Ordem padrão com E1 75.00, empresa A 40.00, empresa B `null` | A, E1, B | PR-09 |
| CT-PR-12 | Mesma massa no painel e na conferência | `validItems` = itens com grupo sem achado de cClassTrib/CST | PR-07 |
| CT-PR-13 | Competência 2026-11; E1 emite um item 200030 em 2026-11-10 e outro em 2026-11-20 (vigência até 2026-11-15) | `itemsWithGroup = 2`, `validItems = 1`, `NOT_IN_FORCE` = 1; a recontagem por dia roda só para o grupo 200030 | PR-07 |
| CT-PR-14 | Duas chamadas iguais em 1 minuto | Segunda com `cacheHit = true` no log e mesmo `calculatedAt` | PR-08 |
| CT-PR-15 | Escritório bloqueado | Painel 200 | bloqueio |
| CT-PR-16 | Flag do painel desligada | 404 `error.reform.readiness.disabled`; `features.readiness.enabled = false` | PR-01 |
| CT-PR-17 | `size=201` | 400 `error.reform.readiness.filter.invalid`, `invalidField = size` | PR-09 |
| CT-PR-18 | E1 com 1 `Nfse` e 1 `Cte` na competência | Não entram em `documents` (só `GoodsInvoice`) | PR-04 |
| CT-PR-19 | `GET /reform/class-codes/200010` com o `Clock` em 2026-12-05 | `situationToday = NOT_IN_FORCE`, `rows` com a linha 200010, `versionLabel = "TESTE v1"` | C.3 |

## 10. Tarefas por agente e ordem

**Ordem.** A Parte A não lê documentos e pode ser desenvolvida já, num branch próprio a partir do master; o merge só
acontece **depois** da release da remodelagem (V86–V89 no master), por causa da numeração do Flyway. As Partes B e C
começam depois da B-FD-05 (motor migrado) estar no master. O contrato está fechado aqui: o front roda em paralelo.

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| D-01 | admin da plataforma + PO | Baixar a tabela de CST/cClassTrib vigente do Portal Nacional da NF-e; registrar URL, versão, data e SHA-256 do XLSX; preencher o mapeamento da A.4; gerar o CSV | — |
| B-TO-1 | backend | V90 a V92; enums da A.1; entidades e repositórios; importador genérico (validação inteira antes de gravar, transação única, lotes) com o parser do layout A.4 e o mapeamento configurável (TO-RN-14); duplicidade; mensagens pt e en | — (merge depois da V89) |
| B-TO-2 | backend | Ativação atômica, descarte, conferida, eventos, situação (TO-RN-08) com o `Clock` de Recife; `PlatformOfficialTableResource` (`@PlatformAdminOnly`, `@AllowedWhenBlocked`) e `GET /reform/official-tables` | B-TO-1 |
| B-TO-3 | backend | `OfficialCatalog` com consulta por histórico (TO-RN-07), assinatura e recarga (TO-RN-11); `IbsCbsClassCodeEvaluator`; testes unitários da TO-RN-07 | B-TO-1 |
| B-VT-1 | backend | `IbsCbsOfficialTableCheck` sobre `GoodsInvoice`/`FiscalDocumentItem`, flag, contexto (VT-RN-11); teste de que os códigos novos **não** estão no `CHECK_RULES` | B-TO-3, B-FD-05 |
| B-PR-1 | backend | Painel (PR-RN-01 a 10) com cálculo agregado em SQL nativo sobre `fiscal_documents`/`fiscal_document_items` e cache; fornecedores (PR-RN-11); `GET /reform/class-codes` e `/{code}`; campos novos em `ReformFeaturesDTO` | B-TO-3, B-FD-05 |
| F-TO-1 | frontend | Console: página `/plataforma/tabelas-oficiais` (`features/platform/official-tables/`) com abas por tipo (cClassTrib agora; NCM no F2): situação e faixa `STALE`/`NO_TABLE`, importar (formulário com versão, data, link, ato, hash oficial, arquivo; erros por linha), lista de versões, detalhe com `rowSummary`, linhas paginadas, eventos, ativar (confirmação com o número de linhas), marcar como conferida, descartar; item no menu da plataforma | contrato |
| F-VT-1 | frontend | Conferência e detalhe da nota: rótulos dos 4 códigos novos no grupo IBS/CBS, selo "tabela {versão}" e faixa quando `NO_TABLE` ou `STALE` (lida de `/reform/official-tables`) | contrato |
| F-PR-1 | frontend | Tela `/reforma/prontidao` (`features/reform/readiness/`, menu "Reforma Tributária", guiado por `/reform/features`): seletor de competência e de lado, cartões do `summary`, tabela paginada com barras de percentual e motivos, link para a conferência (PR-RN-12); painel lateral de fornecedores; faixa da situação da tabela | contrato |
| Q-VT-1 | QA | CT-TO-01 a 17, CT-VT-01 a 14 e CT-PR-01 a 19 por HTTP real, no MySQL local | B-PR-1, F-PR-1 |
| Q-VT-2 | QA | Primeira importação real (do D-01) no ambiente local: conferir `rowCount` e `rowSummary` contra a planilha oficial | D-01, B-TO-2 |
| Q-VT-3 | QA | Massa sintética: 1.000 empresas × 300 notas/mês × 5 itens no modelo novo; medir o painel frio e com cache e o `EXPLAIN`; meta: p95 < 5 s frio para 1.000 empresas | B-PR-1 |
| V-VT-1 | Fiscal | Paralelo de 1 mês: conferir os achados de 5 empresas do regime normal à mão e registrar os falsos positivos; ao fim, o Tony decide severidade e regra de auditoria | go-live |

## 11. Riscos

- **Tabela desatualizada ou convertida errado** gera falso positivo na carteira inteira. Mitigação: contagens antes de
  ativar, hash do original, versão em cada achado, aviso de 60 dias e reativação da versão anterior em um clique.
- **Mudança de layout da planilha oficial** entre versões: o importador recusa coluna desconhecida e não importa pela
  metade; o mapeamento (A.4, TO-RN-14) é revisto a cada versão nova, sem código.
- **Numeração do Flyway:** merge da Parte A antes da V89 quebra o deploy da remodelagem. Mitigação: branch sem merge até
  a release da remodelagem; o reviewer confere o número.
- **Alarme em ano-teste:** tudo `WARNING`, sem pendência e sem e-mail (P-06).
- **Desempenho do painel** na tabela de itens: agregação por grupo, lotes, cache e medição no Q-VT-3 antes de ligar a
  flag.
- **Responsabilidade técnica:** o achado diz o que a tabela oficial diz; a correção do cadastro é decisão do Fiscal.

## 12. Pré-requisitos de go-live

1. Remodelagem em produção (V86–V89, B-FD-05).
2. D-01 feito e a primeira versão real importada e ativada na EC2 pelo admin da plataforma (Q-VT-2 aprovado).
3. Q-VT-1 e Q-VT-3 aprovados.
4. Tony aprova ligar `TAXIFY_IBSCBS_OFFICIAL_TABLE_CHECK_ENABLED` e `TAXIFY_REFORM_READINESS_ENABLED` na EC2.
5. Rotina de checagem mensal do portal combinada com quem é admin da plataforma (P-07).
6. Jurídico: nada novo (dado público oficial; nada sai do Taxify; plataforma sem visão de escritório).

## 13. Fora do escopo

- Regras da NT 2025.002 com código de rejeição, grupos exigidos e vedados pelos indicadores (F1b).
- NFS-e e CT-e (F7); o layout já guarda as colunas.
- Regra de auditoria e pendência para estes achados (depois do paralelo).
- Importação automática da tabela a partir do portal; importação de XLSX direto.
- Visão da plataforma com contagens por escritório (P-21).
- Checagem de alíquota nova (continua a do F1a-0, só `LAW`).

## 14. Perguntas em aberto

**Nenhuma bloqueia a implementação.** Só o dado oficial falta para o go-live:

| Id | O que falta | Quem | O que acontece enquanto isso |
|---|---|---|---|
| D-01 | Planilha oficial de CST/cClassTrib (URL, versão, data, SHA-256, nomes de coluna) | admin da plataforma + PO | O dev implementa com o layout da A.4 e o mapeamento configurável; o teste usa a tabela fictícia da seção 9. Só a primeira importação real espera |

Fechadas nesta revisão pela recomendação (o Tony pode reabrir com motivo novo):

| P | Pergunta | Decisão |
|---|---|---|
| P-VT-02 | Importar o XLSX oficial direto? | **Não.** CSV com mapeamento configurável (TO-RN-14), sem dependência nova; XLSX só se a conversão der trabalho recorrente |
| P-VT-03 | O que entra no futuro módulo "Reforma Tributária"? | Painel de prontidão (e crédito de estoque) no módulo; checagens do motor na conferência básica |
| P-VT-04 | Mostrar o painel aos clientes do escritório? | Não nesta fase (uso interno) |

**Nota sobre o fim do paralelo (antiga P-VT-01).** Não é pergunta desta spec: pela P-06, tudo fica `WARNING`, sem
pendência, até o Tony declarar o fim do paralelo com o resultado do V-VT-1. Sugestão do PO para essa hora: regra de
auditoria MEDIA (sem e-mail) a partir de 2027, nascendo "a conferir", e `WARNING` no motor. Muda a VT-RN-08 e o
`CHECK_RULES`.
