# Motor de conferência fiscal (spec retroativa)

## Status
**Implementado.** Spec as-built: descreve o código como está em 2026-11-26.

- Código: `service/fiscalcheck/*` (`FiscalCheckEngine`, `FiscalCheck`, `FiscalDocumentCheck`, `FiscalCheckContext`, `FiscalDivergence`, `Money`, `OperationCfops`, `ProductRuleCheck`, `OperationCheck`, `TaxCalculationCheck`, `SubstitutionCheck`, `TaxRegimeCodeCheck`, `IbsCbsItemCheck`, `IcmsTotalsCheck`, `IbsCbsDocumentCheck`, `DocumentDivergenceService`), `service/FiscalConferenceService`, `service/FiscalDirections`, `service/EntryCfops`.
- Migrations de dados que o motor lê: `V22__FiscalXmlItemTaxDetail` (detalhe tributário do item e grupo IBS/CBS), `V23__FiscalXmlIbsCbsTotals` (IBSCBSTot e `metadata_version`), `V24__IbsCbsRates` (alíquotas de referência por ano, com 2026 semeado), `V25__FiscalXmlItemIbsCbsAdjustment` (indicador de diferimento ou devolução).
- Commits: use `git log -- src/main/java/br/com/cshub/taxify/service/fiscalcheck`.

Specs relacionadas: [Regras fiscais por produto](2026-11-26-regras-fiscais-por-produto.md) e [Retificação e trilha de auditoria](2026-11-26-retificacao-e-trilha-de-auditoria.md).

## Problema e objetivo
A conferência nota a nota (classificação, cálculo, totais, alíquota interestadual, ST, IBS/CBS) hoje é manual. O motor roda um conjunto de checagens sobre os itens e as notas já importados e devolve achados com **valor encontrado, valor esperado e mensagem com a conta**. Assim o analista confere o porquê, e não só o resultado.

Nada é gravado: o motor roda sob demanda a cada consulta.

Como medir (sem métrica implementada): número de achados `ERROR` e `WARNING` por empresa e período, que é o que a tela "Conferência fiscal" exibe.

## Usuários e cenário
- **Analista fiscal**: abre o acordeão "Conferência fiscal" da empresa (por padrão no mês anterior), filtra por severidade e por grupo e abre a nota.
- **Lista de notas**: cada nota mostra um selo com o número de erros e alertas e até 5 mensagens. Há o filtro "só divergentes".
- **Detalhe da nota**: a aba "Divergências" mostra os achados daquela nota.
- **Consumidores internos**: `FronteiraService` (selo `DIVERGENCIA_FISCAL` na conferência da fronteira) e `AuditEngine` (auditoria da carteira) usam o mesmo motor.

## Histórias de usuário
- Como analista fiscal, quero ver todos os problemas fiscais das notas de uma empresa num período, um por linha, para não abrir nota por nota.
- Como analista fiscal, quero que cada achado diga o valor da nota, o esperado e a conta feita, para auditar o resultado durante o paralelo com a conferência manual.
- Como analista fiscal, quero separar erro de alerta, para atacar primeiro o que é certamente errado.
- Como analista fiscal, quero que notas canceladas não apareçam, para não perder tempo com o que não tem correção.

## Regras de negócio

### Motor e contexto
**RN-01. Arquitetura.** Cada checagem é um bean Spring: `FiscalCheck` trabalha por item e `FiscalDocumentCheck`, pela nota inteira. O `FiscalCheckEngine` roda todos os beans e concatena os achados. Para incluir uma checagem, basta criar um bean. Uma checagem não pode consultar o banco a cada item: o que ela precisa vem no `FiscalCheckContext`, carregado uma vez por empresa (regras de produto da empresa e alíquotas IBS/CBS por ano, em duas consultas).
Ref.: `FiscalCheck.java:7-17`, `FiscalCheckEngine.java:19-68`, `FiscalCheckContext.java:12-40`.

**RN-02. Notas conferíveis.** Notas excluídas (`deleted`) ou canceladas não são conferidas e não geram achado em nenhuma tela.
Ref.: `FiscalCheckEngine.isCheckable` (`FiscalCheckEngine.java:48-51`).

