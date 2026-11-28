# Preço unitário nas sugestões de regras

- **Data:** 2026-11-28
- **Autor:** po-taxify, a pedido do Tony
- **Specs relacionadas:** [Regras fiscais por produto](2026-11-26-regras-fiscais-por-produto.md) (RN-09, RN-14) e [Remodelagem dos documentos fiscais](2026-11-27-remodelagem-documentos-fiscais.md) (modelo `fiscal_document_items`, D-07)
- **Prefixo:** `PU-`

## 1. Status e decisões

| Item | Situação |
|---|---|
| Mostrar só o **preço unitário** (médio em destaque, menor e maior embaixo), sem valor total | **Decidido pelo Tony** (2026-11-28) |
| Base de código | **Modelo novo** da remodelagem (branch `feature/fiscal-documents`). A implementação começa **logo após o deploy da remodelagem** |
| Campo do item, média ponderada, tratamento de unidade, arredondamento | Recomendação do PO nesta spec (PU-RN-02 a 06). Nenhuma é regra fiscal: é estatística sobre o que já foi capturado. As perguntas P-PU-01 a 03 têm recomendação aceitável e **não bloqueiam** |
| Migration, variável de ambiente, serviço do plano | Nenhum. A mudança é aditiva, somente leitura e fica atrás da mesma permissão de hoje |

Pronta para implementação depois do deploy da remodelagem.

## 2. Problema e objetivo

Na modal "Sugestões de regras" o Fiscal vê código, descrição, fornecedor, CFOP, NCM e CST, mas não sabe **quanto o produto custa**. Para decidir se um item é revenda, uso e consumo (1556) ou imobilizado (1551), e para perceber que dois produtos de código parecido são coisas diferentes, ele abre as notas uma a uma ou consulta a planilha. O preço unitário médio de compra, com a faixa de menor a maior, resolve isso na própria linha.

**Como medir:** no paralelo, o Fiscal deixa de abrir a nota para conferir o preço ao criar uma regra a partir da sugestão. A meta é zero aberturas de nota por esse motivo. Para conferir, compare o preço médio de 10 sugestões com a planilha manual: a diferença tem de ser zero (mesmo recorte de notas).

## 3. Usuários e cenário

| Perfil | O que faz |
|---|---|
| FISCAL com `FISCAL_RULES_MANAGE` | Abre "Sugerir regras" (o botão já exige essa permissão no front) e vê o preço em cada linha |
| ADMIN | Igual, para qualquer empresa do escritório |
| FISCAL só com `FISCAL_RULES_VIEW` | Recebe o campo novo se chamar a API, como já recebe as sugestões |
| Plataforma (CS Hub) | Não acessa. Nada muda no console |

- **Frequência:** na entrada de uma empresa na carteira (sugestões em lote) e quando aparece produto ou fornecedor novo.
- **Escritório bloqueado:** a rota é `GET`, então continua funcionando no modo somente leitura (o botão "Criar regra" já é `[write]`). Teste grátis: igual ao plano pago.
- **Isolamento:** o mesmo de hoje (RN-14 da spec retroativa). Uma empresa de outro escritório ou fora da carteira do FISCAL responde 404 por `CompanyValidation.checkExist`.

## 4. Histórias

- Como FISCAL, quero ver o preço unitário médio de compra de cada produto sugerido, para decidir o CFOP (revenda, uso e consumo ou imobilizado) sem abrir as notas.
- Como FISCAL, quero ver o menor e o maior preço, para perceber quando o mesmo código cobre produtos diferentes ou quando houve um preço fora da curva.
- Como FISCAL, quero ser avisado quando o produto veio em unidades diferentes (UN e CX), para não comparar preços que não se comparam.

## 5. O que já existe (reaproveitado)

