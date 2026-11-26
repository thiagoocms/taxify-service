# Auditoria da carteira — auditoria tipo malha (as-built)

## Status
**Implementado** (roadmap M9). Spec retroativa: descreve o código como está em 2026-11-26, sem propor mudança.

- Commits de referência: `dcf2006` (feat: auditoria tipo malha (M9): integridade, coerência dos itens e anomalias por empresa e mês) e `a682e36` (fix: regras gerais só alteradas pela CS Hub: admin da plataforma por `PLATFORM_ADMIN_LOGINS`).
- Migrations: `V43__AuditRules` (`audit_rules`), `V44__AuditRuns` (`audit_runs`). Os achados ficam em `pendencies` (`V35`), tipo `AUDIT_FINDING`.
- Backend: `rest/AuditResource`, `service/audit/AuditRuleDefinition`, `AuditEngine`, `AuditService`, `AuditScheduler`, `common/PlatformAdminService`. As checagens de item vêm de `service/fiscalcheck/*` (`OperationCheck`, `TaxRegimeCodeCheck`, `SubstitutionCheck`, `IbsCbsDocumentCheck`) e os limites do Simples/MEI vêm de `service/assessment/TaxAssessmentCalculator`.
- Frontend: `features/audit` (rota `/auditoria`), `core/services/audit.service.ts`, `core/models/audit.model.ts`.

## Problema e objetivo
A fiscalização estadual cruza dados das notas (numeração, devoluções, CFOP, alíquotas, ST, faturamento) para escolher quem vai fiscalizar. O objetivo é que o time Fiscal veja esses mesmos indícios antes, por empresa e por competência, e corrija ou justifique. O código e o laudo deixam explícito que **achado é indício para conferir, nunca infração** (`AuditRuleDefinition.java:7-12`, `AuditService.java:240-241`).

Como medir (não há métrica implementada): achados abertos por empresa e competência; regras já conferidas pelo time Fiscal em relação às 14 do catálogo.

## Usuários e cenário
- **Analista Fiscal** (`INVOICES_VIEW`): escolhe a empresa e a competência (padrão: mês anterior), lê os achados e baixa o laudo em PDF ou CSV.
- **Analista com `INVOICES_AUDIT`**: roda a auditoria na hora e resolve, desconsidera ou reabre achados (são pendências).
- **Admin da plataforma (CS Hub)**: login em `PLATFORM_ADMIN_LOGINS`, perfil ADMIN e `FISCAL_RULES_MANAGE`. Marca regra como conferida e liga ou desliga regras. As regras valem para **todos os escritórios**.
- **Sistema**: job diário às 05:30 que audita a competência anterior e a atual das empresas com "Auditoria de notas fiscais" no plano.

## Histórias de usuário
- Como analista Fiscal, quero ver os indícios de malha de uma empresa no mês, agrupados em integridade, coerência e anomalia, para corrigir antes da fiscalização.
- Como analista Fiscal, quero baixar um laudo em PDF ou CSV com os achados e o fundamento de cada regra, para arquivar ou mandar ao cliente.
- Como analista Fiscal, quero desconsiderar um achado com justificativa, para que ele não volte toda manhã.
- Como admin da CS Hub, quero marcar uma regra como conferida, para que os achados dela deixem de sair como "a conferir".
- Como admin da CS Hub, quero que só a CS Hub altere as regras, para que um escritório não mude a auditoria dos outros.

## Catálogo de regras existentes
Definidas em `AuditRuleDefinition.java:15-49`. O fundamento legal abaixo é o texto que está no código; **nenhuma delas foi validada nesta spec** (ver Perguntas em aberto).

