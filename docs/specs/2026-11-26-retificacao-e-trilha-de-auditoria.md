# Retificação de XML e trilha de auditoria das notas (spec retroativa)

## Status
**Implementado.** Spec as-built: descreve o código como está em 2026-11-26.

- Código da retificação: `service/FiscalXmlRectificationService`, `service/FiscalXmlRectificationScheduler`, `service/TaxCodeRectifier`, `service/RectificationPrecheck`, `service/ProductFiscalRuleSavedEvent`, `service/ProductFiscalRuleRectificationListener`, `integration/fiscalxml/FiscalXmlRectificationProperties`, `integration/sieg/FiscalXmlFileStorage.storeRectified`.
- Código da trilha: `domain/invoice/InvoiceAuditLog`, `enumerated/InvoiceAuditStatus`, `service/InvoiceAuditTrail`, `service/InvoiceAuditLogService`, `rest/InvoiceAuditLogResource`, `validation/InvoiceAuditLogValidation`, `mapper/InvoiceAuditLogMapper`, `search/InvoiceAuditLogSpecification`.
- Migrations: `V15__InvoiceAuditLog` (tabela e conversão do status ordinal para nome) e `V32__RecheckRectifiedNotes` (limpa `last_rectification_checked_at` das notas retificadas para refazê-las com o CFOP da operação).
- Commits: use `git log -- src/main/java/br/com/cshub/taxify/service/FiscalXmlRectificationService.java`.

Specs relacionadas: [Regras fiscais por produto](2026-11-26-regras-fiscais-por-produto.md) e [Motor de conferência fiscal](2026-11-26-motor-de-conferencia-fiscal.md).

## Problema e objetivo
Quando a nota recebida vem com CFOP, NCM ou código tributário diferente do que a empresa escritura, o analista corrige à mão antes de importar no sistema contábil. A retificação gera um **novo XML** com os campos trocados conforme a regra do produto. O original fica preservado, a exportação passa a servir a versão corrigida e cada troca fica registrada na trilha de auditoria da nota, com o "de → para" de cada campo.

A trilha também registra conferência (OK, divergente, reaberta), cancelamento na SEFAZ e anotações manuais. Ela é **só de inclusão**: nada é editado nem excluído.

Como medir (sem métrica implementada): número de notas com `rectifiedFilePath` e de registros `RECTIFIED` por período. A rotina agendada registra em log quantas notas checou, retificou e quantas falharam.

## Usuários e cenário
- **Analista fiscal** com `INVOICES_RECTIFY`: clica em "Retificar nota" ou "Retificar códigos" no detalhe da nota.
- **Rotina agendada** (sem usuário): de hora em hora por padrão da anotação, ou pelo cron configurado. Retifica as notas recebidas na janela `lookback-days`.
- **Ao salvar uma regra**: o listener retifica na hora as notas do produto (ver a spec de regras, RN-12).
- **Analista** com `INVOICES_AUDIT`: inclui um registro manual na aba "Auditoria" da nota. Quem tem `INVOICES_VIEW` consulta o histórico.

## Histórias de usuário
- Como analista fiscal, quero gerar um XML corrigido com o CFOP e o NCM da regra, mantendo o original, para importar a nota certa na contabilidade.
- Como analista fiscal, quero que os códigos tributários também sejam corrigidos quando isso não muda a estrutura do XML, para não editar o arquivo à mão.
- Como analista fiscal, quero que o sistema retifique sozinho as notas novas, para não depender de clique.
- Como analista fiscal, quero ver no histórico da nota o que foi trocado, quando e por quem, para auditar durante o paralelo com a conferência manual.
- Como analista fiscal, quero que a nota volte ao XML original quando a regra deixa de divergir, para não exportar uma correção que não vale mais.
- Como analista fiscal, quero registrar à mão que a nota está pendente de documentação, precisa de retificação pelo emissor, foi recusada ou está OK.

## Regras de negócio

