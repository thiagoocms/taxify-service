# Apuração do mês (faturamento, RBT12, estimativa de impostos e fechamento) — spec as-built

## Status

**Implementado.** Spec retroativa: descreve o que o código faz hoje e não propõe mudança.

- Commits de referência: `825144d` (regime tributário estruturado e apuração do período), `f6d1e10` (fechamento do mês, histórico de regime), `dfb2978` (IBS/CBS na apuração), `6eadd7a` (cache da apuração por empresa e consultas agrupadas).
- Migrations: `V7__CompanyTaxRegimes` (histórico de regime), `V20__FiscalPeriodClosings` (fechamento). `V48__FiscalXmlItemSimplesCredit` (pCredSN/vCredICMSSN no item) **não é lida pela apuração**: só a Fronteira PE (`FronteiraCalculator`) usa esses campos. Ver a seção Dados.
- Código principal: `rest/TaxAssessmentResource`, `service/assessment/TaxAssessmentService`, `TaxAssessmentCalculator`, `RevenueCfops`, `FiscalPeriodClosingService`, `TaxAssessmentCache`, `TaxAssessmentCacheInvalidator`, `service/CompanyTaxRegimeService`, `enumerated/SimplesAnnex`.

## Problema e objetivo

Antes de emitir o DAS ou o DARF de cada cliente, o analista somava à mão o faturamento do mês e o dos 12 meses anteriores (RBT12), achava a faixa e a alíquota efetiva do Simples, ou estimava PIS/COFINS/IRPJ/CSLL no Presumido e no Real. Também controlava em planilha quais meses já estavam fechados e se a guia tinha sido emitida.

A apuração calcula esses números a partir das notas importadas, para a carteira inteira e para cada empresa, mostra a memória de cálculo de cada imposto (base, alíquota, valor e uma observação) e permite **fechar o mês**. O fechamento congela os valores num snapshot e registra a guia.

Indicadores possíveis (não medidos no código hoje): empresas fechadas por mês sobre as empresas ativas; diferença entre o total estimado e o valor da guia (`guide_amount`) por empresa e mês.

## Usuários e cenário

- **Analista fiscal** (permissão `TAX_ASSESSMENT_VIEW`): abre a lista da carteira no início do mês. O padrão é o mês anterior. Filtra por regime e por alertas e entra no detalhe de cada empresa.
- **Analista ou responsável pelo fechamento** (`TAX_ASSESSMENT_CLOSE`): confere o checklist, registra a guia e fecha o mês. Reabre quando chega uma nota atrasada.
- Frequência: mensal, por empresa. A lista é a tela mais consultada do app, e por isso tem cache (RN-17).

## Histórias de usuário

1. Como analista fiscal, quero ver todas as empresas da minha carteira no mês com faturamento, RBT12 e impostos estimados, para saber por onde começar o fechamento.
2. Como analista fiscal, quero ver o detalhe de uma empresa com faturamento segregado (revenda, produção própria, serviços, devoluções), os impostos com base e alíquota e os últimos 13 meses, para conferir o cálculo antes de emitir a guia.
3. Como analista fiscal, quero alertas de sublimite e teto do Simples, de limite do MEI, de regime não cadastrado e de mês sem nota de saída, para não deixar passar um risco.
4. Como responsável pelo fechamento, quero fechar o mês e congelar os valores, para que uma nota que chegue depois não mude o que já foi apurado.
5. Como responsável pelo fechamento, quero registrar se a guia foi emitida, o valor, o vencimento e observações, para controlar a entrega sem planilha.
6. Como analista fiscal, quero que cada mês use o regime vigente naquele mês, para que a troca de regime em janeiro não refaça os meses do ano anterior.

## Regras de negócio

Convenções que valem para todas as regras: dinheiro em `BigDecimal`. `money(x)` = `x.setScale(2, HALF_UP)` e `percentOf(v, p)` = `v × p / 100` com 2 casas, `HALF_UP` (`TaxAssessmentCalculator.java:299-305`). O mês é `YearMonth`. Sem parâmetro, vale o mês anterior ao atual (`TaxAssessmentService.java:141-150`). Formato inválido gera 400 com `error.assessment.month.invalid`.