| # | Código | Grupo | Severidade | Título | Fundamento no código | Vigência no código |
|---|---|---|---|---|---|---|
| 1 | `NUMERACAO_LACUNA` | INTEGRIDADE | MÉDIA | Lacuna na numeração das notas emitidas | Ajuste SINIEF 07/2005, cl. 14ª | — |
| 2 | `NUMERO_DUPLICADO` | INTEGRIDADE | ALTA | Mesmo número de nota com duas chaves | Ajuste SINIEF 07/2005, cl. 3ª | — |
| 3 | `DEVOLUCAO_SEM_ORIGINAL` | INTEGRIDADE | MÉDIA | Devolução sem a nota original | Ajuste SINIEF 07/2005 e MOC da NF-e (finNFe 4 exige NFref) | — |
| 4 | `NOTA_SO_RESUMO` | INTEGRIDADE | BAIXA | Entrada só com o resumo da SEFAZ | NT 2014.002 e NT 2012.002 | — |
| 5 | `CFOP_DESTINO` | COERÊNCIA | MÉDIA | CFOP incompatível com o destino da operação | Convênio s/nº de 15/12/1970 e Ajuste SINIEF 07/2001 | — |
| 6 | `ALIQUOTA_INTERESTADUAL` | COERÊNCIA | ALTA | Alíquota interestadual de ICMS divergente (4% no importado) | Res. Senado 13/2012 e 22/1989 | — |
| 7 | `CRT_CST` | COERÊNCIA | MÉDIA | CRT × CST/CSOSN | MOC da NF-e e LC 123/2006 | — |
| 8 | `ST_CFOP_CST` | COERÊNCIA | MÉDIA | CFOP de ST com CST/CSOSN sem ST | RICMS-PE (Dec. 44.650/2017), arts. 324 a 343, e Conv. ICMS 142/2018 | — |
| 9 | `ST_SEM_CEST` | COERÊNCIA | MÉDIA | Item com ICMS-ST sem CEST | Conv. ICMS 142/2018, cl. 3ª | — |
| 10 | `IBSCBS_GRUPO` | COERÊNCIA | MÉDIA | NF-e de 2026 do regime normal sem o grupo IBS/CBS | LC 214/2025 e NT 2025.002 | a partir de 01/01/2026 |
| 11 | `FATURAMENTO_ATIPICO` | ANOMALIA | MÉDIA | Faturamento muito acima da média dos 12 meses | "Indicador de malha fiscal" (sem norma) | — |
| 12 | `NFCE_CANCELAMENTO` | ANOMALIA | MÉDIA | Cancelamento de NFC-e acima de 5% no mês | "Indicador de malha fiscal" (sem norma) | — |
| 13 | `RBT12_SUBLIMITE` | ANOMALIA | ALTA | RBT12 perto ou acima do sublimite do Simples | LC 123/2006, arts. 13-A e 19 (R$ 3,6 mi) | — |
| 14 | `RBT12_LIMITE` | ANOMALIA | ALTA | Receita perto ou acima do limite do Simples / MEI | LC 123/2006, art. 3º, II (R$ 4,8 mi) e art. 18-A | — |

## Regras de negócio

### Catálogo e governança

**RN-01 Catálogo criado pelo código.** Na primeira leitura (listar regras, relatório, rodar ou laudo), cada `AuditRuleDefinition` que não tem linha em `audit_rules` é gravada com título, grupo, severidade, fundamento, link e `valid_from` do código, `enabled=true` e sem conferência. **Linha que já existe não é atualizada** se o código mudar depois.
Ref.: `AuditService.java:119-142`.

**RN-02 Só a CS Hub altera regras.** "Conferir" e "ativar/desativar" exigem a permissão `FISCAL_RULES_MANAGE` no endpoint **e** `PlatformAdminService.assertCanManageGlobalRules()`: o usuário tem perfil ADMIN e login (sem caixa e sem espaços) na lista `taxify.platform.admin-logins` (`PLATFORM_ADMIN_LOGINS`, separada por vírgula). Lista vazia = ninguém altera. Quem não cumpre recebe 403 "Regras gerais valem para todos os escritórios: só a CS Hub altera." O front só mostra as ações com `FISCAL_RULES_MANAGE` e `isPlatformAdmin()`.
Ref.: `AuditResource.java:60-71`; `AuditService.java:94-109`; `PlatformAdminService.java:15-53`; `audit.component.ts:60-61`.