### Retificação
**RN-01. Quais notas podem ser retificadas** (`FiscalXmlRectificationService.rectify`, `:101-156`):
- a nota existe e não está excluída; senão, 404 `error.entity.not.found`;
- o usuário tem acesso à empresa da nota (`assertCompanyAccess`);
- não está cancelada; senão, 400 "Esta nota foi cancelada e não pode ser retificada.";
- não foi emitida pela própria empresa; senão, 400 "...use uma carta de correção". O comentário no código diz que nota própria autorizada se corrige com CC-e;
- é NF-e (`documentType` nulo ou `NFE`, e o XML tem `infNFe`); senão, 400 "Só é possível retificar notas do tipo NFe.".

**RN-02. Pré-checagem sem ler o arquivo** (`RectificationPrecheck`, `:20-63`; `FiscalXmlRectificationService.mayNeedCorrection`, `:158-171`). O XML só é lido do storage se algum item, pelas colunas gravadas, puder mudar:
- CFOP da coluna ≠ CFOP esperado (RN-07 da spec de regras), ou NCM ≠ `ncmCorreto` quando a regra fixa NCM;
- algum código fixado na regra e presente na nota difere: CEST, origem, CSOSN (se o item usa CSOSN) ou CST de ICMS, CST de IPI, PIS e COFINS, cClassTrib.

A nota é **sempre** lida quando `metadata_version` é nulo ou menor que 3, ou quando não tem itens gravados. A pré-checagem erra para o lado do "talvez": um código que exigiria outro grupo do XML conta como "pode mudar".

**RN-03. CFOP e NCM.** Para cada `det` com `cProd` que tem regra (RN-06 da spec de regras, pelo emitente da nota):
- CFOP: troca `prod/CFOP` pelo CFOP da regra ajustado ao `ide/idDest` do próprio XML;
- NCM: troca `prod/NCM` se a regra tem NCM.

A troca só acontece quando a tag existe e o valor (com trim) difere. Nada é inserido.
Ref.: `FiscalXmlRectificationService.java:190-239`.

**RN-04. Códigos tributários: só dentro do mesmo grupo do XML** (`TaxCodeRectifier`, `:16-143`). **Alíquotas e valores nunca são alterados.** Os códigos só são trocados quando a tag existe e o código atual e o novo cabem no mesmo grupo:
| Campo | Grupo atual | Códigos aceitos no grupo |
|---|---|---|
| `prod/CEST` | — | troca livre se a tag existe |
| `ICMS*/orig` | qualquer | troca livre se a tag existe |
| CST de ICMS | ICMS40 | 40, 41, 50 |
| CST de ICMS | outros (ICMS00, ICMS20, ...) | só o próprio código, ou seja, nunca troca |
| CSOSN | ICMSSN102 | 102, 103, 300, 400 |
| CSOSN | ICMSSN202 | 202, 203 |
| CST de IPI | IPITrib | 00, 49, 50, 99 |
| CST de IPI | IPINT | 01 a 05 e 51 a 55 |
| CST de PIS/COFINS | PISAliq/COFINSAliq | 01, 02 |
| CST de PIS/COFINS | PISNT/COFINSNT | 04 a 09 |
| CST de PIS/COFINS | PISOutr/COFINSOutr | 49 ou mais |
| cClassTrib | IBSCBS | só se o CST de IBS/CBS da regra for nulo ou igual ao da nota, e o cClassTrib da regra começar pelo CST da nota |

O CST de IBS/CBS em si nunca é reescrito.

**RN-05. Texto de cada correção.** O formato é `"Item <cProd>: <campo> <de> -> <para>"`. Rótulos: `CFOP`, `NCM`, `CEST`, `orig`, `CSOSN`, `CST do ICMS`, `CST do IPI`, `CST do PIS`, `CST do COFINS`, `cClassTrib`. A nota de auditoria é `"Retificacao automatica: " + correções separadas por "; "`, cortada em **500 caracteres**.
Ref.: `FiscalXmlRectificationService.java:185-188, 237`, `TaxCodeRectifier.java:114`.

