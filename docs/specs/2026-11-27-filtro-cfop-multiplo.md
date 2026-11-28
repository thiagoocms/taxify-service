# Filtro de CFOP múltiplo (chips) e totais que acompanham o filtro

Spec do po-taxify, 2026-11-27, revisão 2 (2026-11-28: decisões do Tony na P-CF-01 = c e P-CF-02 = CFOP efetivo; contrato final do backend na seção 6). Pedido do Tony de 2026-11-27.

## Status e decisões

| Parte | Situação |
|---|---|
| **F-CF-01: componente `shared/components/chips-input`** | **Pronta para implementar já.** Não depende do backend nem da remodelagem |
| Backend: notas fiscais (B-CF-01 a B-CF-03) | Pronta para implementar, **depois do merge da remodelagem** (`2026-11-27-remodelagem-documentos-fiscais.md`, branch `feature/fiscal-documents`, B-FD-05 e B-FD-07). É escrita contra o modelo novo (`fiscal_documents` e `fiscal_document_items`) |
| Backend: subtotal dos itens no CFOP (B-CF-04) | **Decidida (2026-11-28): P-CF-01 = opção (c)**. Implementada |
| Backend: Fronteira (B-CF-05) | Pronta para implementar, depois do merge da remodelagem (a B-FD-05 migra a Fronteira) |
| Front das telas (F-CF-02 e F-CF-03) | Pode ser desenvolvido em paralelo contra o contrato desta spec. O merge acontece depois do backend |
| Variável de ambiente | Nenhuma. A feature só lê dados e não chama provedor externo (seção 9) |
| Migration | Nenhuma. O índice que a consulta usa já existe na remodelagem (seção 8) |

Nada bloqueia mais. Decisões do Tony (2026-11-28): **P-CF-01 = (c)** (card com a nota inteira + linha secundária com a soma dos itens nos CFOPs) e **P-CF-02 = CFOP efetivo** (`fiscal_document_items.effective_cfop`, gravado pela remodelagem, FD-RN-57). As demais perguntas seguem a recomendação.

## 1. Estado atual (confirmado no código em 2026-11-27)

### Tela de Notas fiscais
- **Front:** `C:\workspace\taxify-front\src\app\features\invoices\invoice-xml-list\invoice-xml-list.component.ts`. O CFOP é um `app-input` de texto livre (`cfop-field`, html `:42-43`), com **um valor só**. Ele é enviado como `cfop: cfop.replace(/\D/g, '')` (`:459`) e filtra enquanto o usuário digita (debounce de 300 ms, `:279-287`). O estado fica no `InvoiceStateService` (`invoice-state.service.ts:11`, `cfop?: string`), **não na URL**. Da URL a tela só lê `empresa` e `mes` (`:240`, `:243`).
- **Backend (master):** `POST /api/v1/integrations/xmls` (lista) e `POST /api/v1/integrations/xmls/totais` (cards) recebem o mesmo `XmlSearchFilterDTO` (`dto/fiscalxml/XmlSearchFilterDTO.java`). O campo `cfop` é uma string. O filtro do CFOP é `exists` em item com `trim(i.cfop) = :cfop` (`FiscalXmlDocumentRepository.search`, `:79`).
- **Backend (modelo novo, `feature/fiscal-documents`):** `FiscalDocumentRepository.search` (`:53-85`) faz `exists (... i.product.cfop = :cfop)`, com o CFOP gravado sem espaços (spec 3.6 da remodelagem). Os totais vêm de `GoodsInvoiceRepository.totalsByDirection` (`:127-141`), só NF-e e NFC-e. `FiscalXmlQueryService.search` monta `cfopFilter` com `filter.cfop().replaceAll("\\D", "")` (`:173`).

### O que os totais ignoram hoje
`FiscalXmlQueryService.totals` diz no próprio comentário: "same company/period filter as the list, other filters ignored" (master `:98`; worktree `:103`). A consulta `totalsByDirection` só recebe empresa e período. Além disso, o front só manda empresa e período para `/totais` (`loadXmls`, `:439-451`), e nem pede de novo os totais quando muda a origem ou o filtro "só divergentes" (`loadXmls(false)`, `:357` e `:377`).

| Filtro da tela | Campo no corpo | A lista aplica? | Os totais aplicam hoje? |
|---|---|---|---|
| Empresa | `companyId` | Sim | Sim |
| Período | `dataEmissaoInicio`, `dataEmissaoFim` | Sim | Sim |
| Número da nota | `numero` | Sim | **Não** |
| Participante (nome ou CNPJ/CPF) | `participant` | Sim | **Não** |
| CFOP | `cfop` | Sim | **Não** |
| Conferência (a conferir, conferidas, com divergência) | `reviewStatus` | Sim | **Não** |
| Origem (SIEG, SEFAZ, upload) | `origem` | Sim | **Não** |
| Confronto SIEG × SEFAZ (só no SIEG, só na SEFAZ) | `fonte` | Sim | **Não** |
| Só divergentes (checagens fiscais) | `divergencia` | Sim, em memória (`search`, `:171-181`) | **Não** |
| Aba Entradas/Saídas | `direction` | Sim | Não (os cards já separam entrada e saída e são o próprio seletor da aba, html `:81` e `:100`) |

Há mais duas diferenças que a spec trata:
- **CT-e:** a lista do modelo novo traz NF-e, NFC-e e CT-e (`type(d) in (Nfe, Nfce, Cte)`), mas os cards contam só NF-e e NFC-e (`GoodsInvoice`). O CT-e não tem item, então nunca casa com o filtro de CFOP. Com CFOP no filtro, a lista e os cards contam as mesmas notas. Sem ele, a diferença continua como hoje (risco R-03).
- **CFOP filtrado × CFOP exibido:** depois da remodelagem (FD-RN-57) o item grava o CFOP **efetivo** (`effective_cfop`, índice `idx_fdi_document_effective_cfop`), e a coluna "CFOP" da lista e o filtro usam esse mesmo valor. Uma nota retificada de 5102 para 1102 aparece com 1102 e é achada pelo filtro 1102 (P-CF-02, decidida).

### Fronteira PE
- **Backend:** `GET /api/v1/fronteira?companyId&periodo` (`FronteiraResource.report`, `:81-82`) monta o `FronteiraDTOs.Report` inteiro: todas as linhas do extrato, sem paginação, mais o `Summary` do extrato inteiro e as entradas "sem cobrança".
- **De onde vem o CFOP:** **o extrato do e-Fisco não traz CFOP** (`EfiscoExtractNote`: registro, data de passagem, número, UF, valor, ICMS devido e CNPJ do emitente). O CFOP vem dos **itens do XML** da nota casada (CNPJ do emitente + número). É o CFOP **emitido pelo fornecedor** (5xxx/6xxx): `FronteiraService.cfopsOf` (`:481-494`) lê `findDistinctCfopsByDocumentIds` e preenche `NoteRef.cfops` e `Uncharged.cfops`. Linha sem XML (`SEM_XML`, `SO_RESUMO`) não tem CFOP. O cálculo (`FronteiraCalculator`, `:159-172`) deriva um CFOP de **entrada** (o da regra do produto ou `EntryCfops.fromSupplier`), mas ele só aparece na memória de cálculo.
- **Front:** `C:\workspace\taxify-front\src\app\features\fronteira\fronteira.component.ts`. Hoje **não há filtro de CFOP**. O filtro rápido por resultado ou marca (`filter`, `filtered`, `:209-216`) roda no front, sobre as linhas já carregadas. Os cards de resumo (`Summary`) são do **extrato inteiro** e não acompanham nenhum filtro. Os contadores dos filtros rápidos são contados no front (`:464`, `:468`).

### Reaproveitado
- `XmlSearchFilterDTO`, `FiscalXmlQueryService.search/totals`, `FiscalDocumentRepository.search`, `GoodsInvoiceRepository.totalsByDirection` e `FiscalDocumentItemRepository` (índice `idx_fdi_document_effective_cfop (document_id, effective_cfop)`).
- `FronteiraService.report` e a classe interna `Tally` (as mesmas contas de cobrado a mais e a menos, aplicadas ao recorte).
- O `app-input` (`shared/components/input/input.ts`) como referência de estilo, rótulo, `setDisabledState` e autocomplete com `searchFn`. O componente novo **não estende** o `app-input`: o valor dele é uma lista.
- O formato de erro único e o `MessageService` com `i18n/errors_pt.properties`.

## 2. Problema e objetivo

**Problema.** O Fiscal confere as entradas e saídas por grupo de CFOP: revenda (1102/2102), ST (1403/2403), bonificação (1910/2910), devolução, uso e consumo. Hoje o filtro aceita **um** CFOP. Para "todas as compras para revenda, dentro e fora do estado", ele faz duas buscas e soma à mão. E os cards de totais **não mudam com o filtro**: com o filtro "1102", o card continua mostrando o mês inteiro, e o Fiscal tira a soma da planilha. Na Fronteira não há filtro de CFOP. Para saber quanto o e-Fisco cobrou só das notas de ST, ele exporta o CSV e filtra no Excel.

