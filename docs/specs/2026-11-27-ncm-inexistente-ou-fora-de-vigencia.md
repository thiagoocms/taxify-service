# NCM inexistente ou fora de vigência nas notas (F2)

> Feature nova, Onda 1 da [avaliação do RECUPERAC RT33](2026-11-27-avaliacao-recuperac-rt33.md) (F2). Escrita pelo
> po-taxify em 2026-11-27 (revisão 2 do mesmo dia: modelo novo de documentos, nomes em inglês, migration depois da
> V89, perguntas fechadas).
>
> **A importação, o versionamento, a ativação, o aviso de 60 dias, o cache, a consulta por histórico e o mapeamento
> configurável da tabela NCM seguem a Parte A do [validador IBS/CBS (F1a)](2026-11-27-validador-ibs-cbs-tabela-oficial.md),
> regras `TO-RN-01` a `TO-RN-14`, sem repetir aqui.** Esta spec define só o que é próprio do NCM: o layout do arquivo,
> as linhas, as checagens, a regra de auditoria, a consulta e o aviso no formulário da regra por produto.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base
> `br.com.cshub.taxify` (abreviado `…`). Identificadores em inglês; textos para o usuário em português.
>
> Depende de:
> - [Validador IBS/CBS (F1a)](2026-11-27-validador-ibs-cbs-tabela-oficial.md), Parte A (B-TO-1 a B-TO-3 prontos);
> - [Remodelagem dos documentos fiscais](2026-11-27-remodelagem-documentos-fiscais.md): `fiscal_documents`,
>   `fiscal_document_items` (coluna `ncm`), motor e auditoria migrados (B-FD-05);
> - [Motor de conferência fiscal](2026-11-26-motor-de-conferencia-fiscal.md): RN-01 a RN-03;
> - [Auditoria da carteira](2026-11-26-auditoria-da-carteira.md): RN-01 a RN-05, `CHECK_RULES`, job com `INVOICE_AUDIT`;
> - [Regras fiscais por produto](2026-11-26-regras-fiscais-por-produto.md): campo `ncmCorreto`.

## Status e decisões

**Pronta para implementação** depois do B-TO-1 a B-TO-3 do F1a (catálogo) e da B-FD-05 da remodelagem (motor e
auditoria no modelo novo). O **primeiro arquivo real** (go-live) depende do D-03.

| Decisão | Quem e quando | O que diz |
|---|---|---|
| P-07 | Tony, 2026-11-27 | Tabelas oficiais importadas pelo admin da plataforma no console, valendo para todos os escritórios, com aviso quando passam de 60 dias sem atualização |
| P-09 | Recomendação aceita | NCM inválido **não bloqueia** o salvamento da regra por produto: só avisa |
| P-03 | Recomendação aceita | Nenhum serviço novo; a regra de auditoria entra no job que já exige `INVOICE_AUDIT` |
| P-21 | Recomendação aceita | A plataforma não vê contagens por escritório |
| P-NC-01 a P-NC-03 | Fechadas nesta revisão pela recomendação | `00` sem achado; aviso só no formulário; auditoria só das emitidas (seção 16) |

**Relação com a P-06.** A P-06 (alerta até o fim do paralelo, sem pendência nem e-mail) vale para os achados de
**IBS/CBS**. O NCM não é da Reforma: é conferência de cadastro com tabela vigente há anos. Por isso o F2 mantém a regra
de auditoria `NCM_INVALID` (pendência **MEDIA**, nascendo "a conferir", **fora do e-mail**, que só leva ALTA). Se o Tony
quiser o NCM também só como alerta no paralelo, basta não registrar as duas entradas no `CHECK_RULES` (B-NC-3 sai do
escopo); nada mais muda.

**Regra fiscal.** A spec não cria regra fiscal: confere o NCM do item contra a tabela oficial importada. O ato citado
pelo material (Res. Gecex 926/2026, NCM vigente em 25/11/2026, 15.157 linhas) **continua a validar** até o D-03. O
tratamento de `00` (item que não é mercadoria) é orientação do Manual de Orientação do Contribuinte da NF-e, a
confirmar pelo Fiscal no paralelo; a spec adota a opção que **não gera achado** (a mais segura).