**RN-03. Formato do achado.** `FiscalDivergence(code, field, found, expected, severity, message)`. `code` é estável e é por ele que front e filtros se orientam. `message` é texto para pessoas e pode mudar. Severidades: `ERROR` ou `WARNING`.
Ref.: `FiscalDivergence.java:5-16`.

**RN-04. Tolerância e arredondamento** (`Money.java`):
- imposto calculado = `base × alíquota% × (1 − redução%)`, arredondado para **2 casas, HALF_UP**. A alíquota efetiva com redução é calculada com 10 casas (HALF_UP) antes da multiplicação;
- `differs(a, b)` é verdadeiro só quando `|a − b| > 0,01`. Uma diferença de exatamente 1 centavo **não** é divergência;
- valores nulos entram como zero nas somas (`orZero`);
- exibição: `stripTrailingZeros().toPlainString()`, ou seja, ponto decimal e sem zeros à direita ("120", "0.9", "1000").

**RN-05. Comparação de alíquotas.** As alíquotas cadastradas ou de referência são comparadas por `compareTo` exato, **sem tolerância** (`ProductRuleCheck.rate`, `OperationCheck`, `IbsCbsItemCheck.compareRate`).

**RN-06. Sentido da nota para a empresa** (`FiscalDirections.java`):
- nota própria (`ownIssue`): o documento da empresa (só dígitos) é igual ao CNPJ ou CPF do emitente;
- direção: nota de terceiro → `INBOUND`; nota própria com `tpNF = 0` → `INBOUND`; nota própria com `tpNF = 1` → `OUTBOUND`.

### Checagens por item

**RN-07. `ProductRuleCheck` (item × regra do produto).** Só roda em notas **não próprias** (`ownIssue` falso). Item sem regra (RN-06 da spec de regras) não gera achado. Severidade: sempre `ERROR`.
| Código | Campo | Compara | Quando |
|---|---|---|---|
| `RULE_CFOP` | CFOP | `item.cfop` × CFOP da regra ajustado à operação (`OperationCfops`) | sempre. O CFOP vazio na nota também diverge |
| `RULE_NCM` | NCM | `item.ncm` × `ncmCorreto` | se a regra tem NCM. O NCM vazio na nota também diverge |
| `RULE_ORIGEM` | orig | origem do detalhe × regra | item com detalhe, regra e nota têm o campo |
| `RULE_CEST` | CEST | CEST do detalhe × regra | idem |
| `RULE_CSOSN` | CSOSN | CSOSN × regra | item com detalhe que usa CSOSN |
| `RULE_CST_ICMS` | CST_ICMS | CST de ICMS × regra | item com detalhe que **não** usa CSOSN |
| `RULE_ALIQUOTA_IPI/PIS/COFINS` | pIPI/pPIS/pCOFINS | alíquota do detalhe × regra | item com detalhe, regra e nota têm o campo |
| `RULE_ALIQUOTA_ICMS` | pICMS | `item.aliquotaIcms` × alíquota interna da regra | só se `idDest = 1` |
| `RULE_CST_IPI/PIS/COFINS` | CST_* | colunas do item × regra | regra e nota têm o campo |
| `RULE_CST_IBSCBS`, `RULE_CLASS_TRIB` | CST_IBSCBS, cClassTrib | grupo IBS/CBS do item × regra | o item tem o grupo |

- Os textos são comparados com `trim`, distinguindo maiúsculas e minúsculas.
- **Nota já retificada com a regra atual:** CFOP e NCM **não** são comparados quando a nota tem `rectifiedFilePath` e `lastRectificationCheckedAt`, e a última alteração da regra (`lastModifiedDate`, ou `createdDate`) não é posterior a essa checagem. Os códigos tributários continuam sendo comparados.
- Mensagens: `"<Rótulo> <encontrado> diferente do cadastrado para o produto (<esperado>)"` e, para alíquota, `"<Rótulo> <x>% diferente da cadastrada para o produto (<y>%)"`.

Ref.: `ProductRuleCheck.java:18-147`.