**RN-01. Fonte dos valores.** A apuração soma `valor_total` dos itens (o `vProd` do item) por empresa, ano, mês de emissão, direção e CFOP. Ficam de fora as notas canceladas (`status = CANCELED`), as excluídas (`deleted`) e as notas sem direção (`FiscalXmlDocumentItemRepository.java:117-130`). A janela vai de 12 meses antes do período até o fim do período (`TaxAssessmentService.java:208-213`). NFS-e (tabela `service_invoices`) **não** entra: só os itens de NF-e/NFC-e.

**RN-02. Classificação por CFOP** (`RevenueCfops.java:30-80`). Um CFOP que não tem 4 dígitos numéricos vira `NONE`.
- Saída (1º dígito 5, 6 ou 7), pelas 3 últimas posições:
  - `OWN_PRODUCTION_SALE` (produção própria): 101, 103, 105, 109, 111, 113, 116, 118, 122, 401, 402, 651, 653.
  - `RESALE` (revenda): 102, 104, 106, 110, 112, 114, 115, 117, 119, 120, 123, 403, 405, 652, 654, 655, 656.
  - `SERVICE`: 124, 125, 933, 301 a 307, 351 a 360.
  - Qualquer outro CFOP (remessa, transferência, bonificação, retorno, x922 simples faturamento) vira `NONE`.
- Entrada:
  - `SALES_RETURN` (devolução de venda) quando a nota é própria de entrada (1º dígito 1-3) com 201, 202, 203, 204, 410 ou 411, ou quando é a nota do cliente recebida (1º dígito 5-7) com 201, 202, 410 ou 411.
  - `PURCHASE` quando a nota é própria de entrada com 101, 102, 401, 403 ou 126, ou quando é a nota do fornecedor com CFOP de venda (aquele que, numa saída, seria `OWN_PRODUCTION_SALE` ou `RESALE`).
  - Qualquer outro CFOP vira `NONE`.

**RN-03. Receita líquida do mês.** `net = produção própria + revenda + serviços − devoluções de venda` (`TaxAssessmentCalculator.java:44-46`). Pode ser negativa. Compras não entram na receita.

**RN-04. Devoluções por anexo (Simples).** A devolução sai primeiro da revenda. Se a revenda ficar negativa, o excesso sai da produção própria, com piso zero. Os serviços não são abatidos (`TaxAssessmentCalculator.java:68-76`).

**RN-05. Acumulado no ano.** É a soma de `net` de janeiro até o mês do período, inclusive (`TaxAssessmentCalculator.java:100-103`).

**RN-06. RBT12** (`TaxAssessmentCalculator.java:172-193`, comentário cita LC 123, art. 18 §2). O início da atividade é a data de abertura da empresa (`openingDate`) ou, sem ela, o mês da primeira nota de saída não cancelada (`FiscalXmlDocumentRepository.java:178-182`).
- Se não há início conhecido, ou o início é anterior ou igual a `período − 12`: RBT12 = soma de `net` dos 12 meses anteriores ao período (sem o próprio mês). `rbt12Proportional = false`.
- Se o início é o próprio período (ou depois): RBT12 = `net do mês × 12`, proporcional.
- Nos outros casos: RBT12 = `(soma de net dos meses do início até o mês anterior / nº de meses) × 12`. A divisão usa 10 casas `HALF_UP` e o resultado é arredondado com `money`. Fica marcado como proporcional.

**RN-07. Regime do mês** (`CompanyTaxRegimeService.java:106-137`). Vale o último período de `company_tax_regimes` com `valid_from` ≤ 1º dia do mês. `valid_from` nulo significa "desde sempre". Sem histórico, valem os campos da própria empresa (`taxRegimeType`, `businessActivity`, `simplesServiceAnnex`). O anexo de serviço só é guardado para o Simples (`:166-168`).

**RN-08. Regime ausente.** Se o regime é nulo ou `OTHER`, não há estimativa de imposto e o sistema gera o alerta `REGIME_MISSING` (WARNING) (`TaxAssessmentCalculator.java:107-109`).