**Formato provisório.** A implementação começa já, com os nomes de campo **esperados** da seção 6.2 como padrão do
mapeamento configurável (TO-RN-14, `taxify.official-tables.ncm.fields.*`). Se o D-03 mostrar outros nomes, muda a
configuração, não o código.

## 1. Problema e objetivo

**Problema.** Nenhuma tela confere se o NCM do item existe ou estava vigente na data da nota. NCM errado leva a erro de
ST, de CEST, de alíquota de IPI e, a partir da Reforma, de enquadramento em redução. Na Fronteira PE, NCM sem tabela já
vira "sem regra" sem dizer se o problema é o código. Hoje esse erro passa batido, e cada caso achado tarde custa uma
retificação ou uma conversa com o fornecedor.

**Objetivo.** Achado objetivo, com a versão da tabela, na nota e na conferência; indício na auditoria das notas
emitidas; aviso ao cadastrar a regra por produto.

| Como medir | Meta |
|---|---|
| Achados `NCM_UNKNOWN` e `NCM_NOT_IN_FORCE` por mês na carteira (linha de base no primeiro mês ligado) | Queda mês a mês nas notas **emitidas** |
| Paralelo (V-NC-1): achados conferidos à mão em 5 empresas por 1 mês | ≥ 95% confirmados; falsos positivos explicados |

## 2. O que já existe (ou existirá depois da remodelagem)

| Item | Onde |
|---|---|
| NCM do item: `fiscal_document_items.ncm` varchar(10) | Remodelagem, seção 3.6 (`FiscalDocumentItem`) |
| Itens só em `GoodsInvoice` (`Nfe`, `Nfce`); `issue_date`, `status`, `deleted`, `company_role` no documento | Remodelagem, FD-RN-06, 3.4 |
| NCM da regra por produto (`ncmCorreto`) e checagem `RULE_NCM` | `domain/fiscalrule/ProductFiscalRule.java`; motor RN-07 |
| Importação, catálogo e mapeamento configurável de tabela oficial | F1a, Parte A (`official_tables`, `OfficialCatalog`, `/platform/official-tables`, TO-RN-14) |
| Regras da auditoria e mapa de códigos | `service/audit/AuditRuleDefinition.java` (14 regras hoje), `AuditEngine.CHECK_RULES` |
| Agrupador de achados no front | `company-fiscal-conference/finding-groups.ts` (NCM hoje cai em "Outros") |
| Formulário da regra por produto | `features/companies/company-product-fiscal-rules/rule-form/` |
| Rotas da Reforma para o escritório | `rest/ReformResource` (`/reform/features`), `ReformFeaturesDTO` |

## 3. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| Admin da plataforma (CS Hub) | Importa e ativa a NCM vigente; marca "conferida" | A cada alteração da NCM; checagem mensal |
| FISCAL (carteira) e ADMIN | Veem o achado na nota e na conferência; tratam a pendência da auditoria; recebem o aviso ao cadastrar regra | Ao conferir |
| Job da auditoria | Gera o indício `NCM_INVALID` nas notas emitidas das empresas com `INVOICE_AUDIT` | Diário, 05:30 Recife (já existe) |

**Teste grátis.** Disponível (as checagens fazem parte da conferência básica).
**Escritório bloqueado.** Conferência, nota e consulta respondem (GET); a auditoria automática para (já implementado no
`AuditScheduler`); a importação é da plataforma.
**Serviço do plano.** Nenhum novo. Checagens no motor: conferência básica, sem trava. Regra de auditoria: só nas empresas
cujo plano tem `INVOICE_AUDIT` (job atual). Módulo "Reforma Tributária": **não** se aplica ao F2.
**Custo no SIEG: zero.**

## 4. Histórias

- H-NC-1. Como admin da plataforma, quero importar a NCM vigente do Portal Único Siscomex com o ato e a data, para todos
  os escritórios conferirem pela mesma base.
- H-NC-2. Como Fiscal, quero ver na nota quando o NCM não existe ou não estava vigente na data, com a versão da tabela,
  para corrigir o cadastro do cliente ou cobrar o fornecedor.
- H-NC-3. Como ADMIN, quero que a auditoria aponte as notas emitidas com NCM inválido, para tratar como pendência.
- H-NC-4. Como Fiscal, quero ser avisado ao cadastrar uma regra por produto com NCM que não existe, sem ser impedido de
  salvar, para não travar quando a tabela estiver atrasada.

