# Relatórios e simulação (relatório mensal com PDF, curva ABC de compras, simulação de regimes) — spec as-built

## Status

**Implementado** (roadmap M12). Spec retroativa: descreve o código atual e não propõe mudança.

- Commit de referência: `fab24b4` (relatórios e simulação M12: relatório mensal com PDF, curva ABC de compras e simulação de regimes).
- Migrations: nenhuma própria. Só lê tabelas existentes (notas, itens, pendências, fechamentos e regimes).
- Código: `rest/ReportResource`, `service/report/ReportService`, `service/report/ReportPdf` (OpenPDF, `com.lowagie`), `dto/report/ReportDTOs`. Reaproveita `TaxAssessmentService` (spec `2026-11-26-apuracao-do-mes.md`) e `RetentionCalculator` (spec `2026-11-26-retencoes.md`).

## Problema e objetivo

O escritório montava à mão, para o cliente, um resumo do mês (faturamento, impostos, entradas e saídas, retenções, pendências). Para responder "qual regime sai mais barato?" ou "de quem eu mais compro?", precisava de planilhas separadas.

O módulo gera três saídas a partir das notas importadas:
1. **Relatório mensal** por empresa, em tela e em PDF.
2. **Curva ABC de compras** por produto ou fornecedor.
3. **Simulação** de Simples × Presumido × Real nos últimos 12 meses, mostrando as premissas usadas.

Indicador possível (não medido hoje): relatórios mensais gerados por empresa e mês.

## Usuários e cenário

- **Analista fiscal** (`TAX_ASSESSMENT_VIEW`): depois de fechar o mês, gera o PDF e envia ao cliente. Usa a curva ABC e a simulação em reuniões de planejamento, normalmente no fim do ano ou quando o RBT12 se aproxima do sublimite.
- Uma empresa por vez. Não há relatório da carteira.

## Histórias de usuário

1. Como analista fiscal, quero um relatório mensal da empresa com faturamento, impostos estimados, entradas e saídas por CFOP, retenções, pendências e evolução de 13 meses, para prestar contas ao cliente.
2. Como analista fiscal, quero baixar esse relatório em PDF, para enviar ao cliente sem montar nada à mão.
3. Como analista fiscal, quero a curva ABC das compras por produto ou por fornecedor num período de 1 a 24 meses, para mostrar a concentração de compras.
4. Como analista fiscal, quero simular a carga de Simples, Presumido e Real com os números reais das notas e premissas ajustáveis (margem de lucro, alíquota de ISS), para orientar a escolha do regime.

## Regras de negócio

Dinheiro em `BigDecimal`. `percent(v, p)` = `v × p / 100`, com 2 casas `HALF_UP` (`ReportService.java:320-322`). Competência em `AAAA-MM`: vazia significa o mês anterior, e formato inválido gera 400 "Competência inválida: use AAAA-MM." (`:300-309`). `companyId` é obrigatório nos três endpoints. Se ele é inválido ou nulo, a resposta é 400 `error.generic.bad.request`. O acesso à empresa passa por `CompanyValidation.checkExist`: mesmo escritório e ADMIN ou vínculo com a empresa (`:292-298`).

### Relatório mensal

**RN-01. Bloco da apuração** (`ReportService.java:78-81, 119-123`). Regime, receita segregada, RBT12, notas emitidas, impostos, total, histórico de 13 meses e alertas vêm de `TaxAssessmentService.assess`, com as mesmas regras da apuração. **Num mês fechado, esses valores vêm do snapshot do fechamento.**

**RN-02. Entradas e saídas** (`:85-95`; `FiscalXmlDocumentRepository.totalsByDirection`, `:153-168`). Para cada direção, mostra:
- o número de notas não canceladas;
- a soma de `valorTotalNota`, ICMS, ICMS-ST, IPI, PIS e COFINS das não canceladas;
- o número de canceladas.