**RN-09. Simples Nacional: alíquota efetiva** (`SimplesAnnex.java:6-53`, comentário cita LC 123/2006 com as tabelas da LC 155/2016).
- Faixa pelo RBT12 (piso zero), com limites superiores de 180.000, 360.000, 720.000, 1.800.000, 3.600.000 e 4.800.000. Acima de 4,8 milhões fica na 6ª faixa.
- Alíquota efetiva = `(RBT12 × nominal/100 − dedução) / RBT12 × 100`, com 4 casas `HALF_UP`. Na 1ª faixa (dedução 0) ou com RBT12 ≤ 0 vale a alíquota nominal.
- **Atenção:** esse cálculo usa `double`, não `BigDecimal` (ver Perguntas em aberto).
- Tabelas codificadas (nominal %, dedução R$):

| Faixa | I (comércio) | II (indústria) | III (serviços) | IV | V |
|---|---|---|---|---|---|
| 1 | 4,00 / 0 | 4,50 / 0 | 6,00 / 0 | 4,50 / 0 | 15,50 / 0 |
| 2 | 7,30 / 5.940 | 7,80 / 5.940 | 11,20 / 9.360 | 9,00 / 8.100 | 18,00 / 4.500 |
| 3 | 9,50 / 13.860 | 10,00 / 13.860 | 13,50 / 17.640 | 10,20 / 12.420 | 19,50 / 9.900 |
| 4 | 10,70 / 22.500 | 11,20 / 22.500 | 16,00 / 35.640 | 14,00 / 39.780 | 20,50 / 17.100 |
| 5 | 14,30 / 87.300 | 14,70 / 85.500 | 21,00 / 125.640 | 22,00 / 183.780 | 23,00 / 62.100 |
| 6 | 19,00 / 378.000 | 30,00 / 720.000 | 33,00 / 648.000 | 33,00 / 828.000 | 30,50 / 540.000 |

**RN-10. Simples Nacional: DAS por anexo** (`TaxAssessmentCalculator.java:197-231`). Gera uma linha por receita positiva:
- Revenda (após RN-04) no anexo I.
- Produção própria (após RN-04) no anexo II.
- Serviços no anexo de serviço do regime. Sem anexo cadastrado, vale o III.

Cada linha traz o código `DAS_<anexo>`, base = receita, alíquota = efetiva do anexo e valor = `percentOf(base, alíquota)`. A observação mostra o tipo de receita e a faixa. Sem nenhuma receita positiva, sai uma linha `DAS` com base 0, valor 0 e a observação "Sem receita no mês." O fator R **não** é calculado: o anexo V só é usado se estiver cadastrado como anexo de serviço.

**RN-11. Alertas do Simples** (`:211-220`), excludentes nesta ordem:
- RBT12 > 4.800.000: `SIMPLES_CEILING` (DANGER).
- RBT12 > 3.600.000: `SIMPLES_SUBLIMIT` (WARNING).
- RBT12 ≥ 2.880.000 (80% do sublimite): `SIMPLES_NEAR_SUBLIMIT` (INFO).

Mesmo acima do sublimite, ICMS e ISS **não** são calculados fora do DAS: o sistema só avisa. Com RBT12 proporcional, gera também o alerta `RBT12_PROPORTIONAL` (INFO) (`:124-127`).

**RN-12. MEI** (`:233-245`). Gera a linha `DAS_MEI` com base, alíquota e valor nulos ("valor fixo mensal"). Alertas pelo acumulado no ano (RN-05):
- Acima de 97.200: `MEI_EXCLUSION` (DANGER).
- Acima de 81.000: `MEI_EXCESS` (WARNING).
- A partir de 64.800 (80%): `MEI_NEAR_LIMIT` (INFO).

Os limites não são proporcionais aos meses de atividade no ano.

**RN-13. Lucro Presumido** (`:251-268`), com a observação "apuração trimestral, o mês é uma prévia":
- `PIS` = 0,65% e `COFINS` = 3,00% sobre `max(net, 0)`.
- Mercadorias = `max(net − serviços, 0)`. Serviços = `max(serviços, 0)`.
- IRPJ: base = 8% × mercadorias + 32% × serviços. IRPJ = 15% × base + 10% × `max(base − 20.000, 0)`. O adicional é **mensal**.
- CSLL: base = 12% × mercadorias + 32% × serviços. CSLL = 9% × base.
- Cada percentual passa por `percentOf`, com arredondamento a cada etapa.