## 5. Regras de negócio

| RN | Regra |
|---|---|
| **NC-RN-01 Tipo** | `type = NCM` na Parte A do F1a (`OfficialTableType.NCM`). Valem TO-RN-02 a TO-RN-14, inclusive o aviso de 60 dias (TO-RN-08), a consulta por histórico (TO-RN-07) e o mapeamento configurável (TO-RN-14) |
| **NC-RN-02 Flag** | `TAXIFY_NCM_CHECK_ENABLED=false` (padrão): as checagens NC-RN-05 e 06 não rodam, o `contextFor` não lê o catálogo NCM e, por isso, a regra de auditoria não acha nada. `GET /reform/features` traz `ncmCheck.enabled` |
| **NC-RN-03 Onde roda** | Bean novo `NcmCheck implements FiscalCheck`, sobre o modelo novo: itens (`FiscalDocumentItem`) de `GoodsInvoice` conferíveis (motor RN-02: `status <> CANCELED`, `deleted = false`), ou seja `Nfe` e `Nfce`, **emitidas e recebidas**. `Nfse` e `Cte` não têm item (I-16). Sem versão já ativada do tipo `NCM`: não roda. `issue_date` nulo (legado): não roda |
| **NC-RN-04 Normalização** | `ncm` aparado e sem pontos. Nulo ou vazio: não confere. `00`: não confere (P-NC-01 fechada). Comparação sempre como **texto** (nunca número: o zero à esquerda importa) |
| **NC-RN-05 `NCM_UNKNOWN`** | (a) NCM normalizado que não tem 8 dígitos numéricos (ex.: 7 dígitos, letras): mensagem `"NCM {ncm} incompleto ou fora do formato de 8 dígitos"`. (b) 8 dígitos desconhecido pela TO-RN-07 entre os códigos de **nível 8**: `"NCM {ncm} não existe na tabela {versao}"`. Um código de 2, 4, 5, 6 ou 7 dígitos que exista na tabela como capítulo, posição ou subposição cai em (a): não é código de item |
| **NC-RN-06 `NCM_NOT_IN_FORCE`** | 8 dígitos conhecido, mas fora de vigência em `D = issue_date.toLocalDate()` (TO-RN-07): `"NCM {ncm} fora de vigência em {dd/MM/aaaa} (vigente de {início} a {fim ou 'sem fim'}, tabela {versao})"` |
| **NC-RN-07 Formato do achado** | `FiscalDivergence(code, "NCM", ncm, expected, WARNING, message)`. `expected` = descrição oficial do código (até 200 caracteres, cortada com "…") no NC-RN-06; `null` no NC-RN-05. No máximo um achado de NCM por item. `{versao}` = `version_label` da versão de onde veio a linha (no desconhecido, a ativa) |
| **NC-RN-08 Regra de auditoria `NCM_INVALID`** | Nova constante em `AuditRuleDefinition` (nome em inglês; as 14 existentes não mudam): grupo `COERENCIA`, severidade `MEDIA`, título "NCM inexistente ou fora de vigência nas notas emitidas", fundamento "Nomenclatura Comum do Mercosul: tabela vigente publicada no Portal Único Siscomex; NCM do item obrigatório na NF-e (MOC)", link `https://www.gov.br/siscomex/pt-br`, sem vigência inicial. `AuditEngine.CHECK_RULES` ganha `NCM_UNKNOWN` e `NCM_NOT_IN_FORCE` → `NCM_INVALID`. Como toda regra nova, nasce **"a conferir"** (auditoria RN-01 e RN-03): o achado sai com o título "A conferir: ..." até o time fiscal marcar a regra como conferida. Vira pendência `AUDIT_FINDING` **MEDIA**: não entra no resumo por e-mail, que só leva ALTA. Só notas emitidas (`company_role = ISSUER`, auditoria RN-05) |
| **NC-RN-09 Consulta por código** | `GET /api/v1/reform/ncm/{code}?date=AAAA-MM-DD` (padrão: hoje em Recife), qualquer usuário autenticado de escritório, sem flag. Devolve a situação pela TO-RN-07, a descrição e a hierarquia (capítulo, posição, subposições) da versão de onde veio a linha. Não é busca por texto |
| **NC-RN-10 Aviso na regra por produto** (P-09) | Ao sair do campo NCM do formulário da regra por produto, o front chama a NC-RN-09 com a data de hoje. `UNKNOWN`, `INCOMPLETE` ou `NOT_IN_FORCE`: aviso amarelo abaixo do campo ("NCM não encontrado na tabela {versão}" / "NCM incompleto: use 8 dígitos" / "NCM fora de vigência desde {data}"). **Não bloqueia** o salvamento, e o backend da regra por produto não muda. `NO_TABLE` ou erro da consulta: nenhum aviso |
| **NC-RN-11 Fronteira** | Sem mudança no cálculo da Fronteira PE nesta spec. O achado na nota já explica quando o "sem regra" da Fronteira vem de NCM inválido |