**RN-06. Arquivo retificado.**
- O arquivo é novo, com nome `<chave>-retificado-<epochMillis>.xml`, ao lado do original, que nunca é sobrescrito. Cada execução que grava gera um arquivo novo e mantém os anteriores.
- O XML ganha um comentário no início da raiz: `" Retificado por taxify em <data/hora> - N campo(s) ajustado(s) conforme regra cadastrada. ATENCAO: a assinatura digital original deste XML pode estar invalida apos esta alteracao. "`.
- A nota passa a apontar `rectified_file_path` para o novo arquivo.
- A serialização usa UTF-8 com `FEATURE_SECURE_PROCESSING`.
- **O XML não é reassinado.**

Ref.: `FiscalXmlRectificationService.java:45-71, 241-247, 269-281`, `FiscalXmlFileStorage.java:68-75`.

**RN-07. Idempotência.** Se há correções, mas o último registro `RECTIFIED` (não excluído) com a mesma chave da nota tem **exatamente o mesmo texto** (RN-05) e a nota ainda tem `rectified_file_path`, não grava arquivo nem registro. Se o ponteiro tinha sido limpo (RN-08), gera de novo.
Ref.: `FiscalXmlRectificationService.java:135-143, 173-179`.

**RN-08. Desfazer.** Se não há correção, mas a nota tinha `rectified_file_path` (a regra foi excluída ou passou a bater com a nota), o ponteiro é limpo e o sistema registra `RECTIFICATION_REVERTED` ("Retificação desfeita: a nota volta a usar o XML original"). O arquivo retificado fica no storage como histórico.
Ref.: `FiscalXmlRectificationService.java:144-150`.

**RN-09. Marca da checagem.** Toda chamada que passa da RN-01 grava `last_rectification_checked_at = agora`, haja ou não correção. Essa marca define se as RN `rectifiedUnder` do motor (CFOP/NCM não divergem mais) e da rotina agendada (RN-10) se aplicam.
Ref.: `FiscalXmlRectificationService.java:151-152`.

**RN-10. Rotina agendada** (`FiscalXmlRectificationScheduler`, `:43-118`):
- roda no cron `fiscal-xml.rectification.cron` (o default da anotação é `0 3 * * * *`) se `enabled`;
- empresas candidatas: não excluídas, com pelo menos uma regra ativa e com assinatura vigente hoje cujo plano inclui `FISCAL_XML_IMPORT`. A origem (SIEG ou SEFAZ) não importa, e uma importação SIEG incompleta não segura a empresa;
- notas: emitidas de `hoje − lookbackDays` (00:00) até hoje (23:59:59), não próprias, não canceladas e NF-e (ou tipo nulo);
- só são checadas as notas nunca checadas ou com `last_rectification_checked_at` anterior à maior `last_modified_date` das regras da empresa (inclui as excluídas, porque a exclusão lógica atualiza a data);
- uma nota ou empresa com erro não interrompe as demais. O resultado vai para o log de aplicação, sem dado da nota além do ID.

**RN-11. Retificação ao salvar a regra** (`ProductFiscalRuleRectificationListener`, `:39-67`). Roda depois do commit (ou sem transação: `fallbackExecution = true`), numa thread virtual, quando `enabled` e há código de produto. Considera as NF-e não excluídas, não próprias, não canceladas e emitidas desde `hoje − lookbackDays`, que tenham item com o código e, se a regra tem fornecedor, cujo emitente tenha aquele CNPJ ou CPF. Cada nota passa pela RN-01 a RN-09.

**RN-12. Reprocessamento do V32.** A migration limpou `last_rectification_checked_at` de todas as notas com `rectified_file_path` para que fossem refeitas com o CFOP da operação. Até a nova retificação, essas notas voltam a mostrar o CFOP emitido e a divergência.
Ref.: `V32__RecheckRectifiedNotes.java:8-26`.

