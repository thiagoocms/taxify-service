# Planos, assinaturas, serviços liberados e cobrança (as-built)

> Spec retroativa. Descreve o que **já está no código** em 2026-11-26, não uma feature nova.
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend).
> Pacote base do backend: `br.com.cshub.taxify` (abreviado `…`).
> Depende de [2026-11-26-plataforma-acesso-e-empresas.md](2026-11-26-plataforma-acesso-e-empresas.md) (tenant, carteira, permissões).

## Status

**Implementado.**

- Migrations: `V8__Plans`, `V9__PlanServices`, `V10__Subscriptions`, `V11__BillingCharges`, `V12__ExternalLinks`,
  `V13__FirmIntegrations` (estado dos jobs e tokens), `V21__PlansAccountingFirm` (o plano passa a ser do escritório),
  `V30__PlanImportSources` (a fonte de importação passa a vir do plano).
- Integração de cobrança: Conta Azul (OAuth2). A conexão em si está em
  [2026-11-26-integracoes-do-escritorio.md](2026-11-26-integracoes-do-escritorio.md).
- Commit de referência: `1aef0d8` (memória fixa da EC2, que afeta o pool de jobs). Os demais commits deste módulo são
  anteriores ao histórico recente.

## Problema e objetivo

Cada escritório vende pacotes de serviço para os clientes. O Taxify precisa:

1. **Saber o que cada empresa pode usar.** O plano lista serviços (`PlanServiceEnum`), e a assinatura vigente da
   empresa liga ou desliga as rotinas automáticas (importação SIEG/SEFAZ, manifestação, NFS-e nacional, auditoria).
2. **Controlar contrato e honorário.** Valor negociado, ciclo, dia de vencimento e situação, digitados no Taxify
   (MANUAL) ou trazidos de um sistema de cobrança (Conta Azul).
3. **Mostrar a situação financeira da empresa.** Cobranças em aberto, vencidas e pagas, sem abrir o sistema de cobrança.

Medida possível com o que já existe: o resultado da sincronização (`BillingSyncResultDTO`) traz empresas vinculadas, não
encontradas, sem assinatura, cobranças sincronizadas e erros. A meta seria zerar "não encontradas" e "sem assinatura".

## Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| ADMIN | Monta o catálogo de planos, escolhe o provedor de cobrança e as categorias de honorário | Implantação e ajustes |
| Financeiro (perfil FINANCIAL) | Cria e edita assinaturas, lança cobranças manuais, corrige o vínculo com o cliente do provedor e dispara a sincronização | Semanal ou mensal |
| Gestão (MANAGEMENT) | Consulta planos, assinaturas e cobranças | Eventual |
| Agendador | Sincroniza a cobrança de todos os escritórios integrados | Diário, às 03:30 |

## Histórias de usuário

- H1. Como ADMIN, quero cadastrar planos com os serviços incluídos, preço de tabela e ciclo, para padronizar o que vendo.
- H2. Como Financeiro, quero atribuir um plano a uma empresa por um período, para liberar as rotinas do plano só durante o contrato.
- H3. Como Financeiro, quero ser impedido de criar duas assinaturas sobrepostas para a mesma empresa, para não haver dúvida sobre qual plano vale.
- H4. Como ADMIN, quero escolher de onde vêm contratos e honorários (Manual ou Conta Azul) e quais categorias de receita são honorário, para que a sincronização traga só o que interessa.
- H5. Como Financeiro, quero sincronizar com a Conta Azul em segundo plano e ver o resumo, para saber quem ficou sem vínculo ou sem assinatura.
- H6. Como Financeiro, quero vincular à mão uma empresa ao cliente da Conta Azul (por ID ou por outro CPF/CNPJ), para resolver os casos que a busca automática pelo CNPJ não acha.
- H7. Como Financeiro, quero lançar cobranças manuais com status calculado sozinho, para controlar honorários de escritórios sem integração.
- H8. Como Financeiro, quero ver o total em aberto e vencido de cada empresa, para fazer a cobrança.