| O quê | Onde (modelo novo) |
|---|---|
| Cálculo das sugestões: uma consulta agregada por empresa e a moda de cada campo em Java | `service/ProductRuleSuggestionService.java` |
| Recorte: empresa destinatária, nota não excluída, não cancelada, não emitida pela empresa, código preenchido | `FiscalDocumentItemRepository.findReceivedClassificationsByProduct` (`d.deleted = false`, `d.status <> CANCELED`, `d.companyRole <> ISSUER`) |
| Agrupamento: **código + documento do fornecedor** (só dígitos) | `ProductRuleSuggestionService.suggest` (chave `codigo|supplier`) |
| Unidade, quantidade e valor unitário **comerciais** do item: `uCom`, `qCom`, `vUnCom` | `FiscalDocumentItem.commercial` (`TradeUnit`): colunas `commercial_unit`, `commercial_quantity` e `commercial_unit_price`, `decimal(19,4)`, arredondadas para 4 casas HALF_UP no domínio (D-07) |
| `vProd` do item | `FiscalDocumentItem.totalAmount` (`total_amount`, `FiscalDocumentFactory` linha 178) |
| DTO da sugestão | `dto/fiscalrule/ProductRuleSuggestionDTO.java` |
| Rota | `GET /api/v1/product-fiscal-rules/sugestoes` (`ProductFiscalRuleResource`) |
| Formatador de moeda do front | `shared/utils/format.ts` → `formatCurrency` (R$, pt-BR, 2 casas) |
| Modal e modelo do front | `features/companies/company-product-fiscal-rules/company-product-fiscal-rules.component.html` (linhas 54-104) e `core/models/product-fiscal-rule.model.ts` (`ProductRuleSuggestionDTO`) |

## 6. Regras de negócio

| RN | Regra |
|---|---|
| **PU-RN-01 Recorte** | Valem exatamente os itens que entram na sugestão (RN-09 da retroativa): nota da empresa, não excluída, **não cancelada**, em que a empresa não é emitente, com código de produto preenchido. O agrupamento é o mesmo: **código + documento do fornecedor** (só dígitos). Não há filtro de período: o preço considera todas as notas guardadas, como os demais campos da sugestão |
| **PU-RN-02 Campo do preço** | O preço unitário de um item é o **`vUnCom`** gravado (`commercial_unit_price`, 4 casas). **Não** se usa `vProd ÷ qCom` (traz o arredondamento do `vProd` a 2 casas) nem `vUnTrib`/`uTrib` (unidade tributável, outra finalidade). Desconto, frete, seguro, IPI e ST **não** entram: é o preço de tabela do fornecedor na nota |
| **PU-RN-03 Item que conta** | Entra no preço o item com `commercial_unit_price` não nulo e `>= 0` e `commercial_quantity` não nula e `> 0`. Os outros continuam contando nas `ocorrencias` da sugestão, mas não no preço. Se nenhum item conta, `unitPrice` é `null` |
| **PU-RN-04 Unidade** | A unidade é `commercial_unit` com `trim` e em maiúsculas (`" un"` e `"UN"` são a mesma). Unidade nula ou vazia vira `"—"` para efeito de agrupamento. **Não há conversão** entre unidades (CX × UN): o preço é calculado **só sobre a unidade predominante**, que é a de mais itens; no empate, a de maior quantidade somada; depois, a ordem alfabética. As demais unidades vão para `otherUnits`, com a contagem de itens, e viram aviso na tela. A sugestão **não** se divide por unidade (a regra fiscal continua sendo por código + fornecedor) |
| **PU-RN-05 Fórmulas** | Sobre os itens da unidade predominante: `average = Σ(qCom × vUnCom) ÷ Σ qCom` (média **ponderada pela quantidade**, P-PU-01); `min = menor vUnCom`; `max = maior vUnCom`; `itemsConsidered = número de itens`; `totalQuantity = Σ qCom`. A média ponderada fica sempre entre `min` e `max` |
| **PU-RN-06 Arredondamento** | Tudo em `BigDecimal`. `Σ(qCom × vUnCom)` sem arredondar; a divisão do `average` sai com **4 casas, `RoundingMode.HALF_UP`**, a mesma escala da coluna (D-07). `min`, `max` e `totalQuantity` saem como estão gravados (4 casas). O backend entrega o número pronto; o front só formata. **Exibição: 2 casas** (`formatCurrency`); o valor com 4 casas aparece no tooltip (P-PU-02) |
| **PU-RN-07 Custo e desempenho** | **Uma** consulta agregada a mais por chamada, para a empresa inteira, agrupada por código, documento do fornecedor e unidade gravada, com `count`, `sum(quantity)`, `sum(quantity × unitPrice)`, `min(unitPrice)` e `max(unitPrice)`. A junção por fornecedor (só dígitos) e unidade normalizada é feita em Java, somando os somatórios e tomando o menor dos mínimos e o maior dos máximos. Nada por produto (sem N+1). Não há chamada ao SIEG nem a outro serviço externo. O filtro é o mesmo `d.company.id` da consulta atual, então não precisa de índice novo |
| **PU-RN-08 Sem efeito na regra** | O preço é só informativo: não entra em `campos`, não preenche o formulário de regra (`rule-form`) e não é gravado em lugar nenhum |