**Objetivo.**
1. Filtrar por vários CFOPs de uma vez, na tela de Notas fiscais e na Fronteira, com o mesmo componente.
2. Fazer os totais da tela de Notas fiscais refletirem **todos** os filtros ativos.
3. Mostrar na Fronteira os totais do recorte por CFOP, sem mexer no resumo do extrato inteiro.

**Como medir.**
- Paralelo de uma semana: o QA e um Fiscal comparam o total filtrado com a soma manual na planilha, em 3 empresas e 5 combinações de filtro. A meta é diferença zero.
- O Fiscal deixa de exportar o CSV da Fronteira só para separar CFOP (perguntar ao time depois de 2 semanas).
- Quantas buscas usam 2 ou mais CFOPs (log de contagem, seção 10).

## 3. Usuários e cenário

| Perfil | Uso |
|---|---|
| FISCAL | Diário, na conferência do mês. Vê só as empresas da carteira dele (`users_companies`) |
| ADMIN do escritório | O mesmo, no escritório inteiro |
| Plataforma (CS Hub) | Nenhum. A tela não existe no console, e a F1-RN-15 continua valendo |
| Escritório bloqueado (somente leitura) | **Filtra e vê os totais normalmente.** As rotas são de leitura (`@ReadOnlyOperation` na lista e nos totais; GET na Fronteira) e o componente de chips não fica desabilitado |
| Teste grátis | Igual ao assinante. Não há serviço do plano novo: vale o controle de hoje das telas (permissão `INVOICES_VIEW`) |

## 4. Histórias

- **H-01** Como FISCAL, quero digitar 1102, 2102 e 1403 e ver as notas que têm algum desses CFOPs, para conferir as compras para revenda e ST numa busca só.
- **H-02** Como FISCAL, quero que os cards de Entradas e Saídas mostrem o total do que está filtrado, para não somar à mão.
- **H-03** Como FISCAL, quero copiar o link da tela com os CFOPs, para mandar a um colega a mesma visão.
- **H-04** Como FISCAL, na Fronteira, quero filtrar as linhas do extrato pelos CFOPs das notas e ver quanto foi cobrado e calculado só delas, para separar ST de revenda na revisão.
- **H-05** Como dev do front, quero um campo de chips genérico, com autocomplete opcional, para reusar em NCM, CST e outros filtros de múltiplos valores sem refazer o componente.

## 5. Regras de negócio

### 5.1 Valor do CFOP (front e backend)

| Regra | Descrição |
|---|---|
| **CF-RN-01 Normalização** | Tiram-se pontos, espaços e qualquer caractere que não seja dígito: "1.102" vira "1102", e " 5102 " vira "5102". A mesma função roda no front (`normalize` do componente) e no backend (`CfopFilter.normalize`) |
| **CF-RN-02 Validação** | Depois de normalizado, o valor tem que ter **exatamente 4 dígitos** (`^\d{4}$`). Não há conferência do primeiro dígito nem da tabela de CFOP nesta fase (P-CF-05) |
| **CF-RN-03 Duplicado** | O mesmo CFOP depois de normalizado ("1.102" e "1102") é duplicado. No front, o segundo é recusado com o aviso "CFOP 1102 já está no filtro.". O backend **remove o duplicado sem erro** (ver CF-RN-05) |
| **CF-RN-04 Limite** | Até **20 CFOPs** por filtro (P-CF-04). No front, o 21º é recusado com "Limite de 20 CFOPs no filtro.". No backend, mais de 20 valores distintos responde 400 `error.cfopFilter.tooMany` |
| **CF-RN-05 Ordem e canonicalização no backend** | O backend normaliza, valida, remove os duplicados e ordena em ordem crescente antes de consultar. A resposta não ecoa a lista. Na URL, o front mantém a ordem em que os chips foram adicionados |
| **CF-RN-06 Semântica "ou"** | Com vários CFOPs, a nota entra se tiver **pelo menos um item** com **algum** dos CFOPs (OU entre os CFOPs). Os demais filtros continuam combinados com E |
| **CF-RN-07 CFOP comparado** | Compara-se o CFOP **efetivo** do item (`fiscal_document_items.effective_cfop`, sem espaços): o da versão retificada quando a nota tem XML retificado, senão o emitido. É o mesmo valor que a coluna "CFOP" da lista mostra (P-CF-02, decidida em 2026-11-28) |
| **CF-RN-08 Tipos de documento** | O filtro de CFOP só casa com NF-e e NFC-e, que têm itens. **CT-e e NFS-e nunca casam.** Com CFOP no filtro, o CT-e some da lista, como acontece hoje com um CFOP só |
| **CF-RN-09 Compatibilidade por uma release** | O corpo aceita `cfops` (lista, novo) e `cfop` (string, antigo). Se `cfops` vier preenchido (não nulo e não vazio), ele vale e `cfop` é ignorado. Se só vier `cfop` não vazio, ele vira `[cfop]` com o **comportamento de hoje**: só dígitos, **sem validação**, e um valor que não tem 4 dígitos simplesmente não casa com nada. Assim a versão antiga do front continua funcionando durante a troca. O campo `cfop` é removido na release seguinte (tarefa B-CF-06) |

### 5.2 Totais da tela de Notas fiscais

| Regra | Descrição |
|---|---|
| **CF-RN-10 Totais seguem o filtro** | `POST /totais` aplica **todos** os filtros do corpo que a lista aplica: `numero`, `participant`, `reviewStatus`, `origem`, `fonte`, `cfops`/`cfop` e `divergencia`. A única exceção é `direction` (CF-RN-11). A lista e os totais usam **o mesmo predicado**, que fica num lugar só no código (constante JPQL compartilhada ou `Specification`), para não divergirem (risco R-01) |
| **CF-RN-11 Aba não filtra os cards** | `direction` **não** se aplica aos totais. Os dois cards (Entradas e Saídas) são o seletor da aba: clicar em "Entradas" não pode zerar o card de Saídas. O card da aba ativa continua destacado (`active`), como hoje (P-CF-03) |
| **CF-RN-12 Semântica com CFOP: nota inteira** | No card, `count`, `total`, `icms`, `icmsSt`, `ipi`, `pis` e `cofins` somam a **nota inteira** (os campos do cabeçalho, `totalAmount` = vNF e `goodsTotals.*`) de cada nota que passa no filtro, isto é, que tem pelo menos um item com algum dos CFOPs (P-CF-01 = c: o card é a parte b). Assim o card bate com a lista: o `count` é o número de notas não canceladas da lista, e o `total` é a soma da coluna "Valor total" delas (pergunta P-CF-01) |
| **CF-RN-13 Subtotal dos itens no CFOP** *(P-CF-01 = c, decidida)* | Com CFOP no filtro, cada card ganha a linha "valor dos produtos com esses CFOPs (sem frete, seguro e desconto)": a soma, **só dos itens** cujo CFOP efetivo é algum dos CFOPs, de `total_amount` (vProd), `icms_amount`, `icms_st_amount`, `ipi_amount`, `pis_amount` e `cofins_amount`, com `itemCount`. O rótulo deixa claro que é o **valor dos produtos**, sem rateio de frete, seguro, outras despesas e desconto, e sem somar IPI e ST ao valor. Por isso ele pode ser menor que o total da nota. A Taxify não rateia nada nesta fase: o rateio por CFOP do livro fiscal é regra que só entra com fonte (P-CF-01) |
| **CF-RN-14 Canceladas** | Como hoje: a nota cancelada que passa no filtro conta só em `canceledCount` e fica fora de todas as somas, inclusive do subtotal dos itens |
| **CF-RN-15 Só divergentes** | Com `divergencia=true`, os totais são somados em memória sobre as mesmas notas que a lista mantém. As notas que passam nos outros filtros são carregadas e checadas (`divergenceService.summarize`), e ficam só as divergentes ou marcadas DIVERGENT, como no `search` (`:171-181`). A soma usa `BigDecimal.add`, sem arredondamento: os valores já têm escala 2. A lista e os totais fazem a checagem **uma vez cada**. Não há cache entre as duas chamadas nesta fase (risco R-02) |
| **CF-RN-16 Escala e arredondamento** | As somas são exatas, em escala 2, como as colunas (`precision 19, scale 2`). Não há divisão nem arredondamento. SUM de nenhuma linha é 0,00 (`coalesce`) |
| **CF-RN-17 Sinal de filtro** | A resposta traz `filtered = true` quando algum filtro além de empresa, período e aba está ativo. O front mostra então o selo "Totais do filtro" nos cards, com tooltip listando os filtros ("CFOP 1102, 2102 · Participante · Conferidas"), para ninguém confundir com o total do mês. Sem filtro, os cards ficam como hoje |
| **CF-RN-18 Isolamento** | Igual ao de hoje: `companyValidation.checkExist` mais a verificação de carteira. Uma empresa fora da carteira ou de outro escritório responde **404** com o code de "não encontrado" de hoje. O filtro nunca alarga o escopo |

### 5.3 Fronteira PE