## Regras de negócio

### Planos e serviços

- **RN-01 Catálogo por escritório.** Cada plano pertence a um escritório (`plans.accounting_firm_id`). Na criação, o
  escritório vem do usuário logado. Plano de outro escritório responde 404, inclusive quando uma assinatura aponta para
  ele. Fonte: `validation/PlanValidation.java:36-47`, `service/PlanService.java:22-26`.
- **RN-02 Migração do catálogo compartilhado.** A V21 atribuiu cada plano antigo ao escritório cujas empresas o
  assinavam. Um plano usado por vários escritórios foi copiado, uma cópia por escritório, e as assinaturas passaram a
  apontar para a cópia. Planos sem assinatura foram para o único escritório, se havia só um. Caso contrário ficaram sem
  escritório, ocultos, com um aviso no log. Fonte: `db/migration/V21__PlansAccountingFirm.java:27-71`.
- **RN-03 Acesso aos planos.** Leitura: `PLANS_VIEW`. Escrita: ADMIN. Fonte: `rest/PlanResource.java:17-18`.
- **RN-04 Serviços do plano.** `FISCAL_XML_IMPORT`, `INVOICE_AUDIT`, `SIEG_INTEGRATION`, `MULTI_USER_ACCESS`, `REPORTS`,
  `SEFAZ_INTEGRATION`, `SEFAZ_MANIFESTATION`, `NFSE_NATIONAL_INTEGRATION`. Fonte: `enumerated/PlanServiceEnum.java:8-22`.
- **RN-05 Importação depende de duas chaves.** Uma empresa importa notas de uma fonte só se o plano tiver
  `FISCAL_XML_IMPORT` **e** a integração da fonte (`SIEG_INTEGRATION` ou `SEFAZ_INTEGRATION`).
  A manifestação exige ainda `SEFAZ_MANIFESTATION` além da importação SEFAZ. Fonte: `enumerated/PlanServiceEnum.java:25-32`.
- **RN-06 Migração das fontes.** A V30 acrescentou `SIEG_INTEGRATION` a todo plano que tinha `FISCAL_XML_IMPORT` sem
  nenhuma fonte, para nenhuma empresa parar de receber notas. Fonte: `db/migration/V30__PlanImportSources.java:17-28`.
- **RN-07 Onde cada serviço é conferido.** Sempre pela assinatura vigente (RN-09) e pelo plano dela.

  | Serviço | Quem confere |
  |---|---|
  | FISCAL_XML_IMPORT + SIEG_INTEGRATION | `service/FiscalXmlSyncScheduler.java:143-150`, `service/SefazNotesService.java:94` |
  | FISCAL_XML_IMPORT + SEFAZ_INTEGRATION | `service/SefazDistScheduler.java:114`, `service/CompanyCertificateService.java:73-81` (botão "Capturar agora") |
  | + SEFAZ_MANIFESTATION | `service/SefazManifestService.java:179-188` |
  | NFSE_NATIONAL_INTEGRATION | `service/NfseAdnScheduler.java:100` |
  | INVOICE_AUDIT | `service/audit/AuditScheduler.java:76` |
  | FISCAL_XML_IMPORT (retificação) | `service/FiscalXmlRectificationScheduler.java:78` |
  | MULTI_USER_ACCESS, REPORTS | **ninguém** (ver P-01) |

### Assinaturas

- **RN-08 Acesso às assinaturas.** Leitura: `SUBSCRIPTIONS_VIEW`. Escrita: `SUBSCRIPTIONS_MANAGE` (ou ADMIN). As listas
  seguem a carteira do usuário. Fonte: `rest/SubscriptionResource.java:19`, `service/SubscriptionService.java:30-39`.
- **RN-09 Assinatura vigente.** É a assinatura não excluída com `startDate <= hoje <= endDate`. Se houver mais de uma, vale a
  de início mais recente. **O `status` não entra na conta.** Fonte: `repository/SubscriptionRepository.java:21-23`,
  `service/SubscriptionService.java:42-49`.