A soma usa **todas** as notas da direção no mês, de qualquer CFOP, com data de emissão entre o 1º dia 00:00 e o último instante do mês. Sem notas, todos os valores ficam em zero.

**RN-03. Totais por CFOP** (`:96-101`). A soma de `valor_total` dos itens por direção e CFOP do mês usa a mesma consulta da apuração (canceladas fora) e traz **todos** os CFOPs, não só os de receita. A ordem é ENTRADA antes de SAIDA e, dentro de cada direção, valor decrescente. Um CFOP nulo aparece como "-".

**RN-04. Retenções** (`:103-108`). Soma por natureza (A_RECOLHER, A_COMPENSAR, INFORMATIVO) das retenções calculadas na competência (spec de retenções). Naturezas sem valor não aparecem.

**RN-05. Pendências** (`:110-117`).
- `pendencies`: contagem das pendências **abertas da empresa em qualquer competência**, agrupadas por severidade.
- `auditFindings`: pendências do tipo `AUDIT_FINDING` com status `ABERTA` **da competência**.

**RN-06. PDF** (`ReportPdf.java:40-121`). Formato A4, com estas seções:
- título "Relatório mensal - <Mês de AAAA>", empresa, CNPJ, regime e a data de geração;
- Resumo do mês: faturamento líquido, notas de saída, compras (= total das entradas da RN-02, com o número de notas), RBT12 e impostos estimados;
- Impostos estimados: imposto, base, alíquota e valor. **A observação de cada imposto não vai para o PDF**;
- Entradas e saídas por CFOP;
- Retenções por natureza;
- Pendências e auditoria;
- Evolução de 13 meses;
- Alertas;
- rodapé com o aviso de estimativa.

Os valores saem em moeda pt-BR (`NumberFormat` de `pt-BR`) e "-" quando nulos. O PDF é gerado sob demanda e não fica gravado. Nome do arquivo: `relatorio-<competencia>.pdf`.

### Curva ABC de compras

**RN-07. Período** (`ReportService.java:133-144`). Vai de `meses` (padrão 12, limitado a 1..24) meses até a competência, inclusive.

**RN-08. Agrupamento.**
- `por=FORNECEDOR`: soma de `valorTotalNota` das notas de **entrada** não canceladas, agrupada por CNPJ (ou CPF) do emitente. Mostra também o nome e o número de notas (`FiscalXmlDocumentRepository.java:118-126`).
- `por=PRODUTO` (padrão; qualquer outro valor também cai aqui): soma de `valor_total` dos itens das notas de entrada não canceladas, agrupada pelo **código do item** (`cprod`). Mostra a descrição, o NCM e o número de notas (`FiscalXmlDocumentItemRepository.java:132-141`).
- Entram todos os CFOPs de entrada, sem filtro de compra.
- A ordem é por valor decrescente.

**RN-09. Classificação** (`ReportService.java:146-177`). `total` = soma de todos os valores. Para cada linha com valor > 0:
- `share = valor × 100 / total`, com 2 casas `HALF_UP` (0 se o total for 0);
- `accumulated = soma dos shares` (já arredondados), limitado a 100 na exibição;
- a curva vem do acumulado **antes** da linha: < 80 é A, < 95 é B e o resto é C. "O item que cruza um limite fica na classe em que começou."

Linhas com valor ≤ 0 são puladas na lista, mas entram no `total`. A lista mostra no máximo 300 itens, enquanto `countA/B/C` contam todos.

### Simulação de regimes

**RN-10. Janela e regimes** (`ReportService.java:187-200`; `TaxAssessmentService.java:103-129`).
- Cobre os 12 meses que terminam na competência e os regimes Simples Nacional, Lucro Presumido e Lucro Real. MEI fica de fora.
- Cada mês é calculado pelo `TaxAssessmentCalculator` sob o regime simulado, com a atividade e o anexo de serviço do regime vigente na competência.
- **Ignora fechamentos.** A receita lida cobre 24 meses, para dar o RBT12 de cada mês.
- `invoicesIssued` é fixado em 1, para não gerar o alerta de "sem notas".