**RN-03 Conferida × a conferir.** Uma regra é conferida quando `reviewed_at` está preenchido. Conferir grava `reviewed_by` (usuário) e `reviewed_at`; "desconferir" limpa os dois. O achado de regra conferida tem título "Indício: ..."; o de regra não conferida, "A conferir: ..." e detalhe terminado em "Regra ainda não conferida pelo time fiscal.". A severidade é a da regra nos dois casos. A mudança só vale a partir da próxima execução.
Ref.: `AuditRule.java:61-63`; `AuditService.java:95-101`, `:281-290`.

**RN-04 Regra aplicável ao mês.** A regra entra se `enabled` e se a vigência cobre algum dia do mês (`valid_from` ≤ último dia e `valid_until` ≥ primeiro dia, quando preenchidos). Achado de regra desligada ou fora da vigência é descartado e, como a sincronização é por competência, a pendência aberta dele **fecha automaticamente** na próxima execução.
Ref.: `AuditRule.java:65-69`; `AuditService.java:160-167`.

### Execução

**RN-05 Universo da execução.** Notas da empresa com data de emissão no mês (do dia 1 às 00:00 ao último dia às 23:59:59.999999999). "Emitidas" = não excluídas e de emissão própria (`isOwnIssue`). `notesChecked` = total de notas do mês carregadas (inclui entradas e excluídas).
Ref.: `AuditEngine.java:111-123`.

**RN-06 `NUMERACAO_LACUNA`.** Agrupa as emitidas por modelo (vazio = 55) e série (vazia = "0"), só com número numérico. **Notas canceladas contam como número usado.** Lacuna = números que faltam entre o menor e o maior número **do mês** daquela série. Um achado por modelo e série, com a contagem, até 15 números listados ("e outros") e a orientação de conferir a inutilização. Objeto: empresa. Sufixo da chave: `<modelo>|<série>`.
Ref.: `AuditEngine.java:127-169`.

**RN-07 `NUMERO_DUPLICADO`.** Na mesma série, um número com mais de uma chave de acesso distinta gera um achado com as chaves. Objeto: a primeira nota. Sufixo: `<modelo>|<série>:<número>`.
Ref.: `AuditEngine.java:136-147`.

**RN-08 `DEVOLUCAO_SEM_ORIGINAL`.** Para emitidas com `finNFe = 4`, não canceladas, no máximo 100 por execução: lê os `refNFe` do XML. Sem nenhum `refNFe` → achado "sem nota referenciada". Com referências, se **nenhuma** das chaves existir no Taxify para a empresa → achado "nota original não está no Taxify". Se o XML não puder ser lido, a nota é ignorada. Só `refNFe` é considerado. Objeto: a nota.
Ref.: `AuditEngine.java:171-217`.

**RN-09 `NOTA_SO_RESUMO`.** Resumos da SEFAZ (entradas) com emissão no mês, ainda sem XML completo, autorizados e criados há mais de 15 dias geram **um** achado com a quantidade e até 5 exemplos (emitente e valor). Objeto: empresa.
Ref.: `AuditEngine.java:219-234`.

**RN-10 Coerência (regras 5 a 10).** Roda as checagens do motor de conferência (`FiscalCheckEngine`) sobre os itens das notas **emitidas** que podem ser conferidas (não canceladas nem excluídas). Cada código de divergência alimenta uma regra: `CFOP_DESTINATION`→`CFOP_DESTINO`, `INTERSTATE_RATE`→`ALIQUOTA_INTERESTADUAL`, `CRT_CST`→`CRT_CST`, `ST_CFOP_CST`→`ST_CFOP_CST`, `ST_CEST_MISSING`→`ST_SEM_CEST`, `IBSCBS_MISSING`→`IBSCBS_GRUPO`. Outras divergências (como `IBSCBS_TOTALS`) não entram na auditoria. Sai **um achado por regra** no mês, com a mensagem da primeira nota como exemplo e até 15 números de nota. Objeto: a nota, quando é uma só; a empresa, quando são várias.
Ref.: `AuditEngine.java:71-78`, `:238-272`; `FiscalCheckEngine.java:48-51`.