- **RN-10 Datas.** `startDate` e `endDate` são obrigatórias, e o início não pode ser depois do fim (400
  `error.subscription.invalid.dates`). Fonte: `dto/subscription/SubscriptionDTO.java:28-31`,
  `validation/SubscriptionValidation.java:97-99`.
- **RN-11 Sem sobreposição.** Para a mesma empresa, nenhuma assinatura não excluída pode ter período que se cruze com
  outro, incluindo as datas das pontas (409 `error.subscription.overlap`). Fonte:
  `validation/SubscriptionValidation.java:101-109`, `repository/SubscriptionRepository.java:14-19`.
- **RN-12 Campos.** `fee >= 0`, `billingDay` de 1 a 31, `billingCycle` (MONTHLY, QUARTERLY, SEMIANNUAL, YEARLY) e
  `status` (ACTIVE, INACTIVE, CANCELED; nulo é lido como ACTIVE). Fonte: `dto/subscription/SubscriptionDTO.java:32-37`,
  `domain/subscription/Subscription.java:158-160`.
- **RN-13 Assinatura sincronizada é do provedor.** Com `source` diferente de MANUAL, a edição manual não altera `fee`,
  `billingDay` nem `status`. `externalContract*`, `nextDueDate`, `lastSyncedAt` e `syncWarning` são sempre somente
  leitura. Plano, período e ciclo continuam editáveis. Fonte: `mapper/SubscriptionMapper.java:54-68`,
  `domain/subscription/Subscription.java:170-173`.

### Configuração da cobrança

- **RN-14 Provedor do escritório.** `accounting_firms.billing_provider` pode ser MANUAL (nulo é lido como MANUAL) ou
  CONTA_AZUL. Fonte: `domain/accountingfirm/AccountingFirm.java:43-47` e `:72-74`, `enumerated/BillingProviderType.java`.
- **RN-15 Salvar configuração.** `PUT /billing/settings` (ADMIN) grava o provedor e, para provedor integrado, a lista de
  categorias de receita que contam como honorário. Lista vazia significa todas as receitas do cliente. Provedor sem
  implementação responde 400 `error.billing.provider.not.connected`. Fonte: `service/BillingService.java:74-90`,
  `domain/billing/FirmIntegration.java:86`.
- **RN-16 Exigência de conexão.** Sincronizar, listar categorias e buscar o vínculo por documento exigem provedor
  integrado **e conectado**. MANUAL responde 400 `error.billing.provider.manual`, e sem conexão a resposta é 400
  `error.billing.provider.not.connected`. Fonte: `integration/billing/BillingProviderRegistry.java:32-39`.

### Sincronização (Conta Azul)

- **RN-17 Execução em segundo plano.** `POST /billing/sync` devolve 202 com o status do job. Não roda dois jobs ao mesmo
  tempo para o mesmo escritório e tipo de integração (claim atômico no banco). Um job sem heartbeat há mais de 5 min é
  considerado abandonado: aparece como FAILED com a mensagem de reinício e pode ser iniciado de novo. O pool de jobs tem
  de 2 a 4 threads e fila de 200. Fonte: `service/BillingSyncService.java:107-116`,
  `service/job/IntegrationJobRunner.java:49-86` e `:111-124`, `config/IntegrationJobConfig.java:17-27`.