**RN-11. Premissas.**
- `margem` (lucro do Real, em %): padrão 10, limitada a [0, 100].
- `iss` (alíquota fora do Simples, em %): padrão 5, limitada a [0, 5] (`:191-192`).
- `revenue` = soma de `net` dos 12 meses da rodada do Simples. `services` = soma dos serviços.
- A lista `premises` vai na resposta com os textos fixos de `:212-217`.

**RN-12. Linhas de imposto por regime** (`ReportService.java:222-263`).
- Soma os valores mensais por código. As linhas `DAS_*` viram uma linha "DAS (Simples Nacional)". Linhas com valor nulo (DAS_MEI, IRPJ_CSLL do Real) são ignoradas.
- **Fora do Simples:**
  - ISS = `percent(serviços, iss)`, se > 0;
  - ICMS = saldo de ICMS = `max(ICMS destacado nas saídas − ICMS destacado nas entradas, 0)` nos 12 meses, usando todas as notas não canceladas (`:266-278`), se > 0.
- **Simples:** a nota fixa diz que o DAS já inclui ICMS e ISS "até o sublimite de R$ 3,6 milhões". Acima do sublimite, o sistema não ajusta nada.
- **Lucro Real:**
  - lucro = `percent(receita, margem)`;
  - IRPJ = `percent(lucro, 15) + percent(max(lucro − 240.000, 0), 10)`, com adicional **anual**;
  - CSLL = `percent(lucro, 9)`.
- **Lucro Presumido:** usa os valores mensais da apuração (PIS/COFINS cumulativos, IRPJ/CSLL presumidos, com adicional **mensal** acima de 20.000), mais ISS e ICMS.

**RN-13. Total, alíquota efetiva e mais barato.**
- `total` = soma das linhas, com 2 casas `HALF_UP`.
- `effectiveRate = total × 100 / receita`, com 2 casas (0 se a receita for 0).
- `cheapest = true` no(s) regime(s) com o menor total. Em empate, todos são marcados. Com receita 0, nenhum é marcado (`:206-210`).

**RN-14. Regime atual.** `currentRegime` é o rótulo do regime vindo de `assess` na competência, e respeita o fechamento (`:200`).

## Critérios de aceite (comportamento atual)

- **CA-01 (RN-09).** Dado compras por produto de 700, 150, 100 e 50, quando a curva é gerada, então os shares são 70, 15, 10 e 5, as curvas são A, A, B e C, `countA = 2` e o acumulado da 2ª linha é 85 (`ReportServiceTest.curvaAbcClassifiesByAccumulatedShare`).
- **CA-02 (RN-09).** Dado fornecedores de 900 e 100, quando a curva é gerada, então as curvas são A e B, porque o 2º começa em 90%.
- **CA-03 (RN-10, RN-12, RN-13).** Dado 12 meses de receita de 10.000 (4.000 de serviços), com DAS mensal de 640, Presumido mensal de 787,40, PIS+COFINS do Real de 560/mês e saldo de ICMS de 6.000, quando a simulação roda com as premissas padrão, então:
  - Simples = 7.680,00 (6,40%), marcado como mais barato, com a linha única "DAS";
  - Presumido = 9.448,80 + ISS 2.400,00 + ICMS 6.000,00 = 17.848,80;
  - Real = 6.720,00 + 2.400,00 + 6.000,00 + IRPJ 1.800,00 + CSLL 1.080,00 = 18.000,00.
- **CA-04 (RN-01, RN-06).** Dado uma apuração com DAS_I de 400 em 2026-10, quando o relatório mensal e o PDF são gerados, então `totalTaxes = 400`, as entradas têm 0 notas e o arquivo começa com `%PDF-`.
- **CA-05 (RN-07).** Dado `meses=36`, quando a curva é pedida, então o período cobre 24 meses. Com `meses=0`, cobre 1 mês.
- **CA-06 (RN-11).** Dado `iss=8`, quando a simulação roda, então ela usa 5%. Dado `margem=-3`, usa 0%.
- **CA-07 (RN-01).** Dado um mês fechado, quando o relatório mensal é gerado, então receita, impostos e alertas vêm do snapshot, enquanto entradas, saídas, CFOPs, retenções e pendências são lidos ao vivo.