**RN-13. Leitura da versão corrigida.** `GET /{id}/produtos` mostra o CFOP retificado quando a nota foi retificada com a regra atual e guarda o emitido em `cfopEmitido`. `GET /{id}/xml?retificado=true` serve o retificado quando existe, senão o original. As exportações (`/exportar`, `/exportar/zip`) servem o retificado quando existe.
Ref.: `FiscalXmlQueryService.java:211-228, 291-320`, `FiscalXmlResource.java:121-138, 197-219`.

### Trilha de auditoria
**RN-14. Registro.** `InvoiceAuditLog(company, externalInvoiceKey, status, notes, user, createdDate)`.
- `externalInvoiceKey` é a chave de acesso, ou o ID da nota quando ela não tem chave (`accessKeyOrId`).
- `user` nulo quer dizer rotina automática ou chamada sem contexto de segurança, como o listener na thread virtual.
- `notes` é obrigatório no banco, tem no máximo 500 caracteres, passa por `strip` e é cortado no limite. Se vier vazio, recebe o texto padrão do status.

Ref.: `InvoiceAuditTrail.java:10-52`, `V15__InvoiceAuditLog.java`.

**RN-15. Status e quem grava:**
| Status | Texto padrão | Quem grava |
|---|---|---|
| `OK` | Nota conferida | conferência `REVIEWED` (`FiscalXmlReviewService`) ou registro manual |
| `DIVERGENT` | Conferida com divergência | conferência `DIVERGENT` (a nota é obrigatória) |
| `REVIEW_REOPENED` | Conferência desfeita: a nota voltou para a conferir | conferência `PENDING` |
| `RECTIFIED` | Nota retificada | retificação (RN-05) |
| `RECTIFICATION_REVERTED` | Retificação desfeita: a nota volta a usar o XML original | RN-08 |
| `CANCELED` | Nota cancelada na SEFAZ | evento de cancelamento (`FiscalXmlEventService`) |
| `NEEDS_RECTIFICATION`, `PENDING_DOCUMENTATION`, `REJECTED` | Precisa de retificação / Pendente de documentação / Nota recusada | registro manual |

**RN-16. Só inclusão.** `PUT` e `DELETE` não são expostos no resource. Os métodos `update` e `delete` do serviço lançam 400 `error.auditLog.immutable`.
Ref.: `InvoiceAuditLogResource.java:23-53`, `InvoiceAuditLogService.java:30-39`.

**RN-17. Registro manual.**
- Exige `externalInvoiceKey`, `companyId`, `status` (nome do enum) e `notes` (`@NotBlank`).
- O autor é sempre o usuário logado, e o `userId` do corpo é ignorado (`InvoiceAuditLogValidation.java:44-50`).
- A empresa é validada com controle de acesso.
- O front só oferece `PENDING_DOCUMENTATION`, `NEEDS_RECTIFICATION`, `REJECTED` e `OK` (`invoice-audit-log.model.ts:25-26`), mas a API aceita qualquer status do enum.

**RN-18. Consulta.** A lista é paginada e filtra por igualdade em `externalInvoiceKey`, `status`, `userId` e `companyId`. Esconde os excluídos e fica restrita ao escritório e à carteira do usuário (`CompanyScopedCrudService`). O detalhe valida o acesso à empresa.