O que cada checagem verifica hoje:
- **CFOP_DESTINO**: com `idDest` 1 o CFOP tem de começar por 1 ou 5; com 2, por 2 ou 6; com 3, por 3 ou 7 (`OperationCheck.java:34-66`).
- **ALIQUOTA_INTERESTADUAL**: só com `idDest = 2`, CST com alíquota (00, 10, 20, 70, 90), sem CSOSN, alíquota diferente de zero e UF de origem e destino conhecidas e diferentes. Alíquota esperada: 4% para origem 1, 2, 3 ou 8; 7% de SP, RJ, MG, PR, SC ou RS para N, NE, CO ou ES; 12% nos demais casos. A comparação é exata (`OperationCheck.java:73-101`).
- **CRT_CST**: CRT 1 ou 4 tem de informar CSOSN; CRT 2 ou 3 tem de informar CST (`TaxRegimeCodeCheck.java:21-43`).
- **ST_CFOP_CST**: CFOP 5401, 5402, 5403, 5405, 6401, 6402, 6403 ou 6404 exige CST 10, 30, 60, 70 ou 90, ou CSOSN 201, 202, 203, 500 ou 900 (`SubstitutionCheck.java:28-52`).
- **ST_SEM_CEST**: CST 10, 30, 60 ou 70, ou CSOSN 201, 202, 203 ou 500, com CEST vazio (`SubstitutionCheck.java:53-59`).
- **IBSCBS_GRUPO**: modelo 55 ou 65, CRT 3, emissão em 2026 ou depois, nota com itens e nenhum item com o grupo IBS/CBS (`IbsCbsDocumentCheck.java:37-62`).
- Itens sem o detalhe tributário (importados antes desse campo existir) são pulados nas checagens de CRT e de ST.

**RN-11 `NFCE_CANCELAMENTO`.** NFC-e (modelo 65) emitidas no mês. Precisa de pelo menos 20 notas e ao menos 1 cancelada. Taxa = canceladas / total, com 4 casas decimais (HALF_UP). Achado se a taxa for **maior que 5%**, com a taxa em % (1 casa) no título.
Ref.: `AuditEngine.java:67-68`, `:296-308`.

**RN-12 `FATURAMENTO_ATIPICO`.** Usa a apuração do mês (`TaxAssessmentService.assess`). Atual = receita líquida do mês. Histórico = receitas líquidas positivas dos outros meses do `history` da apuração. Precisa de pelo menos 3 meses de histórico e receita atual positiva. Média = soma / quantidade, com 2 casas (HALF_UP). Achado se **atual > 3 × média**; o título traz a razão com 1 casa. Se a apuração falhar, nenhuma anomalia baseada nela é gerada (só a de NFC-e).
Ref.: `AuditEngine.java:65-66`, `:276-285`, `:310-330`.

**RN-13 `RBT12_SUBLIMITE` e `RBT12_LIMITE`.** Repassam os alertas da apuração do mês, no máximo um achado por regra, com o RBT12 no detalhe:
- `SIMPLES_NEAR_SUBLIMIT` (RBT12 ≥ 80% de R$ 3.600.000) e `SIMPLES_SUBLIMIT` (> R$ 3.600.000) → `RBT12_SUBLIMITE`;
- `SIMPLES_CEILING` (> R$ 4.800.000), `MEI_NEAR_LIMIT` (faturamento do ano ≥ 80% de R$ 81.000), `MEI_EXCESS` (> R$ 81.000) e `MEI_EXCLUSION` (> R$ 97.200) → `RBT12_LIMITE`.
Ref.: `AuditEngine.java:80-86`, `:286-293`; `TaxAssessmentCalculator.java:28-33`, `:211-243`.