**Enum novo.** `NcmSituation`: `IN_FORCE` "Vigente", `NOT_IN_FORCE` "Fora de vigência", `UNKNOWN` "Não encontrado",
`INCOMPLETE` "Incompleto", `NO_TABLE` "Sem tabela".

## 6. Modelo de dados e layout

### 6.1 Migration

**V93__NcmCodes** (próximo número livre depois das migrations do F1a, V90–V92; conferir na hora do merge; nada vai
para o master antes da V89 da remodelagem). `TableMigration`, tabela nova.

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | varchar(36) not null, PK | |
| `table_id` | varchar(36) not null, FK `fk_ncm_codes_table` → `official_tables` | |
| `code` | varchar(8) not null | só dígitos, com o zero à esquerda |
| `code_level` | tinyint not null | número de dígitos (2 a 8) |
| `description` | varchar(1000) not null | como vem no arquivo, aparada |
| `valid_from` | date not null | |
| `valid_to` | date | nulo = sem fim |
| `start_act` | varchar(100) | tipo, número e ano do ato de início, juntos |

Sem colunas de auditoria (parte do agregado da versão, como `ibs_cbs_class_codes`). Único `uk_ncm_codes (table_id,
code, valid_from)`; índice `idx_ncm_codes_table_level (table_id, code_level)`. Volume: ~15 mil linhas por versão (o
material cita 15.157). Nenhuma mudança em `fiscal_documents` ou `fiscal_document_items`. Entidade `NcmCode` em
`domain/reform/officialtable/`.

### 6.2 Layout do arquivo (`type = NCM`)

O arquivo é o **JSON oficial** da tabela NCM do módulo Classif do Portal Único Siscomex, sem conversão. O portal
informa que o arquivo é atualizado toda meia-noite. Os nomes de campo abaixo são o **padrão do mapeamento** (TO-RN-14),
**esperados, a confirmar no D-03**; se forem outros, muda a configuração `taxify.official-tables.ncm.fields.*`, sem
código, e esta tabela é atualizada.

| Chave do mapeamento | Campo padrão no JSON | Uso | Regra |
|---|---|---|---|
| `act` | `Ato` (raiz) | `legal_act` da versão, se o admin não informar | texto até 200 |
| `last-update` | `Data_Ultima_Atualizacao_NCM` (raiz) | só exibido no `rowSummary` | texto |
| `list` | `Nomenclaturas` (raiz) | as linhas | lista obrigatória, não vazia |
| `code` | `Codigo` | `code` e `code_level` | pontos removidos; 2 a 8 dígitos |
| `description` | `Descricao` | `description` | 1 a 1.000 caracteres |
| `valid-from` | `Data_Inicio` | `valid_from` | data obrigatória no formato `date-format` |
| `valid-to` | `Data_Fim` | `valid_to` | mesmo formato; `open-end-date` ou vazio = sem fim; não anterior ao início |
| `act-type`, `act-number`, `act-year` | `Tipo_Ato_Ini`, `Numero_Ato_Ini`, `Ano_Ato_Ini` | `start_act` | opcionais, juntados com espaço |
| `date-format` | `dd/MM/yyyy` | formato das datas | — |
| `open-end-date` | `31/12/9999` | data que significa "sem fim" | — |