## 7. Contrato da API

Rota sem mudança: `GET /api/v1/product-fiscal-rules/sugestoes?companyId=&somenteSemRegra=&codigoProduto=&fornecedor=`, com `FISCAL_RULES_VIEW`. A ordenação (`ocorrencias` desc, código, fornecedor) e o limite de 500 continuam os de hoje. Não há paginação nova.

**Campo novo, aditivo, em cada item da lista:** `unitPrice` (objeto ou `null`). Os campos atuais (em pt) não mudam.

| Campo | Tipo | Regra |
|---|---|---|
| `unitPrice` | objeto \| `null` | `null` quando nenhum item conta (PU-RN-03) |
| `unitPrice.average` | número, 4 casas | PU-RN-05/06 |
| `unitPrice.min` | número, 4 casas | menor `vUnCom` |
| `unitPrice.max` | número, 4 casas | maior `vUnCom` |
| `unitPrice.unit` | string | unidade predominante normalizada (`"UN"`, ou `"—"` sem unidade) |
| `unitPrice.itemsConsidered` | inteiro | itens da unidade predominante que entraram no cálculo |
| `unitPrice.totalQuantity` | número, 4 casas | Σ `qCom` desses itens (para o tooltip) |
| `unitPrice.otherUnits` | lista | `[{ "unit": "CX", "itemsConsidered": 1 }]`, por `itemsConsidered` desc e depois `unit`; `[]` quando só há uma unidade |

Exemplo (dados fictícios):

```json
{
  "codigoProduto": "PRD-001",
  "descricaoProduto": "Parafuso sextavado 8mm",
  "ocorrencias": 6,
  "possuiRegra": false,
  "campos": { "cfopCorreto": { "valor": "1102", "ocorrencias": 6, "percentual": 100.0 } },
  "cfopFornecedor": { "valor": "5102", "ocorrencias": 6, "percentual": 100.0 },
  "documentoFornecedor": "11222333000181",
  "nomeFornecedor": "Fornecedor Exemplo Ltda",
  "unitPrice": {
    "average": 14.7000,
    "min": 12.0000,
    "max": 15.5000,
    "unit": "UN",
    "itemsConsidered": 3,
    "totalQuantity": 100.0000,
    "otherUnits": [ { "unit": "CX", "itemsConsidered": 1 } ]
  }
}
```

Records Java: `ProductRuleSuggestionDTO.UnitPrice(BigDecimal average, BigDecimal min, BigDecimal max, String unit, long itemsConsidered, BigDecimal totalQuantity, List<OtherUnit> otherUnits)` e `OtherUnit(String unit, long itemsConsidered)`. **Sem codes de erro novos.**

## 8. Front (modal "Sugestões de regras")

| Regra | Comportamento |
|---|---|
| **PU-RN-09 Coluna** | Coluna nova **"Preço unitário"** logo depois de "Produto". Linha 1, em destaque (negrito): `formatCurrency(average)` + ` / ` + `unit` (ex.: "R$ 14,70 / UN"). Linha 2 (`cell-sub`): "mín R$ 12,00 · máx R$ 15,50". Se `min == max`, a linha 2 vira "preço único". Com `unitPrice` nulo: "—" |
| **PU-RN-10 Aviso de unidade** | Com `otherUnits` não vazio, uma linha 3 em cor de alerta (a mesma de `.low-agreement`): "também em CX (1 item)"; com mais de uma, "também em CX, KG (3 itens)", somando os itens |
| **PU-RN-11 Memória no tooltip** | Tooltip na linha 1: "Média ponderada pela quantidade: 100 UN em 3 itens. Valor exato R$ 14,7000. Considera só UN, notas não canceladas recebidas pela empresa, sem desconto, frete ou impostos." A quantidade usa até 4 casas sem zeros à direita (pt-BR) |
| **PU-RN-12 Texto de ajuda** | Acrescentar ao parágrafo `suggestions-help`: "O preço unitário é o valor unitário comercial da nota (média ponderada pela quantidade, com o menor e o maior embaixo), na unidade mais usada." |
| **PU-RN-13 Responsivo** | **1366 px:** a tabela cabe sem rolagem horizontal na modal `xl`; a coluna de preço tem `white-space: nowrap` na linha 1. **390 px:** a `suggestions-list` já rola; a coluna de preço não quebra o valor no meio ("R$ 1.234,56" fica numa linha só) e as linhas 2 e 3 podem quebrar |