**RN-14 Achado vira pendência.** Cada achado vira uma pendência `AUDIT_FINDING` com a severidade da regra, competência `AAAA-MM`, chave `AUDIT:<código>:<AAAA-MM>[:<sufixo>]` (cortada em 150 caracteres), título com o prefixo da RN-03 e detalhe = detalhe do achado + "Regra: <título> (<fundamento>)." A sincronização é `syncScoped` (só `AUDIT_FINDING` daquela competência): achado novo abre, achado repetido atualiza, achado que sumiu fecha sozinho, e achado desconsiderado com a causa presente continua fechado (mesmas regras da spec de pendências, RN-04/RN-05).
Ref.: `AuditService.java:156-167`, `:281-290`; `PendencyService.java:96-106`.

**RN-15 Registro da execução.** Um `audit_runs` por empresa e competência (upsert): `ran_at`, `ran_by` (usuário ou "sistema"), `findings` (achados após o filtro da RN-04) e `notes_checked`.
Ref.: `AuditService.java:168-180`; `V44__AuditRuns.java`.

**RN-16 Competência.** Parâmetro `competencia` no formato `AAAA-MM`; vazio = mês anterior ao atual; inválido → 400 "Competência inválida: use AAAA-MM."
Ref.: `AuditService.java:317-327`.

**RN-17 Relatório.** Lê as pendências `AUDIT_FINDING` não excluídas da empresa e competência. Ordem: abertas primeiro, depois severidade (ALTA→INFO), depois código da regra. Traz os dados da última execução (ou `null` se nunca auditada). O código da regra é extraído da chave natural.
Ref.: `AuditService.java:183-203`, `:292-301`.

**RN-18 Laudo.** 
- **CSV**: UTF-8 com BOM, separador `;`, textos entre aspas. Colunas: Empresa; CNPJ; Competência; Grupo; Regra; Severidade; Situação da regra (conferida/a conferir); Achado; Detalhe; Situação.
- **PDF** (A4): título, empresa, CNPJ e competência, linha "Auditado em ... por ... - N nota(s) do mês, M achado(s)" ou "Competência ainda não auditada.", aviso de que achado é indício, tabela (Severidade, Regra com "(a conferir)", Achado, Situação) ou "Nenhum achado na competência." e, no fim, "Fundamentos das regras" só das regras com achado.
O front só habilita os botões quando já existe execução.
Ref.: `AuditService.java:207-277`; `audit.component.ts:103-106`.

**RN-19 Job diário.** Cron `audit.cron` (padrão `0 30 5 * * *`), em thread virtual, sem duas execuções em paralelo. Audita a competência anterior e a atual de cada empresa não excluída e ativa, **de todos os escritórios**, cuja assinatura vigente tenha o serviço `INVOICE_AUDIT`. Falha numa empresa ou mês é logada e não para as outras. A execução manual (`/executar`) **não** confere o plano.
Ref.: `AuditScheduler.java:19-79`.

**RN-20 Acesso.** Relatório, laudos e lista de regras exigem `INVOICES_VIEW`; rodar exige `INVOICES_AUDIT`; conferir e ativar exigem a RN-02. O acesso à empresa é validado em toda chamada com `companyId` (`CompanyValidation.checkExist`). Para resolver, desconsiderar ou reabrir um achado, a tela usa os endpoints de pendências (`INVOICES_AUDIT`, com comentário obrigatório).
Ref.: `AuditResource.java:16-71`; `AuditService.java:309-315`; `audit.component.ts:213-231`.