Validações de conjunto: (`code`, `valid_from`) não se repete; vigências do mesmo código não se sobrepõem. Campo
desconhecido na linha é ignorado (o JSON é o oficial; não é planilha montada à mão). Erros seguem a TO-RN-03 (`errors`
com `line` = posição na lista, base 1, e `column` = nome do campo; erro na raiz com `line = 0`). Tamanho: o arquivo
oficial deve caber no limite de 20 MB (TO-RN-03); o D-03 confirma. A leitura é em streaming (Jackson `JsonParser`), sem
carregar a árvore inteira.

`rowSummary` (no `GET /platform/official-tables/{id}`): `{rowCount, byLevel: {"2": n, "4": n, "5": n, "6": n, "7": n,
"8": n}, inForceTodayLevel8, withEndDate, lastUpdate}`.

## 7. Contrato da API

Rotas da plataforma: as da Parte A do F1a com `type=NCM` (nenhuma rota nova de plataforma).

| Método e rota | Quem | Parâmetros | Respostas |
|---|---|---|---|
| `GET /api/v1/reform/ncm/{code}` | autenticado de escritório | `date` (opcional, `AAAA-MM-DD`, de 1996-01-01 até hoje + 2 anos) | 200 corpo abaixo; 400 `error.reform.ncm.code.invalid` (com letras, vazio ou mais de 8 dígitos depois de tirar os pontos); 400 `error.reform.ncm.date.invalid` |

```json
{
  "code": "01012100",
  "date": "2026-11-27",
  "situation": "IN_FORCE",
  "description": "Teste: item A",
  "validFrom": "2022-04-01",
  "validTo": null,
  "hierarchy": [{"code": "01", "description": "Teste: capítulo 01"}, {"code": "0101", "description": "Teste: posição 0101"}],
  "versionLabel": "NCM TESTE v1"
}
```

`situation` ∈ `NcmSituation`. Em `UNKNOWN`, `INCOMPLETE` e `NO_TABLE`, `description`, `validFrom` e `validTo` vêm
nulos; `hierarchy` traz os níveis superiores que existirem (ajuda a achar o código certo; vazia em `NO_TABLE`), do mais
curto para o mais longo. `versionLabel` nulo em `NO_TABLE`.

`GET /api/v1/reform/features` ganha `ncmCheck: {enabled}`.

| Code | Status | Texto (pt) |
|---|---|---|
| `error.reform.ncm.code.invalid` | 400 | "NCM inválido: use até 8 dígitos." |
| `error.reform.ncm.date.invalid` | 400 | "Data inválida." |

## 8. Variáveis de ambiente

| Variável | Padrão | Quem liga | O que faz |
|---|---|---|---|
| `TAXIFY_NCM_CHECK_ENABLED` | `false` | Tony, depois da primeira tabela NCM ativa na EC2 e do Q-NC | NC-RN-02 |
| `taxify.official-tables.ncm.fields.*` (yaml; env pelo binding do Spring) | nomes da 6.2 | dev, depois do D-03 | TO-RN-14 |

O aviso de 60 dias usa a `TAXIFY_OFFICIAL_TABLE_STALE_DAYS` do F1a. A flag entra em `application.yaml` e nos dois
compose com `false`.

## 9. Logs

Os da Parte A do F1a (`official.table.*`, `official.catalog.reloaded`) com `type = NCM`. Nenhum log por achado. Nunca
o conteúdo do arquivo, CNPJ ou valor.

## 10. Critérios de aceite

- **CA-NC-01 (NC-RN-05)** Dado a tabela de teste ativa e a flag ligada, quando uma nota tem item com NCM `99999999`,
  então há `NCM_UNKNOWN` `WARNING` com a versão na mensagem.
- **CA-NC-02 (NC-RN-06)** Dado o código `84713012` com fim de vigência em 2026-03-31, quando a nota é de 2026-04-02,
  então há `NCM_NOT_IN_FORCE`; numa nota com `issue_date` 2026-03-31T23:59, não há.
- **CA-NC-03 (NC-RN-04)** Dado itens com NCM `00`, nulo ou vazio, então não há achado de NCM.
- **CA-NC-04 (NC-RN-08)** Dado uma empresa com `INVOICE_AUDIT` que emitiu nota com NCM `99999999`, quando a auditoria
  roda, então há um achado "A conferir: ..." da regra `NCM_INVALID`, pendência `AUDIT_FINDING` MEDIA, e o resumo por
  e-mail do dia não o inclui.