- **RN-18 Uma execução.** São processadas todas as empresas não excluídas do escritório, 3 em paralelo. Fonte:
  `service/BillingSyncService.java:69` e `:133-172`.
  1. **Vínculo:** uma empresa sem `ExternalLink` é procurada no provedor pelo CNPJ/CPF, com e sem máscara, e deve haver
     igualdade exata de dígitos. Se não achar, entra em `unmatched`. Fonte: `service/BillingSyncService.java:175-189`,
     `integration/contaazul/ContaAzulBillingProvider.java:65-81`.
  2. **Contrato:** sem assinatura vigente, a empresa entra em `withoutSubscription`. Com assinatura vigente, os
     contratos do cliente são lidos (início desde 2000-01-01 até hoje + 10 anos). Fonte:
     `service/BillingSyncService.java:190-201`, `integration/contaazul/ContaAzulBillingProvider.java:84-98`.
  3. **Cobranças:** são lidas as contas a receber dos clientes vinculados nas categorias de honorário, com vencimento
     do 1º dia de 13 meses atrás até hoje + 3 meses, em janelas de até 365 dias e lotes de 40 clientes. Na execução
     incremental, só o que mudou desde a última sincronização. Fonte: `service/BillingSyncService.java:71-73` e
     `:204-228`, `integration/contaazul/ContaAzulBillingProvider.java:101-137`.
  4. **Honorário:** `fee` passa a ser o valor da cobrança mais recente não cancelada com vencimento até hoje + 45 dias.
     Fonte: `service/BillingSyncService.java:75` e `:230-238`.
- **RN-19 Aplicar contrato.** A assinatura vira `source = CONTA_AZUL` e recebe `lastSyncedAt`. Entre os contratos
  ATIVOS, vence o de início mais recente: copia id, número, `status = ACTIVE`, `nextDueDate` e `billingDay` (o dia do
  próximo vencimento). Valor e ciclo só são copiados se vierem do provedor, e a Conta Azul não envia nenhum dos dois.
  Mais de um contrato ativo gera o aviso `billing.sync.warning.multiple.contracts`. Sem contrato ativo, a assinatura
  recebe `billing.sync.warning.no.contract` e, se houver contratos, `status = INACTIVE`. Fonte:
  `service/BillingSyncService.java:241-267`, `integration/contaazul/ContaAzulBillingProvider.java:87-96`.
- **RN-20 Gravar cobrança.** Upsert por (empresa, origem, `externalId`). A cobrança é ligada à assinatura vigente. Valor
  nulo vira 0, a descrição é cortada em 500 caracteres e `deleted` volta a false. Fonte:
  `service/BillingSyncService.java:269-289`.
- **RN-21 Status da cobrança sincronizada.** Mapa da Conta Azul: RECEBIDO/ACQUITTED = PAID;
  RECEBIDO_PARCIAL/PARTIAL = PARTIALLY_PAID; ATRASADO/OVERDUE = OVERDUE; RENEGOCIADO = RENEGOTIATED;
  PERDIDO/LOST/CANCELADO/CANCELED = CANCELED; qualquer outro valor = OPEN. Uma cobrança OPEN ou PARTIALLY_PAID com
  vencimento antes de hoje vira OVERDUE. Fonte: `integration/contaazul/ContaAzulBillingProvider.java:160-172`,
  `service/BillingSyncService.java:292-296`.
- **RN-22 Marca de sincronizado.** `last_sync_at` só é gravado se a execução terminar **sem erros**. Um erro numa
  empresa não desfaz as outras, porque cada gravação faz commit próprio. Fonte: `service/BillingSyncService.java:165-167`
  e `:344-351`.
- **RN-23 Agendador.** Roda pelo cron `BILLING_SYNC_CRON` (padrão `0 30 3 * * *`) em modo incremental, para todo
  escritório não excluído com provedor diferente de MANUAL. Se falhar ao iniciar um escritório, registra no log e passa
  para o próximo. Fonte: `service/BillingSyncScheduler.java:33-48`.

### Vínculo com o cliente do provedor

- **RN-24 Um vínculo por empresa e provedor.** `GET` responde 204 quando não há vínculo ou o escritório é MANUAL.
  `PUT` grava `externalId` e `externalName` informados. `POST /lookup` procura no provedor por outro CPF/CNPJ e vincula
  (400 `error.billing.link.customer.not.found`). `DELETE` faz exclusão lógica. Com escritório MANUAL, `PUT` e `DELETE`
  respondem 400 `error.billing.provider.manual`. Leitura: `BILLING_VIEW`. Escrita: `BILLING_MANAGE`.
  Fonte: `rest/ExternalLinkResource.java:23-55`, `service/ExternalLinkService.java:44-95`.

### Cobranças manuais e visão da empresa