## 9. Modelo de dados, variáveis e logs

- **Banco:** nenhuma tabela, coluna, índice ou migration. Lê `fiscal_document_items` (tabela grande) pelo mesmo filtro de empresa da consulta atual.
- **Variáveis de ambiente:** nenhuma. A feature é somente leitura e informativa, então o risco é baixo e não precisa nascer desligada.
- **Logs:** nenhum log novo. Se for preciso medir, só `companyId`, o número de sugestões e o tempo em ms. Nunca preço, código, descrição ou CNPJ.

## 10. Critérios de aceite

| CA | RN | Dado / Quando / Então |
|---|---|---|
| CA-01 | PU-RN-02, 05 | Dado 3 itens UN do mesmo código e fornecedor (CT-01), quando peço as sugestões, então `unitPrice = {average: 14.7000, min: 12.0000, max: 15.5000, unit: "UN", itemsConsidered: 3, totalQuantity: 100.0000, otherUnits: []}` |
| CA-02 | PU-RN-06 | Dado o CT-02, então `average = 10.0001` (HALF_UP em 4 casas) e a tela mostra "R$ 10,00 / UN" |
| CA-03 | PU-RN-04, 10 | Dado itens em "un", "UN " e "CX" (CT-03), então `unit = "UN"`, o preço considera só os UN, `otherUnits = [{unit:"CX", itemsConsidered:1}]` e a tela mostra "também em CX (1 item)" |
| CA-04 | PU-RN-04 | Dado empate de itens entre UN e KG (CT-04), então vence a de maior quantidade somada |
| CA-05 | PU-RN-01 | Dado um item de nota cancelada, excluída ou emitida pela empresa com preço fora da faixa, então ele não altera `average`, `min`, `max` nem `itemsConsidered` |
| CA-06 | PU-RN-03 | Dado um produto cujos itens têm todos quantidade 0 ou preço nulo, então `unitPrice = null`, a tela mostra "—" e `ocorrencias` continua contando esses itens |
| CA-07 | PU-RN-01 | Dado o mesmo código com dois fornecedores, então cada sugestão traz o seu próprio preço |
| CA-08 | PU-RN-07 | Dado uma empresa com 500 sugestões, então a chamada faz exatamente 2 consultas em `fiscal_document_items` (classificações e preços), seja qual for o número de produtos |
| CA-09 | RN-14 retroativa | Dado `companyId` de outro escritório (ou fora da carteira do FISCAL), então 404, como hoje |
| CA-10 | Seção 3 | Dado o escritório bloqueado, então a modal abre e mostra os preços |
| CA-11 | PU-RN-09, 13 | Dado 1366 px e 390 px, então a coluna aparece conforme a PU-RN-09, sem quebrar o valor em R$ e, em 1366, sem rolagem horizontal |
| CA-12 | PU-RN-08 | Dado "Criar regra" a partir de uma sugestão com preço, então o formulário não recebe nenhum campo de preço e o payload salvo é igual ao de hoje |

## 11. Casos de teste (dados fictícios)

Fornecedor A: `11.222.333/0001-81`. Fornecedor B: `11.444.777/0001-61`. Código `PRD-001`, salvo indicação.