- **CA-NC-05 (NC-RN-08)** Dado o catálogo da auditoria, quando se lista as regras da auditoria, então há 15 regras, e
  `NCM_INVALID` aparece ativa e não conferida.
- **CA-NC-06 (NC-RN-02)** Dado a flag desligada, então não há `NCM_*` na conferência nem achado `NCM_INVALID` na
  auditoria.
- **CA-NC-07 (NC-RN-10)** Dado o formulário da regra por produto com NCM `99999999`, quando o usuário sai do campo,
  então aparece o aviso, e o salvamento continua permitido e grava o NCM informado.
- **CA-NC-08 (NC-RN-01, TO-RN-07)** Dado a versão nova (publicada em 2026-12-01) sem o código `84713099` que a anterior
  trazia sem fim, então a nota de 2026-11-20 com esse código não tem achado e a de 2026-12-02 tem `NCM_NOT_IN_FORCE`.
- **CA-NC-09 (TO-RN-14)** Dado o mapeamento com `code: [Codigo, Cod_NCM]`, quando o JSON traz `Cod_NCM`, então a
  importação passa.

## 11. Casos de teste

Tabela de teste "NCM TESTE v1" (fixture com códigos e datas **fictícios para o teste**, não a tabela oficial),
publicada em 2026-10-30:

| Código | Nível | Vigência | Descrição (fictícia) |
|---|---|---|---|
| `01` | 2 | 2022-04-01, sem fim | "Teste: capítulo 01" |
| `0101` | 4 | 2022-04-01, sem fim | "Teste: posição 0101" |
| `01012100` | 8 | 2022-04-01, sem fim | "Teste: item A" |
| `84713012` | 8 | 2022-04-01 a 2026-03-31 | "Teste: item B" |
| `84713099` | 8 | 2022-04-01, sem fim | "Teste: item C" |
| `22030000` | 8 | 2022-04-01, sem fim | "Teste: item D" |

Empresas: E1 = 11.222.333/0001-81 (com `INVOICE_AUDIT`), E2 = 12.345.678/0001-95 (fornecedor de E1), E3 =
33.444.555/0001-81 (outro escritório). Usuários `csadmin@exemplo.test` (admin da plataforma), `fiscal1@exemplo.test`.
Documentos como `Nfe`/`Nfce` do modelo novo.