- **RN-25 Acesso.** `/billing/charges`: leitura `BILLING_VIEW`, escrita `BILLING_MANAGE`, limitado à carteira.
  Fonte: `rest/BillingChargeResource.java:16`, `service/BillingChargeService.java:24-31`.
- **RN-26 Só a manual é editável.** Criar sempre grava `source = MANUAL`, sem `externalId`. Editar ou excluir uma
  cobrança sincronizada responde 400 `error.billing.charge.synced`. Uma edição não muda a empresa nem a origem. Fonte:
  `validation/BillingChargeValidation.java:52-76`, `service/BillingChargeService.java:34-38`,
  `mapper/BillingChargeMapper.java:48-57`.
- **RN-27 Campos da cobrança manual.** `companyId`, `dueDate` e `amount > 0` são obrigatórios, `paidAmount >= 0` e a
  descrição tem até 500 caracteres. A assinatura informada tem de ser da mesma empresa (400
  `error.billing.charge.subscription.company`). Fonte: `dto/billing/BillingChargeDTO.java:14-35`,
  `validation/BillingChargeValidation.java:79-89`.
- **RN-28 Valores e status calculados.** `paidAmount` nulo vira 0, e pago maior que o valor responde 400
  `error.billing.charge.overpaid`. `openAmount = amount - paidAmount`, sem arredondamento extra (o banco guarda
  `decimal(15,2)`). O status segue esta ordem: CANCELED, se pedido; PAID, se o aberto for 0; OVERDUE, se o vencimento
  for antes de hoje; PARTIALLY_PAID, se houver algo pago; OPEN nos demais casos. O status da requisição só é aceito se
  for CANCELED. Fonte: `validation/BillingChargeValidation.java:93-114`.
- **RN-29 Visão da empresa.** `GET /billing/companies/{id}` (`BILLING_VIEW`) devolve o provedor, o vínculo, o total em
  aberto (soma de `openAmount` das OPEN e PARTIALLY_PAID), o total vencido (soma de `openAmount` das OVERDUE), a
  quantidade de vencidas e as cobranças da mais recente para a mais antiga pelo vencimento. Fonte:
  `service/BillingService.java:40` e `:101-120`.

## Critérios de aceite

Os exemplos usam valores e empresas fictícios.

- **CA-01 (RN-01)** Dado o plano P do escritório A, quando um Financeiro do escritório B cria uma assinatura com
  `planId = P`, então a resposta é 404.
- **CA-02 (RN-05, RN-07)** Dada uma empresa cujo plano vigente tem só `SIEG_INTEGRATION`, sem `FISCAL_XML_IMPORT`,
  quando o agendador do SIEG roda, então a empresa fica fora da rodada.
- **CA-03 (RN-05)** Dado um plano com `FISCAL_XML_IMPORT` e `SEFAZ_MANIFESTATION` mas sem `SEFAZ_INTEGRATION`, quando
  se pergunta se a empresa manifesta, então a resposta é não.
- **CA-04 (RN-09)** Dadas as assinaturas S1 (2026-01-01 a 2026-12-31) e S2 (2027-01-01 a 2027-12-31), quando
  `GET /subscriptions/current?companyId=…` é chamado em 2026-11-26, então volta S1. Sem assinatura vigente, a
  resposta é 404 `error.subscription.not.found`.
- **CA-05 (RN-09)** Dada uma assinatura vigente com `status = INACTIVE`, quando o agendador do SIEG roda, então a
  empresa **continua** com direito, porque o status não é conferido. Esse é o comportamento atual (ver P-02).
- **CA-06 (RN-11)** Dada S1 de 2026-01-01 a 2026-06-30, quando se cria S2 de 2026-06-30 a 2026-12-31 para a mesma
  empresa, então a resposta é 409 `error.subscription.overlap`. Com S2 a partir de 2026-07-01, a assinatura é criada.
- **CA-07 (RN-13)** Dada uma assinatura sincronizada com `fee = 350,00`, quando o Financeiro a edita enviando
  `fee = 200,00`, então o `fee` gravado continua 350,00, e o novo período é gravado.