| CT | Cenário | Conta | Esperado |
|---|---|---|---|
| CT-01 | A, UN: (q 10; vUn 12,0000), (q 30; 14,0000), (q 60; 15,5000) | Σq = 100; Σ(q×p) = 120 + 420 + 930 = 1.470; 1.470 ÷ 100 = 14,7 (a média simples seria 13,8333) | avg 14.7000, min 12.0000, max 15.5000, itens 3, qtd 100.0000. Tela: "R$ 14,70 / UN" e "mín R$ 12,00 · máx R$ 15,50" |
| CT-02 | A, UN: (q 1; 10,0000), (q 1; 10,0001) | 20,0001 ÷ 2 = 10,00005 → HALF_UP 4 casas = 10,0001 | avg 10.0001; tela "R$ 10,00"; tooltip "R$ 10,0001" |
| CT-03 | A: (q 2; "un"; 5,0000), (q 2; "UN "; 5,0000), (q 1; "UN"; 6,5000), (q 1; "CX"; 60,0000) | UN: 3 itens, Σq = 5, Σ(q×p) = 10 + 10 + 6,5 = 26,5; 26,5 ÷ 5 = 5,3 | avg 5.3000, min 5.0000, max 6.5000, unit "UN", itens 3, otherUnits [CX, 1]. Tela: "também em CX (1 item)" |
| CT-04 | A: UN (q 2; 3,0000) e (q 3; 3,0000); KG (q 1,5; 4,0000) e (q 1,5; 4,0000) | 2 × 2 itens; Σq UN = 5 > Σq KG = 3 | unit "UN", avg 3.0000, "preço único", otherUnits [KG, 2] |
| CT-05 | CT-01 + item de nota **cancelada** (q 50; 99,0000) + item de nota **excluída** (q 5; 1,0000) + item de nota **emitida pela empresa** (q 5; 200,0000) | ignorados | igual ao CT-01 |
| CT-06 | A: (q 0; 10,0000) e (q 2; preço nulo) | nenhum conta | `unitPrice: null`, `ocorrencias: 2`, tela "—" |
| CT-07 | CT-01 em A + B com (q 4; 20,0000) | separados | sugestão A como CT-01; sugestão B: avg 20.0000, itens 1, "preço único" |
| CT-08 | Fornecedor gravado com e sem máscara (`11.222.333/0001-81` e `11222333000181`), 1 item UN cada: (q 1; 8,0000) e (q 3; 12,0000) | junção em Java: Σq = 4; Σ(q×p) = 8 + 36 = 44; 44 ÷ 4 = 11 | uma sugestão; avg 11.0000, min 8.0000, max 12.0000 |
| CT-09 | Unidade vazia: (q 1; ""; 2,0000) e (q 1; null; 4,0000) | mesma unidade "—"; 6 ÷ 2 = 3 | unit "—", avg 3.0000; tela "R$ 3,00 / —" |
| CT-10 | Preço com 4 casas abaixo de R$ 1: (q 1.000; 0,0350) | 35 ÷ 1.000 = 0,035 | avg 0.0350; tela "R$ 0,04" (P-PU-02); tooltip "R$ 0,0350" |
| CT-11 | Isolamento: FISCAL do escritório X pede `companyId` do escritório Y; FISCAL sem a empresa na carteira | — | 404 nos dois |
| CT-12 | Escritório bloqueado, ADMIN abre a modal | — | 200 com `unitPrice`; "Criar regra" desabilitado como hoje |
| CT-13 | Contagem de consultas (Hibernate statistics ou `@DataJpaTest` com contador) com 3 produtos e com 300 produtos | — | 2 consultas em itens nos dois casos |
| CT-14 | `somenteSemRegra=false&codigoProduto=PRD-001&fornecedor=11222333000181` (formulário de regra) | — | traz `unitPrice` do CT-01; o formulário ignora o campo |
| CT-15 | Visual: 1366 × 768 e 390 × 844 (Chrome headless), com uma linha "R$ 1.234,56 / UN" e aviso de unidade | — | conforme a PU-RN-13 |

Fuso e virada de mês não se aplicam: não há filtro de data nem rotina agendada. Concorrência também não: a feature é só leitura.

## 12. Tarefas por agente

O contrato está fechado nesta spec, então backend e frontend podem rodar em paralelo, depois do deploy da remodelagem.