## Critérios de aceite
- **CA-01 (RN-01).** Dada uma nota cancelada, quando se chama `POST /retificar`, então a resposta é 400 com "Esta nota foi cancelada...". O mesmo vale para uma nota própria (mensagem da CC-e) e para um CT-e ("Só é possível retificar notas do tipo NFe.").
- **CA-02 (RN-02).** Dada uma nota com `metadata_version` 3 ou maior cujos itens já batem com as regras, quando se retifica, então o arquivo não é lido do storage, não há correção e a marca da checagem é atualizada.
- **CA-03 (RN-03, RN-05, RN-06).** Dada uma nota interestadual com item `TEC-001` com CFOP 2101 e regra 1102, quando se retifica, então um novo arquivo é gravado com CFOP 2102, o original fica intacto, `rectified_file_path` aponta para o novo e é gravado o registro `RECTIFIED` com "Retificacao automatica: Item TEC-001: CFOP 2101 -> 2102".
- **CA-04 (RN-04).** Dado um item em ICMS00 e uma regra com CST 60, quando se retifica, então o CST não muda (outro grupo). Dado um item em ICMS40 com CST 41 e regra 40, então o CST vira 40. Em nenhum caso `pICMS` ou `vICMS` mudam.
- **CA-05 (RN-07).** Dada uma nota já retificada com as mesmas correções, quando se retifica de novo, então não há arquivo nem registro novo.
- **CA-06 (RN-08).** Dada uma nota retificada cuja regra foi excluída, quando se retifica, então o ponteiro é limpo e é gravado `RECTIFICATION_REVERTED`.
- **CA-07 (RN-08, RN-07).** Dada a regra recriada igual depois da reversão, quando se retifica, então o arquivo é gerado de novo, mesmo com texto igual ao último `RECTIFIED`.
- **CA-08 (RN-10).** Dada uma empresa com regras e sem assinatura vigente com `FISCAL_XML_IMPORT`, quando a rotina roda, então a empresa fica de fora.
- **CA-09 (RN-10).** Dada uma nota checada depois da última alteração de regra, quando a rotina roda, então ela não é checada de novo.
- **CA-10 (RN-10).** Dada uma nota que falha (arquivo ilegível), quando a rotina roda, então as demais seguem.
- **CA-11 (RN-11).** Dada uma regra salva para o fornecedor A, quando o commit termina, então só as notas do fornecedor A com o produto, dentro da janela, são retificadas.
- **CA-12 (RN-16).** Dado um registro da trilha, quando se tenta alterá-lo ou excluí-lo, então não há endpoint (405/404) e o serviço recusa com 400.
- **CA-13 (RN-17).** Dado um POST com `userId` de outro usuário, quando é gravado, então o autor é o usuário logado.
- **CA-14 (RN-18).** Dado um usuário fora da carteira da empresa, quando lista ou abre registros dela, então não os vê ou recebe acesso negado.

### Casos de teste com dados fictícios
| # | Entrada | Esperado |
|---|---|---|
| T-01 | NF-e interna, item `TEC-001` com CFOP 1102 e NCM 52081100; regra 1102 sem NCM | sem correção, sem leitura do arquivo (versão 3 ou maior) |
| T-02 | Mesmo item com NCM 52081200; regra com NCM 52081100 | correção "Item TEC-001: NCM 52081200 -> 52081100" |
| T-03 | PISAliq com CST 01; regra com CST de PIS 06 | sem troca (06 é de PISNT) |
| T-04 | PISNT com CST 04; regra 06 | "Item TEC-001: CST do PIS 04 -> 06" |
| T-05 | IBSCBS com CST 000 e cClassTrib 000002; regra com CST 000 e cClassTrib 000001 | "cClassTrib 000002 -> 000001" |
| T-06 | IBSCBS com CST 000; regra com CST 200 e cClassTrib 200001 | sem troca (o CST difere) |
| T-07 | Item sem regra | ignorado |
| T-08 | Nota cancelada | 400 |
| T-09 | Nota com 40 itens divergentes (texto acima de 500 caracteres) | nota de auditoria cortada em 500 caracteres; a resposta da API traz a lista completa |
| T-10 | XML original apagado do storage | 4xx/5xx "Falha ao ler, processar ou gravar o XML durante a retificação." (`SiegIntegrationException`) |

## Endpoints da API
| Método | Caminho | Permissão | O que faz |
|---|---|---|---|
| POST | `/api/v1/integrations/xmls/{documentId}/retificar` | INVOICES_RECTIFY | RN-01 a RN-09. Devolve `{documentId, accessKey, originalFilePath, rectifiedFilePath, correcoesAplicadas[]}` |
| GET | `/api/v1/integrations/xmls/{documentId}/produtos` | INVOICES_VIEW | itens com o CFOP efetivo (RN-13) |
| GET | `/api/v1/integrations/xmls/{documentId}/xml?retificado=` | INVOICES_VIEW | XML original ou retificado |
| POST | `/api/v1/integrations/xmls/exportar`, `/exportar/zip` | INVOICES_VIEW | exportação com o retificado quando existe |
| GET | `/api/v1/invoice-audit-log?companyId=&externalInvoiceKey=&status=&page=&size=` | INVOICES_VIEW | lista |
| GET | `/api/v1/invoice-audit-log/{id}` | INVOICES_VIEW | detalhe |
| POST | `/api/v1/invoice-audit-log` | INVOICES_AUDIT | registro manual (201) |