**RN-08. `OperationCheck` (item × tipo de operação).** Só roda quando o `idDest` da nota é 1, 2 ou 3.
- `CFOP_DESTINATION` (`WARNING`): o primeiro dígito do CFOP do item precisa estar no grupo do `idDest` (1: 1 ou 5; 2: 2 ou 6; 3: 3 ou 7). Mensagem: `"CFOP 5102 não combina com operação interestadual (idDest 2)"`. Valor esperado: "2xxx/6xxx".
- `INTERSTATE_RATE` (`ERROR`): só em operação interestadual (`idDest = 2`), com item que tem detalhe, sem CSOSN, CST em {00, 10, 20, 70, 90}, `pICMS` maior que zero, origem conhecida e UFs de emitente e destinatário conhecidas e diferentes. Alíquota esperada:
  - origem em {1, 2, 3, 8} → **4%** (o comentário do código cita a Res. Senado 13/2012);
  - senão, emitente em {SP, RJ, MG, PR, SC, RS} e destinatário em {N, NE, CO, ES} → **7%**;
  - senão → **12%** (o comentário cita a Res. Senado 22/1989).
  - Mensagem: `"Alíquota interestadual de ICMS 12% - de SP para PE com origem 0 deveria ser 7%"`.

Ref.: `OperationCheck.java:15-101`.

**RN-09. `TaxCalculationCheck` (valores × códigos e bases do próprio item).** Não depende de cadastro.
- `TAX_CODE_VALUE` (`ERROR`): código "sem imposto" com valor maior que zero. Mensagem: `"ICMS de 15 destacado com código 40, que não tem imposto"`. Esperado: "0". Códigos considerados:
  - ICMS (CST ou CSOSN na coluna `situacaoTributariaIcms`): 40, 41, 50, 60, 102, 103, 300, 400, 500;
  - IPI: 01 a 05 e 51 a 55;
  - PIS/COFINS: 04 a 09.
- `TAX_CALCULATION` (`ERROR`): `valor = base × alíquota` (RN-04, sem redução, porque a base já vem reduzida):
  - ICMS: só para CST em {00, 10, 20, 70, 90} e item sem CSOSN (ou sem detalhe);
  - IPI, PIS e COFINS: usam base e alíquota do detalhe (só ad valorem: se a base ou a alíquota for nula, não compara).
  - Mensagem: `"Valor de ICMS 120.02 diferente de base x alíquota (1000 x 12% = 120)"`.
- `TAX_CALCULATION` em `vICMSST` (`WARNING`): para CST 10 ou 70 sem CSOSN, `ICMS-ST esperado = baseST × alíqST − ICMS próprio`. É alerta porque FCP-ST e especificidades estaduais podem deslocar o valor. Mensagem: `"ICMS-ST 130 diferente de base ST x alíquota ST - ICMS próprio (132)"`.
- Se faltar base, alíquota ou valor, não compara.

Ref.: `TaxCalculationCheck.java:13-97`.

**RN-10. `SubstitutionCheck` (ST).** Só roda para itens com detalhe e com `situacaoTributariaIcms`. O CST é lido pelos 2 últimos dígitos (aceita "060").
- `ST_CFOP_CST` (`WARNING`): CFOP em {5401, 5402, 5403, 5405, 6401, 6402, 6403, 6404} sem código de ST (CST 10/30/60/70/90 ou CSOSN 201/202/203/500/900). Mensagem: `"CFOP 5405 é de operação com substituição tributária, mas o item informa CST 00"`.
- `ST_CEST_MISSING` (`WARNING`): item com CST 10/30/60/70 ou CSOSN 201/202/203/500 sem CEST. Os códigos 90 e 900 não exigem CEST. A mensagem cita o Conv. ICMS 142/2018.

Ref.: `SubstitutionCheck.java:11-75`.

**RN-11. `TaxRegimeCodeCheck` (CRT × CST/CSOSN).** Roda quando a nota tem CRT e o item tem detalhe e código de ICMS. Severidade `ERROR`, código `CRT_CST`:
- CRT 1 ou 4 sem CSOSN → `"Emitente do Simples Nacional (CRT 1) informou CST 00 de ICMS - deveria usar CSOSN"`;
- CRT 2 ou 3 com CSOSN → `"Emitente do regime normal (CRT 3) informou CSOSN 102 - deveria usar CST de ICMS"`.