| Regra | Descrição |
|---|---|
| **CF-RN-20 Parâmetro** | `GET /api/v1/fronteira` ganha o parâmetro opcional `cfops` (lista; aceita `cfops=6102,6403` ou `cfops=6102&cfops=6403`). As regras CF-RN-01 a 05 valem para ele. Não há parâmetro antigo a manter |
| **CF-RN-21 CFOP comparado** | O CFOP **emitido pelo fornecedor** nos itens do XML casado: o mesmo que a tela já mostra em `NoteRef.cfops` e `Uncharged.cfops` (P-CF-06). Uma linha casa quando a nota dela tem pelo menos um desses CFOPs (OU) |
| **CF-RN-22 Linha sem XML** | Linha sem nota casada (`SEM_XML`, `SO_RESUMO`) não tem CFOP e **nunca casa** com o filtro. A resposta informa quantas ficaram de fora por isso (`linesWithoutXml`), e a tela mostra "N linha(s) do extrato sem XML não entram no filtro de CFOP" |
| **CF-RN-23 Nota cancelada** | A linha de nota cancelada que tem o CFOP casa e entra no recorte, porque foi cobrada. Ela conta como no `Tally` de hoje |
| **CF-RN-24 O relatório continua inteiro** | Com `cfops`, `lines`, `semCobranca`, `summary` e `previaIcms` do `Report` **não mudam**: continuam do extrato inteiro. O que é novo é o bloco `cfopFilter`, que traz quais linhas e quais entradas sem cobrança casam e os totais do recorte. O front filtra a tabela por essa lista. Assim o pedido de revisão, o PDF e o CSV continuam do mês inteiro (P-CF-07) e os cards do resumo continuam a ser a guia do mês |
| **CF-RN-25 Totais do recorte** | `cfopFilter.totals` aplica às linhas que casam **as mesmas contas do `Tally`** de hoje (a mesma classe, sem fórmula nova): `notes` (linhas), `invoicesAmount` (soma de `valorNota` do extrato), `icmsCharged` (soma de `icmsDevido`), `icmsCalculated` (soma de `calc.valor` quando há cálculo sem `semRegra`), `chargedAbove` (soma das diferenças COBROU_A_MAIS) e `chargedBelow` (COBROU_A_MENOS e NAO_CALCULOU). Para as entradas sem cobrança que casam: `unchargedCount` e `unchargedPreview` (soma de `calculo.valor`). Tudo em `BigDecimal`, escala 2, sem arredondamento novo. Na Fronteira a semântica é sempre a da **nota inteira**, porque o e-Fisco cobra por nota e o ICMS devido não é separado por item |
| **CF-RN-26 Filtros rápidos combinados** | O filtro rápido por resultado ou marca (no front) se aplica **sobre** as linhas do recorte de CFOP. Os contadores dos filtros rápidos passam a contar só as linhas do recorte quando há CFOP. A faixa "Totais do recorte" mostra só o recorte de CFOP, sem o filtro rápido (somar dinheiro fica no backend), e diz isso no rótulo: "Notas com CFOP 6102, 6403" |
| **CF-RN-27 Sem extrato** | Sem extrato no mês (`extract = null`), o recorte vale para a prévia: `matchingUncharged`, `unchargedCount` e `unchargedPreview`. `matchingLines` fica vazio e `notes = 0` |

### 5.4 Estado na URL (front)

| Regra | Descrição |
|---|---|
| **CF-RN-30 Formato** | `?cfops=1102,5102`: os CFOPs normalizados, separados por vírgula, na ordem em que foram adicionados. Sem CFOP, o parâmetro sai da URL. Vale nas duas telas (`/notas-fiscais` e a rota da Fronteira) |
| **CF-RN-31 Atualização** | Cada adição ou remoção faz `router.navigate([], { queryParams: { cfops }, queryParamsHandling: 'merge', replaceUrl: true })`, sem criar entrada nova no histórico a cada chip, e dispara a busca **na hora**, sem debounce, porque a ação é discreta. Os outros parâmetros (`empresa`, `mes`) são preservados |
| **CF-RN-32 Precedência** | Ao abrir a tela, `cfops` na URL vale mais que o estado salvo no `InvoiceStateService`. Sem `cfops` na URL, vale o estado salvo, como hoje, que agora guarda `cfops: string[]`, e o front escreve na URL o que restaurou |
| **CF-RN-33 URL inválida** | Valores inválidos ou duplicados na URL são **descartados em silêncio**, e ficam no máximo os 20 primeiros válidos. O front reescreve a URL só com o que ficou (`replaceUrl`). Não há toast: link velho não pode virar erro |
| **CF-RN-34 Troca de empresa e período** | Os chips **permanecem** ao trocar de empresa ou de período: o CFOP é intenção do usuário, não dado da empresa. "Limpar filtros", se existir na tela, limpa os chips |

### 5.5 Componente `chips-input` (genérico)

| Regra | Descrição |
|---|---|
| **CI-RN-01 Valor** | `ControlValueAccessor` com valor `string[]` (nunca `null` para fora: vazio é `[]`; `writeValue(null)` vira `[]`). Funciona com `formControlName`, `[formControl]` e `[(ngModel)]` |
| **CI-RN-02 Entradas** | `label` (obrigatório, vira o `<label>` do input), `placeholder`, `normalize: (raw: string) => string` (padrão: `trim`), `validate: (value: string) => string \| null` (texto do erro ou `null`; padrão: aceita qualquer valor não vazio), `maxItems: number \| null` (padrão `null`, sem limite), `allowDuplicates` (padrão `false`), `addLabel` (padrão "Adicionar"), `hint` (texto de ajuda sob o campo), `listLabel` (rótulo da lista para leitor de tela; padrão: `label`), `readonly` (padrão `false`), `separators` (padrão `[',', ';', ' ', '\n', '\t']`, usados só ao colar), `suggestions`, `suggestionsMinChars` (padrão 1), `suggestionsDebounceMs` (padrão 300), `maxSuggestions` (padrão 10), `duplicateMessage: (value) => string` (padrão "{valor} já foi adicionado.") e `limitMessage: (max) => string` (padrão "Limite de {max} itens.") |
| **CI-RN-03 Saídas** | `added(value)`, `removed(value)` e `rejected({ raw, value, reason: 'invalid' \| 'duplicate' \| 'limit', message })`. O valor do formulário muda só com `added` e `removed` |
| **CI-RN-04 Adicionar** | Ao clicar em "Adicionar" ou dar Enter no input: aplica `normalize`, depois `validate`, depois a checagem de duplicado e por fim o limite. Se passar, o chip entra **no fim** da lista, o input é limpo e o foco fica no input. Se for recusado, o texto digitado **fica** no input, a mensagem aparece sob o campo (`role="alert"` não; ver CI-RN-10) e o input ganha `aria-invalid="true"` até o próximo caractere. Enter **nunca** submete o formulário pai (`preventDefault`). Input vazio, ou só espaço, não faz nada nem mostra erro |
| **CI-RN-05 Colar** | Texto colado com algum `separator` é quebrado em partes, e cada parte passa pela CI-RN-04 na ordem. As válidas entram. As recusadas são resumidas numa mensagem só ("2 valores não entraram: 11 (inválido), 1102 (já adicionado)"), e o input fica vazio. Texto colado sem separador cai no input normal |
| **CI-RN-06 Remover** | O "x" de cada chip (`<button type="button" aria-label="Remover {valor}">`) remove o chip e devolve o foco ao input. Backspace com o input **vazio** remove o último chip. Delete ou Backspace com o foco no botão "x" de um chip remove aquele chip, e o foco vai para o chip anterior ou, se não houver, para o input |
| **CI-RN-07 Layout** | O input e o botão "Adicionar" ficam numa linha. Os chips ficam **abaixo**, ovais (`border-radius` total), lado a lado, quebrando linha (`flex-wrap`), com espaço de 8 px. A 1366 px, o campo ocupa a largura que a grade da tela der. A 390 px, ocupa 100% e os chips quebram linha sem rolagem horizontal; chip com texto maior que a largura corta com reticências e `title`. O "x" tem área de toque de no mínimo 24 × 24 px (WCAG 2.2, 2.5.8) |
| **CI-RN-08 Desabilitado** | `setDisabledState(true)` (ou `[disabled]`): input `disabled`, botão "Adicionar" `disabled`, chips sem "x", host com classe `is-disabled` e `aria-disabled="true"` no grupo. O autocomplete não abre. O valor é mantido |
| **CI-RN-09 Somente leitura** | `readonly=true`: input com atributo `readonly`, botão "Adicionar" oculto, chips sem "x", Backspace não remove e host com classe `is-readonly`. Os chips continuam focáveis para leitura. **Os filtros desta spec não usam `readonly` nem `disabled` no escritório bloqueado** (filtrar é leitura) |
| **CI-RN-10 Anúncio (aria-live)** | Uma região `aria-live="polite"` visualmente oculta anuncia "{valor} adicionado. {n} no total.", "{valor} removido. {n} no total." e as mensagens de recusa. A mensagem visível de recusa fica sob o campo, ligada ao input por `aria-describedby` |
| **CI-RN-11 Semântica** | O grupo tem `role="group"` com `aria-labelledby` do label. A lista de chips é `role="list"` com `aria-label` = `listLabel`, e cada chip é `role="listitem"`. Sem chips, a lista não é renderizada |
| **CI-RN-12 Contador** | Com `maxItems`, o hint mostra "{n} de {max}" quando n ≥ 1. No limite, o input continua habilitado (para o usuário ler a mensagem), mas a adição é recusada com `limitMessage` |