## Casos de teste com números (fictícios)

| # | Entrada | Cálculo | Esperado |
|---|---|---|---|
| T1 | ABC por produto: P1 700, P2 150, P3 100, P4 50 | shares 70/15/10/5; acumulado antes da linha 0/70/85/95 | A, A, B, C |
| T2 | ABC com uma linha de valor 0 e outra de 1.000 | o 0 é pulado | 1 item, curva A, share 100 |
| T3 | ABC sem nenhuma compra no período | total 0 | itens vazios; contagens 0 |
| T4 | ABC com 3 linhas: 333,33 / 333,33 / 333,34 | shares 33,33 / 33,33 / 33,33; acumulado antes da linha 0 / 33,33 / 66,66 | curvas A, A, A; acumulado final 99,99 (o arredondamento do share não fecha 100) |
| T5 | Simulação com receita 0 em 12 meses | totais 0 | `effectiveRate` 0; nenhum `cheapest` |
| T6 | Real com receita de 3.000.000, margem 10% | lucro 300.000; IRPJ 45.000 + 10% × 60.000 = 51.000; CSLL 27.000 | linhas IRPJ 51.000,00 e CSLL 27.000,00 |
| T7 | Saldo de ICMS: saídas 4.000, entradas 10.000 | max(−6.000, 0) | sem linha ICMS |
| T8 | Relatório de mês sem nenhuma nota | — | entradas e saídas zeradas; CFOPs vazios; PDF "Nenhuma nota no mês." |
| T9 | Relatório com 1 nota de entrada cancelada | — | `entries.notes` 0, `entries.canceled` 1 |
| T10 | `competencia=2026/10` | — | 400 "Competência inválida: use AAAA-MM." |

## Endpoints da API

Base: `/api/v1/relatorios`. A permissão da classe é `TAX_ASSESSMENT_VIEW`.

| Método | Caminho | Permissão | Descrição |
|---|---|---|---|
| GET | `/relatorios/mensal?companyId=&competencia=` | TAX_ASSESSMENT_VIEW | Relatório mensal (JSON) |
| GET | `/relatorios/mensal.pdf?companyId=&competencia=` | TAX_ASSESSMENT_VIEW | Relatório mensal em PDF (`application/pdf`, attachment) |
| GET | `/relatorios/curva-abc?companyId=&competencia=&por=PRODUTO\|FORNECEDOR&meses=1..24` | TAX_ASSESSMENT_VIEW | Curva ABC de compras |
| GET | `/relatorios/simulacao?companyId=&competencia=&margem=&iss=` | TAX_ASSESSMENT_VIEW | Simulação Simples × Presumido × Real |

Telas (taxify-front):
- `/relatorios` (`features/reports/reports.component`), protegida por `permissionGuard('TAX_ASSESSMENT_VIEW')`.
- A tela tem seleção de empresa (autocomplete, ligada à empresa corrente via `CurrentCompanyService`), navegação de mês e três abas: mensal, ABC e simulação.
- O botão "Relatório PDF" só aparece na aba mensal.
- A aba ABC alterna PRODUTO/FORNECEDOR e o número de meses.
- A aba de simulação tem um formulário de premissas (margem 10, ISS 5). **Campo vazio é enviado como 0, não como o padrão** (`Number(x) || 0`, `reports.component.ts:177-179`).
- Serviço: `core/services/report.service.ts`.

## Dados