## Critérios de aceite (comportamento atual)
- **CA-01 (RN-01)** Dado um banco sem `audit_rules`, quando chamo `GET /auditoria/regras`, então recebo as 14 regras na ordem do catálogo, ativas e não conferidas, e as linhas passam a existir no banco.
- **CA-02 (RN-02)** Dado um ADMIN de um escritório com `FISCAL_RULES_MANAGE` cujo login não está em `PLATFORM_ADMIN_LOGINS`, quando confere uma regra, então recebe 403 e a regra não muda.
- **CA-03 (RN-02)** Dado `PLATFORM_ADMIN_LOGINS` vazio, quando qualquer usuário tenta ativar ou desativar uma regra, então recebe 403.
- **CA-04 (RN-03)** Dado a regra `NUMERACAO_LACUNA` não conferida e uma lacuna no mês, quando a auditoria roda, então o achado se chama "A conferir: NF-e série 1: ..." e o detalhe termina com "Regra ainda não conferida pelo time fiscal.".
- **CA-05 (RN-03)** Dado a mesma regra conferida, quando a auditoria roda de novo, então o achado passa a "Indício: ..." (mesma chave, mesma pendência).
- **CA-06 (RN-04)** Dado um achado aberto de `NFCE_CANCELAMENTO`, quando a regra é desligada e a auditoria roda, então o achado fica RESOLVIDO automaticamente.
- **CA-07 (RN-04)** Dado a competência 2025-12, quando a auditoria roda, então `IBSCBS_GRUPO` não gera achado (vigência a partir de 01/01/2026).
- **CA-08 (RN-06)** Dado as NF-e série 1 de nº 100, 101 e 104 no mês, quando a auditoria roda, então existe um achado "2 número(s) sem nota entre 100 e 104" listando 102 e 103.
- **CA-09 (RN-07)** Dado duas NF-e série 1 nº 200 com chaves diferentes, então existe o achado ALTA `NUMERO_DUPLICADO`.
- **CA-10 (RN-08)** Dado uma devolução emitida (finNFe 4) sem `refNFe`, então existe o achado "Devolução nº X sem nota referenciada".
- **CA-11 (RN-10)** Dado três notas emitidas com CFOP 6102 em operação interna (idDest 1), então existe **um** achado `CFOP_DESTINO` "3 nota(s) emitida(s): ..." apontando para a empresa.
- **CA-12 (RN-10)** Dado uma nota cancelada com CFOP incompatível, então ela não gera achado de coerência.
- **CA-13 (RN-14)** Dado um achado desconsiderado com comentário, quando o job diário roda e a causa continua, então o achado continua DESCONSIDERADO.
- **CA-14 (RN-16)** Dado `competencia=2026/10`, quando peço o relatório, então recebo 400 "Competência inválida: use AAAA-MM."
- **CA-15 (RN-19)** Dado uma empresa ativa sem `INVOICE_AUDIT` no plano, quando o job roda, então ela não é auditada. Rodar à mão funciona.
- **CA-16 (RN-18)** Dado uma competência nunca auditada, quando abro a tela, então os botões "Laudo PDF" e "Exportar CSV" estão desabilitados.

## Casos de teste (exemplos fictícios)
| # | Regra | Entrada | Cálculo | Resultado |
|---|---|---|---|---|
| 1 | NFCE_CANCELAMENTO | 40 NFC-e, 3 canceladas | 3/40 = 0,0750 > 0,05 | Achado "3 de 40 NFC-e canceladas no mês (7,5%)" |
| 2 | NFCE_CANCELAMENTO | 40 NFC-e, 2 canceladas | 2/40 = 0,0500, não é > 0,05 | Sem achado |
| 3 | NFCE_CANCELAMENTO | 19 NFC-e, 5 canceladas | menos de 20 notas | Sem achado |
| 4 | FATURAMENTO_ATIPICO | Mês R$ 400.000,00; histórico 100.000, 110.000, 90.000 | média 100.000,00; 3 × média = 300.000; 400.000 > 300.000 | Achado "Faturamento de R$ 400.000,00 - 4,0 vezes a média" |
| 5 | FATURAMENTO_ATIPICO | Mês R$ 300.000,00, mesmo histórico | 300.000 não é > 300.000 | Sem achado |
| 6 | FATURAMENTO_ATIPICO | Só 2 meses de histórico | menos de 3 meses | Sem achado |
| 7 | ALIQUOTA_INTERESTADUAL | Emitente SP, destinatário PE, origem 0, CST 00, 12% | esperado 7% | Achado ALTA |
| 8 | ALIQUOTA_INTERESTADUAL | Emitente PE, destinatário SP, origem 0, CST 00, 12% | esperado 12% | Sem achado |
| 9 | ALIQUOTA_INTERESTADUAL | Emitente PE, destinatário BA, origem 1, CST 00, 12% | esperado 4% (importado) | Achado |
| 10 | ST_SEM_CEST | Item com CSOSN 500 sem CEST | — | Achado |
| 11 | ST_SEM_CEST | Item com CST 90 sem CEST | 90 não exige CEST | Sem achado |
| 12 | RBT12_SUBLIMITE | RBT12 R$ 2.900.000,00 | ≥ 80% de 3,6 mi (2.880.000) | Achado (alerta SIMPLES_NEAR_SUBLIMIT) |
| 13 | NOTA_SO_RESUMO | Resumo autorizado criado há 10 dias | menos de 15 dias | Sem achado |
| 14 | NUMERACAO_LACUNA | nº 100 autorizada, 101 cancelada, 102 autorizada | a cancelada conta como usada | Sem lacuna |