### 5.6 Autocomplete do componente (pronto, opcional, **não usado nesta spec**)

| Regra | Descrição |
|---|---|
| **CI-RN-20 Fonte** | `suggestions`: `null` (padrão, sem autocomplete), uma **lista estática** `{ value: string; label?: string }[]` ou uma **função** `(term: string) => Observable<{ value: string; label?: string }[]>` |
| **CI-RN-21 Quando busca** | Depois de `suggestionsMinChars` caracteres (no termo já com `trim`), com `debounceTime(suggestionsDebounceMs)` e `distinctUntilChanged`, e cancelando a busca anterior (`switchMap`). A lista estática é filtrada no próprio componente: contém o termo em `value` ou `label`, sem diferença de maiúscula ou acento, com no máximo `maxSuggestions` |
| **CI-RN-22 Já adicionado** | Com `allowDuplicates=false`, a sugestão cujo `normalize(value)` já está na lista **não aparece** |
| **CI-RN-23 Estados** | "Buscando..." enquanto a função não respondeu, "Nenhuma sugestão" com resposta vazia (ou tudo já adicionado) e "Não foi possível buscar sugestões" com erro do Observable. No erro, a digitação livre continua funcionando e o erro **não** vai para o console do usuário como toast |
| **CI-RN-24 Teclado** | Seta para baixo abre a lista (se fechada e com termo válido) e move a opção ativa; seta para cima volta, e as duas param nas pontas sem dar a volta. Enter com opção ativa adiciona o `value` dela (passa pela CI-RN-04); Enter sem opção ativa adiciona o texto digitado. Esc fecha a lista sem limpar o input. Tab fecha a lista e segue o foco normal. Home e End ficam no input (texto) |
| **CI-RN-25 Mouse e toque** | Clicar numa opção adiciona o valor. O `mousedown` faz `preventDefault` para o input não perder o foco. Clicar fora fecha a lista |
| **CI-RN-26 ARIA (combobox 1.2)** | O input tem `role="combobox"`, `aria-autocomplete="list"`, `aria-expanded`, `aria-controls` (id da lista) e `aria-activedescendant` (id da opção ativa). A lista é `role="listbox"` e cada opção `role="option"` com `aria-selected` na ativa. O estado "Buscando..." e "Nenhuma sugestão" é anunciado na região `aria-live` da CI-RN-10. Sem `suggestions`, nenhum desses atributos é renderizado |
| **CI-RN-27 Rótulo** | A opção mostra o `label` se houver (com o `value` em destaque), senão o `value`. O chip mostra sempre o `value` normalizado |

## 6. Contrato da API

### 6.1 `POST /api/v1/integrations/xmls` (lista) e `POST /api/v1/integrations/xmls/totais` (cards)
Quem acessa: `INVOICES_VIEW` (FISCAL da carteira, ADMIN do escritório), como hoje. `@ReadOnlyOperation`, inclusive no escritório bloqueado.

Corpo (`XmlSearchFilterDTO`). Só muda a parte de CFOP; os outros campos ficam como estão (FD-RN-23 da remodelagem mantém o JSON):

| Campo | Tipo | Novo? | Regra |
|---|---|---|---|
| `cfops` | `string[]` | **novo** | CF-RN-01 a 06. `null` ou `[]` = sem filtro de CFOP |
| `cfop` | `string` | antigo, **deprecated** | CF-RN-09. Ignorado se `cfops` vier preenchido. Sai na release seguinte |
| demais (`companyId`, `dataEmissaoInicio`, `dataEmissaoFim`, `numero`, `direction`, `participant`, `reviewStatus`, `pagination`, `origem`, `fonte`, `divergencia`) | como hoje | não | Em `/totais`, todos se aplicam, menos `direction` e `pagination` (CF-RN-10 e 11) |

Exemplo:
```json
{
  "companyId": "0b6f3c2e-1111-4a4a-9a9a-000000000001",
  "dataEmissaoInicio": "2026-11-01",
  "dataEmissaoFim": "2026-11-30",
  "participant": "alfa",
  "cfops": ["5102", "6102"],
  "pagination": { "page": 0, "size": 10 }
}
```

A resposta da lista não muda (`Page<FiscalXmlSummaryDTO>`). Ordenação e paginação ficam como hoje: `issueDate` DESC e `size` do corpo.

Resposta de `/totais` (`FiscalXmlTotalsDTO`). Os campos de hoje ficam iguais e entram dois novos:
```json
{
  "inbound":  { "count": 2, "total": 1500.00, "icms": 180.00, "icmsSt": 0.00, "ipi": 0.00, "pis": 0.00, "cofins": 0.00, "canceledCount": 1 },
  "outbound": { "count": 0, "total": 0.00, "icms": 0.00, "icmsSt": 0.00, "ipi": 0.00, "pis": 0.00, "cofins": 0.00, "canceledCount": 0 },
  "filtered": true,
  "cfopItems": {
    "inbound":  { "itemCount": 2, "itemsAmount": 1100.00, "icms": 132.00, "icmsSt": 0.00, "ipi": 0.00, "pis": 0.00, "cofins": 0.00 },
    "outbound": { "itemCount": 0, "itemsAmount": 0.00, "icms": 0.00, "icmsSt": 0.00, "ipi": 0.00, "pis": 0.00, "cofins": 0.00 }
  }
}
```

| Campo novo | Regra |
|---|---|
| `filtered` (boolean) | CF-RN-17 |
| `cfopItems` (objeto ou `null`) | CF-RN-13 (P-CF-01 = c). É `null` sem filtro de CFOP; com CFOP, `inbound` e `outbound` vêm **sempre** (zerados quando não há item). Campos: `itemCount`, `itemsAmount` (vProd), `icms`, `icmsSt`, `ipi`, `pis`, `cofins` |

Erros (formato único `{timestamp, status, code, message, details}`, com os campos extras no topo):

| Status | code | Mensagem (pt) | Campos extras | Quando |
|---|---|---|---|---|
| 400 | `error.cfopFilter.invalid` | "CFOP inválido: {0}. Informe 4 dígitos, por exemplo 1102." | `invalidValues: string[]` (os valores como vieram, no máximo 20) | Algum item de `cfops` não tem 4 dígitos depois de normalizado (inclusive vazio dentro da lista) |
| 400 | `error.cfopFilter.tooMany` | "Informe no máximo {0} CFOPs no filtro." | `maxItems: 20` | Mais de 20 valores distintos |
| 404 | o de "não encontrado" de hoje | o de hoje | não | Empresa fora da carteira ou de outro escritório (CF-RN-18) |

O `cfop` antigo nunca gera esses dois erros (CF-RN-09).

### 6.2 `GET /api/v1/fronteira`
Quem acessa: `INVOICES_VIEW`, como hoje. Parâmetros: `companyId` (obrigatório), `periodo` (opcional, `AAAA-MM`) e **`cfops` (novo, opcional, lista)**. Os mesmos erros 400 da seção 6.1 e o 404 de hoje para empresa de fora.

O `FronteiraDTOs.Report` fica igual (CF-RN-24) e ganha o campo `cfopFilter`, que é `null` sem `cfops`:
```json
"cfopFilter": {
  "cfops": ["6102"],
  "matchingLines": [1, 4],
  "matchingUncharged": ["0b6f3c2e-2222-4a4a-9a9a-000000000009"],
  "linesWithoutXml": 1,
  "totals": {
    "notes": 2, "invoicesAmount": 1900.00, "icmsCharged": 190.00, "icmsCalculated": 200.00,
    "chargedAbove": 0.00, "chargedBelow": 10.00, "unchargedCount": 1, "unchargedPreview": 45.00
  }
}
```

| Campo | Regra |
|---|---|
| `cfops` | A lista canônica (normalizada, sem duplicado, em ordem crescente, CF-RN-05). Aqui ela é ecoada porque o front a usa no rótulo |
| `matchingLines` | O `linha` (`Line.linha`) de cada linha que casa, em ordem crescente (CF-RN-21 a 23) |
| `matchingUncharged` | O `documentId` de cada entrada sem cobrança que casa |
| `linesWithoutXml` | Quantas linhas do extrato não têm nota casada (CF-RN-22). Não depende do filtro, mas só vem com ele |
| `totals` | CF-RN-25 |

Os relatórios `relatorio.pdf`, `relatorio.csv`, `pedido-revisao.pdf` e `pedido-revisao.txt` **não** recebem `cfops` (CF-RN-24 e P-CF-07).