- **CA-08 (RN-18, RN-19)** Dada a empresa de CNPJ 11.111.111/0001-11 sem vínculo e um cliente da Conta Azul com
  documento "11.111.111/0001-11" e dois contratos ATIVOS (iniciados em 2025-03-01 e 2026-02-01), quando a sincronização
  roda, então a empresa fica vinculada, a assinatura vigente recebe o contrato de 2026-02-01 e `syncWarning` avisa
  "2 contratos ativos".
- **CA-09 (RN-18)** Dadas as cobranças de 300,00 (vencimento em 2026-11-10) e 320,00 (vencimento em 2026-12-10) e hoje
  igual a 2026-11-26, quando a sincronização termina, então `fee = 320,00`, porque 2026-12-10 está dentro de hoje + 45
  dias.
- **CA-10 (RN-21)** Dada uma conta a receber "EM_ABERTO" vencida em 2026-11-20 e hoje igual a 2026-11-26, quando ela é
  sincronizada, então o status gravado é OVERDUE.
- **CA-11 (RN-22)** Dada uma execução com erro em uma das 30 empresas, quando ela termina, então as outras 29 ficam
  gravadas, o erro aparece em `errors` e `last_sync_at` não muda.
- **CA-12 (RN-17)** Dado um job de cobrança em andamento, quando outro usuário clica em "Sincronizar", então recebe 202
  com o status do job em andamento, e nenhum job novo começa.
- **CA-13 (RN-26)** Dada uma cobrança com `source = CONTA_AZUL`, quando o Financeiro tenta editá-la ou excluí-la, então
  a resposta é 400 `error.billing.charge.synced`.
- **CA-14 (RN-28)** Dada uma cobrança manual de 500,00 com 200,00 pagos e vencimento em 2026-12-05 (hoje 2026-11-26),
  quando é salva, então `openAmount = 300,00` e `status = PARTIALLY_PAID`. Com vencimento em 2026-11-20, o status é
  OVERDUE. Com 500,00 pagos, PAID. Com 600,00 pagos, a resposta é 400 `error.billing.charge.overpaid`.
- **CA-15 (RN-28)** Dada uma cobrança manual enviada com `status = PAID` e nada pago, quando é salva, então o status
  gravado é calculado (OPEN ou OVERDUE), e não PAID.
- **CA-16 (RN-29)** Dadas uma cobrança OPEN com 100,00 em aberto, uma PARTIALLY_PAID com 50,00 em aberto e uma OVERDUE
  com 80,00 em aberto, quando a visão da empresa é aberta, então `openAmount = 150,00`, `overdueAmount = 80,00` e
  `overdueCount = 1`.
- **CA-17 (RN-16)** Dado um escritório MANUAL, quando alguém chama `POST /billing/sync`, então a resposta é 400
  `error.billing.provider.manual`.

## Endpoints da API

Base: `/taxify-service/api/v1`.

| Método | Caminho | Permissão |
|---|---|---|
| GET | `/plans`, `/plans/{id}` | PLANS_VIEW |
| POST, PUT, DELETE | `/plans[/{id}]` | ADMIN |
| GET | `/subscriptions`, `/subscriptions/{id}`, `/subscriptions/current?companyId=` | SUBSCRIPTIONS_VIEW |
| POST, PUT, DELETE | `/subscriptions[/{id}]` | SUBSCRIPTIONS_MANAGE |
| GET | `/billing/settings`, `/billing/categories`, `/billing/sync` | BILLING_SYNC |
| PUT | `/billing/settings` | ADMIN |
| POST | `/billing/sync` (202) | BILLING_SYNC |
| GET | `/billing/companies/{companyId}` | BILLING_VIEW |
| GET | `/billing/companies/{companyId}/link` (200 ou 204) | BILLING_VIEW |
| PUT, DELETE | `/billing/companies/{companyId}/link` | BILLING_MANAGE |
| POST | `/billing/companies/{companyId}/link/lookup` | BILLING_MANAGE |
| GET | `/billing/charges`, `/billing/charges/{id}` (filtros `companyId`, `status`, `source`) | BILLING_VIEW |
| POST, PUT, DELETE | `/billing/charges[/{id}]` | BILLING_MANAGE |