| Tarefa | Descrição |
|---|---|
| **B-PU-01** | Em `FiscalDocumentItemRepository`, criar `findReceivedUnitPricesByProduct(companyId)` com o mesmo `where` de `findReceivedClassificationsByProduct`, mais `i.commercial.quantity > 0` e `i.commercial.unitPrice >= 0` (não nulos), agrupado por `i.product.code`, `d.issuer.document` e `i.commercial.unit`. Linha: código, documento, unidade, `count(i)`, `sum(quantity)`, `sum(quantity * unitPrice)`, `min(unitPrice)` e `max(unitPrice)` (PU-RN-07) |
| **B-PU-02** | Em `ProductRuleSuggestionService`, juntar as linhas por `codigo|onlyDigits(fornecedor)` e unidade normalizada (PU-RN-04), escolher a unidade predominante, calcular a PU-RN-05/06 e preencher `unitPrice` só nos produtos que vão para a resposta. Os records `UnitPrice` e `OtherUnit` entram em `ProductRuleSuggestionDTO`. Testes: CT-01 a CT-10, CT-13 e CT-14 em `ProductRuleSuggestionServiceTest` e no teste do repositório. Se a guarda do contrato acusar o campo novo, regerar a linha de base no mesmo PR |
| **F-PU-01** | Em `core/models/product-fiscal-rule.model.ts`, `unitPrice?: SuggestionUnitPrice | null` com `average, min, max, unit, itemsConsidered, totalQuantity, otherUnits: {unit, itemsConsidered}[]` |
| **F-PU-02** | Na modal, a coluna, o aviso, o tooltip e o texto de ajuda (PU-RN-09 a 12), com `formatCurrency` para 2 casas e um formatador para o tooltip (4 casas, pt-BR) em `shared/utils/format.ts`. Testes de unidade do componente (nulo, preço único, uma e várias unidades extras) |
| **F-PU-03** | Ajuste visual em 1366 e 390 px (PU-RN-13), com print headless antes e depois |
| **Q-PU-01** | Validar os CA-01 a CA-12 com a massa fictícia dos CT. No paralelo, comparar 10 sugestões com a planilha manual do Fiscal (diferença zero) |

## 13. Riscos, go-live e fora do escopo

**Riscos**
- **Unidade comercial sem padrão entre fornecedores** ("UN", "UND", "PC", "PÇ"): a normalização só faz `trim` e maiúsculas. "UN" e "UND" aparecem como unidades diferentes, com aviso. É aceitável, porque o aviso deixa visível (P-PU-03).
- **Preço sem desconto:** o Fiscal pode esperar o custo líquido. O tooltip deixa explícito que é o `vUnCom` sem desconto, frete ou impostos.
- **Histórico inteiro sem período:** um preço antigo puxa a média. A faixa menor/maior mostra a dispersão. Recorte por período fica para depois, se o paralelo pedir.

**Go-live:** depende só do deploy da remodelagem. Não depende de jurídico, SES ou infra. Não há dado novo exposto: o preço já é visível na nota para quem acessa a empresa.

**Fora do escopo:** valor total (decisão do Tony); conversão entre unidades; custo líquido (desconto, frete, IPI, ST); preço por período ou tendência; preço no formulário de regra, na lista de produtos (`/produtos`) ou em relatório; renomear a rota ou os campos atuais para inglês (fica com a onda de padronização).

## 14. Perguntas em aberto

Nenhuma bloqueia. Todas têm recomendação aceitável, que é o que está escrito nas RN.

| P | Pergunta | Opções | Recomendação do PO | Impacto se a resposta for outra |
|---|---|---|---|---|
| P-PU-01 | Média simples ou ponderada pela quantidade? | (a) ponderada `Σ(q×p) ÷ Σq`; (b) simples, média dos `vUnCom` | **(a)**: é o custo médio de compra de fato. Uma compra de 1 unidade a preço alto não pesa igual a uma de 1.000. É o critério de custo médio que o Fiscal já usa em estoque. Não é regra fiscal | Na (b), troca a fórmula na B-PU-02 (`avg` em vez de `sum(q×p)/sum(q)`) e o texto do tooltip. No CT-01, a média vira 13,8333 |
| P-PU-02 | Preço abaixo de R$ 1 com 3 ou 4 casas (parafuso a R$ 0,0350): 2 casas sempre? | (a) 2 casas sempre, 4 no tooltip; (b) até 4 casas quando o valor é menor que R$ 1 | **(a)**: segue o pedido do Tony (2 casas) e o tooltip guarda o exato | Na (b), só o front muda (F-PU-02), com um formatador condicional |
| P-PU-03 | Unidades diferentes no mesmo produto e fornecedor: aviso ou separar? | (a) preço da unidade predominante + aviso das outras; (b) uma sugestão por unidade; (c) misturar tudo | **(a)**: a regra fiscal é por código + fornecedor, então separar a sugestão (b) quebraria a "Criar regra" e a concordância, e misturar (c) dá uma média sem sentido (UN com CX) | Na (b), muda o agrupamento da sugestão inteira, mexe na RN-09 da retroativa e precisa de spec própria. Na (c), cai a PU-RN-04 e o aviso |