### 6.3 Front: modelos
- `FiscalXmlSearchFilterDTO` (`core/models/fiscal-xml.model.ts:56`): troca `cfop?: string` por `cfops?: string[]`. O front novo nunca manda `cfop`.
- `FiscalXmlTotalsDTO`: entram `filtered: boolean` e `cfopItems?: {...} | null`.
- `FronteiraReportDTO` (`core/models/fronteira.model.ts`): entra `cfopFilter?: {...} | null`.
- `FronteiraService.report(companyId, periodo, cfops?)`: manda `cfops` como parâmetro repetido.

### 6.4 Contrato final implementado (backend, 2026-11-28)

Confere com o front (feito contra mock):
1. O front novo só manda `cfops`; `cfop` continua aceito por uma release (CF-RN-09) e loga INFO "legacy cfop filter used" com o `companyId`.
2. `/totais` recebe o mesmo `XmlSearchFilterDTO` da lista. `direction` e `pagination` são aceitos e **ignorados** nos totais (CF-RN-11).
3. Resposta de `/totais`: `inbound`, `outbound` (como antes), `filtered` (boolean, CF-RN-17) e `cfopItems` (`null` sem CFOP; com CFOP, `inbound` e `outbound` sempre presentes, com `itemCount`, `itemsAmount`, `icms`, `icmsSt`, `ipi`, `pis`, `cofins`). `filtered` é `true` com `numero`, `participant`, `reviewStatus`, `origem` (inclusive inválida, que não casa nada), `fonte` (`SO_SIEG`/`SO_SEFAZ`), `cfops`/`cfop` ou `divergencia=true`.
4. O filtro compara o CFOP **efetivo** (`effective_cfop`).
5. Erros 400 no formato único, com `message` em pt/en: `error.cfopFilter.invalid` + `invalidValues` (strings como vieram, `null` vira `""`, no máximo 20) e `error.cfopFilter.tooMany` + `maxItems: 20`. A validação acontece depois da checagem de acesso à empresa (empresa de fora continua 404).
6. Fronteira: `GET /api/v1/fronteira?companyId&periodo&cfops` aceita `cfops=6102&cfops=6949` e `cfops=6102,6949`. `lines`, `semCobranca`, `summary` e `previaIcms` vêm sempre inteiros. `cfopFilter` é `null` sem `cfops` e **sempre preenchido** com `cfops` (sem extrato: `matchingLines` vazio, `notes` 0, `linesWithoutXml` 0, a prévia recortada). Campos: `cfops`, `matchingLines` (int, crescente), `matchingUncharged` (UUID), `linesWithoutXml`, `totals` {`notes`, `invoicesAmount`, `icmsCharged`, `icmsCalculated`, `chargedAbove`, `chargedBelow`, `unchargedCount`, `unchargedPreview`}. `POST /fronteira/importar`, `POST /fronteira/sincronizar`, PDF, CSV e pedido de revisão não recebem `cfops` e devolvem `cfopFilter: null`. `Line.linha` é único por linha do extrato (o parser numera em sequência, 1..n).
7. O predicado único fica em `FiscalDocumentSearchFilter.WHERE` (lista, cards e subtotal dos itens). Um filtro novo da tela (por exemplo `documentTypes`) entra como componente do record + uma cláusula no `WHERE` + um termo em `narrowsTheScope()`.

## 7. Implementação no modelo novo (orientação ao backend)

- **Lista:** em `FiscalDocumentRepository.search`, troque `:cfop` por `(:noCfops = true or exists (select i.id from FiscalDocumentItem i where i.document = d and i.product.cfop in :cfops))`. Com lista vazia, passe `noCfops = true` e uma lista sentinela (`List.of("")`), porque `in ()` vazio é inválido no MySQL e no Hibernate.
- **Totais (b):** uma consulta nova em `GoodsInvoiceRepository`, `totalsByDirectionFiltered`, com **o mesmo WHERE** da busca (CF-RN-10), menos a direção, e o `group by d.direction` e as somas do `totalsByDirection` de hoje. Para não duplicar o texto, extraia o WHERE numa constante compartilhada ou passe as duas consultas para `Specification`/Criteria. O reviewer confere que os predicados são os mesmos. Os filtros de origem e fonte usam `FiscalDocumentSource`, como a busca.
- **Totais com `divergencia=true` (CF-RN-15):** reaproveite o caminho em memória do `search`, com a mesma lista de notas e `summarize`, e some num método único `sumByDirection(List<GoodsInvoice>)`.
- **Subtotal dos itens (c, B-CF-04):** uma consulta em `FiscalDocumentItemRepository`: `select d.direction, count(i), coalesce(sum(i.totalAmount),0), coalesce(sum(i.icms.amount),0), coalesce(sum(i.icmsSt.amount),0), coalesce(sum(i.ipi.amount),0), coalesce(sum(i.pis.amount),0), coalesce(sum(i.cofins.amount),0)`, de `FiscalDocumentItem i join i.document d`, com o mesmo WHERE da nota, `i.product.cfop in :cfops` e `d.status <> CANCELED`, e `group by d.direction`. Com `divergencia=true`, ela roda restrita aos ids das notas que sobraram, em lotes de 500.
- **Fronteira:** em `FronteiraService.report`, depois de montar `lines` e `uncharged`, se `cfops` não estiver vazio, filtre pelas listas `cfops` que já foram calculadas (`cfopsOf`, sem consulta nova), rode um `Tally` novo só nas linhas que casam e monte o `cfopFilter`. Não recalcule nada.
- **Normalizador e validador:** uma classe `CfopFilter` (por exemplo em `common/` ou `validation/`) com `normalize`, `validate(List<String>) -> List<String>` (canônica) e `legacy(String)`, usada pelas duas telas. Os erros vão via `BadRequestException` com os campos extras.

## 8. Modelo de dados

Nenhuma tabela, coluna ou migration nova.
- A subconsulta `exists ... ci.document = d and ci.effectiveCfop in (...)` usa o índice `idx_fdi_document_effective_cfop (document_id, effective_cfop)`, criado pela remodelagem (V86_1).
- A consulta de subtotal (B-CF-04) parte das notas da empresa e do período (`idx` de `(company_id, issue_date)` da remodelagem) e chega aos itens por `document_id`. Ela lê da tabela as colunas de imposto que não estão no índice, o que é aceitável porque o escopo é uma empresa e um período.
- Tabela grande: `fiscal_document_items` pode ter milhões de linhas na carteira de 5.000 empresas, mas toda consulta desta spec é **de uma empresa** e tem a data como filtro. O QA mede com `EXPLAIN` (Q-CF-02).

## 9. Variáveis de ambiente

Nenhuma. A justificativa: a feature só lê dados que já existem, não chama SIEG, SEFAZ nem e-Fisco, não manda e-mail e não muda cálculo fiscal. Os totais com filtro são somas das mesmas colunas de hoje. O risco de confusão fica coberto pelo selo "Totais do filtro" (CF-RN-17) e pelo paralelo da seção 2. Se o Tony preferir liberar aos poucos, a alternativa é `TAXIFY_FILTERED_TOTALS_ENABLED` (padrão `false`, desligada = totais como hoje), mas eu não recomendo: ela dobra os testes por pouco ganho.

## 10. Logs

Nada de valor, CNPJ, nome de participante ou número de nota.

| Onde | Nível | Conteúdo |
|---|---|---|
| `FiscalXmlQueryService.totals` | DEBUG | `companyId`, quantidade de CFOPs, quais filtros vieram ligados (nomes, não valores, ex.: `participant,reviewStatus`), `divergencia` (bool), tempo em ms |
| `CfopFilter.legacy` | INFO, uma linha por requisição | "legacy cfop filter used" + `companyId`. Mede quando o `cfop` antigo pode sair |
| `FronteiraService.report` com `cfops` | DEBUG | `companyId`, período, quantidade de CFOPs, `matchingLines.size`, `linesWithoutXml`, tempo em ms |
| 400 de CFOP | não loga além do padrão do handler | o valor inválido vai na resposta, não no log |

## 11. Critérios de aceite