### Telas do front

| Local | Componente | Observação |
|---|---|---|
| `/cadastro/planos` | `features/plans/plan-list`, `plan-form` | `permissionGuard('PLANS_VIEW')`. "Novo plano" e as ações só aparecem para ADMIN. Rótulos dos serviços em `core/models/plan.model.ts:34-43` |
| Empresa → Assinaturas | `features/companies/company-subscriptions`, `subscription-form` | ações com `SUBSCRIPTIONS_MANAGE` |
| Empresa → Cobrança | `features/companies/company-billing` | ações com `BILLING_MANAGE` |
| Modal "Dados do escritório" → Cobrança | `features/accounting-firm/firm-integrations/firm-integrations.component.ts:217-308` | provedor, categorias, "Sincronizar" com acompanhamento (`core/services/sync-job-polling.ts`) |
| Serviços HTTP | `core/services/plan.service.ts`, `subscription.service.ts`, `billing.service.ts` | |

## Dados e integrações

| Tabela | Migration | Conteúdo |
|---|---|---|
| `plans` | V8, V21 | `name`, `description`, `list_price decimal(15,2)`, `billing_cycle`, `accounting_firm_id` |
| `plan_services` | V9 | PK (`plan_id`, `service`). Guardava ORDINAL e foi convertida para texto |
| `subscriptions` | V10 | `company_id`, `plan_id`, período, `fee decimal(15,2)`, `billing_day`, `status`, `source`, `external_contract_*`, `next_due_date`, `last_synced_at`, `sync_warning` |
| `billing_charges` | V11 | `amount/paid/open decimal(15,2)`, `due_date`, `competence_date`, `status`, `source`, `external_id`, `subscription_id` |
| `external_links` | V12 | (`company_id`, `provider`) único, `external_id`, `external_name` |
| `firm_integrations` | V13 | `fee_category_ids`, `last_sync_at` e o estado do job (`sync_status`, `sync_started_at`, `sync_heartbeat_at`, `sync_processed`, `sync_total`, `sync_result`, `sync_error`) |

Integração externa: API v2 da Conta Azul (`integration/contaazul/ContaAzulApiClient.java`), com Pessoas, Contratos,
Contas a receber e Categorias de receita. O porte neutro fica em `integration/billing/BillingProvider.java`, e um
provedor novo é só mais um bean.

## Configuração

| Variável | Padrão | Efeito |
|---|---|---|
| `BILLING_SYNC_ENABLED` | `true` | liga ou desliga o agendador |
| `BILLING_SYNC_CRON` | `0 30 3 * * *` | horário da sincronização diária incremental |
| `CONTA_AZUL_CLIENT_ID`, `CONTA_AZUL_CLIENT_SECRET`, `CONTA_AZUL_REDIRECT_URI` | vazio | sem os três, a Conta Azul fica "não configurada" |
| `CONTA_AZUL_AUTHORIZE_URL`, `CONTA_AZUL_TOKEN_URL` | URLs públicas da Conta Azul | |

## Testes existentes

- `validation/PlanValidationTest`, `mapper/PlanMapperTest`.
- `validation/SubscriptionValidationTest` (datas e sobreposição), `mapper/SubscriptionMapperTest` (proteção dos campos
  sincronizados), `search/SubscriptionSpecificationTest`.
- `validation/BillingChargeValidationTest` (status calculado e pagamento acima do valor).
- `service/BillingSyncServiceTest`: vínculo por documento com contrato e fee da última cobrança, vários contratos
  ativos com aviso, escritório manual sem nada a sincronizar, cobrança aberta vencida virando OVERDUE.
- `integration/contaazul/ContaAzulBillingProviderTest` (status, janelas de 365 dias, datas), `service/ExternalLinkServiceTest`,
  `service/job/IntegrationJobRunnerTest`.