Ref.: `TaxRegimeCodeCheck.java:11-43`.

**RN-12. `IbsCbsItemCheck` (grupo IBS/CBS do item).** Só roda se o item tem o grupo.
- `IBSCBS_CLASS_TRIB` (`WARNING`): cClassTrib ausente ou fora do formato de 6 dígitos, ou que não começa pelo CST.
- `IBSCBS_RATE` (`ERROR`): para CST 000 ou 200, `pCBS`, `pIBSUF` e `pIBSMun` precisam ser iguais à alíquota de referência do **ano de emissão** (`ibs_cbs_rates`). Se o ano não tem linha, não há checagem. A comparação é pela alíquota nominal (a redução fica em gRed). Mensagem: `"Alíquota de CBS 1% diferente da referência de 2026 (0.9%)"`.
- `IBSCBS_CALCULATION` (`ERROR`): `vCBS`, `vIBSUF` e `vIBSMun` = `base × alíquota × (1 − redução)` (RN-04), e `vIBS = vIBSUF + vIBSMun`. Não roda quando há diferimento ou devolução (`ibscbs_diferimento_devolucao = true`).

Ref.: `IbsCbsItemCheck.java:17-114`, `V24__IbsCbsRates.java` (2026: CBS 0,9%, IBS UF 0,1%, IBS Mun 0%; o comentário cita a LC 214/2025).

### Checagens por nota

**RN-13. `IcmsTotalsCheck` (ICMSTot × soma dos itens).** Só para `documentType = NFE` e nota com itens. Código `ICMS_TOTALS`, `ERROR`. Compara vBC, vICMS, vIPI, vPIS, vCOFINS e, **só quando todos os itens têm detalhe**, vST. Se o total da nota for nulo, não compara. Mensagem: `"ICMS no total da nota (240) diferente da soma dos itens (239.5)"`.
Ref.: `IcmsTotalsCheck.java:15-56`.

**RN-14. `IbsCbsDocumentCheck`.**
- `IBSCBS_MISSING` (`WARNING`): nenhum item tem o grupo IBS/CBS e a nota é modelo 55 ou 65 (ou `documentType = NFE` sem modelo), CRT 3, emitida em 2026 ou depois. Mensagem: `"Nota de emitente do regime normal emitida a partir de 2026 sem o grupo IBS/CBS"`.
- `IBSCBS_TOTALS` (`ERROR`): IBSCBSTot (vBCIBSCBS, vIBS, vCBS) × soma dos grupos dos itens. O vIBS do item é `vIBS` ou, se ele faltar, `vIBSUF + vIBSMun`.

Ref.: `IbsCbsDocumentCheck.java:17-82`.

### Saídas

**RN-15. Conferência do período (`FiscalConferenceService`).** Roda sobre os itens das notas da empresa com emissão entre `dataEmissaoInicio 00:00` e `dataEmissaoFim 23:59:59.999…` (sem data, o período fica aberto) e devolve uma linha por achado (`FiscalCheckFindingDTO`). Nos achados de nota, `numeroItem`, `codigoProduto` e `descricaoProduto` vêm nulos. Ficam de fora as notas não conferíveis (RN-02). Uma nota sem nenhum item armazenado não entra (a busca parte dos itens).
Ref.: `FiscalConferenceService.java:25-90`.

**RN-16. Resumo por nota (`DocumentDivergenceService`).** Para a lista de notas e a fronteira, o resultado por nota é `{errors, warnings, messages}`, com até **5 mensagens distintas** na ordem em que aparecem. Notas sem achado ficam fora do mapa. Os itens são lidos em blocos de 500 IDs.
Ref.: `DocumentDivergenceService.java:20-90`. O filtro "só divergentes" da lista (`FiscalXmlQueryService.java:171-181`) roda o motor em **todas** as notas do filtro e pagina em memória. Também conta como divergente a nota que o analista marcou como `DIVERGENT`.