### Componente (F-CF-01)
| CA | RN | Dado / Quando / Então |
|---|---|---|
| CA-01 | CI-RN-01, 04 | Dado um `chips-input` com `normalize` de CFOP e o valor `[]`, quando digito "1.102" e clico "Adicionar", então o chip "1102" aparece abaixo do campo, o input fica vazio e o valor do controle é `["1102"]` |
| CA-02 | CI-RN-04 | Dado o mesmo campo, quando digito "5102" e dou Enter, então entra o chip "5102" ao lado do primeiro e o formulário pai **não** é submetido |
| CA-03 | CI-RN-04, CF-RN-02 | Dado o `validate` de 4 dígitos, quando digito "11" e dou Enter, então nenhum chip entra, "11" continua no input, aparece "CFOP inválido: informe 4 dígitos." sob o campo e o input fica `aria-invalid="true"` |
| CA-04 | CI-RN-04, CF-RN-03 | Dado `["1102"]` e `allowDuplicates=false`, quando adiciono "1.102", então nada entra e aparece "CFOP 1102 já está no filtro." |
| CA-05 | CI-RN-12, CF-RN-04 | Dado `maxItems=20` e 20 chips, quando adiciono um 21º válido, então ele é recusado com "Limite de 20 CFOPs no filtro." e o hint mostra "20 de 20" |
| CA-06 | CI-RN-06 | Dado `["1102","5102"]` e o input vazio, quando aperto Backspace, então o valor vira `["1102"]`; quando clico no "x" de "1102", então o valor vira `[]` e o foco volta ao input |
| CA-07 | CI-RN-05 | Dado `[]`, quando colo "1102, 5102;11 1102", então o valor vira `["1102","5102"]` e aparece "2 valores não entraram: 11 (inválido), 1102 (já adicionado)" |
| CA-08 | CI-RN-08 | Dado o controle desabilitado, então o input e o botão têm `disabled`, os chips não têm "x", o host tem `is-disabled` e o grupo tem `aria-disabled="true"` |
| CA-09 | CI-RN-09 | Dado `readonly=true`, então o input tem `readonly`, o botão não é renderizado, os chips não têm "x" e Backspace não remove nada |
| CA-10 | CI-RN-10, 11 | Dado um leitor de tela, quando adiciono "1102" e depois removo, então a região `aria-live` recebe "1102 adicionado. 1 no total." e depois "1102 removido. 0 no total.", e a lista tem `role="list"` com itens `role="listitem"` |
| CA-11 | CI-RN-07 | Dado 12 chips, a 1366 px e a 390 px, então os chips quebram linha sem rolagem horizontal da página e o "x" mede no mínimo 24 × 24 px |
| CA-12 | CI-RN-20, 21, 26 | Dado `suggestions` como função e `suggestionsMinChars=2`, quando digito "1" nada é chamado; quando digito "11", então depois de 300 ms a função é chamada uma vez, a lista abre com `role="listbox"` e o input fica `aria-expanded="true"` |
| CA-13 | CI-RN-24 | Dado a lista aberta com 3 opções, quando aperto seta para baixo duas vezes e Enter, então entra a 2ª opção e `aria-activedescendant` apontava para ela antes do Enter; quando aperto Esc com a lista aberta, então ela fecha e o texto fica |
| CA-14 | CI-RN-22 | Dado `["1102"]` e uma sugestão `1102`, então ela não aparece na lista |
| CA-15 | CI-RN-23 | Dado uma função que demora, então aparece "Buscando..."; que responde `[]`, então "Nenhuma sugestão"; que dá erro, então "Não foi possível buscar sugestões" e Enter ainda adiciona o texto digitado |
| CA-16 | CI-RN-21 | Dado `suggestions` como lista estática `[{value:'1102',label:'Compra para comercialização'}]`, quando digito "comercializacao" (sem acento), então a opção aparece |
| CA-17 | CI-RN-26 | Dado `suggestions=null`, então o input não tem `role="combobox"` nem `aria-expanded` |

### Notas fiscais
| CA | RN | Dado / Quando / Então |
|---|---|---|
| CA-20 | CF-RN-06, 30, 31 | Dado a tela de uma empresa em novembro, quando adiciono 5102 e 6102, então a URL fica `?cfops=5102,6102` (com `empresa`/`mes` preservados, se havia), a lista mostra as notas com algum item 5102 **ou** 6102 e a busca sai na hora |
| CA-21 | CF-RN-10, 12 | Dado o filtro `cfops=[5102]`, então os cards somam a nota inteira das notas da lista: `count` = notas não canceladas da lista, e `total` = soma da coluna "Valor total" delas (CT-10) |
| CA-22 | CF-RN-10 | Dado qualquer filtro (número, participante, conferência, origem, fonte, só divergentes), quando ele é aplicado, então os cards mudam junto com a lista (CT-12 a CT-15) |
| CA-23 | CF-RN-11 | Dado o filtro `cfops=[5102]`, quando clico no card "Entradas", então a lista mostra só as entradas e o card de Saídas **continua** com o valor dele |
| CA-24 | CF-RN-14 | Dado uma nota cancelada com item 5102, quando filtro 5102, então ela aparece na lista, conta em "1 cancelada (fora dos totais)" e não entra em nenhuma soma |
| CA-25 | CF-RN-17 | Dado algum filtro além de empresa, período e aba, então os cards mostram o selo "Totais do filtro" com tooltip dos filtros; sem filtro, o selo não aparece |
| CA-26 | CF-RN-13 *(se P-CF-01 = c)* | Dado `cfops=[5102]`, então cada card mostra "Valor dos produtos com esses CFOPs: R$ 2.100,00 · ICMS R$ 252,00" com a nota "sem frete, seguro e desconto" (CT-10; a revisão 1 dizia 1.100,00 / 132,00, que não batia com o CT-10) |
| CA-27 | CF-RN-32, 33 | Dado que abro `/notas-fiscais?cfops=1102,11,1.102,2102`, então os chips são `1102` e `2102`, a URL vira `?cfops=1102,2102` e nenhum toast aparece |
| CA-28 | CF-RN-09 | Dado a versão anterior do front, que manda `cfop: "110"` enquanto o usuário digita, então a API responde 200 com a lista vazia (sem 400) |
| CA-29 | CF-RN-04, 6.1 | Dado `cfops` com 21 valores distintos, quando chamo `/totais` ou a lista, então a resposta é 400 `error.cfopFilter.tooMany` com `maxItems: 20` |
| CA-30 | CF-RN-02, 6.1 | Dado `cfops: ["1102","11"]`, então a resposta é 400 `error.cfopFilter.invalid` com `invalidValues: ["11"]` |
| CA-31 | CF-RN-18 | Dado um FISCAL sem a empresa B na carteira, quando chama `/totais` com `companyId` de B e `cfops`, então a resposta é 404, igual à de hoje |
| CA-32 | Usuários | Dado o escritório bloqueado, então o campo de chips está habilitado, a busca e os totais respondem 200 |

### Fronteira
| CA | RN | Dado / Quando / Então |
|---|---|---|
| CA-40 | CF-RN-20, 21, 24 | Dado o extrato de novembro da empresa, quando adiciono 6102, então a tabela mostra só as linhas cuja nota tem item 6102, a URL fica `?cfops=6102` e os cards do resumo (guia, cobrado a mais) **não mudam** |
| CA-41 | CF-RN-25 | Dado o filtro 6102, então a faixa "Notas com CFOP 6102" mostra notas, valor das notas, ICMS cobrado, ICMS calculado, cobrado a mais e cobrado a menos do recorte (CT-30) |
| CA-42 | CF-RN-22 | Dado linhas sem XML no extrato, quando filtro, então elas somem e aparece "1 linha do extrato sem XML não entra no filtro de CFOP" |
| CA-43 | CF-RN-26 | Dado o filtro 6102 e o filtro rápido "Cobrou a mais", então a tabela mostra a interseção, e o contador de "Cobrou a mais" conta só as linhas do recorte |
| CA-44 | CF-RN-24 | Dado o filtro 6102, quando abro "Pedido de revisão" ou baixo PDF/CSV, então o conteúdo é do mês inteiro, igual sem filtro |
| CA-45 | CF-RN-27 | Dado um mês sem extrato, quando filtro 6102, então a prévia mostra só as entradas com 6102 e a faixa mostra a contagem e a prévia do recorte |

## 12. Casos de teste (dados fictícios)

Empresa A: "Comércio Exemplo Ltda", CNPJ 11.222.333/0001-81, escritório E1, na carteira de `fiscal1@exemplo.test`. Empresa B: escritório E2. Período: 01/11/2026 a 30/11/2026, horário de Recife.

Notas de **entrada** de A em novembro (vNF = total da nota; o par de cada item é vProd/ICMS):

| Nota | Emitente | Situação | vNF | ICMS (nota) | Itens (CFOP: vProd / ICMS) |
|---|---|---|---|---|---|
| N1 | Fornecedor Alfa | autorizada, PENDING | 1.000,00 | 120,00 | i1 5102: 600,00 / 72,00 · i2 5405: 400,00 / 48,00 |
| N2 | Fornecedor Beta | autorizada, REVIEWED | 500,00 | 60,00 | i1 5102: 500,00 / 60,00 |
| N3 | Fornecedor Alfa | autorizada, PENDING | 300,00 | 36,00 | i1 6102: 300,00 / 36,00 |
| N4 | Fornecedor Beta | **cancelada** | 200,00 | 24,00 | i1 5102: 200,00 / 24,00 |
| N5 | Fornecedor Gama | autorizada, PENDING | 1.050,00 (vProd 1.000,00 + frete 50,00) | 120,00 | i1 5102: 1.000,00 / 120,00 |
| C1 | Transportadora Delta | CT-e autorizado | 80,00 | n/a | sem itens |

Os demais impostos (ST, IPI, PIS, COFINS) são 0,00 em todas, para simplificar.