## Endpoints da API
Base `/api/v1/auditoria` (classe com `@RequiresPermission(INVOICES_VIEW)`).

| Método | Caminho | Permissão | O que faz |
|---|---|---|---|
| GET | `/?companyId&competencia` | INVOICES_VIEW | Relatório (RN-17) |
| POST | `/executar?companyId&competencia` | INVOICES_AUDIT | Audita agora e devolve o relatório |
| GET | `/laudo.pdf?companyId&competencia` | INVOICES_VIEW | PDF (attachment `laudo-auditoria-<comp>.pdf`) |
| GET | `/laudo.csv?companyId&competencia` | INVOICES_VIEW | CSV (attachment) |
| GET | `/regras` | INVOICES_VIEW | Catálogo (RN-01) |
| POST | `/regras/{code}/conferencia?conferida=true\|false` | FISCAL_RULES_MANAGE + admin da plataforma | Conferir ou desconferir |
| POST | `/regras/{code}/ativa?ativa=true\|false` | FISCAL_RULES_MANAGE + admin da plataforma | Ligar ou desligar |

Fechar e reabrir um achado: `POST /api/v1/pendencias/{id}/resolver|desconsiderar|reabrir` (ver a spec de pendências).

## Telas
`/auditoria` (`AuditComponent`, guard `INVOICES_VIEW`; atalho na home e no menu):
- busca de empresa e navegação por mês (começa no mês anterior);
- botões "Rodar auditoria" (`INVOICES_AUDIT`), "Laudo PDF" e "Exportar CSV" (habilitados só com execução);
- contadores por grupo e severidade dos achados abertos, filtro por grupo e "mostrar fechados";
- tabela de achados (Severidade, Achado, Ações): abrir a nota quando o objeto é uma nota, resolver, desconsiderar com comentário e reabrir;
- visão "Regras": Regra, Fundamento (com link), Situação (conferida/a conferir, ativa/inativa) e Ações (conferir e ativar só para o admin da plataforma).

## Dados e integrações
- **`audit_rules`** (V43): `code` (único), `title`, `rule_group`, `severity`, `legal_basis`, `link`, `valid_from`, `valid_until`, `enabled`, `reviewed_by`, `reviewed_at`, `deleted` + colunas de auditoria. **Global**: não tem coluna de escritório.
- **`audit_runs`** (V44): `company_id`, `competence`, `ran_at`, `ran_by`, `findings`, `notes_checked`; único `(company_id, competence)`.
- **`pendencies`**: achados com `type = AUDIT_FINDING`.
- Lê: `fiscal_xml_documents` e itens, XML no storage (para `refNFe`), resumos da SEFAZ, regras por produto e alíquotas de IBS/CBS (contexto do motor de conferência) e a apuração do mês.