## Critérios de aceite
- **CA-01 (RN-02).** Dada uma nota cancelada com ICMS calculado errado, quando se abre a conferência do período, então ela não aparece.
- **CA-02 (RN-04, RN-09).** Dado um item CST 00 com base 1.000,00, alíquota 12% e vICMS 120,01, quando é conferido, então não há achado. Com vICMS 120,02, então há `TAX_CALCULATION` com o esperado "120".
- **CA-03 (RN-07).** Dada uma nota própria cujo item diverge da regra do produto, quando é conferida, então não há `RULE_*`.
- **CA-04 (RN-07).** Dada uma nota retificada depois da última edição da regra, quando é conferida, então não há `RULE_CFOP`/`RULE_NCM`. Se a regra for editada depois, eles voltam.
- **CA-05 (RN-08).** Dado um item interestadual de SP para PE, origem 0, CST 00 e pICMS 12, quando é conferido, então há `INTERSTATE_RATE` com o esperado "7".
- **CA-06 (RN-08).** Dado o mesmo item com origem 1 e pICMS 4, quando é conferido, então não há achado de alíquota.
- **CA-07 (RN-08).** Dado `idDest = 2` e um item com CFOP 5102, quando é conferido, então há `CFOP_DESTINATION` (`WARNING`).
- **CA-08 (RN-10).** Dado CFOP 5405 com CST 00, quando é conferido, então há `ST_CFOP_CST`. Dado CST 60 sem CEST, então há `ST_CEST_MISSING`.
- **CA-09 (RN-11).** Dado CRT 1 e um item com CST 00, quando é conferido, então há `CRT_CST` (`ERROR`).
- **CA-10 (RN-12).** Dada uma nota de 2026 com CST 000 e pCBS 1, quando é conferida, então há `IBSCBS_RATE` com o esperado "0.9".
- **CA-11 (RN-12).** Dado um item com gDif, quando é conferido, então não há `IBSCBS_CALCULATION`.
- **CA-12 (RN-13).** Dada uma NF-e com vICMS total 240,00 e itens somando 239,50, quando é conferida, então há `ICMS_TOTALS`.
- **CA-13 (RN-14).** Dada uma NF-e de 2026 de emitente CRT 3 sem grupo IBS/CBS, quando é conferida, então há `IBSCBS_MISSING` (`WARNING`). Se o emitente for CRT 1, então não há.
- **CA-14 (RN-16).** Dada uma nota com 7 achados de mensagens diferentes, quando a lista é carregada, então o selo mostra o total de erros e alertas e só 5 mensagens.

### Casos de teste com números fictícios
| # | Entrada | Cálculo | Resultado |
|---|---|---|---|
| T-01 | CST 00, vBC 1.000,00, pICMS 12, vICMS 120,00 | 1000 × 12 / 100 = 120,00 | sem achado |
| T-02 | CST 20, vBC 600,00 (já reduzida), pICMS 18, vICMS 108,00 | 600 × 18% = 108,00 | sem achado |
| T-03 | CST 40, vICMS 15,00 | — | `TAX_CODE_VALUE`, esperado 0 |
| T-04 | CST 10, vBC 1.000, pICMS 12 (vICMS 120), vBCST 1.400, pICMSST 18, vICMSST 130 | 1400 × 18% = 252,00 − 120 = 132,00 | `TAX_CALCULATION` em vICMSST, `WARNING`, esperado 132 |
| T-05 | Interestadual de RS para SP, origem 0, CST 00, pICMS 7 | esperado 12 (SP não está no grupo N/NE/CO/ES) | `INTERSTATE_RATE` |
| T-06 | Interestadual com a mesma UF dos dois lados | `expectedInterstateRate` = nulo | sem achado de alíquota |
| T-07 | IBS/CBS 2026, CST 000, base 1.000,00, pCBS 0,9, vCBS 9,00, pIBSUF 0,1, vIBSUF 1,00, pIBSMun 0, vIBSMun 0, vIBS 1,00 | 9,00 / 1,00 / 0 | sem achado |
| T-08 | IBS/CBS CST 200, base 1.000,00, pCBS 0,9, redução 60%, vCBS 3,60 | 0,9 × 40 / 100 = 0,36% → 3,60 | sem achado |
| T-09 | Item sem `taxDetail` (importado antes do V22, sem backfill) | — | rodam: `RULE_CFOP`/`RULE_NCM`, `RULE_CST_IPI/PIS/COFINS` e `RULE_ALIQUOTA_ICMS` (colunas), `CFOP_DESTINATION`, `TAX_CODE_VALUE`, cálculo do ICMS e `ICMS_TOTALS` (sem vST). Não rodam: `RULE_CST_ICMS`, origem e CEST, `INTERSTATE_RATE`, IPI/PIS/COFINS calculados, ST e `CRT_CST` |
| T-10 | Valor zero em tudo (vBC 0, pICMS 0, vICMS 0, CST 00) | 0 × 0 = 0 | sem achado |
| T-11 | Nota cancelada com qualquer erro | — | fora (RN-02) |
| T-12 | Nota de devolução (finNFe 4) | **não há tratamento específico**: roda como qualquer nota | ver P-04 |