| CT | Dado | Esperado | RN |
|---|---|---|---|
| CT-NC-01 | Importar o JSON de teste com as 6 linhas acima (códigos com pontos: `01.01`, `0101.21.00`...) | 201; `rowCount = 6`; `byLevel = {"2":1,"4":1,"8":4}` | 6.2 |
| CT-NC-02 | JSON com `Data_Inicio = 31/02/2026` na linha 3 | 400 `error.reform.table.file.invalid`, `errors[0] = {line: 3, column: "Data_Inicio", reason: "data inválida"}`; nada gravado | 6.2 |
| CT-NC-03 | JSON sem `Nomenclaturas` | 400 com `line = 0` e o motivo "coluna obrigatória ausente" | 6.2 |
| CT-NC-04 | JSON com `Data_Fim = 31/12/9999` | `valid_to` nulo | 6.2 |
| CT-NC-05 | Item `01012100` em nota de 2026-11-10 | Sem achado | 05 |
| CT-NC-06 | Item `84713012` em nota com `issue_date` 2026-04-02T08:00 | `NCM_NOT_IN_FORCE`: "NCM 84713012 fora de vigência em 02/04/2026 (vigente de 01/04/2022 a 31/03/2026, tabela NCM TESTE v1)" | 06 |
| CT-NC-07 | Item `84713012` em nota com `issue_date` 2026-03-31T23:59 | Sem achado | 06 |
| CT-NC-08 | Item `99999999` | `NCM_UNKNOWN`: "NCM 99999999 não existe na tabela NCM TESTE v1" | 05 b |
| CT-NC-09 | Item `1012100` (7 dígitos) | `NCM_UNKNOWN`: "NCM 1012100 incompleto ou fora do formato de 8 dígitos" | 05 a |
| CT-NC-10 | Item `0101` (posição existente) | `NCM_UNKNOWN` "incompleto" | 05 a |
| CT-NC-11 | Item `00`, item nulo, item vazio | Sem achado | 04 |
| CT-NC-12 | Item `0101.21.00` (com pontos, se o parser do XML gravar assim) | Normaliza para `01012100`: sem achado | 04 |
| CT-NC-13 | Nota `CANCELED` com `99999999` | Sem achado | 03 |
| CT-NC-14 | Nota recebida de E2 por E1 (`company_role = RECIPIENT`) com `99999999` | Achado na conferência de E1; **sem** achado de auditoria (a auditoria só olha emitidas) | 03, 08 |
| CT-NC-15 | Auditoria de E1 com 3 notas emitidas com `99999999` | 1 achado "A conferir: 3 nota(s) emitida(s): ncm inexistente ou fora de vigência ..." MEDIA; 1 pendência `AUDIT_FINDING`; resumo por e-mail inalterado | 08 |
| CT-NC-16 | Mesmo caso com a regra marcada como conferida | Título "Indício: ..." na execução seguinte, mesma pendência | 08 |
| CT-NC-17 | Flag desligada | Nenhum `NCM_*`; auditoria sem `NCM_INVALID` | 02 |
| CT-NC-18 | Flag ligada, nenhuma versão NCM ativa | Nenhum `NCM_*`; `GET /reform/ncm/01012100` → `NO_TABLE` | 03, 09 |
| CT-NC-19 | Versão "NCM TESTE v2" publicada em 2026-12-01 sem `84713099`; ativada. Notas de 2026-11-20 e 2026-12-02 com `84713099` | Primeira sem achado; segunda `NCM_NOT_IN_FORCE` citando "NCM TESTE v1" | TO-07 |
| CT-NC-20 | `GET /reform/ncm/0101.21.00` | 200 `IN_FORCE`, `code = 01012100`, `hierarchy` com `01` e `0101` | 09 |
| CT-NC-21 | `GET /reform/ncm/84713012?date=2026-04-02` | `NOT_IN_FORCE` | 09 |
| CT-NC-22 | `GET /reform/ncm/0101` | `INCOMPLETE`, `hierarchy` com `01` | 09 |
| CT-NC-23 | `GET /reform/ncm/12AB` e `GET /reform/ncm/123456789` | 400 `error.reform.ncm.code.invalid` | 09 |
| CT-NC-24 | Usuário de escritório bloqueado chama a consulta | 200 | 09 |
| CT-NC-25 | Formulário da regra com `99999999`, salvar | Aviso exibido; `POST`/`PUT` da regra 200 com o NCM gravado | 10 |
| CT-NC-26 | Importação de um JSON com 15.000 linhas sintéticas | 201 em menos de 30 s no ambiente local; uma transação | 6.2 |
| CT-NC-27 | Conferência de 200 notas de E1 com a flag ligada | Nenhuma consulta por item; o catálogo NCM carregado uma vez por instância | 03 |
| CT-NC-28 | Mapeamento com `code: [Codigo, Cod_NCM]`; JSON com `Cod_NCM` | 201 | TO-14 |
| CT-NC-29 | Usuário de E3 abre a nota de E1 com achado de NCM | 404 (isolamento de sempre; o catálogo é global, o documento não) | FD-RN-17 |

## 12. Tarefas por agente