- Leitura: `fiscal_xml_documents`, `fiscal_xml_document_items`, `service_invoices` (pelas retenções), `pendencies`, `fiscal_period_closings` e `company_tax_regimes` (pela apuração).
- Nada é gravado. O PDF é gerado em memória.
- O relatório e o PDF trazem razão social, CNPJ, valores e contagem de pendências do cliente. São dados sigilosos: o acesso segue a carteira (`checkExist`), e o PDF baixado sai do controle do sistema.

## Configuração

Nenhuma. Os padrões e limites (margem 10, ISS 5 com teto 5, ABC 80/95, máximo de 300 itens, adicional do IRPJ de 240.000/ano, CSLL 9%) são constantes em `ReportService.java:44-52, 191-192, 250-253`.

## Testes existentes

- `service/report/ReportServiceTest`: curva ABC por produto e por fornecedor; simulação somando ISS e ICMS fora do Simples e marcando o mais barato; geração do PDF mensal (smoke: `%PDF-`).

**Lacunas de teste:**
- `monthly` só tem o smoke do PDF. Faltam testes de entradas e saídas por direção, de ordenação de CFOPs, de totais de retenção, de pendências por severidade e de `auditFindings` filtrado pela competência.
- Faltam testes do limite de `meses` (0, 25), de `por` inválido (vira PRODUTO) e da lista truncada em 300 itens.
- Faltam testes das premissas fora do intervalo e do empate em `cheapest`.
- Falta teste de mês fechado (snapshot × dados ao vivo).
- Faltam testes de permissão e de acesso à empresa de outra carteira.
- `ReportPdf` não tem teste de conteúdo. Não há testes de front.

## Fora do escopo / limitações conhecidas

- Não há relatório da carteira (várias empresas), nem envio automático por e-mail ao cliente.
- A curva ABC e a simulação não têm PDF ou CSV.
- A simulação não considera folha (fator R), receitas sem nota, ST/monofásico, benefícios, DIFAL nem ICMS/ISS fora do DAS acima do sublimite. MEI não é simulado.
- O "saldo de ICMS" usa o ICMS destacado em todas as entradas, inclusive uso e consumo, ativo e devoluções, sem verificar se o crédito é permitido.
- A curva ABC por produto agrupa pelo código do item, que é o código do fornecedor: fornecedores diferentes com o mesmo código se misturam, e o mesmo produto com códigos diferentes se separa.

## Perguntas em aberto

1. **Mês fechado no relatório.** No mês fechado, receita e impostos vêm do snapshot e o resto é lido ao vivo. Se chegar nota depois do fechamento, o PDF fica incoerente. Entradas, saídas, CFOPs e retenções também devem ser congelados?
2. **Pendências no relatório.** `pendencies` conta as abertas de todas as competências e `auditFindings` só as da competência. O cliente deve ver só as do mês?
3. **"Compras (notas de entrada)"** no PDF é o total de todas as notas de entrada (remessas, devoluções, uso e consumo incluídos). Deveria usar só os CFOPs de compra, como a apuração?
4. **Curva ABC** inclui qualquer CFOP de entrada. Filtrar por compra? Agrupar produto por NCM ou por código + fornecedor?
5. **Teto de 5% do ISS** na simulação: o código limita a alíquota a [0, 5] sem citar a fonte. Tony, confirma a regra e diz se deve haver alíquota mínima?
6. **Adicional do IRPJ inconsistente:** o Presumido usa limite mensal (20.000/mês, pela apuração) e o Real usa anual (240.000). Padronizar?
7. **Saldo de ICMS** igual para Presumido e Real, sem considerar crédito vedado nem DIFAL. É aceitável como estimativa, ou a simulação deve usar a classificação de CFOP?
8. **Simples acima do sublimite** na simulação: o DAS segue com ICMS e ISS dentro. Devemos alertar ou calcular por fora?
9. **Front envia 0** quando o campo de premissa está vazio. O comportamento esperado é usar o padrão (10 / 5)?
10. **PDF sem memória de cálculo:** as observações dos impostos (faixa, base presumida, crédito) não vão para o PDF. Incluir?