## Endpoints da API e telas
| Método | Caminho | Permissão | Uso |
|---|---|---|---|
| GET | `/api/v1/product-fiscal-rules/conferencia?companyId=&dataEmissaoInicio=&dataEmissaoFim=` | FISCAL_RULES_VIEW | RN-15 |
| GET | `/api/v1/product-fiscal-rules/divergencias?...` | FISCAL_RULES_VIEW | só `RULE_CFOP`/`RULE_NCM`, no formato antigo (ver a spec de regras) |
| GET | `/api/v1/integrations/xmls` (busca de notas) | INVOICES_VIEW | campo `divergencias` do resumo (RN-16) |

O acesso à empresa é validado por `CompanyValidation.checkExist` (escritório e carteira do usuário).

Front (`C:\workspace\taxify-front\src\app`):
- `features/companies/company-fiscal-conference/`: acordeão "Conferência fiscal" (com `FISCAL_RULES_VIEW`). O período padrão é o mês anterior, com filtros por severidade e por grupo (`finding-groups.ts`: RULE, CALC, REGIME, OPERATION, IBSCBS, TOTALS; o que não se encaixa cai em "Outros") e link para a nota.
- `features/invoices/invoice-xml-list/`: selo de divergências por nota.
- `features/invoices/invoice-detail/` (aba "Divergências"): chama `/conferencia` com o dia de emissão da nota, filtra pelo `documentId` no cliente e esconde `RULE_CFOP`/`RULE_NCM` (que vêm de `/divergencias`).
- `features/fronteira/`: selo `DIVERGENCIA_FISCAL` com as mensagens do resumo.

## Dados
- Leitura: `fiscal_xml_documents` (cabeçalho: `destino_operacao`, `emitente_uf`, `destinatario_uf`, `emitente_regime_tributario`, modelo, totais ICMSTot e IBSCBSTot, `own_issue`, `status`, `rectified_file_path`, `last_rectification_checked_at`) e `fiscal_xml_document_items` (colunas originais e as do V22/V25).
- Tabela `ibs_cbs_rates` (V24): `ano` (PK), `aliquota_cbs`, `aliquota_ibs_uf`, `aliquota_ibs_mun` (decimal 7,4). Semeada com 2026 = 0,9 / 0,1 / 0.
- Nada é gravado pelo motor.

## Configuração
Nenhuma propriedade própria. O preenchimento dos campos do V22 a V25 em notas antigas depende do `FiscalXmlTaxDetailBackfill` (fora deste escopo).

## Testes existentes
- `FiscalCheckEngineTest`: carrega as regras uma vez e roda todas as checagens. A regra sem NCM não compara NCM, e o item sem regra não gera achado.
- `Phase2ChecksTest`: campos tributários da regra, CSOSN só em nota com CSOSN e alíquota interna só em operação interna, CRT×CST, cálculo e código isento com valor, item do Simples sem achado de cálculo, tabela 4/7/12%, alíquota interestadual errada e CFOP de outro destino, importado a 4%, totais × itens.
- `IbsCbsChecksTest`: item correto, redução, alíquota, valor e cClassTrib errados, ano sem referência, CST não tributado, diferimento, grupo ausente em 2026 e totais.
- `SubstitutionCheckTest`: CFOP de ST sem CST de ST, ST sem CEST, item sem detalhe ignorado.
- `ProductFiscalDivergenceServiceTest`: ver a spec de regras.
- `FiscalDirectionsTest`: sentido da nota. `SharedHelpersTest`: `OperationCfops`, `ProductRules`, `IssuePeriod`.