Front (`C:\workspace\taxify-front\src\app`):
- `features/invoices/invoice-detail` (aba "Divergências"): botão "Retificar nota" quando há divergência de CFOP/NCM, e "Retificar códigos" quando só há achados `RULE_*` de códigos. Ambos só aparecem com `INVOICES_RECTIFY` e em nota não própria. Há um diálogo de confirmação ("Alíquotas e valores não são alterados. O XML original é preservado.") e o resultado aparece num modal. Depois a tela recarrega a auditoria e as divergências.
- Mesma tela, aba "Auditoria": tabela com o histórico (`getAll` com `page 0`, `size 50`, filtro pela chave de acesso) e o botão "Adicionar registro" (com `INVOICES_AUDIT`).
- Serviços: `core/services/fiscal-xml.service.ts` (`retificar`) e `core/services/invoice-audit-log.service.ts`.

## Dados
- `fiscal_xml_documents`: `xml_file_path`, `rectified_file_path`, `last_rectification_checked_at` e `metadata_version` (V23).
- `invoice_audit_log` (V15): `id`, `deleted`, auditoria, `status` varchar(30), `company_id` (FK), `user_id` (FK, anulável), `external_invoice_key` varchar(100), `notes` varchar(500) not null. O V15 converte o status antigo gravado como ordinal (OK, NEEDS_RECTIFICATION, PENDING_DOCUMENTATION, REJECTED, RECTIFIED) para o nome.
- Storage de XML (S3 em produção, prefixo `fiscal-xml`): os arquivos `*-retificado-<millis>.xml` se acumulam a cada retificação que grava.

## Configuração
| Propriedade | Env | Default da classe | `application-default.yaml` | `docker-compose.yml` |
|---|---|---|---|---|
| `fiscal-xml.rectification.enabled` | `FISCAL_XML_RECTIFICATION_ENABLED` | true | true | true |
| `fiscal-xml.rectification.cron` | `FISCAL_XML_RECTIFICATION_CRON` | "0 30 * * * *" (não é usado pela anotação, que tem default próprio "0 3 * * * *") | `0 0/1 * * * *` (a cada minuto) | `0 0/30 * * * *` |
| `fiscal-xml.rectification.lookback-days` | `FISCAL_XML_RECTIFICATION_LOOKBACK_DAYS` | 1 | 50 | 50 |

Em `application.yaml` as três propriedades vêm de variáveis de ambiente sem default.

## Testes existentes
- `FiscalXmlRectificationServiceTest`: nota inexistente, não NF-e, sem regra, item já igual, novo arquivo com CFOP e original intacto, usuário no registro, reexecução idempotente, remover a regra reverte, recriar depois da reversão retifica de novo, NCM, não lê o arquivo quando as colunas batem, reverte sem ler, lê quando difere, rejeita outro tipo sem ler.
- `TaxCodeRectifierTest`: troca no mesmo grupo e não toca código de outro grupo nem valores.
- `RectificationPrecheckTest`: item igual, CFOP ou NCM fixado diferente, NCM não fixado ignorado, CST × CSOSN, campo ausente.
- `FiscalXmlRectificationSchedulerTest`: desligado, empresa só SEFAZ, sem plano ou assinatura, SIEG incompleto não segura, retifica todas as notas, falha isolada por nota e por empresa.
- `ProductFiscalRuleRectificationListenerTest`: ver a spec de regras.
- `InvoiceAuditLogMapperTest` (incluindo `toEntityRejectsAnUnknownStatus`), `InvoiceAuditLogValidationTest`, `FiscalXmlReviewServiceTest` (conferência registra quem e quando, divergência exige nota), `FiscalXmlEventServiceTest` (cancelamento).