**Lacunas de teste:**
- `BillingService` não tem teste (configuração, visão da empresa, somatórios).
- `BillingSyncScheduler` não tem teste.
- Não há teste de que um erro numa empresa não marca `last_sync_at` (RN-22).
- Não há teste de que `refreshFees` ignora cobrança CANCELED nem do limite de 45 dias.
- Não há teste da migração V21 (cópia de plano compartilhado) nem da V30.
- Não há teste de que o status da assinatura é ignorado na liberação dos serviços (RN-09), nem do contrário, se isso mudar.

## Fora do escopo / limitações conhecidas

- O Taxify não **emite** cobrança: só lê do provedor ou registra a manual.
- Não há webhook da Conta Azul. A atualização é por polling diário ou pelo botão.
- Só existe o provedor Conta Azul.
- O preço de tabela (`listPrice`) e o ciclo do plano são informativos. Nada é calculado com eles.
- `MULTI_USER_ACCESS` e `REPORTS` existem no catálogo, mas não liberam nem bloqueiam nada.
- Fronteira, certidões, retenções e pendências não são conferidos pelo plano (não há serviço para eles no enum).
- Sem assinatura vigente, as rotinas automáticas param, mas as telas e os endpoints manuais (consulta de notas,
  Fronteira etc.) continuam abertos.

## Perguntas em aberto

- **P-01 `MULTI_USER_ACCESS` e `REPORTS`.** Estão no plano e na tela, mas nenhum código confere. Devem bloquear algo
  (por exemplo, número de usuários ou a tela de relatórios) ou devem sair do catálogo?
- **P-02 Status da assinatura ignorado.** A liberação dos serviços usa só as datas (RN-09). Uma assinatura INACTIVE ou
  CANCELED, inclusive quando a Conta Azul marca o contrato como inativo, continua liberando as rotinas até `endDate`.
  É o desejado?
- **P-03 Comentário vs. regra em `BillingResource`.** O Javadoc diz "ADMIN only", mas a classe exige `BILLING_SYNC`
  (`rest/BillingResource.java:28-29`). Qual vale? Hoje o perfil FINANCIAL sincroniza.
- **P-04 `PUT /billing/settings` aceita provedor não conectado.** A validação só confere se o provedor existe
  (`BillingService.java:76`), embora a mensagem diga "não conectado". Exigir a conexão antes de salvar?
- **P-05 Vínculo excluído vs. constraint única.** `external_links` tem chave única (`company_id`, `provider`), mas o
  `DELETE` é lógico. Depois de desvincular, (a) o `PUT` ou o `lookup` criam uma linha nova e devem violar a constraint;
  (b) a sincronização volta a vincular pelo CNPJ a empresa que o usuário desvinculou de propósito e também esbarra na
  constraint. Reativar a linha excluída? Respeitar o desvínculo manual?
- **P-06 Sincronização em empresas inativas.** Todas as empresas não excluídas entram, inclusive as com
  `active = false` (ex-clientes). Filtrar?
- **P-07 Honorário pelo valor bruto da última cobrança.** `fee` passa a ser o `amount` da cobrança mais recente até
  hoje + 45 dias, mesmo que seja parcela avulsa ou reajuste pontual. A regra está certa para a CSHUB?
- **P-08 Período fixo do histórico.** A sincronização completa olha 13 meses para trás e 3 para frente, valores fixos no
  código. Precisa ser configurável?
- **P-09 Sobreposição na mesma data.** Uma assinatura que termina em 30/06 e outra que começa em 30/06 conflitam
  (CA-06). Era essa a intenção, ou o fim deveria ser exclusivo?
- **P-10 Assinatura sem empresa ou plano.** `SubscriptionValidation.checkCompany/checkPlan` aceitam relação nula sem
  erro, e `checkValidationsExtras` usa `entity.getCompany().getId()`. O `@NotBlank` do DTO segura hoje. Vale manter a
  validação duplicada no serviço?