**RN-14. Lucro Real** (`:271-281`).
- PIS = `max(1,65% × max(net,0) − 1,65% × compras, 0)`.
- COFINS = `max(7,60% × max(net,0) − 7,60% × compras, 0)`.
- Compras = soma dos itens `PURCHASE` (RN-02). O crédito que passa do débito é descartado: não há saldo credor para o mês seguinte.
- A linha `IRPJ_CSLL` sai com valor nulo ("dependem do lucro contábil").

**RN-15. Total e mês sem nota.** `totalTaxes` = soma dos valores não nulos (`:129-130`). Se a empresa não tem nenhuma nota de saída não cancelada no mês, qualquer CFOP (`FiscalXmlDocumentRepository.java:170-176`), o sistema gera o alerta `NO_INVOICES` (WARNING).

**RN-16. IBS/CBS informativo** (`TaxAssessmentService.java:239-277`, `FiscalXmlDocumentItemRepository.java:98-115`).
- Soma vIBS (ou vIBSUF + vIBSMun quando vIBS falta) e vCBS dos itens com grupo IBS/CBS (`cst` não nulo), de notas não canceladas do mês, por direção.
- Débito vem das saídas e crédito das entradas. Saldo = débito − crédito, que pode ser negativo. Escala 2, `HALF_UP`.
- O bloco é nulo quando nenhuma nota do mês tem o grupo. **Não entra em `totalTaxes`.** A observação fixa diz que, em 2026, o valor é só informado e que a CBS passa a ser devida em 2027.

**RN-17. Lista da carteira e cache** (`TaxAssessmentService.java:84-94, 154-206`; `TaxAssessmentCache`; `TaxAssessmentCacheInvalidator`).
- A lista traz as empresas do escritório do usuário, não excluídas e ativas (`active != false`). Para quem não é ADMIN, só as empresas da sua carteira (`TenantAccessService.filterAccessible`). Usuário sem escritório recebe 400 `error.assessment.firm.required`.
- A lista sai sem histórico. O cálculo por empresa e mês fica em cache em memória, com TTL de 5 minutos e no máximo 20.000 entradas (ao estourar, o cache é limpo).
- O cache de uma empresa cai a cada insert, update ou delete via Hibernate de nota, item, empresa ou período de regime dessa empresa. A queda acontece na hora e de novo após o commit. Se não dá para identificar a empresa, cai o cache inteiro.
- Um cálculo iniciado antes da mudança não é guardado: cada empresa tem uma versão, que é comparada no `put`.
- O detalhe da empresa nunca usa o cache. Fechamento e contador de entradas a conferir são sempre lidos na hora.

**RN-18. Fechamento** (`FiscalPeriodClosingService.java:44-60`, `FiscalPeriodClosing.java:173-183`).
- Não fecha um mês futuro: período > mês atual gera 400 `error.closing.future`. O mês corrente, ainda em andamento, **pode** ser fechado.
- Não fecha um mês já fechado: 400 `error.closing.already.closed`.
- Ao fechar, o sistema recalcula o mês com histórico, ignorando o snapshot anterior, e grava `status = CLOSED`, `closed_by` (login, ou "system"), `closed_at` e `snapshot` (JSON do `TaxAssessmentDTO`). Também grava os valores de cabeçalho: `tax_regime`, `net_revenue`, `rbt12` e `total_taxes`.

**RN-19. Mês fechado responde pelo snapshot** (`TaxAssessmentService.java:166-176, 303-311`). Tudo que o cálculo mostra vem do snapshot, inclusive regime, alertas e histórico. O bloco `closing` é sempre atual. Um snapshot ilegível gera log WARN e o mês é recalculado.

**RN-20. Reabertura** (`FiscalPeriodClosingService.java:62-73`, `FiscalPeriodClosing.java:186-191`). Reabrir põe `status = OPEN` e apaga `closed_at`, `closed_by` e `snapshot`. A guia e as observações ficam. Reabrir um mês que não está fechado não faz nada e não gera erro.

**RN-21. Guia** (`FiscalPeriodClosingService.java:75-83`, `GuideUpdateDTO`). A guia pode ser editada com o mês aberto ou fechado. Os campos são `guideIssued` (boolean), `guideAmount` (≥ 0, opcional), `guideDueDate` (opcional) e `notes` (até 1000 caracteres; espaços nas pontas são removidos e texto vazio vira nulo). Se o registro de fechamento ainda não existe, ele é criado com status `OPEN`.