**Lacunas:**
- não há teste de `InvoiceAuditTrail` (corte em 500, texto padrão por status);
- não há teste de que `InvoiceAuditLogService.update/delete` recusam;
- não há teste de rejeição de nota cancelada ou própria em `rectify`;
- não há teste do corte da nota em 500 caracteres e do efeito disso na idempotência (P-03);
- não há teste do filtro `needsRectificationCheck` do scheduler com exclusão de regra;
- não há teste de integração do listener com transação real.

## Fora do escopo e limitações conhecidas
- **O XML retificado não é reassinado**: a assinatura original fica inválida, e o arquivo avisa isso no comentário. A correção válida perante o fisco continua com o emissor.
- Alíquotas, valores e totais nunca são recalculados. Uma mudança de código que exige outro grupo do XML não é feita.
- Só NF-e recebida. Nota própria se corrige por CC-e, que não é gerada pelo Taxify.
- A retificação depende do XML original no storage. Se ele foi expurgado, a retificação falha.
- A rotina e o listener só olham notas emitidas dentro de `lookback-days`. Notas mais antigas só se atualizam pelo botão.
- Os arquivos retificados se acumulam sem limpeza (um por execução que grava).
- A trilha não guarda o "antes e depois" estruturado, só o texto de até 500 caracteres.

## Perguntas em aberto
- **P-01 (comportamento e auditoria).** O listener e a rotina rodam sem contexto de segurança, então o registro `RECTIFIED` disparado ao salvar uma regra fica sem usuário e aparece como "rotina automática", embora a ação tenha partido de um analista. O registro deveria guardar quem salvou a regra?
- **P-02 (segurança da trilha).** A API aceita registro manual com qualquer status, inclusive `RECTIFIED`, `CANCELED` e `RECTIFICATION_REVERTED`, que deveriam ser só do sistema. Um `RECTIFIED` manual com o mesmo texto pode até travar a idempotência (RN-07). O backend deve restringir aos status manuais? E o `externalInvoiceKey` não é validado contra uma nota existente da empresa.
- **P-03 (inconsistência).** O texto da correção é cortado em 500 caracteres e é ele que decide a idempotência (RN-07). Duas listas de correções diferentes com o mesmo começo são tratadas como iguais, e o arquivo não é refeito. Além disso, a trilha perde as correções que passam do limite. Para auditoria no paralelo, a lista completa precisa ficar guardada?
- **P-04 (inconsistência).** A idempotência busca o último `RECTIFIED` só pela chave de acesso, sem filtrar a empresa. Se a mesma chave existir em duas empresas da plataforma (por exemplo, emitente e destinatário clientes), o registro de uma pode interferir na outra. Na prática só a destinatária é retificada, mas isso não está garantido no código.
- **P-05 (configuração).** O default do cron na anotação (`0 3 * * * *`) é diferente do default da classe de propriedades (`0 30 * * * *`), e `application-default.yaml` usa um cron de um minuto. Qual frequência vale para a EC2? O `docker-compose.aws.yml` não define essas variáveis (conferir o `.env` da EC2).
- **P-06 (comportamento).** Quando a retificação é pulada por idempotência, `correcoesAplicadas` volta preenchido e `rectifiedFilePath` volta nulo na resposta. A rotina conta essa nota como "retificada" no log. O front deve tratar esse caso como "já estava retificada"?
- **P-07 (front).** A aba "Auditoria" filtra pela chave de acesso. Notas sem chave (registradas pelo ID) nunca mostram histórico. Além disso, a lista não pede ordenação (`sort`), então a ordem cronológica não é garantida e passa de 50 registros sem paginação.
- **P-08 (LGPD e retenção).** Por quanto tempo os XMLs retificados e a trilha devem ser guardados? Hoje nada expira a trilha nem os arquivos retificados.