| CT | Entrada | Esperado | RN |
|---|---|---|---|
| CT-01 | Sem filtro | Lista: N1, N2, N3, N4, N5, C1 (6). Card Entradas: `count` 4 (N1+N2+N3+N5), `total` 1.000+500+300+1.050 = **2.850,00**, `icms` 120+60+36+120 = **336,00**, `canceledCount` 1, `filtered=false` | CF-RN-12, 14 |
| CT-10 | `cfops=[5102]` | Lista: N1, N2, N4, N5 (C1 e N3 saem). Card: `count` 3, `total` 1.000+500+1.050 = **2.550,00**, `icms` 120+60+120 = **300,00**, `canceledCount` 1, `filtered=true`. `cfopItems.inbound` (opção c): `itemCount` 3 (i1 de N1, N2 e N5; N4 cancelada fica fora), `itemsAmount` 600+500+1.000 = **2.100,00**, `icms` 72+60+120 = **252,00** | CF-RN-06, 12, 13, 14 |
| CT-11 | `cfops=["5.102","6102","5102"]` | Canônica `[5102, 6102]`. Lista: N1, N2, N3, N4, N5. Card: `count` 4, `total` **2.850,00**, `icms` **336,00**. `cfopItems`: `itemCount` 4, `itemsAmount` 600+500+300+1.000 = **2.400,00**, `icms` 72+60+36+120 = **288,00** | CF-RN-01, 03, 05 |
| CT-12 | `cfops=[5102]` + `participant="alfa"` | Lista: N1. Card: `count` 1, `total` **1.000,00**, `icms` **120,00**. `cfopItems`: `itemCount` 1, **600,00** / **72,00** | CF-RN-10 |
| CT-13 | `cfops=[5405]` | Lista: N1. Card: `total` **1.000,00** (a nota inteira); `cfopItems`: **400,00** / **48,00** | CF-RN-12, 13 |
| CT-14 | `reviewStatus=REVIEWED` (sem CFOP) | Lista: N2. Card: `count` 1, `total` **500,00**, `filtered=true`, `cfopItems=null` | CF-RN-10, 17 |
| CT-15 | `divergencia=true`, com N3 e N5 divergentes nas checagens | Lista: N3, N5. Card: `count` 2, `total` 300+1.050 = **1.350,00**, `icms` **156,00** | CF-RN-15 |
| CT-16 | `cfops=[1102]` | Lista vazia. Cards zerados, `canceledCount` 0, `filtered=true` | CF-RN-06, 16 |
| CT-17 | `direction=INBOUND` + `cfops=[5102]` | Lista: só entradas. Card Saídas igual ao de `cfops=[5102]` sem direção | CF-RN-11 |
| CT-18 | Corpo antigo `cfop="5102"` | Igual ao CT-10. Log INFO "legacy cfop filter used" | CF-RN-09 |
| CT-19 | Corpo antigo `cfop="51"` | 200, lista vazia, sem 400 | CF-RN-09 |
| CT-20 | `cfop="6102"` e `cfops=["5102"]` | Vale `cfops`: igual ao CT-10 | CF-RN-09 |
| CT-21 | `cfops=["1102","11",""]` | 400 `error.cfopFilter.invalid`, `invalidValues: ["11",""]` | CF-RN-02 |
| CT-22 | `cfops` com 21 valores distintos válidos (1101 a 1121) | 400 `error.cfopFilter.tooMany`, `maxItems: 20`. Com 21 valores em que 2 são iguais (20 distintos), responde 200 | CF-RN-04, 05 |
| CT-23 | `fiscal1` (carteira só com A) chama com `companyId` de B (E2) | 404, code de hoje | CF-RN-18 |
| CT-24 | ADMIN de E2 chama com `companyId` de A | 404 | CF-RN-18 |
| CT-25 | Escritório E1 bloqueado, `cfops=[5102]` | 200, igual ao CT-10 | Usuários |
| CT-26 | Nota N6 emitida em 30/11/2026 às 23:30 (Recife) com item 5102, período 01/11 a 30/11 | Entra na lista e no card. Com o período 01/12 a 31/12, não entra | IssuePeriod de hoje |
| CT-27 | N1 retificada (5102 trocado por 1102 pela regra do produto, `effective_cfop` = 1102), filtro `[1102]` | Casa (compara o efetivo, CF-RN-07), e a coluna CFOP mostra 1102; com `[5102]`, não casa | CF-RN-07, P-CF-02 |
| CT-28 | Duas abas do navegador, cada uma com um filtro diferente, recarregando os totais ao mesmo tempo | Cada aba recebe o próprio total (rota sem estado, sem cache compartilhado) | CF-RN-10 |
| CT-29 | Front: abrir `?cfops=1102,11,1.102,2102` | Chips `1102` e `2102`; URL reescrita com `replaceUrl`; o botão "voltar" não passa pela URL inválida | CF-RN-33 |

Fronteira (empresa A, extrato de 11/2026; cálculo com mínimo = máximo):

| Linha | Nota casada | CFOPs do XML | valorNota | ICMS devido | Calculado | Resultado |
|---|---|---|---|---|---|---|
| L1 | X | 6102 | 1.000,00 | 100,00 | 100,00 | CONFERE |
| L2 | Y | 6403 | 800,00 | 80,00 | 50,00 | COBROU_A_MAIS (dif. 30,00) |
| L3 | (sem XML) | n/a | 400,00 | 40,00 | n/a | PENDENTE, SEM_XML |
| L4 | Z | 6102 e 6403 | 900,00 | 90,00 | 100,00 | COBROU_A_MENOS (dif. 10,00) |

Entradas sem cobrança: U1 (6102, prévia 45,00) e U2 (6949, prévia 12,00).

| CT | Entrada | Esperado | RN |
|---|---|---|---|
| CT-30 | `cfops=6102` | `matchingLines` [1, 4], `linesWithoutXml` 1, `totals`: `notes` 2, `invoicesAmount` 1.000+900 = **1.900,00**, `icmsCharged` 100+90 = **190,00**, `icmsCalculated` 100+100 = **200,00**, `chargedAbove` **0,00**, `chargedBelow` **10,00**, `matchingUncharged` [U1], `unchargedCount` 1, `unchargedPreview` **45,00**. `summary` do extrato inteiro sem mudança: `icmsDevido` 100+80+40+90 = **310,00**, `cobradoAMais` 30,00, `valorGuia` 310−30 = **280,00** | CF-RN-21, 22, 24, 25 |
| CT-31 | `cfops=6403` | `matchingLines` [2, 4], `notes` 2, `invoicesAmount` **1.700,00**, `icmsCharged` **170,00**, `icmsCalculated` **150,00**, `chargedAbove` **30,00**, `chargedBelow` **10,00**, `matchingUncharged` [] | CF-RN-25 |
| CT-32 | `cfops=6102,6403` | `matchingLines` [1, 2, 4] (L4 conta **uma vez**), `notes` 3, `icmsCharged` **270,00** | CF-RN-06, 21 |
| CT-33 | `cfops=6102` + filtro rápido "Cobrou a menos" (front) | Tabela: L4. Contador "Cobrou a menos" = 1; "Cobrou a mais" = 0 | CF-RN-26 |
| CT-34 | `cfops=6102`, mês sem extrato | `matchingLines` [], `notes` 0, `matchingUncharged` [U1], `unchargedPreview` **45,00** | CF-RN-27 |
| CT-35 | `cfops=6102`, baixar `relatorio.csv` | O mesmo arquivo de sem filtro (4 linhas) | CF-RN-24 |
| CT-36 | `cfops=61` | 400 `error.cfopFilter.invalid` | CF-RN-20 |
| CT-37 | Empresa de E2 | 404 | CF-RN-18 |

## 13. Tarefas por agente