**RN-22. Checklist de fechamento.** `closing.pendingReviews` conta as notas de **entrada** não canceladas do mês com `review_status` nulo ou `PENDING` (`FiscalXmlDocumentRepository.java:187-197`). O front monta 4 itens: notas de saída > 0, entradas conferidas, regime cadastrado e guia emitida (`tax-assessment-detail.component.ts:76-88`). O checklist **não bloqueia** o fechamento.

**RN-23. Histórico de regime** (`CompanyTaxRegimeService.java:55-103`).
- Gravar um período com o mesmo `valid_from` substitui o período existente.
- Excluir um período é exclusão lógica.
- Depois de cada mudança, os campos de regime da empresa passam a espelhar o período vigente hoje; um período com início futuro ainda não conta.
- Ao editar o regime no cadastro ou importar da Acessórias, o sistema registra a mudança a partir do mês informado (ou do atual). O primeiro regime de uma empresa vale "desde sempre".

## Critérios de aceite (comportamento atual)

- **CA-01 (RN-02, RN-03).** Dado itens de saída 5102 de R$ 10.000,00 e de entrada 1202 de R$ 2.500,00 no mês, quando a apuração roda, então revenda = 10.000,00, devoluções = 2.500,00 e receita líquida = 7.500,00.
- **CA-02 (RN-02).** Dado um item de saída com CFOP 5152, 5910 ou 5922, quando a apuração roda, então o item não entra na receita.
- **CA-03 (RN-01).** Dada uma nota de saída cancelada, quando a apuração roda, então seus itens não entram na receita nem na contagem de notas emitidas.
- **CA-04 (RN-06).** Dado uma empresa com abertura há mais de 12 meses, quando a apuração roda, então RBT12 = soma dos 12 meses anteriores e `rbt12Proportional = false`.
- **CA-05 (RN-06, RN-11).** Dado uma empresa do Simples com início de atividade 2 meses antes do período, quando a apuração roda, então RBT12 = média dos 2 meses × 12, `rbt12Proportional = true` e o alerta `RBT12_PROPORTIONAL` aparece.
- **CA-06 (RN-09, RN-10).** Dado Simples com RBT12 = R$ 480.000,00, revenda de 30.000,00 e serviços de 10.000,00 no anexo III, quando a apuração roda, então `DAS_I` = 6,6125% e R$ 1.983,75, `DAS_III` = 9,8250% e R$ 982,50, e o total é R$ 2.966,25.
- **CA-07 (RN-11).** Dado RBT12 de R$ 3.840.000,00, quando a apuração roda, então só aparece o alerta `SIMPLES_SUBLIMIT`.
- **CA-08 (RN-12).** Dado MEI com acumulado no ano de R$ 100.000,00, quando a apuração roda, então aparece `MEI_EXCLUSION` e `DAS_MEI` sai sem valor.
- **CA-09 (RN-13).** Dado Presumido com revenda de 100.000,00 e serviços de 50.000,00, quando a apuração roda, então PIS = 975,00, COFINS = 4.500,00, IRPJ = 4.000,00 (base 24.000,00) e CSLL = 2.520,00 (base 28.000,00).
- **CA-10 (RN-14).** Dado Real com receita de 100.000,00 e compras de 60.000,00, quando a apuração roda, então PIS = 660,00, COFINS = 3.040,00 e `IRPJ_CSLL` sai sem valor.
- **CA-11 (RN-08, RN-15).** Dado uma empresa sem regime e sem notas, quando a apuração roda, então não há impostos e os alertas são `REGIME_MISSING` e `NO_INVOICES`.
- **CA-12 (RN-18, RN-19).** Dado um mês fechado, quando entra uma nota nova do mês, então a apuração continua com os valores do snapshot.
- **CA-13 (RN-20).** Dado um mês fechado, quando o usuário reabre, então os valores voltam a ser recalculados e a guia é mantida.
- **CA-14 (RN-18).** Dado o mês seguinte ao atual, quando o usuário tenta fechar, então recebe 400 "Não é possível fechar um mês que ainda não começou."
- **CA-15 (RN-17).** Dado a lista da carteira em cache, quando uma nota da empresa é gravada, então a próxima leitura recalcula essa empresa e as outras continuam vindo do cache.
- **CA-16 (RN-16).** Dado nenhum item com grupo IBS/CBS no mês, quando a apuração roda, então `ibsCbs` é nulo.
- **CA-17 (RN-07).** Dado uma empresa que era Simples até dez/2025 e passou ao Presumido em jan/2026, quando o usuário apura nov/2025, então o regime aplicado é o Simples.