**Lacunas:** sem teste de `FiscalConferenceService` (filtro de canceladas, achados de nota com item nulo), de `DocumentDivergenceService` (limite de 5 mensagens, blocos de 500) nem de `Money` (fronteira exata de 0,01 e HALF_UP). Também falta teste de ICMS-ST (T-04) em `TaxCalculationCheck`, de `IcmsTotalsCheck` com vST quando um item não tem detalhe e um caso de nota de devolução.

## Fora do escopo e limitações conhecidas
- Não há perfil por tipo de empresa (Simples, tecidos 5,5%, PROIND) e nenhuma conta de fronteira ou DIFAL. A fronteira está em spec própria.
- Não há tratamento de nota de devolução, complementar ou de ajuste (finNFe): todas passam pelas mesmas checagens.
- FCP, FCP-ST, ICMS desonerado e benefício (cBenef) não são conferidos. A MVA não é recalculada.
- PIS/COFINS por quantidade (alíquota em reais) não são conferidos.
- Não é possível silenciar ou aceitar um achado: ele volta a cada consulta.
- A ordem dos achados entre checagens depende da ordem de injeção dos beans (não há `@Order`).
- O filtro "só divergentes" roda o motor em todas as notas do filtro, o que custa caro em períodos grandes.
- O `finding-groups.ts` do front não tem os códigos `ST_CFOP_CST` e `ST_CEST_MISSING`, que aparecem como "Outros".
- Não há tela nem endpoint para cadastrar alíquotas IBS/CBS de anos seguintes, embora o comentário do V24 diga "os anos seguintes entram por cadastro".

## Perguntas em aberto
- **P-01 (regra fiscal).** A tabela de alíquota interestadual (4/7/12) não trata as origens 6 e 7 nem exceções como a venda a não contribuinte. Ela basta para a carteira? O Tony valida a tabela e a fonte.
- **P-02 (regra fiscal).** O cálculo de ICMS-ST desconta o ICMS próprio do item (`vICMS`). Para PE, a conferência da ST precisa considerar FCP-ST ou regras estaduais? Hoje só gera alerta.
- **P-03 (regra fiscal).** `IbsCbsDocumentCheck` considera dispensados os CRT 1, 2 e 4, mas `TaxRegimeCodeCheck` trata o CRT 2 como regime de CST. O CRT 2 (Simples acima do sublimite) deve ser obrigado ao grupo IBS/CBS em 2026?
- **P-04 (regra fiscal).** Notas de devolução e de remessa não têm tratamento: uma devolução de compra interestadual, por exemplo, gera `CFOP_DESTINATION` ou `RULE_CFOP`? Quais checagens devem ser puladas por finalidade ou CFOP?
- **P-05 (inconsistência).** `ProductRuleCheck` continua apontando `RULE_CST_*`, `RULE_ORIGEM`, `RULE_CEST` e `RULE_CLASS_TRIB` mesmo depois de uma retificação que reescreveu esses códigos no XML, porque as colunas do item guardam o valor emitido e só CFOP/NCM são "perdoados" (RN-07). Esses achados devem sumir depois da retificação, como CFOP/NCM?
- **P-06 (arredondamento).** A tolerância é estritamente maior que R$ 0,01 para cálculos e totais, e zero para alíquotas. Para totais de notas com muitos itens, 1 centavo basta? O paralelo com a conferência manual deve confirmar.
- **P-07 (dado).** As alíquotas IBS/CBS de 2027 em diante não têm cadastro (só o seed de 2026 via migration). Quem mantém e por onde?
- **P-08 (escopo).** O CST de ICMS 51 (diferimento) não aparece em nenhuma lista (nem "sem imposto" nem "calculado"), então nunca é conferido. É intencional?