O backend vem **depois do merge da remodelagem** (B-FD-05 e B-FD-07 no master). O F-CF-01 pode começar já. O F-CF-02 e o F-CF-03 podem ser desenvolvidos contra o contrato da seção 6, mas o merge fica para depois do backend.

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| B-CF-01 | backend | `CfopFilter` (normalizar, validar, canonicalizar e `legacy`), `cfops` no `XmlSearchFilterDTO` com `cfop` deprecated (CF-RN-09), codes `error.cfopFilter.invalid` e `error.cfopFilter.tooMany` em `errors_pt/en.properties`, com os campos extras | merge da remodelagem |
| B-CF-02 | backend | Lista com `cfops` (`FiscalDocumentRepository.search` com `in`, sentinela para a lista vazia). Testes CT-10, 11, 16, 18 a 22 | B-CF-01 |
| B-CF-03 | backend | Totais com todos os filtros menos a direção (CF-RN-10 a 12, 14 a 17): WHERE compartilhado com a lista, caminho em memória para `divergencia` e campo `filtered`. Testes CT-01 e CT-10 a 17, 23 a 26 e 28 | B-CF-02 |
| B-CF-04 | backend | `cfopItems` (CF-RN-13). P-CF-01 = c (decidida) | B-CF-03 |
| B-CF-05 | backend | Fronteira: parâmetro `cfops`, bloco `cfopFilter` com o `Tally` aplicado ao recorte (CF-RN-20 a 27). Testes CT-30 a 32 e 34 a 37 | B-CF-01 |
| B-CF-06 | backend | **Release seguinte:** remover `cfop` do DTO, o log de legado e o CT-18 a CT-20. Só depois de o log INFO ficar zerado por 7 dias | B-CF-02 em produção |
| F-CF-01 | frontend | `shared/components/chips-input` (CI-RN-01 a 27), com testes unitários dos CA-01 a 17 e uma página de demonstração local para a captura headless a 1366 e 390 px | nenhuma |
| F-CF-02 | frontend | Notas fiscais: trocar o `app-input` do CFOP pelo `chips-input` (normalize e validate de CFOP, `maxItems=20`, rótulo "CFOP", placeholder "Ex.: 1102", hint "Digite e tecle Enter"). Estado na URL (CF-RN-30 a 34) e no `InvoiceStateService` (`cfops: string[]`). Mandar **todos** os filtros para `/totais` e pedir os totais de novo em toda mudança de filtro, menos aba e página (corrigir `loadXmls(false)` da origem e do "só divergentes"). Selo "Totais do filtro" e, se P-CF-01 = c, a linha de subtotal dos itens | F-CF-01, contrato B-CF-03 |
| F-CF-03 | frontend | Fronteira: o mesmo componente na barra de filtros da conferência, `cfops` na URL e no GET, tabela filtrada por `matchingLines`/`matchingUncharged`, faixa "Notas com CFOP …" com `cfopFilter.totals`, aviso de `linesWithoutXml` e contadores dos filtros rápidos sobre o recorte | F-CF-01, contrato B-CF-05 |
| Q-CF-01 | QA | Componente: CA-01 a 17, teclado só, leitor de tela (NVDA ou VoiceOver) e prints headless a 1366 e 390 px | F-CF-01 |
| Q-CF-02 | QA | Notas fiscais: CA-20 a 32 e CT-01 a 29 com a massa fictícia. `EXPLAIN` da lista e dos totais com CFOP numa base com pelo menos 100 mil itens: precisa usar `idx_fdi_document_effective_cfop`, sem *full scan* de `fiscal_document_items` | B-CF-03, F-CF-02 |
| Q-CF-03 | QA | Fronteira: CA-40 a 45 e CT-30 a 37 | B-CF-05, F-CF-03 |
| Q-CF-04 | QA | Paralelo de uma semana com um Fiscal: 3 empresas × 5 combinações de filtro, total do card contra a soma manual da planilha. A meta é diferença 0,00 | Q-CF-02 |

## 14. Riscos, pré-requisitos e fora do escopo

**Riscos**
- **R-01 Lista e totais divergirem:** um filtro aplicado num lugar e esquecido no outro. Mitigação: um WHERE só, compartilhado (seção 7), e um teste que roda cada filtro nas duas rotas e compara `count` com `totalElements` menos as canceladas e os CT-e.
- **R-02 Custo do "só divergentes":** a checagem em memória roda duas vezes por busca (lista e totais). Numa empresa com 5.000 notas no mês, isso dobra o tempo de hoje. Mitigação: medir no Q-CF-02. Se passar de 3 s, abrir uma tarefa de cache por requisição ou de rota combinada (fora desta spec).
- **R-03 CT-e nos cards:** sem filtro de CFOP, a lista conta CT-e e os cards não (é o comportamento da remodelagem). Com o filtro ativo, os dois batem. Fica registrado; ver a P-CF-08.
- **R-04 Leitura errada do subtotal dos itens** (opção c): alguém usar o vProd dos itens como valor contábil por CFOP. Mitigação: o rótulo da CF-RN-13 e nenhum uso dele em apuração ou relatório.
- **R-05 Front antigo × backend novo:** coberto pela CF-RN-09. O inverso (front novo × backend antigo) **não** é suportado: o backend antigo ignora `cfops` e mostra tudo. Por isso a ordem do deploy é **backend primeiro**.

**Pré-requisitos de go-live**
- Remodelagem dos documentos fiscais em produção (V86-V89 e B-FD-05/07).
- Deploy do backend antes do front (R-05). Aprovação do Tony para o deploy na EC2, como sempre.
- Sem SES, sem jurídico e sem infra nova.

**Fora do escopo**
- Filtro pelo CFOP de entrada da Fronteira (P-CF-06). (O CFOP efetivo entrou: P-CF-02.)
- Validação do CFOP contra a tabela oficial (P-CF-05) e o autocomplete de CFOP com a descrição. O componente já suporta; falta a tabela versionada com fonte.
- Rateio de frete, seguro, desconto e outras despesas por CFOP.
- CFOP do CT-e (que fica no `ide` do CT-e, não em itens).
- Filtro de CFOP nas exportações (ZIP e JSON de `/exportar`) e nos relatórios da Fronteira.
- Outros filtros na URL (número, participante, conferência). Só `cfops` vai para a URL agora.
- Aplicar o `chips-input` a NCM, CST e outros filtros. Fica para as próximas specs.

## 15. Perguntas em aberto

### Bloqueia (só a B-CF-04 e a linha de subtotal do F-CF-02)

**P-CF-01 Semântica do total com filtro por CFOP.** **Decidida pelo Tony em 2026-11-28: opção (c).** O CFOP é do item, e o total do card é da nota.
- (a) Somar **só os itens** com aqueles CFOPs. Responde "quanto comprei em 5102", mas o card deixa de bater com a lista (uma nota com 5102 e 5405 aparece inteira na lista e pela metade no card). E o valor é o vProd, sem frete, IPI e ST: não é o total da nota nem o valor contábil do livro, que exigiria rateio, e rateio é regra fiscal que só entra com fonte.
- (b) Somar **as notas inteiras** que têm pelo menos um item daqueles CFOPs. O card bate com a lista e com a coluna "Valor total", e é a mesma regra da lista. Mas uma nota "segmentada" entra inteira.
- (c) **(b) no card e (a) como linha secundária**, rotulada "Itens nos CFOPs filtrados: valor dos produtos, sem frete, seguro e desconto".
- **Recomendação: (c).** O card continua coerente com a lista (o Fiscal confere contando as linhas), e a linha de itens responde à pergunta de negócio sem inventar rateio. Se o Tony escolher **(b)**, cai a B-CF-04 e a linha do front; o resto da spec fica como está. Se escolher **(a)** só, o `count` do card passa a ser de itens, o `total` deixa de bater com a lista e a CF-RN-12 é reescrita (o CA-21 cai). Nesse caso eu recomendo trocar o título do card para "Itens nos CFOPs".

### Com recomendação aceitável (a spec já segue a recomendação)

**P-CF-02 Filtrar pelo CFOP emitido ou pelo efetivo (retificado)?** Hoje o filtro compara o emitido, e a coluna mostra o efetivo. O efetivo depende da regra do produto em memória e não cabe num `exists` com índice. **Recomendação (rev. 1):** o emitido. **Decisão do Tony (2026-11-28): o efetivo.** A remodelagem já grava o CFOP efetivo no item (`effective_cfop`, FD-RN-57) com índice, então cabe no `exists` sem migration nova (CF-RN-07).

**P-CF-03 A aba Entradas/Saídas filtra os cards?** **Recomendação:** não (CF-RN-11). Os cards são o próprio seletor da aba. Se filtrasse, clicar em "Entradas" zeraria o card de Saídas. Se o Tony quiser que filtre, o card da outra direção mostra "—" e vira só um botão.

**P-CF-04 Limite de chips.** **Recomendação:** 20. Um grupo fiscal grande (por exemplo, todas as devoluções de entrada) cabe, e o `in` continua barato. Se for outro número, só muda a constante e o CT-22.

**P-CF-05 Validar o primeiro dígito (1, 2 ou 3 entrada; 5, 6 ou 7 saída) ou a tabela de CFOP?** O material de mercado cita essa estrutura como a da tabela de CFOP do Anexo do Convênio s/nº de 15/12/1970 (SINIEF), **sem fonte conferida nesta spec: a validar pelo Fiscal**. **Recomendação:** só 4 dígitos agora. Um CFOP inexistente apenas não acha nada, o que é inofensivo num filtro. A tabela oficial versionada pode vir com o autocomplete, numa spec própria com a fonte.

**P-CF-06 Na Fronteira, filtrar pelo CFOP do fornecedor (6102) ou pelo de entrada (2102)?** A tela mostra hoje o do fornecedor. **Recomendação:** o do fornecedor (CF-RN-21), que é o que o Fiscal vê na linha. O de entrada só existe no cálculo e varia com a regra do produto.

**P-CF-07 O PDF, o CSV e o pedido de revisão da Fronteira respeitam o filtro?** **Recomendação:** não. São documentos do mês, a guia é do extrato inteiro e o pedido de revisão filtrado esconderia cobranças a mais. Se o Tony quiser, dá para pôr um CSV "do recorte" depois.

**P-CF-08 Os cards passam a incluir o CT-e (valor da prestação) sem filtro de CFOP?** **Recomendação:** não nesta spec, porque o CT-e não tem ICMS, IPI, PIS e COFINS no mesmo layout dos cards. Fica como está na remodelagem, com uma linha "Inclui só NF-e e NFC-e" no tooltip do card. Se ele quiser incluir, abrimos uma spec curta.