## Casos de teste com números (fictícios)

| # | Entrada | Cálculo | Resultado esperado |
|---|---|---|---|
| T1 | Simples, anexo III; 12 meses anteriores e o mês com revenda de 30.000 + serviços de 10.000 | RBT12 = 12 × 40.000 = 480.000 (3ª faixa). I: (480.000 × 9,5% − 13.860)/480.000 = 6,6125%. III: (64.800 − 17.640)/480.000 = 9,825% | DAS_I 1.983,75; DAS_III 982,50; total 2.966,25 |
| T2 | Simples, 1º mês de atividade, revenda de 10.000 e devolução (1202) de 2.500 | net 7.500; RBT12 = 7.500 × 12 = 90.000 (1ª faixa, nominal 4%) | DAS_I base 7.500,00, valor 300,00; alerta RBT12_PROPORTIONAL |
| T3 | Simples: revenda 1.000, produção 5.000, devolução 3.000 | revenda líquida −2.000, que vira 0; produção 5.000 − 2.000 = 3.000 | DAS_I sem linha; DAS_II base 3.000,00 |
| T4 | Simples: ago 10.000, set 20.000, out 5.000; início em ago; período out | (30.000 / 2) × 12 | RBT12 180.000,00, proporcional |
| T5 | Simples, mês só com remessas (5949) e 2 notas de saída | net 0 | linha DAS base 0, valor 0, "Sem receita no mês."; sem NO_INVOICES |
| T6 | Simples, mês sem nenhuma nota de saída | — | linha DAS valor 0 + alerta NO_INVOICES |
| T7 | Nota de saída 5102 de 8.000, cancelada | excluída pela consulta | revenda 0; invoicesIssued 0 |
| T8 | MEI, 10.000/mês de jan a out | acumulado 100.000 > 97.200 | MEI_EXCLUSION; DAS_MEI sem valor |
| T9 | Presumido: revenda 100.000, serviços 50.000 | PIS 0,65% × 150.000; COFINS 3%; IRPJ 15% × 24.000 + 10% × 4.000; CSLL 9% × 28.000 | 975,00; 4.500,00; 4.000,00; 2.520,00; total 11.995,00 |
| T10 | Real: revenda 100.000, compras (1102) 60.000 | PIS 1.650 − 990; COFINS 7.600 − 4.560 | 660,00; 3.040,00; IRPJ_CSLL nulo |
| T11 | Real: revenda 10.000, compras 50.000 | 165 − 825 vira 0; 760 − 3.800 vira 0 | PIS 0,00; COFINS 0,00 (o crédito excedente se perde) |
| T12 | Empresa sem regime, sem notas | — | taxes vazio; REGIME_MISSING, NO_INVOICES |
| T13 | Mês 2026-10 fechado com total de 2.966,25; depois chega uma nota de 5.000 de out | snapshot | continua 2.966,25 até reabrir |
| T14 | `month=2026-13` | parse falha | 400 "Mês inválido, use o formato AAAA-MM." |
| T15 | Entrada de uso e consumo (1556 / 2556) | `NONE` | não entra em compras nem na receita |

## Endpoints da API

Base: `/api/v1/fiscal/apuracao`. A permissão da classe é `TAX_ASSESSMENT_VIEW`. Toda operação por empresa passa por `CompanyValidation.checkExist`, que confere se a empresa é do mesmo escritório e se o usuário é ADMIN ou está vinculado a ela; senão, responde como não encontrada.