## Configuração
| Chave (env) | Padrão | Efeito |
|---|---|---|
| `audit.enabled` (`AUDIT_ENABLED`) | `true` | Liga o job |
| `audit.cron` (`AUDIT_CRON`) | `0 30 5 * * *` | Todo dia às 05:30 |
| `taxify.platform.admin-logins` (`PLATFORM_ADMIN_LOGINS`) | vazio | Logins da CS Hub que alteram as regras gerais |

Constantes no código (`AuditEngine.java:62-68`): 15 itens de evidência, 100 devoluções lidas, 15 dias de carência para o resumo, fator de 3×, mínimo de 3 meses de histórico, 5% de cancelamento e mínimo de 20 NFC-e.

## Testes existentes
- `AuditEngineTest`: mês limpo; lacunas e números repetidos por série; devolução sem original; entradas só com resumo; checagens de item viram um achado por regra; canceladas não são checadas; anomalias (faturamento, NFC-e e limites do Simples).
- `AuditServiceTest`: catálogo criado no primeiro uso; regra não conferida dá "a conferir" e conferida dá "indício"; regra desligada ou fora da vigência fica de fora; conferência grava quem e quando; só o admin da plataforma altera as regras.
- `PlatformAdminServiceTest`: só os admins listados da CS Hub; outro escritório não altera; lista vazia não deixa ninguém.

**Lacunas:** sem teste do `AuditScheduler` (filtro por plano, dois meses), do PDF/CSV, do `AuditResource`, dos limites exatos (5% e 3×), do corte de 150 caracteres da chave e do front.

## Fora do escopo / limitações conhecidas
- Lacunas de numeração só **dentro do mês**: uma lacuna entre o último número do mês anterior e o primeiro do mês não é vista. Notas inutilizadas não são lidas, então toda inutilização aparece como lacuna.
- Coerência só nas notas **emitidas** pela empresa; as entradas não passam pelas regras 5 a 10.
- `valid_until` existe na tabela, mas não há tela nem endpoint para preenchê-lo (nem para `valid_from` depois da criação).
- Mudanças no código (título, severidade, fundamento) não chegam às regras já gravadas (RN-01).
- `GET /regras` e `GET /auditoria` podem gravar no banco (criam o catálogo).
- Sem regras por perfil de empresa (Simples, tecidos, PROIND): todas valem para todos, com exceção das condições das próprias checagens (CRT, ano).
- Fronteira PE, DIFAL e retenções não fazem parte da auditoria.
- O fundamento legal é texto livre do código e não foi validado pelo time Fiscal (por isso existe o "a conferir").

## Perguntas em aberto (para o Tony)
1. Validar o fundamento legal de cada uma das 14 regras do catálogo, em especial o RICMS-PE arts. 324 a 343 citado em `ST_CFOP_CST`, e as NTs citadas em `NOTA_SO_RESUMO` e `IBSCBS_GRUPO`.
2. Os limiares das anomalias (3× a média com pelo menos 3 meses; NFC-e > 5% com pelo menos 20 notas; 15 dias de carência para o resumo) têm alguma fonte ou critério do time Fiscal, ou são provisórios?
3. Nota cancelada deve contar como número usado na lacuna? E nota inutilizada deveria ser importada para não gerar lacuna falsa?
4. A tabela de alíquota interestadual (4%, 7% e 12%) cobre as exceções que o time usa (por exemplo, produtos com regra própria)? Hoje a comparação é exata e só para CST 00, 10, 20, 70 e 90.
5. Um achado "a conferir" deveria ter severidade menor que o mesmo achado de regra conferida? Hoje é igual.
6. Os limites do MEI (R$ 81.000 e R$ 97.200) e o critério de "perto" (80%) usados pela apuração estão corretos para 2026?
7. Regras por perfil de empresa (Simples, tecidos, PROIND) devem existir na auditoria, ou ela continua igual para todos?
8. A auditoria manual deve exigir `INVOICE_AUDIT` no plano, como o job?