**Ordem.** Depois do B-TO-1 a B-TO-3 do F1a e da B-FD-05 da remodelagem. A B-NC-1 (parser e catálogo) não lê documento
e pode ser feita junto da Parte A do F1a, com o mesmo cuidado de merge (só depois da V89).

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| D-03 | admin da plataforma + PO | Baixar o JSON da NCM vigente do Portal Único Siscomex; confirmar os nomes dos campos (6.2), o formato de data e o tamanho; registrar URL, ato e data; ajustar a configuração se preciso | — |
| B-NC-1 | backend | V93; entidade `NcmCode`; parser em streaming com o mapeamento configurável plugado no importador genérico do F1a; `rowSummary` do NCM; catálogo NCM no `OfficialCatalog` | B-TO-1 a B-TO-3 (F1a) |
| B-NC-2 | backend | `NcmCheck` (NC-RN-03 a 07) sobre `GoodsInvoice`/`FiscalDocumentItem`, com a flag; teste de contagem de consultas | B-NC-1, B-FD-05 |
| B-NC-3 | backend | `NCM_INVALID` em `AuditRuleDefinition` e as duas entradas no `CHECK_RULES`; atualizar os testes que contam 14 regras | B-NC-2 |
| B-NC-4 | backend | `GET /reform/ncm/{code}` (NC-RN-09); `ncmCheck` em `ReformFeaturesDTO`; mensagens pt e en | B-NC-1 |
| F-NC-1 | frontend | Aba "NCM" na página `/plataforma/tabelas-oficiais` do F-TO-1 (mesmos componentes; `rowSummary` por nível) | F-TO-1 |
| F-NC-2 | frontend | `finding-groups.ts`: grupo novo `NCM` ("NCM") para `NCM_UNKNOWN` e `NCM_NOT_IN_FORCE`; rótulos na conferência e na aba de divergências da nota, com a versão | contrato |
| F-NC-3 | frontend | Aviso no campo NCM do formulário da regra por produto (NC-RN-10), sem bloquear | contrato (pode começar antes do B-NC-4) |
| Q-NC-1 | QA | CT-NC-01 a 29 por HTTP real, no MySQL local | B-NC-4, F-NC-3 |
| Q-NC-2 | QA | Primeira importação real (D-03) local: `rowCount` e `byLevel` contra o total do portal | D-03, B-NC-1 |
| V-NC-1 | Fiscal | Paralelo de 1 mês em 5 empresas; marcar a regra `NCM_INVALID` como conferida quando confiar nela | go-live |

## 13. Riscos

- **NCM perde o zero à esquerda** quando passa por planilha: o Taxify guarda e compara sempre como texto (NC-RN-04).
- **Tabela atrasada** gera `NCM_UNKNOWN` em código novo: aviso de 60 dias (TO-RN-08), versão no achado e regra de
  auditoria nascendo "a conferir".
- **Nomes de campo do JSON diferentes** do esperado: o importador recusa com erro claro; o D-03 confirma antes do
  go-live; a mudança é de configuração (TO-RN-14).
- **Código extinto** em notas antigas: tratado pela consulta por histórico (TO-RN-07), sem falso positivo.
- **Numeração do Flyway:** V93 só depois de V90–V92 e da V89 no master.

## 14. Pré-requisitos de go-live

1. Remodelagem em produção e F1a Parte A em produção (B-TO-1 a B-TO-3, F-TO-1).
2. D-03 feito e a primeira versão NCM importada e ativada na EC2 (Q-NC-2 aprovado).
3. Q-NC-1 aprovado.
4. Tony aprova ligar `TAXIFY_NCM_CHECK_ENABLED` na EC2.

## 15. Fora do escopo

- Busca de NCM por texto (o portal oficial já faz).
- Enquadramento de NCM nos anexos da LC 214 (F3).
- Tabela de NCM monofásicos (F6) e mudança no cálculo da Fronteira PE.
- Conferência de NCM × CEST e NCM × alíquota de IPI.
- Aviso de NCM inválido na **lista** de regras por produto.

## 16. Perguntas em aberto

**Nenhuma bloqueia a implementação.** Só o dado oficial falta para o go-live:

| Id | O que falta | Quem | O que acontece enquanto isso |
|---|---|---|---|
| D-03 | JSON oficial da NCM (URL, ato, data, nomes de campo, formato de data, tamanho) | admin da plataforma + PO | O dev implementa com os nomes esperados como padrão do mapeamento configurável; o teste usa a tabela fictícia da seção 11. Só a primeira importação real espera |

Fechadas nesta revisão pela recomendação (o Tony pode reabrir com motivo novo):

| P | Pergunta | Decisão |
|---|---|---|
| P-NC-01 | NCM `00` fica sem achado? | **Sim**, sem achado nesta fase; achado `WARNING` próprio cruzando com CFOP de mercadoria só se o paralelo mostrar abuso. O Fiscal confirma a leitura do MOC no V-NC-1 |
| P-NC-02 | Aviso de NCM inválido também na lista de regras por produto? | **Não** nesta fase (fora do escopo); avaliar no paralelo |
| P-NC-03 | A regra `NCM_INVALID` olha também as recebidas? | **Não**: a auditoria é das emitidas (RN-05); o erro do fornecedor aparece na conferência |