| Método | Caminho | Permissão | Descrição |
|---|---|---|---|
| GET | `/fiscal/apuracao?month=AAAA-MM` | TAX_ASSESSMENT_VIEW | Carteira no mês, sem histórico |
| GET | `/fiscal/apuracao/{companyId}?month=` | TAX_ASSESSMENT_VIEW | Uma empresa, com 13 meses de histórico |
| POST | `/fiscal/apuracao/{companyId}/fechamento?month=` | TAX_ASSESSMENT_CLOSE | Fecha o mês (snapshot) |
| DELETE | `/fiscal/apuracao/{companyId}/fechamento?month=` | TAX_ASSESSMENT_CLOSE | Reabre o mês |
| PUT | `/fiscal/apuracao/{companyId}/guia?month=` | TAX_ASSESSMENT_CLOSE | Guia e observações (`GuideUpdateDTO`) |
| GET | `/companies/{id}/regimes` | COMPANIES_VIEW | Histórico de regime |
| POST | `/companies/{id}/regimes` | COMPANIES_VIEW + ADMIN (`@AdminOnly(writeOnly)`) | Inclui ou substitui um período |
| DELETE | `/companies/{id}/regimes/{regimeId}` | COMPANIES_VIEW + ADMIN | Exclui um período (lógico) |

Telas (taxify-front):
- `/apuracao` (`features/assessment/tax-assessment.component`): lista com filtros por regime, "sem regime" e busca, cards-atalho (alertas, sem notas, abertas, fechadas), paginação de 20 e exportação CSV feita no front.
- `/apuracao/:companyId` (`tax-assessment-detail.component`): faturamento segregado, tabela de impostos com base, alíquota e observação (a memória de cálculo), bloco IBS/CBS, gráfico de 13 meses, checklist, formulário da guia e botões de fechar e reabrir, visíveis só com `TAX_ASSESSMENT_CLOSE`. O formulário da guia vem preenchido com `totalTaxes` quando ainda não há valor de guia.
- Serviço: `core/services/tax-assessment.service.ts`. Rotas protegidas por `permissionGuard('TAX_ASSESSMENT_VIEW')`.

## Dados

- `fiscal_period_closings` (V20): `id`, `company_id` (FK), `period` (date, 1º dia do mês), `status` (OPEN/CLOSED), `closed_at`, `closed_by`, `snapshot` (TEXT, JSON do `TaxAssessmentDTO`), `tax_regime`, `net_revenue`, `rbt12`, `total_taxes` (decimal 19,2), `guide_issued`, `guide_amount`, `guide_due_date`, `notes` (1000) e auditoria. Chave única `(company_id, period)`.
- `company_tax_regimes` (V7): `company_id`, `valid_from` (nulo = desde sempre), `tax_regime`, `business_activity`, `simples_service_annex`, `deleted` e auditoria. Índice `(company_id, valid_from)`.
- Leitura: `fiscal_xml_documents` (direção, status, emissão, `review_status`) e `fiscal_xml_document_items` (`cfop`, `valor_total`, grupo IBS/CBS). Índice `idx_fxdi_document_cfop_valor` (commit `6eadd7a`).
- `fiscal_xml_document_items.aliquota_credito_sn` / `valor_credito_sn` (V48): **não usados pela apuração**. São lidos só pela Fronteira PE.
- O snapshot guarda nome e CNPJ da empresa e os valores. É dado sigiloso do cliente, coberto pelas mesmas regras de acesso das notas.

## Configuração

Não há propriedade externa. O TTL do cache (5 min) e o máximo de entradas (20.000) são constantes em `TaxAssessmentCache.java:27-29`. Limites, tabelas do Simples e alíquotas do Presumido e do Real estão fixos no código (`TaxAssessmentCalculator`, `SimplesAnnex`). Não existe tabela parametrizável.

## Testes existentes

- `service/assessment/TaxAssessmentCalculatorTest`: classificação de CFOP, alíquota efetiva por anexo, devoluções, RBT12 proporcional, sublimite, MEI, Presumido, Real, regime ausente e sem notas.
- `service/assessment/TaxAssessmentCacheTest`: TTL, queda por empresa, versão e invalidação por escrita.
- `service/assessment/TaxAssessmentServiceIbsCbsTest`: débito, crédito e saldo; bloco nulo.
- `service/CompanyTaxRegimeServiceTest`: regime vigente por mês, fallback para os campos da empresa, primeiro regime "desde sempre", mudança e "sem mudança".
- `common/TaxRegimesTest`: rótulos.

**Lacunas de teste:**
- Nenhum teste de `FiscalPeriodClosingService`: fechar, mês futuro, já fechado, reabrir mantendo a guia, snapshot respondendo no lugar do cálculo, snapshot ilegível.
- Nenhum teste de `TaxAssessmentService.assessFirm` (filtro de ativas e de carteira, combinação de cache com snapshot).
- Nenhum teste de resource ou permissão (`TAX_ASSESSMENT_CLOSE`).
- Falta teste de borda de faixa do Simples (RBT12 exatamente 180.000,00 ou 3.600.000,00) e de 4º a 6ª faixa.
- Falta teste dos anexos IV e V e da RN-04 com devolução maior que a revenda.
- Falta cobertura completa da tabela de CFOPs (p.ex. 5124/5125, 5351-5360, 1410/1411, 5401-5405).
- Falta teste do adicional do IRPJ abaixo de 20.000 e de receita líquida negativa.
- Não há testes de front (`*.spec.ts`) para as telas da apuração.

## Fora do escopo / limitações conhecidas

- É uma estimativa pelas notas: não usa folha (fator R), receita sem nota, segregação de ST/monofásico, ISS/ICMS fora do DAS acima do sublimite nem benefícios fiscais (avisos em `TaxAssessmentCalculator.java:20-25` e na tela).
- NFS-e prestadas não entram na receita de serviços; só itens de NF-e com CFOP de serviço.
- Não há DIFAL, ICMS próprio nem IPI na apuração. O IBS/CBS é só informativo.
- Não emite guia nem integra com PGDAS-D ou DCTFWeb; a guia é só um registro manual.
- Reabrir apaga quem fechou e quando. Não há histórico de fechamentos e reaberturas além de `last_modified_by/date`.
- O checklist não impede o fechamento.
- O cache é em memória, por instância. Com mais de uma instância, cada uma tem o seu.

## Perguntas em aberto

1. **Alíquota efetiva com `double`.** `SimplesAnnex.effectiveRate` e `bracketOf` usam `double`, contra a regra do projeto de usar `BigDecimal` para dinheiro. Pode haver diferença de arredondamento na 4ª casa. Migrar para `BigDecimal`?
2. **NFS-e fora da receita.** Prestadoras que só emitem NFS-e aparecem com receita 0 e alerta `NO_INVOICES`. A apuração deveria somar `service_invoices` PRESTADA?
3. **Tabela de CFOPs (RN-02)** precisa de validação do Tony:
   - Industrialização por encomenda (x124/x125) é tratada como serviço e cai no anexo de serviço do Simples (padrão III). Está certo?
   - Transporte (x351-x360) entra com presunção de 32% no Presumido. Está certo?
   - Devolução de compra emitida pela empresa (x201/x202 de saída) é `NONE` e não reduz as compras do Lucro Real.
   - Entrada 1556/1407 (uso e consumo) não gera crédito.
4. **Presumido.** O adicional do IRPJ é calculado por mês (acima de R$ 20.000) enquanto a apuração é trimestral. Deveria acumular o trimestre (limite de R$ 60.000)? A presunção de 8%/12% vale para todas as mercadorias, sem distinguir atividade (revenda de combustível, por exemplo).
5. **Lucro Real.** O crédito de PIS/COFINS é só sobre compras para revenda ou industrialização, e o crédito excedente se perde em vez de ir para o mês seguinte. É aceitável como estimativa?
6. **MEI.** Os limites de 81.000 e 97.200 não são proporcionais aos meses de atividade no ano de abertura. Deveriam ser?
7. **Fechar o mês corrente.** Hoje é permitido fechar o mês ainda em andamento. É desejado?
8. **Fator R / anexo V.** O anexo vem só do cadastro. Deve haver alerta quando o anexo de serviço é V ou III sem informação de folha?
9. **Auditabilidade do fechamento.** Reabrir apaga `closed_by`, `closed_at` e o snapshot. Para a fase de paralelo com a conferência manual, precisamos guardar o histórico de fechamentos?
10. **V48 no escopo desta spec.** O crédito do Simples (pCredSN/vCredICMSSN) não é usado na apuração do mês. Confirmar que ele pertence só à spec da Fronteira PE.
