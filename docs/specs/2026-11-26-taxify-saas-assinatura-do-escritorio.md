# Taxify SaaS: teste grátis, assinatura do escritório e limites por plano

> Feature nova, em 3 fases que podem ser entregues separadamente. Pedido do Tony: transformar o Taxify em SaaS.
> 1. Acesso e teste grátis.
> 2. Cobrança da assinatura do Taxify por um gateway.
> 3. Limites por plano (empresas, usuários e módulos por escritório), ligados à assinatura.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base:
> `br.com.cshub.taxify` (abreviado `…`).
> Depende de: [planos, assinaturas e cobrança](2026-11-26-planos-assinaturas-e-cobranca.md),
> [plataforma, acesso e empresas](2026-11-26-plataforma-acesso-e-empresas.md) e
> [integrações do escritório](2026-11-26-integracoes-do-escritorio.md).
>
> É **regra comercial**, não fiscal. Preços, faixas, régua de inadimplência e retenção de dados **não estão definidos
> aqui**: estão em "Perguntas em aberto". As RNs marcadas "(proposta, P-xx)" são o comportamento que eu recomendo, e
> o Tony confirma antes da implementação. Prazos e valores ainda não decididos aparecem como parâmetros
> configuráveis, nunca como número inventado.

## Status

**Fase 1 implementada e revisada (spec atualizada em 2026-11-26 com o que saiu da implementação e da revisão). Fases 2
e 3 em especificação.**
- **Fase 1:** todas as perguntas foram respondidas pelo Tony em 2026-11-26 (P1-01 a P1-16, mais as decisões 17 a 20)
  e estão nas RNs da seção 7. Nenhuma RN da fase 1 está marcada como proposta. O **go-live do cadastro público**
  (ligar `TAXIFY_SIGNUP_ENABLED=true` na EC2) continua dependendo de duas pendências que não são de código:
  - a versão real dos termos de uso, da política de privacidade e do acordo de operador (F1-RN-11);
  - o SES configurado pela CS Hub na AWS (I1-1).

  Até lá, a fase 1 pode ir para produção com o cadastro fechado.
  - **Pré-requisito de qualquer deploy da fase 1:** `PLATFORM_FIRM_ID` preenchido no `.env` da EC2. Sem ele, ninguém
    é admin da plataforma (F1-RN-04), o console fica inacessível e a isenção da CS Hub (F1-RN-12) não vale.
  - Os itens que a implementação criou e a revisão aceitou estão nas seções 6, 7, 10 e 11; os riscos conhecidos, em
    "Riscos conhecidos da implementação da fase 1".
- **Fase 2:** depende de P2-01 a P2-06 e da conta no gateway.
- **Fase 3:** depende de P3-02 a P3-08.

---

## 1. Os dois níveis de "plano" (não misturar)

| | (a) **Plano de honorário** (já existe) | (b) **Plano Taxify** (novo) |
|---|---|---|
| Quem vende a quem | O escritório vende serviços contábeis aos clientes dele | A CS Hub vende o Taxify ao escritório |
| Entidades | `Plan`, `Subscription`, `BillingCharge`, `ExternalLink` (V8-V12, V21, V30) | `PlatformPlan`, `FirmSubscription`, `PlatformInvoice` (novas) |
| A quem a assinatura se liga | `subscriptions.company_id`, ou seja, **à empresa cliente** (`domain/subscription/Subscription.java:33-35`) | `firm_subscriptions.accounting_firm_id`, ou seja, **ao escritório** |
| Quem cadastra | O ADMIN **do próprio escritório** (`rest/PlanResource.java`, `validation/PlanValidation.java:42-47`). Catálogo por escritório (V21) | O admin da plataforma (CS Hub). Catálogo global |
| Cobrança | A Conta Azul **do escritório** (OAuth na conta dele), só leitura de contratos e contas a receber dos clientes (`service/BillingSyncService.java`) | Gateway da CS Hub (Asaas ou Iugu) emite e recebe Pix, boleto e cartão |
| O que libera | As rotinas automáticas **por empresa**: captura SIEG/SEFAZ, manifestação, NFS-e, auditoria, retificação (`PlanServiceEnum`, conferido em 10 lugares) | O acesso do escritório ao Taxify (fase 1), a cobrança (fase 2) e os limites e módulos (fase 3) |

O que confirmei no código sobre (a):
- É **o escritório controlando o próprio cliente**. Como quem escreve o catálogo é o próprio ADMIN do escritório,
  hoje qualquer escritório se dá todos os serviços. (a) **não serve** como controle comercial da CS Hub.
- O status da assinatura é ignorado: valem só as datas (RN-09 da spec de cobrança). `MULTI_USER_ACCESS` e `REPORTS`
  não são conferidos em lugar nenhum.
- A sincronização Conta Azul casa empresa cliente por CNPJ, com contrato e contas a receber **do cliente do
  escritório**. Reaproveitar isso para cobrar o escritório misturaria os papéis: o escritório da CS Hub teria de ter os
  outros escritórios como "empresas" da carteira dele.

**Decisão recomendada: modelo novo**, separado de (a). O que dá para reaproveitar é só a **infraestrutura**:
- o padrão de porta neutra `integration/billing/BillingProvider` + registry, como molde para um `SaasPaymentGateway`;
- o `IntegrationJobRunner` (claim atômico, heartbeat) para a conciliação;
- o `SecretCipher` para guardar segredos;
- o `PlatformAdminService` e a regra de 404 entre tenants.

Na tela, (a) passa a se chamar **"Planos de honorário"** (P3-05).

Relação depois da fase 3:
`serviço efetivo da empresa = serviço do Plano de honorário da empresa (hoje) E módulo do Plano Taxify do escritório`.

---

## 2. Avaliação (conjunto)

### O que existe hoje e preocupa (confirmado no código)

1. **Cadastro público aberto e grátis.** `POST /users` anônimo cria um `AccountingFirm` e um ADMIN
   (`validation/UserValidation.java:66-75`, `config/SecurityConfig.java:60-62`). Sem prazo, sem limite, com todos os
   módulos, captura SEFAZ/SIEG e storage S3 pagos pela CS Hub.
2. **`profile: 'ADMIN'` montado no navegador** (`taxify-front/src/app/features/auth/auth.component.ts:286`). Hoje o
   backend já força ADMIN no cadastro anônimo (`UserValidation.java:74`), então o campo é ignorado. Mas o contrato fica
   enganoso: o mesmo endpoint `POST /users` serve ao cadastro público e ao ADMIN que cria colaborador, e uma regressão
   nessa validação vira elevação de privilégio. Correção: endpoint de cadastro próprio, sem campo de perfil (F1-RN-02).
3. **`PLATFORM_ADMIN_LOGINS=admin` por padrão** em `docker-compose.yml:97` (`${PLATFORM_ADMIN_LOGINS:-admin}`). A EC2
   sobe com `-f docker-compose.yml -f docker-compose.aws.yml`, e o `docker-compose.aws.yml` não define a variável. Se o
   `.env` da EC2 não definir, quem se cadastrar primeiro com o login `admin` vira admin da plataforma. Isso fica
   **mais grave** com esta feature, porque o admin da plataforma passará a liberar escritórios e ver a cobrança
   (F1-RN-04).
4. **Leitura por POST.** A lista de notas (`POST /integrations/xmls`), os totais (`POST /integrations/xmls/totais`),
   as exportações (`POST /integrations/xmls/exportar` e `/exportar/zip`), a busca da memória da Fronteira
   (`POST /fronteira/memoria/buscar`) e o `/buscar` das NFS-e são leituras feitas por POST
   (`rest/FiscalXmlResource.java:83-90` e `:202-219`, `rest/FronteiraResource.java:56`,
   `rest/ServiceInvoiceResource.java:47`). Um "somente leitura" que bloqueie pelo método HTTP quebraria a consulta e
   a exportação. Isso é tratado na F1-RN-07.
5. **Não há envio de e-mail** no backend (sem `JavaMailSender` nem SDK de SES). A verificação de e-mail e os avisos
   do teste exigem um serviço novo e a configuração do SES na AWS.
6. Não existe nenhuma tela ou endpoint que veja vários escritórios. O console da plataforma é o primeiro ponto do
   sistema que cruza tenants.
7. O usuário não tem "inativo" (a exclusão é definitiva e o login fica preso em `uk_users_login`) nem telefone. O
   escritório não tem CNPJ, e-mail financeiro nem telefone (`AccountingFirm` tem só `name` e `billing_provider`).

### Ordem recomendada e tamanho

| Fase | O que entrega | Tamanho | Observação |
|---|---|---|---|
| **1. Teste grátis e liberação** | Cadastro próprio com CNPJ, e-mail financeiro e telefone; verificação de e-mail; aceite dos termos; teste de 14 dias com limite pequeno; bloqueio só leitura com as capturas paradas; avisos por faixa e e-mail; liberação, extensão e suspensão pelo admin da plataforma; console com revisão dos escritórios migrados; correção dos riscos 2 e 3 | **G** (cerca de 2 semanas) + configuração do SES (domínio, DKIM/SPF, saída do sandbox do SES) | Era M. Cresceu com as respostas do Tony: verificação e avisos por e-mail (serviço de e-mail novo), limite durante o teste (a trava contra concorrência e a importação da Acessórias vieram da fase 3) e a classificação das leituras por POST |
| **2. Cobrança via gateway** | Catálogo Taxify com preço, contratação pelo escritório, recorrência Pix/boleto/cartão no gateway, webhooks, régua de inadimplência com bloqueio e reativação automáticos, conciliação diária, NFS-e da CS Hub | **G** (2 a 3 semanas de código + conta, homologação e sandbox do gateway) | Reaproveita o e-mail, o bloqueio e o console da fase 1 |
| **3. Limites por plano** | Limites vindos do plano (3a), downgrade e módulos (3b) | **P** (3a) + **M** (3b) | A 3a ficou menor: a contagem, a trava e o bloqueio nascem na fase 1, e a 3a só troca a fonte do limite pelo plano |

**Ordem: 1, 2 e 3a, depois 3b.**

### MVP vendável

**Fase 1 + cobrança manual fora do Taxify.**
- O prospect faz o teste de 14 dias.
- A CS Hub fecha o contrato e cobra pelo que já usa (Conta Azul da CS Hub ou o painel do gateway, sem integração).
- O admin da plataforma marca o escritório como "Liberado" e suspende à mão quem não pagar.

**Condição de go-live:** o cadastro público só abre em produção com a versão real dos termos de uso, da política de
privacidade e do acordo de operador (F1-RN-11) e com o SES configurado (I1-1). Até lá, a fase 1 pode ir para produção
**com o cadastro fechado**: a migração, o console e a correção do admin da plataforma já protegem o que existe.

### Riscos

- **Perda de notas da SEFAZ no bloqueio.** Com o bloqueio, as capturas param (decisão do Tony). A distribuição de DF-e
  guarda os documentos por cerca de 90 dias (prazo informado pelo Tony). Um bloqueio mais longo que isso pode perder
  notas de entrada que não voltam por esse canal. Mitigação: aviso na tela com os dias de bloqueio (F1-RN-09) e
  retomada pelo último NSU (F1-RN-08).
- **E-mail.** Sem SES configurado (domínio verificado, DKIM/SPF, fora do sandbox), ninguém consegue verificar o
  cadastro, e o teste não começa. Os e-mails de aviso podem cair no spam. A configuração é da CS Hub, seguindo o passo
  a passo escrito na I1-1 (decisão 16).
- **Uso do Taxify para e-mail em massa.** O reenvio anônimo poderia ser usado para disparar e-mails a terceiros e
  queimar a reputação do SES. Mitigação: teto por hora, por dia e por IP (F1-RN-03, decisão 17).
- **LGPD.**
  - O IP do cadastro, o telefone e o e-mail são dados pessoais. Eles são guardados como prova do aceite e para
    contato comercial, e precisam aparecer na política de privacidade (o Tony vai providenciar).
  - O bloqueio mantém leitura e exportação, então o escritório consegue cumprir as obrigações com os clientes dele.
  - A retenção depois do cancelamento segue em aberto (P2-08).
- **Fiscal da própria CS Hub.** A venda do SaaS exige NFS-e da CS Hub. O enquadramento do serviço é questão fiscal da
  CS Hub (P2-07).
- **Jurídico.** Os termos, a privacidade e o acordo de operador ainda não existem. Isso bloqueia a abertura do
  cadastro público (F1-RN-11).
- **PCI.** Dado de cartão nunca pode passar pelo backend nem pelo log do Taxify (F2-RN-04).
- **Webhook falso.** O endpoint de webhook é público. Sem validação forte, qualquer pessoa marca uma fatura como paga
  (F2-RN-05).
- **Confundir os dois níveis de plano** (seção 1).
- **Bloquear escritório ativo no deploy** (mitigado por F1-RN-13).
- **Token de 2 h.** A situação de acesso é conferida no backend a cada requisição, não só pelo claim do token.

#### Riscos conhecidos da implementação da fase 1 (aceitos na revisão)

- **Deploy sem `PLATFORM_FIRM_ID`.** Sem a variável no `.env` da EC2, ninguém é admin da plataforma e a isenção da
  CS Hub não vale. É pré-requisito de deploy (ver Status).
- **`POST /accounting-firms` cria escritório sem assinatura.** O endpoint que já existia continua criando escritório
  sem `firm_subscription`. Esse escritório é tratado como `SUSPENDED` (somente leitura, F1-RN-07) até o admin da
  plataforma agir pelo console.
- **A importação incremental da Acessórias não retraz a empresa pulada pelo limite.** Uma empresa que ficou em
  `skippedByLimit` (F1-RN-10a) só volta numa importação incremental se mudar na Acessórias. Depois de liberar vaga,
  pode ser preciso uma importação completa ou o cadastro manual.
- **O CPF único revela se um CPF já existe.** A unicidade do CPF consulta a tabela `documents`, e o 409 do cadastro
  anônimo revela que aquele CPF já está no Taxify (enumeração).
- **O console filtra e pagina em memória.** `GET /platform/firms` carrega os escritórios e filtra/pagina na
  aplicação. Serve para centenas de escritórios; com milhares, a consulta precisa ir para o banco.
- **Proxy confiável.** Com `TAXIFY_TRUSTED_PROXIES` vazio, o backend aceita `X-Forwarded-For` vindo de loopback e de
  redes privadas. Em produção a variável deve fixar a sub-rede do Docker Compose; senão, outro container na rede
  privada consegue forjar o IP do limite por IP (F1-RN-06) e do aceite (F1-RN-11).

---

## 3. Problema e objetivo

Hoje qualquer pessoa cria um escritório e usa o Taxify inteiro de graça e sem prazo. A CS Hub não tem como cobrar o
escritório pelo sistema, nem bloquear quem não paga, nem limitar o que cada um usa.

| Fase | Objetivo | Medida |
|---|---|---|
| 1 | Todo escritório novo passa por um teste de 14 dias com e-mail verificado e limite pequeno, e nenhum grava nada depois do prazo sem liberação | Escritórios com escrita fora do teste e sem liberação = 0 (console). Escritórios migrados não revisados = 0 em até 30 dias depois do deploy |
| 2 | A cobrança recorrente e o bloqueio por falta de pagamento acontecem sem trabalho manual | Faturas pagas que não liberaram o escritório em até 10 min = 0. Divergências na conciliação diária = 0 |
| 3 | Nenhum escritório passa do limite do plano | Criações acima do limite = 0 (teste de concorrência do QA) |

## 4. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| Prospect (visitante) | Cadastra o escritório, verifica o e-mail e testa | Uma vez |
| ADMIN do escritório | Vê os dias restantes do teste ou a situação da assinatura; recebe os avisos por e-mail; (fase 2) contrata e paga; (fase 3) vê o uso × limite | No teste, diário; depois, mensal |
| Colaborador do escritório | Usa o sistema; vê a faixa de "somente leitura" quando o escritório está bloqueado | Diário |
| Admin da plataforma (CS Hub) | Revisa os escritórios migrados; libera, estende o teste, suspende e reativa; (fase 2) acompanha a inadimplência | Diário no começo, depois semanal |
| Gateway (fase 2) | Envia webhooks de pagamento | A cada evento |
| Agendadores | Vencimento do teste, e-mails de aviso, parada das capturas dos bloqueados; (fase 2) régua e conciliação | Diário |

## 5. Histórias de usuário

**Fase 1**
- H1. Como prospect, quero cadastrar meu escritório e testar o Taxify por 14 dias depois de confirmar meu e-mail, para decidir se assino.
- H2. Como ADMIN do escritório em teste, quero ver na tela quantos dias faltam e receber e-mails antes do fim, para não ser pego de surpresa.
- H3. Como CS Hub, quero que o escritório com teste vencido, suspenso ou inadimplente só consiga ver e exportar, para que o teste vire venda sem prender os dados do escritório.
- H4. Como ADMIN de um escritório bloqueado, quero saber que as capturas pararam e que um bloqueio longo pode perder notas da SEFAZ, para regularizar a tempo.
- H5. Como admin da plataforma, quero liberar, estender o teste, suspender e reativar um escritório, para fechar vendas e tratar exceções sem mexer no banco.
- H6. Como admin da plataforma, quero revisar um a um os escritórios que já existiam, para decidir quem vira teste, quem é suspenso e quem continua liberado.
- H7. Como admin da plataforma, quero ver o nome, o e-mail e o telefone do ADMIN de cada escritório e as contagens de empresas e usuários, para fazer o contato comercial sem ver dado de cliente.
- H8. Como CS Hub, quero que ninguém vire admin da plataforma pelo cadastro público e que o cadastro resista a abuso, para proteger todos os escritórios.
- H9. Como CS Hub, quero que o cadastro só abra ao público com termos de uso aceitos e registrados, para ter respaldo jurídico.

**Fase 2**
- H10. Como ADMIN do escritório, quero escolher o plano e pagar por Pix, boleto ou cartão com cobrança recorrente, para não depender de cobrança manual.
- H11. Como ADMIN do escritório, quero ser avisado antes do vencimento e depois do atraso, para regularizar antes do bloqueio.
- H12. Como ADMIN do escritório inadimplente, quero que o acesso volte sozinho assim que eu pagar, para não depender de atendimento.
- H13. Como admin da plataforma, quero ver faturas, situação e inadimplência de cada escritório, para cobrar e prever receita.
- H14. Como CS Hub, quero emitir a NFS-e de cada mensalidade paga sem trabalho manual, para cumprir a obrigação fiscal da venda.

**Fase 3**
- H15. Como admin da plataforma, quero que cada Plano Taxify tenha limite de empresas, de usuários e de módulos, para vender por faixa.
- H16. Como admin da plataforma, quero que um downgrade nunca apague nem desative nada, para não causar perda de dado fiscal do cliente.

---

## 6. Modelo de dados (comum às fases)

Os números das migrations são os próximos livres hoje (a última é `V56__CertidoesEstaduais`). Conferir na hora de
implementar.

| Tabela | Fase | Conteúdo |
|---|---|---|
| `accounting_firms` (+colunas) | 1 | `document_number` (CNPJ do escritório, 14 dígitos, **índice único próprio**, fora da tabela `documents`: o CNPJ do escritório pode ser também uma empresa cliente de outro escritório), `billing_email` (300), `contact_phone` (telefone/WhatsApp, só dígitos, 10 ou 11) |
| `firm_subscriptions` | 1 | `id`, `accounting_firm_id` (FK, **uma linha vigente por escritório**), `platform_plan_id` (FK, nulo na fase 1), `status` (PENDING_VERIFICATION, TRIAL, ACTIVE, PAST_DUE, BLOCKED, SUSPENDED, CANCELED), `trial_ends_on`, `access_until` (nulo = sem fim), `blocked_since`, `blocked_reason` (TRIAL_EXPIRED, ACCESS_EXPIRED, NON_PAYMENT, MANUAL), `migrated` (verdadeiro para a linha criada pela migração da F1-RN-13; com `reviewed_at` nulo, alimenta o filtro "liberado na migração e não revisado"), `reviewed_at`, `reviewed_by` (revisão dos migrados), `source` (MANUAL, ASAAS ou IUGU), `gateway_customer_id`, `gateway_subscription_id`, `notes` (500), auditoria, `deleted` |
| `firm_subscription_events` | 1 | `id`, `accounting_firm_id`, `type` (SIGNUP, EMAIL_VERIFIED, TRIAL_STARTED, TRIAL_EXTENDED, CONVERTED_TO_TRIAL, RELEASED, REVIEWED, CONTACT_UPDATED, SUSPENDED, REACTIVATED, BLOCKED, UNVERIFIED_PURGED, PLAN_CHANGED, PAYMENT_CONFIRMED, CANCELED…), `before_json`, `after_json` (TEXT), `reason` (motivo informado na ação do admin da plataforma, F1-RN-14), `actor` (login, `system` ou `gateway`), `created_date` |
| `terms_acceptances` | 1 | `id`, `user_id`, `accounting_firm_id`, `terms_version`, `accepted_at` (data e hora), `ip` (obrigatório) |
| `email_verifications` | 1 | `id`, `user_id`, `token_hash` (SHA-256 do token; o token em claro nunca é gravado), `expires_at`, `used_at`, `resend` (verdadeiro quando o token veio de `POST /signup/resend`; o e-mail do cadastro tem falso e não conta nos limites de reenvio), `created_date` |
| `firm_notifications` | 1 | `id`, `accounting_firm_id`, `type` (TRIAL_D3, TRIAL_D1, TRIAL_D0), `reference_date` (o `trial_ends_on` a que se refere), `sent_at`; **único** (`accounting_firm_id`, `type`, `reference_date`) |
| `platform_plans` | 2 | `id`, `name` (150), `description` (500), `price decimal(15,2)`, `billing_cycle`, `max_companies int null`, `max_users int null` (nulo = ilimitado), `active` (vendável), auditoria, `deleted` |
| `platform_plan_modules` | 3b | PK (`platform_plan_id`, `module` varchar(40)) |
| `platform_invoices` | 2 | `id`, `accounting_firm_id`, `firm_subscription_id`, `gateway`, `gateway_payment_id` (único por gateway), `amount decimal(15,2)`, `due_date`, `status` (PENDING, PAID, OVERDUE, CANCELED, REFUNDED), `billing_type` (PIX, BOLETO, CREDIT_CARD), `paid_at`, `invoice_url` (página do gateway), `nfse_status`, `nfse_number` |
| `payment_webhook_events` | 2 | `id`, `gateway`, `event_id` (**único** por gateway), `event_type`, `received_at`, `processed_at`, `result`, `payload_hash`. O payload bruto **não** é guardado inteiro (pode ter dado pessoal); só os ids necessários |

---

## 7. Fase 1: teste grátis e liberação

### Decisões do Tony (2026-11-26)

| # | Tema | Decisão |
|---|---|---|
| 1 | Prazo e limite do teste | 14 dias. Durante o teste há um limite pequeno de empresas e usuários, configurável. Os valores estão na decisão 13 |
| 2 | Modo do cadastro | Teste direto: quem se cadastra entra em teste grátis assim que verifica o e-mail (ver 8) |
| 3 | Efeito do bloqueio | Somente leitura, igual para teste vencido, suspensão e inadimplência: o escritório entra, vê e exporta, mas não cria nem edita |
| 4 | Capturas no bloqueio | Todas as capturas automáticas param (SIEG, SEFAZ, e-Fisco, Acessórias). Ao reativar, a SEFAZ retoma do último NSU. A tela avisa que um bloqueio longo pode perder notas (90 dias da SEFAZ) |
| 5 | Exportação | O bloqueado ou cancelado mantém as exportações que já existem (XML em ZIP, relatórios). Nada novo |
| 6 | Termos | Ainda não existem; o Tony vai providenciar. O sistema sai com aceite obrigatório (versão, data e hora, IP) e texto configurável. O cadastro público não abre em produção sem a versão real |
| 7 | Dados do cadastro | CNPJ do escritório (validado e único), e-mail financeiro (separado do login) e telefone/WhatsApp |
| 8 | Antiabuso | Verificação de e-mail (o teste só começa depois do clique no link; envio por SES) e limite por IP (no máximo 3 cadastros por IP por dia, configurável). Sem captcha |
| 9 | Isenção | Só a própria CS Hub. O admin da plataforma pode estender o teste de um escritório específico |
| 10 | Escritórios existentes | Ficam liberados sem prazo na virada. O admin da CS Hub revisa um a um pelo console (vira teste, suspende ou continua liberado) |
| 11 | Console | Mostra nome, e-mail e telefone só do ADMIN de cada escritório, mais as contagens de empresas e usuários. Nenhum dado fiscal dos clientes |
| 12 | Avisos | Faixa na tela com os dias restantes e e-mails 3 dias e 1 dia antes e no vencimento |
| 13 | Valores do limite do teste (P1-13) | 5 empresas e 2 usuários por padrão, configuráveis (`TAXIFY_TRIAL_MAX_COMPANIES=5`, `TAXIFY_TRIAL_MAX_USERS=2`) |
| 14 | O que conta no limite (P1-14) | Empresa inativa **não** ocupa vaga. Filial conta à parte: cada CNPJ completo é uma empresa, sem agrupar pela raiz. O ADMIN conta como usuário. Não se cria "inativar/reativar usuário" agora: continua só a exclusão (fora do escopo da fase 1) |
| 15 | Rotinas que não são captura no bloqueio (P1-15) | Param todas: auditoria automática, retificação, pendências, certidões e sincronização da Conta Azul do próprio escritório. Voltam ao reativar |
| 16 | E-mail (P1-16) | Link de verificação vale 24 h. Cadastro não verificado é apagado em 7 dias. Até 3 reenvios por hora. Remetente `nao-responda@cshub.com.br`. A CS Hub configura o SES na AWS (domínio, DKIM/SPF, saída do sandbox), com um passo a passo escrito (I1-1) |
| 17 | Teto diário de reenvio | Além dos 3 reenvios por hora por login, no máximo `TAXIFY_EMAIL_RESEND_PER_DAY` (padrão 5) reenvios por login por dia civil de Recife e `TAXIFY_EMAIL_RESEND_PER_IP_PER_DAY` (padrão 10) por IP por dia. Acima do teto, `/signup/resend` responde 202 sem enviar (não revela conta). Motivo: evitar que o Taxify seja usado para mandar e-mails em massa a terceiros e proteger a reputação do SES |
| 18 | `POST /fronteira/memoria/buscar` no bloqueio | Continua liberado como leitura, mesmo usando o e-CPF do contador no e-Fisco: é uma consulta pontual disparada pelo usuário, e gravar o resultado é cache |
| 19 | Nome do escritório | Não ganha restrição de caracteres nesta fase |
| 20 | Recuperação dos meses bloqueados (QA-02) | Ao reativar um escritório (de BLOCKED, SUSPENDED ou CANCELED para TRIAL ou ACTIVE), as capturas por janela de período (SIEG NF-e, SIEG NFS-e e e-Fisco) buscam de novo, uma vez, na rodada seguinte, os meses do bloqueio que ficaram fora da janela normal. O limite é `TAXIFY_UNBLOCK_RECOVERY_MONTHS` meses para trás a partir do mês atual (padrão 3, alinhado a `FISCAL_XML_PURGE_RETENTION_MONTHS=3`). SEFAZ e NFS-e Nacional seguem pelo NSU. Substitui o "sem reprocessamento à parte do período parado" para essas capturas |

### Regras de negócio

#### Cadastro

- **F1-RN-01 Endpoint de cadastro próprio.** Endpoint novo `POST /signup` (anônimo), com um DTO **sem campo de
  perfil**:
  - escritório: `name`, `documentNumber` (CNPJ), `billingEmail`, `contactPhone`;
  - usuário: `name`, `document` (CPF), `login`, `email`, `password`;
  - `termsVersion`.

  O backend sempre grava o usuário como ADMIN, sem perfis de acesso. `POST /users` passa a aceitar **só ADMIN
  logado** (sai o `anonymous()` de `config/SecurityConfig.java:60-62`). O front deixa de enviar `profile: 'ADMIN'`
  (`auth.component.ts:286`). Fonte: decisão 7 e risco 2 da Avaliação.

  `GET /signup/config` (anônimo) devolve `{available, termsVersion, termsUrl, privacyUrl, dpaUrl, trialDays,
  verificationHours}`. `available` segue a trava de produção da F1-RN-11. O front usa essa resposta para montar a tela
  de cadastro (links dos termos, prazo do teste e validade do link) e **esconde "Criar conta" com `available=false`**.
- **F1-RN-02 Validação dos dados do escritório.** Fonte: decisões 7 e 19.
  - **CNPJ:** só dígitos, 14 posições, dígitos verificadores válidos (400 `error.signup.firm.document.invalid`) e único
    entre os escritórios, contando os excluídos e os cadastros não verificados ainda não expurgados (409
    `error.signup.firm.document.exists`, com a mensagem "Este CNPJ já tem conta no Taxify. Fale com a CS Hub."). A
    unicidade é **só entre escritórios** (`accounting_firms.document_number`), e não esbarra em empresa cliente com o
    mesmo CNPJ.
  - **E-mail financeiro:** obrigatório, formato de e-mail, até 300 (fora disso 400 `error.signup.billing.email.invalid`). É um campo separado do e-mail do usuário, e pode
    ter o mesmo valor.
  - **Telefone/WhatsApp:** obrigatório, guardado só com dígitos (DDD + número, 10 ou 11 dígitos; fora disso 400
    `error.signup.phone.invalid`).
  - **Nome do escritório:** obrigatório, **sem restrição de caracteres** nesta fase (decisão 19).
  - As regras atuais do usuário continuam: login único (RN-07 da spec de plataforma; 409
    `error.user.login.already.exists`), CPF único (RN-11; 409 `error.user.document.already.exists`) e senha
    obrigatória. Os mesmos códigos valem em `POST /users`. A unicidade do CPF consulta a tabela `documents` (ver
    "Riscos conhecidos da implementação").
- **F1-RN-03 Verificação de e-mail antes do teste.** O cadastro cria o escritório, o ADMIN, o aceite dos termos e uma
  `firm_subscription` com `status = PENDING_VERIFICATION`, e envia ao `email` do ADMIN um link
  `{TAXIFY_PUBLIC_URL}/auth/verificar?token=<token>` (padrão `https://taxify.cshub.com.br`), com o remetente da
  F1-RN-17. Fonte: decisões 8, 16 e 17.
  - **Token:** 32 bytes aleatórios (base64url). Só o SHA-256 é gravado. Vale uma vez só e por
    `TAXIFY_EMAIL_VERIFICATION_HOURS` horas contadas da emissão (padrão **24**): `expires_at = emissão + 24 h`.
  - `POST /signup/verify { token }`:
    - token válido: `status = TRIAL`, `trial_ends_on` pela F1-RN-05, e os eventos `EMAIL_VERIFIED` e `TRIAL_STARTED`;
    - token vencido (agora > `expires_at`) ou usado: 400 `error.signup.token.invalid`.
  - Antes da verificação, o login responde 403 `error.signup.email.not.verified`, e o front oferece "Reenviar e-mail".
  - **Reenvio:** `POST /signup/resend { login }` **sempre** responde 202, exista ou não o login, para não revelar
    contas. Os limites abaixo valem juntos, e o primeiro que estourar impede o envio:
    - **por hora:** no máximo `TAXIFY_EMAIL_RESEND_PER_HOUR` reenvios por login nos últimos 60 minutos (padrão **3**);
    - **por dia, por login:** no máximo `TAXIFY_EMAIL_RESEND_PER_DAY` reenvios por login no mesmo dia civil de
      `America/Recife` (padrão **5**; zera às 00:00 de Recife);
    - **por dia, por IP:** no máximo `TAXIFY_EMAIL_RESEND_PER_IP_PER_DAY` reenvios saídos do mesmo IP (F1-RN-06) no
      mesmo dia civil de Recife (padrão **10**), somando todos os logins;
    - o e-mail do cadastro não conta como reenvio (`email_verifications.resend = false`).
    - Acima de qualquer limite, a resposta continua 202 e nada é enviado. O log registra `signup.resend.limited` sem
      login, e-mail nem IP.
    - Cada reenvio gera um token novo, com 24 h contadas do reenvio, e invalida o anterior.
  - **Expurgo:** um cadastro ainda em `PENDING_VERIFICATION` criado há mais de `TAXIFY_UNVERIFIED_PURGE_DAYS` dias
    (padrão **7**, ou seja, 7 × 24 h desde o `POST /signup`; reenvios não reiniciam a contagem) é excluído
    (escritório, usuário e documentos) por um agendador diário (`TAXIFY_UNVERIFIED_PURGE_CRON`, padrão
    `0 20 3 * * *`, Recife). O CNPJ, o login e o CPF ficam livres de novo. Fica registrado o evento
    `UNVERIFIED_PURGED`, sem os dados pessoais.
- **F1-RN-04 Admin da plataforma blindado.** Fonte: risco 3 da Avaliação.
  1. O padrão `admin` sai de `docker-compose.yml:97`, e o padrão passa a ser vazio, como no `application.yaml:148`.
  2. Nova variável `PLATFORM_FIRM_ID`: só é admin da plataforma quem tem perfil ADMIN, login em
     `PLATFORM_ADMIN_LOGINS` **e** escritório igual a `PLATFORM_FIRM_ID`. Um login igual criado em outro escritório
     nunca vira admin. **Sem `PLATFORM_FIRM_ID` no `.env` da EC2, ninguém é admin da plataforma**: a variável é
     pré-requisito de deploy.
  3. `POST /signup` recusa um login que esteja em `PLATFORM_ADMIN_LOGINS` com a mesma resposta de login em uso (409
     `error.user.login.already.exists`), para não revelar a lista.
  4. No startup, com a lista vazia ou `PLATFORM_FIRM_ID` vazio, o log registra um aviso.
- **F1-RN-05 Teste de 14 dias.** `trial_ends_on = data da verificação + (TAXIFY_TRIAL_DAYS − 1)`, com
  `TAXIFY_TRIAL_DAYS = 14` por padrão. **O dia da verificação conta como dia 1**: verificado em 2026-11-26, o último
  dia é 2026-12-09. O teste vale até 23:59:59 do último dia. Todas as datas usam `America/Recife`, e não
  `LocalDate.now()` da JVM, que no container roda em UTC. Fonte: decisão 1.
- **F1-RN-06 Limite por IP.** No máximo `TAXIFY_SIGNUP_MAX_PER_IP_PER_DAY` cadastros criados por IP por dia civil
  (Recife). O padrão é 3, a decisão do Tony. Acima disso, a resposta é 429 `error.signup.rate.limited`. A contagem usa
  `terms_acceptances.ip` (cada cadastro grava um aceite), e não some no restart. Fonte: decisão 8.
  - O IP vem do `X-Forwarded-For` posto pelo Caddy. O backend só aceita esse header quando a conexão vem de um proxy
    confiável (`TAXIFY_TRUSTED_PROXIES`) e, fora isso, usa o IP da conexão.
  - `TAXIFY_TRUSTED_PROXIES` vazio = loopback e redes privadas. **Em produção, a variável deve fixar a sub-rede do
    Docker Compose** (ver "Riscos conhecidos da implementação").
  - O mesmo IP é usado no teto diário de reenvio por IP (F1-RN-03).
  - Não há captcha.

#### Situação de acesso e bloqueio

- **F1-RN-07 Situação de acesso.** Calculada a cada requisição (cache só no escopo da requisição) a partir da
  `firm_subscription` do escritório:

  | Situação | Condição | Acesso |
  |---|---|---|
  | `PENDING_VERIFICATION` | E-mail ainda não verificado | Sem login (F1-RN-03) |
  | `TRIAL` | Hoje <= `trial_ends_on` | Total, com o limite do teste (F1-RN-10a) |
  | `ACTIVE` | `access_until` nulo ou hoje <= `access_until` | Total |
  | `PAST_DUE` (fase 2) | Fatura vencida dentro da carência | Total, com aviso |
  | `BLOCKED` | Teste vencido (`TRIAL_EXPIRED`), `access_until` vencido (`ACCESS_EXPIRED`) ou carência vencida (`NON_PAYMENT`, fase 2) | Somente leitura |
  | `SUSPENDED` | Suspensão manual pelo admin da plataforma (`MANUAL`) | Somente leitura |
  | `CANCELED` | Cancelado | Somente leitura (retenção em P2-08) |

  - Motivos de bloqueio (`blocked_reason`): `TRIAL_EXPIRED`, `ACCESS_EXPIRED` (liberação manual com `access_until`
    vencido), `NON_PAYMENT` (fase 2) e `MANUAL`.
  - A passagem de TRIAL para BLOCKED e de ACTIVE com `access_until` vencido para BLOCKED é **calculada na hora**, sem
    depender de agendador. Um agendador diário (`TAXIFY_ACCESS_CRON`, padrão `0 10 0 * * *`, Recife) só grava a
    mudança e o evento `BLOCKED`, e preenche `blocked_since` e `blocked_reason`.
  - Um escritório **sem** `firm_subscription` é tratado como `SUSPENDED` e gera aviso no log. Isso não deveria
    acontecer depois da F1-RN-13, mas acontece com o `POST /accounting-firms` que já existia (ver "Riscos conhecidos
    da implementação"). O escritório isento (F1-RN-12) é sempre total.
- **F1-RN-08 Somente leitura.** Vale para BLOCKED, SUSPENDED e CANCELED, sem distinção de motivo. Fonte: decisões 3,
  5 e 18. Um filtro único, depois da autenticação, decide pelo **tipo da operação**, e não só pelo método HTTP:
  - **liberado:** GET e HEAD; os POST marcados com a anotação nova `@ReadOnlyOperation`; e as rotas da F1-RN-09;
  - **bloqueado:** todo o resto (POST, PUT, PATCH, DELETE), com 403 `error.firm.access.blocked` e o corpo
    `{ "status": "BLOCKED", "reason": "TRIAL_EXPIRED", "blockedSince": "2026-12-10" }`. `reason` assume um dos
    motivos da F1-RN-07.

  Os POST que só leem e recebem `@ReadOnlyOperation`, confirmados no código e na revisão:

  | Endpoint | Fonte | Observação |
  |---|---|---|
  | `POST /integrations/xmls` (lista de notas) | `rest/FiscalXmlResource.java:83` | |
  | `POST /integrations/xmls/totais` | `rest/FiscalXmlResource.java:89` | |
  | `POST /integrations/xmls/exportar` | `rest/FiscalXmlResource.java:202` | |
  | `POST /integrations/xmls/exportar/zip` (XML em ZIP) | `rest/FiscalXmlResource.java:212` | |
  | `POST /fronteira/memoria/buscar` | `rest/FronteiraResource.java:56` | Continua liberado mesmo usando o e-CPF do contador no e-Fisco: é consulta pontual disparada pelo usuário, e gravar o resultado é cache (decisão 18). Não confundir com a captura automática do e-Fisco, que para (F1-RN-10) |
  | `POST /fronteira/regras/testar` | `rest/FronteiraResource.java` | Simula a regra sem gravar (acrescentado na implementação e aceito na revisão) |
  | `POST …/buscar` das NFS-e | `rest/ServiceInvoiceResource.java:49` | |
  | `POST …/totais` das NFS-e (`ServiceInvoiceResource#totals`) | `rest/ServiceInvoiceResource.java:55` | Totais da lista de NFS-e (confirmado na revisão) |

  O teste-guarda garante que **todo** POST seja de escrita (por padrão) ou esteja anotado, e lista os anotados para
  a revisão. Qualquer POST novo anotado entra nesta tabela.

  As exportações e os relatórios que já existem continuam liberados, porque são GET ou POST anotado:
  - os PDFs e CSV da Fronteira (`rest/FronteiraResource.java:85-124`);
  - o relatório mensal (`rest/ReportResource.java:33`);
  - o laudo (`rest/AuditResource.java:39`);
  - os PDFs de nota (`rest/FiscalXmlResource.java:141`);
  - os PDFs de certidão (`rest/CertidaoResource.java:43`).

  **Nenhuma exportação nova** entra nesta fase. A sessão de usuário que já existe não cai: a próxima escrita recebe o
  403.
- **F1-RN-09 Sempre liberado, mesmo bloqueado.**
  - `POST /auth`;
  - `GET /firm-access`;
  - as rotas de `/signup/**` (inclusive `GET /signup/config`);
  - (fase 2) `/billing-taxify/**`, para ver e pagar faturas;
  - tudo do escritório isento;
  - tudo de `/platform/**` para o admin da plataforma.

  **Aviso:** quando o escritório está bloqueado, todas as telas mostram uma faixa fixa com:
  - "Somente leitura: {motivo} desde {blockedSince}";
  - "As capturas automáticas estão paradas há {N} dias";
  - "A SEFAZ disponibiliza as notas por cerca de 90 dias. Um bloqueio longo pode fazer notas de entrada se perderem";
  - "Ao reativar, o Taxify busca de novo no SIEG e no e-Fisco os meses bloqueados, até {N} meses para trás. Meses mais
    antigos não são recuperados", com N = `TAXIFY_UNBLOCK_RECOVERY_MONTHS` (F1-RN-10, decisão 20);
  - o contato da CS Hub (e, na fase 2, o botão de pagar).

  Fonte: decisão 4.
- **F1-RN-10 Rotinas automáticas no bloqueio.** Para um escritório em BLOCKED, SUSPENDED, CANCELED ou
  PENDING_VERIFICATION, **param todas as capturas automáticas e todas as rotinas automáticas que gravam dados**. A
  conferência fica num ponto único (`FirmAccessService.allowsAutomaticRoutines(firmId)`) chamado por cada agendador
  antes de processar o escritório. Fonte: decisões 4, 15 e 20.

  | Rotina | Agendador | No bloqueio |
  |---|---|---|
  | SIEG | `service/FiscalXmlSyncScheduler` | Para |
  | SEFAZ (DistDF-e) e manifestação | `service/SefazDistScheduler`, `service/SefazManifestService` | Para |
  | NFS-e nacional | `service/NfseAdnScheduler` | Para (é captura automática) |
  | e-Fisco | `service/fronteira/EfiscoSyncScheduler` | Para |
  | Acessórias | `service/AcessoriasImportScheduler` | Para |
  | Auditoria automática | `AuditScheduler` | Para (decisão 15) |
  | Retificação | `FiscalXmlRectificationScheduler` | Para (decisão 15) |
  | Pendências | `PendencyScheduler` | Para (decisão 15) |
  | Certidões | `CertidaoScheduler` | Para (decisão 15) |
  | Sincronização da Conta Azul do próprio escritório (Plano de honorário) | `BillingSyncScheduler` | Para (decisão 15) |
  | Expurgo de XML | `service/FiscalXmlPurgeScheduler` | Sem mudança (é regra de retenção, não captura) |

  **Retomada:** ao voltar para TRIAL ou ACTIVE, todas as rotinas da tabela voltam na próxima rodada, sem ação manual.
  - A SEFAZ continua do último NSU gravado por empresa (`sefaz_dist_control.ult_nsu`,
    `domain/xml/SefazDistControl.java:34`), e a NFS-e do NSU de `NfseAdnControl`. Nada é zerado no bloqueio.
  - A Acessórias incremental cobre o período pelo `DtLastDH` da última importação.
  - **Recuperação dos meses bloqueados (decisão 20).** Na saída de BLOCKED, SUSPENDED ou CANCELED para TRIAL ou ACTIVE
    (por qualquer ação: `extend-trial`, `release`, `reactivate`, `convert-to-trial` e, na fase 2, o pagamento), as
    capturas por janela de período buscam de novo o período do bloqueio (de `blocked_since` até a data da reativação),
    **uma vez**, a partir da rodada seguinte, avançando um passo por empresa a cada vez dela no rodízio da SIEG (uma página
    de cada lado, ou uma janela de NFS-e), para respeitar o limite de requisições. Com 306 empresas e lote 2, a recuperação
    completa de 3 meses leva cerca de um dia:

    | Captura | Janela normal | O que é recuperado |
    |---|---|---|
    | SIEG NF-e emitidas e recebidas (a sincronização do SIEG pede só NF-e; CT-e não vem por nenhum caminho hoje) | Dia 1 do mês até hoje | Os meses do bloqueio fora da janela (anteriores ao mês atual) |
    | SIEG NFS-e | 35 dias | O trecho do bloqueio anterior ao início da janela de 35 dias |
    | e-Fisco | Mês atual e mês anterior | Os meses do bloqueio anteriores ao mês anterior |

    - **Limite:** no máximo `TAXIFY_UNBLOCK_RECOVERY_MONTHS` meses para trás a partir do mês atual (padrão **3**,
      alinhado ao expurgo de XML `FISCAL_XML_PURGE_RETENTION_MONTHS=3`). Reativado em 2027-01, o mês mais antigo
      buscado é 2026-10. Meses além disso não são buscados.
    - **Persistência:** o período a recuperar fica gravado e sobrevive a restart. Falha não marca como concluído (tenta
      na rodada seguinte). Um novo bloqueio antes de concluir pausa a recuperação, que continua depois, juntando os
      períodos.
    - **Transparência:** a faixa de bloqueio avisa o limite (F1-RN-09). A trilha do escritório registra o período
      recuperado e os meses que ficaram de fora, visíveis no histórico do console.
    - **Sem duplicar:** as notas já importadas não duplicam (a mesma deduplicação de sempre).
    - Depois da recuperação, as capturas voltam só à janela normal. A recuperação não se repete.
  - Auditoria, retificação, pendências, certidões e Conta Azul voltam com a janela normal de cada uma, sem
    reprocessamento à parte do período parado.

  Os botões manuais de captura ("Capturar agora", sincronizar SIEG) são POST de escrita e ficam bloqueados pela
  F1-RN-08. A busca pontual da memória da Fronteira não é captura automática e segue liberada (decisão 18).

#### Limite durante o teste

- **F1-RN-11 Termos de uso obrigatórios e trava de produção.** Fonte: decisão 6.
  - O cadastro exige `termsVersion` igual a `TAXIFY_TERMS_VERSION` (vazio dá 400 `error.signup.terms.required`;
    diferente, 409 `error.signup.terms.outdated`).
  - O aceite grava em `terms_acceptances` a versão, a data e hora (Recife) e o IP (F1-RN-06).
  - O texto é configurável: a tela mostra links para `TAXIFY_TERMS_URL`, `TAXIFY_PRIVACY_URL` e `TAXIFY_DPA_URL`
    (termos de uso, política de privacidade e acordo de operador), com uma caixa de aceite obrigatória. O front lê a
    versão e os links em `GET /signup/config` (F1-RN-01).
  - **Trava de produção:** `POST /signup` só funciona com `TAXIFY_SIGNUP_ENABLED=true` **e** uma versão de termos que
    não comece com `RASCUNHO`. Caso contrário, responde 503 `error.signup.unavailable`, `GET /signup/config` devolve
    `available=false`, e o front esconde "Criar conta". O padrão de `TAXIFY_SIGNUP_ENABLED` é `false`.
  - **Critério de go-live:** o cadastro público não abre em produção sem a versão real dos termos e sem o SES
    configurado (I1-1). Ligar a variável na EC2 é deploy de produção e exige aprovação explícita do Tony.
- **F1-RN-10a Limite do teste.** Em `TRIAL`, o escritório pode ter no máximo `TAXIFY_TRIAL_MAX_COMPANIES` empresas
  (padrão **5**) e `TAXIFY_TRIAL_MAX_USERS` usuários (padrão **2**). Fonte: decisões 1, 13 e 14.
  - **Empresas em uso:** `deleted = false` e `active` nulo ou `true`. Empresa inativa **não** ocupa vaga.
  - **Cada CNPJ completo (14 dígitos) é uma empresa.** Matriz e filiais contam à parte; não há agrupamento pela raiz
    do CNPJ (8 primeiros dígitos).
  - **Usuários em uso:** `deleted = false`, todos os tipos, **inclusive o ADMIN**. Com o padrão 2, o escritório tem o
    ADMIN e mais 1 colaborador.
  - Não existe inativar/reativar usuário nesta fase (seção 12): a vaga de usuário só volta pela exclusão, que é
    definitiva.
  - O bloqueio vale para:
    - `POST /companies`;
    - o `PUT /companies/{id}` que reativa uma empresa (`active` de `false` para `true` ou nulo). Editar uma empresa
      ativa nunca é bloqueado;
    - a importação da Acessórias (pela tela);
    - `POST /users`.
  - Com o limite atingido, a resposta é 409 `error.plan.limit.companies` ou `error.plan.limit.users`, com `limit`,
    `inUse` e `planName = "Teste grátis"`. Mensagem: "O teste grátis permite {limite} empresas e o escritório já tem
    {emUso}. Fale com a CS Hub para contratar."
  - **Importação da Acessórias** (na ordem da API):
    - empresa nova e ativa sem vaga: não é criada, entra em `skipped` com "limite de empresas atingido" e conta no
      campo novo `skippedByLimit` do `AcessoriasImportResultDTO`;
    - empresa existente e inativa que voltou a "Ativa": os dados são atualizados, `active` continua `false`, e ela
      conta em `skippedByLimit`;
    - as atualizações e os vínculos seguem, e o job termina sem falhar.
    - A importação incremental não retraz uma empresa pulada que não mudou na Acessórias (ver "Riscos conhecidos da
      implementação").
  - **Concorrência:** a conferência e a gravação acontecem na mesma transação, que antes trava a linha do escritório
    (`SELECT id FROM accounting_firms WHERE id = ? FOR UPDATE`). Na importação, a trava é por empresa criada ou
    reativada, nunca pelo job inteiro. Estouro da espera da trava responde 409 `error.plan.limit.busy`.
  - Excluir ou inativar empresa e excluir usuário nunca são bloqueados, e a vaga volta na hora.
  - Log: `plan.limit.blocked firm={id} kind={COMPANY|USER} limit={n} inUse={n} path={…}`, sem CNPJ, nome ou e-mail.
  - O cadastro em si cria o primeiro ADMIN sem conferir limite.
  - O escritório `ACTIVE` liberado à mão ou pela migração **não tem limite** até a fase 3 (ver P3-02).

#### Isenção, migração e admin da plataforma

- **F1-RN-12 Isenção.** Só o escritório `PLATFORM_FIRM_ID` (CS Hub). Ele nunca é bloqueado, nunca tem limite e nunca
  para as rotinas. Não há cortesia para outros escritórios: a exceção é estender o teste ou liberar à mão (F1-RN-14).
  Fonte: decisão 9.
- **F1-RN-13 Escritórios que já existem.** Uma migration Java idempotente cria uma `firm_subscription` para todo
  escritório não excluído que ainda não tenha uma, com:
  - `status = ACTIVE`, `access_until` nulo, `reviewed_at` nulo;
  - `migrated = true`;
  - `notes = "liberado na migração"`;
  - o evento `RELEASED` com `actor = system`.

  Ninguém é bloqueado no deploy. Os campos novos do escritório (CNPJ, e-mail financeiro, telefone) ficam nulos para
  os migrados e são preenchidos pelo admin da plataforma em `PUT /platform/firms/{id}/contact` (F1-RN-15). Fonte: decisão 10.
- **F1-RN-14 Ações do admin da plataforma.** Em `POST /platform/firms/{id}/access/{ação}`, cada ação grava um evento
  com antes, depois e motivo (`firm_subscription_events.reason`), e vale na próxima requisição do escritório. Fonte:
  decisões 9 e 10.

  | Ação | Efeito |
  |---|---|
  | `extend-trial` | Nova `trial_ends_on` >= hoje; o escritório volta a TRIAL se estava BLOCKED por TRIAL_EXPIRED |
  | `convert-to-trial` | ACTIVE (migrado) → TRIAL com `trial_ends_on` informada (padrão: hoje + 13, o mesmo cálculo da F1-RN-05) |
  | `release` | → ACTIVE, com `access_until` opcional (é também a saída de BLOCKED por ACCESS_EXPIRED) |
  | `suspend` | → SUSPENDED, motivo obrigatório |
  | `reactivate` | SUSPENDED → TRIAL ou ACTIVE, conforme as datas |
  | `cancel` | → CANCELED |
  | `mark-reviewed` | Mantém liberado e grava `reviewed_at`/`reviewed_by` |

  Qualquer uma dessas ações em um escritório migrado também conta como revisão. Editar o contato
  (`PUT /platform/firms/{id}/contact`, F1-RN-15) **não** conta como revisão: para revisar, use uma ação desta tabela
  (por exemplo, `mark-reviewed`).
- **F1-RN-15 Console da plataforma.** `GET /platform/firms`, só pela anotação nova `@PlatformAdminOnly` (403 para os
  demais), reconhecida pelo teste-guarda `noEndpointIsLeftWithoutAnAccessRule`. Fonte: decisão 11.
  - Cada linha traz: id, nome e CNPJ do escritório, data do cadastro, situação, `trial_ends_on`, `access_until`,
    `blockedSince`, "revisado" (sim/não), empresas em uso, empresas inativas, usuários em uso e, **só dos usuários
    ADMIN** do escritório, nome e e-mail, mais o telefone de contato e o e-mail financeiro (`billingEmail`) do escritório.
  - As contagens de empresas e usuários em uso seguem a mesma regra da F1-RN-10a.
  - **Edição do contato:** `PUT /platform/firms/{id}/contact` (`@PlatformAdminOnly`), com o corpo
    `{documentNumber, billingEmail, contactPhone}`. Campo nulo mantém o valor atual; corpo todo nulo dá 400
    `error.generic.bad.request`.
    - Mesmas validações e códigos do cadastro (F1-RN-02): 400 `error.signup.firm.document.invalid`,
      `error.signup.billing.email.invalid` e `error.signup.phone.invalid`; 409 `error.signup.firm.document.exists`
      (CNPJ de outro escritório, sem contar o próprio).
    - Em `PENDING_VERIFICATION`: 409 `error.platform.action.invalid` (o cadastro não verificado é expurgado com os
      dados pessoais, F1-RN-03).
    - Grava o evento `CONTACT_UPDATED` com antes e depois. O log (`platform.firm.contact.updated firm={id}`) não leva
      CNPJ, e-mail nem telefone.
    - Não conta como revisão (F1-RN-14). Responde com a linha atualizada do console.
  - **Nunca** traz nome, CNPJ ou dado fiscal das empresas clientes, nem dados de colaboradores que não sejam ADMIN.
  - **Exceção (2026-11-27, P-CP-01 da spec [Painel de controle da captura de XML](2026-11-27-painel-captura-xml.md)):**
    o painel de captura da plataforma (`GET /platform/capture-panel`) mostra o nome da empresa, o número do
    estabelecimento (`/0001`), as contagens de notas e as datas da captura, sem CNPJ completo, com log de cada acesso.
    Go-live só depois de a política de privacidade e o acordo de operador citarem a exceção. O `GET /platform/firms`
    não muda.
  - Filtros: situação, "liberado na migração e não revisado" (`migrated = true` e `reviewed_at` nulo) e busca pelo
    nome ou CNPJ do escritório.
  - As consultas são agregações próprias (`count … group by accounting_firm_id`) e não passam pelos `CrudService` com
    escopo de tenant.
  - Nesta fase, o filtro e a paginação são feitos em memória, o que atende a centenas de escritórios (ver "Riscos
    conhecidos da implementação").

#### Avisos e trilha

- **F1-RN-16 Avisos do fim do teste.** Fonte: decisão 12.
  - **Faixa na tela**, para todos os usuários do escritório em TRIAL, durante todo o teste: "Teste grátis: faltam
    {X} dias", com `X = trial_ends_on − hoje + 1`. No último dia: "Seu teste termina hoje". Os dados vêm de
    `GET /firm-access`.
  - **E-mails:** um agendador diário (`TAXIFY_TRIAL_NOTICE_CRON`, padrão `0 0 9 * * *`, horário de Recife) envia aos
    e-mails dos ADMIN do escritório:
    - `TRIAL_D3` quando `trial_ends_on − hoje = 3`;
    - `TRIAL_D1` quando `= 1`;
    - `TRIAL_D0` no próprio `trial_ends_on` ("termina hoje").
  - O envio é idempotente pela chave única de `firm_notifications`. Se o teste for estendido, a nova data gera novos
    avisos. Um aviso cuja data já passou (ex.: o agendador ficou parado) **não** é enviado atrasado.
  - Os e-mails trazem só o nome do escritório, a data e o link do Taxify (`TAXIFY_PUBLIC_URL`), sem dado de cliente.
- **F1-RN-17 Serviço de e-mail.** Interface `MailSender` com uma implementação SES (produção, credencial pela role
  IAM da EC2) e uma implementação de log (desenvolvimento e testes, que nunca envia). Fonte: decisão 16.
  - Remetente: `TAXIFY_MAIL_FROM`, padrão `nao-responda@cshub.com.br`. O domínio é verificado no SES pela CS Hub
    (I1-1).
  - Região do SES: `TAXIFY_MAIL_SES_REGION`, padrão `us-east-2` (a confirmar na I1-1).
  - Links dos e-mails: `TAXIFY_PUBLIC_URL`, padrão `https://taxify.cshub.com.br`.
  - Uma falha de envio é registrada e tentada de novo na próxima rodada.
  - Em produção, o token de verificação **nunca** vai para o log. `TAXIFY_MAIL_LOG_BODY` (padrão `false`) faz a
    implementação de log escrever o corpo do e-mail, com o link; **só para desenvolvimento**, nunca ligado na EC2.
- **F1-RN-18 Trilha e log.** Toda mudança de `firm_subscription` gera um evento. Bloqueios de escrita geram
  `firm.access.blocked firm={id} reason={…} method={…} path={…}`, sem CNPJ, nome, e-mail ou IP.

### Critérios de aceite

- **CA1-01 (F1-RN-01, F1-RN-03)** Dado `TAXIFY_SIGNUP_ENABLED=true` e os termos vigentes, quando um visitante envia
  `POST /signup` válido, então o escritório, o ADMIN e o aceite são criados, a situação é `PENDING_VERIFICATION`, um
  e-mail de verificação é enviado com o remetente `nao-responda@cshub.com.br`, e o login responde 403
  `error.signup.email.not.verified`.
- **CA1-02 (F1-RN-03, F1-RN-05)** Dado um cadastro pendente, quando o link é aberto em 2026-11-26, então a situação
  vira `TRIAL` com `trial_ends_on = 2026-12-09`, e o login funciona. Quando o mesmo link é aberto de novo, então a
  resposta é 400 `error.signup.token.invalid`.
- **CA1-03 (F1-RN-01)** Quando `POST /signup` chega com `"profile": "EMPLOYEE"` ou `accessProfiles`, então os campos
  são ignorados, e o usuário é ADMIN. Quando um anônimo chama `POST /users`, então a resposta é 401 ou 403.
- **CA1-04 (F1-RN-02)** Dado o CNPJ 11.111.111/0001-11 com dígitos inválidos, quando o cadastro é enviado, então a
  resposta é 400 `error.signup.firm.document.invalid`. Dado um CNPJ válido já usado por outro escritório, então a
  resposta é 409 `error.signup.firm.document.exists`. Dado o mesmo CNPJ cadastrado como **empresa cliente** em outro
  escritório, então o cadastro do escritório é aceito.
- **CA1-05 (F1-RN-02)** Quando o telefone vem como "(81) 9 9999-0000", então é gravado "81999990000". Com
  "9999-0000", a resposta é 400 `error.signup.phone.invalid`.
- **CA1-06 (F1-RN-04)** Dado `PLATFORM_ADMIN_LOGINS=csadmin` e `PLATFORM_FIRM_ID=F0`, quando um visitante tenta o
  login `csadmin` no cadastro, então a resposta é 409 com a mensagem genérica. Dado um `csadmin` existente em outro
  escritório, quando ele faz login, então `platformAdmin = false`. Sem a variável no `.env`, ninguém é admin da
  plataforma, e o log tem o aviso.
- **CA1-07 (F1-RN-06)** Dados 3 cadastros criados do mesmo IP em 2026-11-26 (Recife), quando chega o 4º, então a
  resposta é 429 `error.signup.rate.limited`. Em 2026-11-27, o cadastro é aceito.
- **CA1-08 (F1-RN-05, F1-RN-07)** Dado `trial_ends_on = 2026-12-09`, quando o ADMIN cria uma empresa às 23:30 de
  Recife do dia 9, então a resposta é 200. Às 00:05 do dia 10, a resposta é 403 `error.firm.access.blocked` com
  `reason = TRIAL_EXPIRED`.
- **CA1-09 (F1-RN-08)** Dado o escritório bloqueado, quando um FISCAL abre a lista de notas
  (`POST /integrations/xmls`), exporta o ZIP (`POST /integrations/xmls/exportar/zip`), busca a memória da Fronteira
  (`POST /fronteira/memoria/buscar`), testa uma regra (`POST /fronteira/regras/testar`) ou baixa o relatório da
  Fronteira, então tudo responde 200. Quando ele retifica uma nota, cria uma empresa ou edita um usuário, então a
  resposta é 403 `error.firm.access.blocked`.
- **CA1-10 (F1-RN-08)** Quando o teste-guarda roda, então todo `@PostMapping` sem `@ReadOnlyOperation` é tratado como
  escrita, e a lista dos anotados bate com a tabela da F1-RN-08.
- **CA1-11 (F1-RN-09)** Dado o escritório bloqueado desde 2026-12-10, quando o usuário abre qualquer tela em
  2026-12-20, então vê a faixa com "capturas automáticas paradas há 10 dias" e o aviso dos 90 dias da SEFAZ.
- **CA1-12 (F1-RN-10)** Dado o escritório bloqueado, quando rodam os agendadores do SIEG, da SEFAZ, da NFS-e, do
  e-Fisco, da Acessórias, da auditoria, da retificação, das pendências, das certidões e da Conta Azul, então nenhuma
  empresa dele é processada, e o expurgo de XML continua rodando. Quando o escritório é reativado, então a próxima
  rodada de cada um desses agendadores processa as empresas dele, e a SEFAZ consulta a partir do `ult_nsu` gravado
  antes do bloqueio.
- **CA1-13 (F1-RN-01, F1-RN-11)** Dado `TAXIFY_SIGNUP_ENABLED=false` ou `TAXIFY_TERMS_VERSION=RASCUNHO-1`, quando um
  visitante chama `POST /signup`, então a resposta é 503 `error.signup.unavailable`, `GET /signup/config` devolve
  `available=false`, e a tela de login não mostra "Criar conta". Com o cadastro ligado e a versão real, então
  `GET /signup/config` devolve `available=true`, a versão, os três links, `trialDays = 14` e
  `verificationHours = 24`.
- **CA1-14 (F1-RN-11)** Quando o cadastro é enviado sem `termsVersion`, então a resposta é 400; com a versão antiga,
  409. Com a versão vigente, `terms_acceptances` recebe o usuário, a versão, a data e hora e o IP.
- **CA1-15 (F1-RN-10a)** Dado um escritório em teste com `TAXIFY_TRIAL_MAX_COMPANIES=5` e 5 empresas ativas, quando o
  ADMIN cria a 6ª, então a resposta é 409 `error.plan.limit.companies` com `limit = 5`, `inUse = 5` e
  `planName = "Teste grátis"`. Com `TAXIFY_TRIAL_MAX_USERS=2` e 2 usuários (o ADMIN e 1 colaborador), a criação de
  outro colaborador responde 409 `error.plan.limit.users`.
- **CA1-16 (F1-RN-10a)** Dado o teste com limite de 5, 3 em uso, e a Acessórias devolvendo 4 empresas novas "Ativa",
  quando a importação roda, então 2 são criadas, `skippedByLimit = 2`, e o job termina com sucesso.
- **CA1-17 (F1-RN-10a)** Dado o limite de 5 com 4 em uso, quando duas requisições `POST /companies` chegam ao mesmo
  tempo, então uma responde 200, a outra 409, e o uso final é 5.
- **CA1-18 (F1-RN-12)** Dado o escritório `PLATFORM_FIRM_ID` com 500 empresas e sem `firm_subscription`, quando
  qualquer usuário dele grava algo, então nada é bloqueado nem limitado.
- **CA1-19 (F1-RN-13)** Dados 3 escritórios existentes, quando a migration roda duas vezes, então há exatamente 3
  `firm_subscriptions` ACTIVE, sem fim, não revisadas e com `migrated = true`, e nenhum 403 nem 409 de limite logo
  depois do deploy.
- **CA1-20 (F1-RN-14)** Dado um escritório migrado, quando o admin aplica `convert-to-trial` em 2026-11-26 sem
  informar data, então ele fica TRIAL até 2026-12-09, conta como revisado, e há um evento com o login do admin.
  Quando o admin aplica `extend-trial` até 2026-12-20 num teste vencido, então o escritório volta a TRIAL na próxima
  requisição.
- **CA1-21 (F1-RN-15)** Dado um ADMIN de escritório comum, quando ele chama `GET /platform/firms`, então a resposta é
  403. Para o admin da plataforma, cada linha traz o nome e o e-mail dos ADMIN e o telefone do escritório, e nenhum
  campo de empresa cliente nem de colaborador que não seja ADMIN.
- **CA1-22 (F1-RN-16)** Dado `trial_ends_on = 2026-12-09`, quando o agendador de avisos roda nos dias 06, 08 e 09/12,
  então são enviados TRIAL_D3, TRIAL_D1 e TRIAL_D0, um de cada, mesmo que o agendador rode duas vezes no mesmo dia. A
  faixa mostra "faltam 14 dias" em 26/11 e "Seu teste termina hoje" em 09/12.
- **CA1-23 (F1-RN-03)** Dado um link de verificação emitido em 2026-11-26 às 10:00 (Recife), quando ele é aberto em
  2026-11-27 às 09:59, então o teste começa; quando um link emitido no mesmo horário é aberto às 10:01 do dia 27,
  então a resposta é 400 `error.signup.token.invalid`. Dado um login com 3 reenvios nos últimos 60 minutos, quando
  chega o 4º, então a resposta é 202 e nenhum e-mail sai.
- **CA1-24 (F1-RN-03)** Dado um cadastro pendente criado em 2026-11-26 às 10:00 (Recife), quando o expurgo roda em
  2026-12-03 às 09:59, então o cadastro continua; quando roda às 10:01, então o escritório, o usuário e os documentos
  são excluídos, o evento `UNVERIFIED_PURGED` é gravado sem dado pessoal, e o mesmo CNPJ, login e CPF podem se
  cadastrar de novo.
- **CA1-25 (F1-RN-10a)** Dado o limite de 5 empresas com 5 cadastradas, sendo 1 inativa, quando o ADMIN cria uma
  empresa, então a resposta é 200. Dado o limite de 5 com 4 em uso, incluindo uma matriz, quando o ADMIN cadastra uma
  filial com a mesma raiz de CNPJ, então a resposta é 200 e o uso vai a 5; a filial seguinte responde 409.
- **CA1-26 (F1-RN-03, decisão 17)** Dado um login que já recebeu 5 reenvios em 2026-11-26 (Recife), espaçados para
  não estourar o limite por hora, quando chega o 6º no mesmo dia, então a resposta é 202, nenhum e-mail sai e o log
  tem `signup.resend.limited` sem login, e-mail nem IP; às 00:05 de 2026-11-27, o reenvio sai. Dado um IP que já
  originou 10 reenvios enviados no dia, para logins diferentes, quando chega um 11º para outro login, então a
  resposta é 202 e nenhum e-mail sai; o mesmo pedido vindo de outro IP sai.
- **CA1-27 (F1-RN-07, F1-RN-14)** Dado um escritório liberado com `access_until = 2026-12-31`, quando o ADMIN cria uma
  empresa às 00:05 de 2027-01-01 (Recife), então a resposta é 403 `error.firm.access.blocked` com
  `reason = ACCESS_EXPIRED`. Quando o admin da plataforma aplica `release` sem data, então a próxima escrita passa.
- **CA1-28 (F1-RN-10, decisão 20)** Dado um escritório bloqueado de 2026-12-25 a 2027-01-04 e
  `TAXIFY_UNBLOCK_RECOVERY_MONTHS=3`, quando ele é reativado em 2027-01-04, então as rodadas seguintes da empresa no SIEG
  (NF-e emitidas e recebidas) busca dezembro de 2026, além da janela normal de janeiro, e nenhuma nota já
  importada duplica; depois disso volta só à janela normal; a SEFAZ e a NFS-e Nacional retomam pelo NSU.
  Dado um bloqueio de 2026-08-10 a 2027-01-04, então nada antes de 2026-10 é buscado, e a trilha registra 2026-08 e
  2026-09 como não recuperados.

### Casos de teste (fase 1)

Dados fictícios (CNPJs fictícios com DV válido; IPs da faixa de documentação 203.0.113.0/24; nenhum dado real de
cliente). Os limites são os padrões da decisão 13 (5 empresas e 2 usuários) e os parâmetros de e-mail, os das decisões
16 e 17 (24 h, 7 dias, 3 reenvios por hora, 5 por login por dia, 10 por IP por dia). Horários em `America/Recife`.

| # | Cenário | Entrada | Esperado | RN |
|---|---|---|---|---|
| CT1-01 | Cadastro válido | `POST /signup` com CNPJ válido fictício, e-mail financeiro, telefone "81999990000", termos vigentes | 200; PENDING_VERIFICATION; e-mail enviado (mock) com remetente `nao-responda@cshub.com.br` e link em `TAXIFY_PUBLIC_URL`; login 403 | F1-RN-01, F1-RN-03, F1-RN-17 |
| CT1-02 | Verificação | Link aberto em 2026-11-26 | TRIAL até 2026-12-09; eventos EMAIL_VERIFIED e TRIAL_STARTED | F1-RN-03, F1-RN-05 |
| CT1-03 | Link dentro e fora das 24 h; link reutilizado | Token A emitido em 26/11 10:00 e usado em 27/11 09:59; token B (outro cadastro) emitido em 26/11 10:00 e usado em 27/11 10:01; token A usado de novo | A: TRIAL; B: 400 `error.signup.token.invalid` e continua PENDING_VERIFICATION; A de novo: 400 | F1-RN-03 |
| CT1-04 | Reenvio | `POST /signup/resend` com login existente e com login inexistente; depois, 4 reenvios do login existente entre 11:00 e 11:30; um 5º às 12:01 | Os dois primeiros respondem 202, e só o existente recebe e-mail; o token anterior para de valer. Os 3 primeiros reenvios saem; o 4º responde 202 sem e-mail e loga `signup.resend.limited` sem login nem e-mail; o das 12:01 sai (o primeiro reenvio saiu da janela de 60 min) | F1-RN-03 |
| CT1-05 | Expurgo do não verificado | Cadastro pendente criado em 26/11 10:00 (com um reenvio em 01/12); expurgo executado em 03/12 09:59 e em 03/12 10:01 | 09:59: mantido. 10:01: escritório, usuário e documentos excluídos (o reenvio não reinicia a contagem); evento `UNVERIFIED_PURGED` sem dado pessoal; o mesmo CNPJ, login e CPF podem ser cadastrados de novo | F1-RN-03 |
| CT1-06 | Perfil forjado | `profile=EMPLOYEE`, `accessProfiles=[FISCAL]` | ADMIN sem perfis | F1-RN-01 |
| CT1-07 | `POST /users` anônimo | Sem token | 401/403; nada criado | F1-RN-01 |
| CT1-08 | CNPJ inválido, duplicado ou de cliente | DV errado; CNPJ de outro escritório; CNPJ de empresa cliente de outro escritório | 400; 409; 200 | F1-RN-02 |
| CT1-09 | Telefone | "(81) 9 9999-0000"; "9999-0000" | Gravado "81999990000"; 400 | F1-RN-02 |
| CT1-10 | E-mail financeiro igual ao do usuário | `billingEmail = email` | 200 | F1-RN-02 |
| CT1-11 | Login reservado | Login `csadmin` (listado) | 409 com a mensagem genérica | F1-RN-04 |
| CT1-12 | Admin da plataforma de outro escritório | `csadmin` no escritório F9 ≠ `PLATFORM_FIRM_ID` | `platformAdmin=false`; `/platform/**` 403 | F1-RN-04 |
| CT1-13 | Limite por IP | 4 cadastros do mesmo IP no mesmo dia; o 5º no dia seguinte | O 4º responde 429; o do dia seguinte, 200 | F1-RN-06 |
| CT1-14 | IP forjado | `X-Forwarded-For` enviado direto ao backend, de um IP fora de `TAXIFY_TRUSTED_PROXIES` | Ignorado; vale o IP da conexão | F1-RN-06 |
| CT1-15 | Último dia do teste | 2026-12-09, 23:59 Recife (02:59 UTC do dia 10) | Escrita aceita | F1-RN-05, F1-RN-07 |
| CT1-16 | Teste vencido: leitura | 2026-12-10: lista de notas (POST), totais, ZIP, PDF e CSV da Fronteira, busca da memória da Fronteira, teste de regra da Fronteira, relatório mensal, laudo | Todos 200 | F1-RN-08 |
| CT1-17 | Teste vencido: escrita | Criar empresa, editar usuário, retificar nota, "Capturar agora", sincronizar SIEG, upload de A1 | Todos 403 `error.firm.access.blocked` | F1-RN-08 |
| CT1-18 | Mesmo efeito por motivo | Suspensão manual, `access_until` vencido e (fase 2) inadimplência | O mesmo resultado dos CT1-16 e CT1-17, com `reason` MANUAL, ACCESS_EXPIRED e NON_PAYMENT | F1-RN-07, F1-RN-08 |
| CT1-19 | Faixa de bloqueio | Bloqueado desde 2026-12-10, aberto em 2026-12-20 | "paradas há 10 dias" + aviso dos 90 dias | F1-RN-09 |
| CT1-20 | Capturas e rotinas paradas | Bloqueado; rodam os agendadores SIEG, SEFAZ, NFS-e, e-Fisco, Acessórias, auditoria, retificação, pendências, certidões e Conta Azul; roda o expurgo de XML | Nenhuma empresa do escritório processada pelos 10 primeiros (log/contagem); o expurgo de XML roda normalmente | F1-RN-10 |
| CT1-21 | Retomada pelo NSU | `ult_nsu = 000000000000500` antes do bloqueio; reativação | A primeira consulta usa 500; nenhum NSU zerado | F1-RN-10 |
| CT1-22 | Recuperação dos meses bloqueados | Bloqueado de 2026-12-25 a 2027-01-04, reativado em 2027-01-04; `TAXIFY_UNBLOCK_RECOVERY_MONTHS=3`; notas fictícias de 2026-12-27 no SIEG, além das de 2026-12-10 já importadas | SIEG NF-e: a rodada seguinte busca dezembro de 2026 e importa as de 27/12; as de 10/12 não duplicam. SIEG NFS-e e e-Fisco: o período já está na janela normal, sem busca extra. SEFAZ e NFS-e Nacional pelo NSU. Depois volta só à janela normal | F1-RN-10 |
| CT1-23 | Trava de produção | `TAXIFY_SIGNUP_ENABLED=false`; ou versão `RASCUNHO-1`; depois, cadastro ligado com a versão real | 503 `error.signup.unavailable`, `GET /signup/config` com `available=false` e "Criar conta" escondido; com o cadastro ligado, `available=true`, `trialDays=14`, `verificationHours=24` e os três links | F1-RN-01, F1-RN-11 |
| CT1-24 | Aceite | Sem versão; versão antiga; versão vigente | 400; 409; 200 com versão, data e hora e IP gravados | F1-RN-11 |
| CT1-25 | Empresa N+1 no teste | Limite 5, uso 5 | 409, `limit=5`, `inUse=5`; nenhum `documents` novo | F1-RN-10a |
| CT1-26 | Inativa não conta | 5 empresas cadastradas, 1 inativa (uso 4), limite 5; cria uma empresa | 200; uso 5; o console mostra 5 em uso e 1 inativa | F1-RN-10a |
| CT1-27 | Reativação no limite | Uso 5/5, `PUT active=true` numa inativa | 409; continua inativa | F1-RN-10a |
| CT1-28 | Usuário N+1 no teste (o ADMIN conta) | Limite 2: o ADMIN + 1 colaborador; cria outro colaborador | 409 `error.plan.limit.users` com `limit=2`, `inUse=2`; depois de excluir o colaborador, 200 | F1-RN-10a |
| CT1-29 | Acessórias no teste | Limite 5, uso 3; E1..E4 novas "Ativa"; E5 existente com o nome alterado | E1 e E2 criadas; E3 e E4 em `skipped`; E5 atualizada; `skippedByLimit=2`; job concluído | F1-RN-10a |
| CT1-30 | Concorrência | Limite 5, uso 4: 2 `POST /companies` em paralelo; e POST junto com a importação | Uma criação só; uso final 5 | F1-RN-10a |
| CT1-31 | Liberado sem limite | Escritório migrado ACTIVE com 300 empresas | 200 | F1-RN-10a, F1-RN-13 |
| CT1-32 | Isento | Escritório F0, 500 empresas, sem linha | Nada bloqueado nem limitado; rotinas rodam | F1-RN-12 |
| CT1-33 | Migração | 3 escritórios, migration 2 vezes | 3 linhas ACTIVE não revisadas, com `migrated = true`; 0 bloqueios; o filtro "liberado na migração e não revisado" mostra as 3 | F1-RN-13, F1-RN-15 |
| CT1-34 | Revisão | `convert-to-trial` sem data em 26/11; `mark-reviewed`; `suspend` sem motivo | TRIAL até 09/12 e revisado; revisado (sai do filtro de não revisados); 400 | F1-RN-14 |
| CT1-35 | Estender o teste | Teste vencido → `extend-trial` até 2026-12-20 | Acesso de volta na próxima requisição; evento gravado com o motivo em `reason` | F1-RN-14 |
| CT1-36 | Console | `GET /platform/firms` | ADMINs com nome e e-mail e telefone do escritório; sem nome ou CNPJ de cliente; sem colaborador que não seja ADMIN; 403 para ADMIN comum | F1-RN-15 |
| CT1-37 | Avisos | `trial_ends_on = 09/12`; agendador em 06, 08 e 09/12, duas vezes por dia | Exatamente 1 D3, 1 D1 e 1 D0 | F1-RN-16 |
| CT1-38 | Aviso depois da extensão | D3 enviado e o teste estendido até 20/12 | Novos D3, D1 e D0 para 20/12 | F1-RN-16 |
| CT1-39 | Aviso atrasado | Agendador parado de 05 a 07/12 | D3 não é enviado depois; D1 sai em 08/12 | F1-RN-16 |
| CT1-40 | Log sem dado pessoal | CT1-17, CT1-25, CT1-43 e verificação | `firm.access.blocked`, `plan.limit.blocked` e `signup.resend.limited` sem CNPJ, nome, e-mail ou IP; o token nunca aparece no log de produção (`TAXIFY_MAIL_LOG_BODY=false`) | F1-RN-03, F1-RN-17, F1-RN-18 |
| CT1-41 | Retomada das rotinas que não são captura | Bloqueado por 10 dias (CT1-20) e reativado | Na próxima rodada, auditoria, retificação, pendências, certidões e Conta Azul processam as empresas do escritório, sem ação manual | F1-RN-10 |
| CT1-42 | Filial conta à parte | Limite 5, uso 4 com uma matriz fictícia `/0001` ativa; cadastra a filial `/0002` da mesma raiz; depois a filial `/0003` | `/0002`: 200, uso 5; `/0003`: 409 `error.plan.limit.companies` com `limit=5`, `inUse=5` | F1-RN-10a |
| CT1-43 | Teto diário de reenvio por login | Login pendente L1, IP 203.0.113.10; em 26/11, reenvios às 08:00, 08:10, 08:20, 10:00 e 10:10; um 6º às 15:00; um 7º em 27/11 às 00:05 | Os 5 primeiros saem (nenhum estoura o limite por hora); o das 15:00 responde 202 sem e-mail e loga `signup.resend.limited`; o de 27/11 00:05 sai | F1-RN-03 |
| CT1-44 | Teto diário de reenvio por IP | Em 26/11, do IP 203.0.113.20, 10 reenvios enviados para os logins pendentes L1..L10 (2 por hora); um 11º para L11 do mesmo IP; o mesmo pedido para L11 do IP 203.0.113.21; um pedido do IP 203.0.113.20 para um login inexistente | 10 primeiros saem; o 11º responde 202 sem e-mail; o de 203.0.113.21 sai; o login inexistente responde 202 sem e-mail (as respostas são iguais, nada revela conta) | F1-RN-03, F1-RN-06 |
| CT1-45 | `access_until` vencido | Escritório liberado com `access_until = 2026-12-31`; escrita em 2027-01-01 00:05; depois `release` sem data | 403 `reason = ACCESS_EXPIRED`; o agendador de acesso grava BLOCKED com `blocked_reason = ACCESS_EXPIRED`; depois do `release`, escrita aceita | F1-RN-07, F1-RN-14 |
| CT1-46 | Limite da recuperação | Bloqueado de 2026-08-10 a 2027-01-04, reativado em 2027-01-04; `TAXIFY_UNBLOCK_RECOVERY_MONTHS=3` | SIEG NF-e: recupera 2026-10, 2026-11 e 2026-12; 2026-08 e 2026-09 não são buscados. e-Fisco: recupera 2026-10 e 2026-11 (2026-12 é o mês anterior, já na janela normal). SIEG NFS-e: recupera de 2026-10-01 até a véspera da janela de 35 dias. A trilha registra 2026-08 e 2026-09 como não recuperados | F1-RN-09, F1-RN-10 |

### Tarefas (fase 1)

| # | Tipo | Tarefa | Depende de |
|---|---|---|---|
| B1-1 | Backend | Migrations: colunas do escritório (`document_number` único, `billing_email`, `contact_phone`), `firm_subscriptions` (com `migrated`), `firm_subscription_events` (com `reason`), `terms_acceptances`, `email_verifications` (com `resend`), `firm_notifications`; entidades e repositórios | — |
| B1-2 | Backend | Migration Java idempotente dos escritórios existentes, com `migrated = true` (F1-RN-13) | B1-1 |
| B1-3 | Backend | Blindagem do admin da plataforma: tirar o padrão `admin` do compose, `PLATFORM_FIRM_ID`, login reservado, aviso no startup (F1-RN-04) | — |
| B1-4 | Backend | `MailSender` com implementação SES e de log; `TAXIFY_MAIL_FROM` com padrão `nao-responda@cshub.com.br`, `TAXIFY_MAIL_SES_REGION`, `TAXIFY_MAIL_LOG_BODY`, `TAXIFY_PUBLIC_URL` (F1-RN-17) | — |
| B1-5 | Backend | `POST /signup` com DTO próprio, validação do CNPJ, do telefone e do e-mail financeiro, aceite dos termos com IP, limite por IP com `TAXIFY_TRUSTED_PROXIES`, trava de produção, `GET /signup/config`; `POST /users` só ADMIN (F1-RN-01, F1-RN-02, F1-RN-06, F1-RN-11) | B1-1 |
| B1-6 | Backend | Verificação de e-mail: token de 24 h, `/signup/verify`, `/signup/resend` com até 3 por hora, 5 por login por dia e 10 por IP por dia, login bloqueado antes da verificação, expurgo dos não verificados em 7 dias com o evento `UNVERIFIED_PURGED`; parâmetros configuráveis (F1-RN-03, F1-RN-05) | B1-4, B1-5 |
| B1-7 | Backend | `FirmAccessService` (situação calculada, fuso, isenção, motivo ACCESS_EXPIRED) + agendador de acesso (`TAXIFY_ACCESS_CRON`) + `GET /firm-access` + filtro de somente leitura + `@ReadOnlyOperation` nos POST de leitura + teste-guarda dos POST (F1-RN-07 a F1-RN-09, F1-RN-12) | B1-1 |
| B1-8 | Backend | Parada das rotinas: `allowsAutomaticRoutines` nos 10 agendadores da F1-RN-10 (capturas, auditoria, retificação, pendências, certidões e Conta Azul), expurgo de XML sem mudança, testes de retomada pelo NSU. **Recuperação dos meses bloqueados** (decisão 20): na reativação, registrar o período a recuperar, limitado por `TAXIFY_UNBLOCK_RECOVERY_MONTHS`; na rodada seguinte, SIEG NF-e, SIEG NFS-e e e-Fisco buscam uma vez os meses fora da janela normal, com a deduplicação de sempre; registrar na trilha o período recuperado e os meses não recuperados (F1-RN-10) | B1-7 |
| B1-9 | Backend | `PlanLimitService` para o teste: contagens (inativa não conta, CNPJ completo, ADMIN conta), trava `FOR UPDATE`, 409 com detalhes, limite em `POST /companies`, reativação, Acessórias (`skippedByLimit`) e `POST /users`; log (F1-RN-10a) | B1-7 |
| B1-10 | Backend | `@PlatformAdminOnly` + teste-guarda; `GET /platform/firms` agregado com os contatos dos ADMIN e o filtro por `migrated`; ações de acesso e revisão com eventos e motivo (F1-RN-14, F1-RN-15, F1-RN-18) | B1-1, B1-3 |
| B1-11 | Backend | Agendador de avisos D3/D1/D0, idempotente, com os modelos de e-mail (F1-RN-16) | B1-4, B1-7 |
| I1-1 | Infra | SES configurado pela CS Hub: verificar o domínio `cshub.com.br` e o remetente `nao-responda@cshub.com.br`, DKIM/SPF, sair do sandbox, permissão na role IAM da EC2, **confirmar a região** (`TAXIFY_MAIL_SES_REGION`, padrão `us-east-2`); variáveis novas no `.env` da EC2 com `TAXIFY_SIGNUP_ENABLED=false`, **`PLATFORM_FIRM_ID` preenchido** e `TAXIFY_TRUSTED_PROXIES` com a sub-rede do compose. **Entregável: passo a passo escrito** em `docs/infra/ses-envio-de-email.md` (domínio e registros DNS, DKIM/SPF, pedido de saída do sandbox, política IAM mínima de envio, região, variáveis do `.env`, teste de envio pela implementação SES), sem segredo nem credencial no arquivo. Deploy só com aprovação do Tony | B1-4 |
| F1-1 | Frontend | Cadastro novo em `POST /signup`: campos do escritório (CNPJ com máscara e validação do DV, e-mail financeiro, telefone/WhatsApp), sem `profile`, caixa de aceite com os links dos termos, privacidade e acordo lidos de `GET /signup/config`; "Criar conta" escondido com `available=false` | B1-5 |
| F1-2 | Frontend | Telas "verifique seu e-mail" (informando que o link vale `verificationHours` horas), `/auth/verificar` (com a mensagem de link vencido e o botão "reenviar") e "reenviar"; mensagem para o login não verificado | B1-6 |
| F1-3 | Frontend | Faixa do teste (dias restantes); faixa de somente leitura com os dias de captura parada, o aviso dos 90 dias e o limite de recuperação (`TAXIFY_UNBLOCK_RECOVERY_MONTHS`); tratamento do 403 `error.firm.access.blocked` no interceptor (toast e botões de escrita desabilitados quando bloqueado) | B1-7 |
| F1-4 | Frontend | Mensagem clara para o 409 de limite; "Nova empresa" e "Novo usuário" desabilitados no limite; `skippedByLimit` no resumo da importação da Acessórias | B1-9 |
| F1-5 | Frontend | `platformAdminGuard`, menu "Plataforma", lista de escritórios (filtros por situação e "não revisado", contatos dos ADMIN, `billingEmail` e telefone do escritório, contagens), as ações com confirmação, data e motivo, e a edição do contato (CNPJ com máscara e DV, e-mail financeiro e telefone) em `PUT /platform/firms/{id}/contact` | B1-10 |
| Q1-1 | QA | Cadastro, verificação e antiabuso: CT1-01 a CT1-14, CT1-23, CT1-24, CT1-43 e CT1-44 (inclui o teste HTTP do `SecurityConfig`, que hoje é uma lacuna) | B1-5, B1-6 |
| Q1-2 | QA | Situação e somente leitura: CT1-15 a CT1-19 e CT1-45 | B1-7 |
| Q1-3 | QA | Rotinas e retomada: CT1-20 a CT1-22, CT1-41 e CT1-46 | B1-8 |
| Q1-4 | QA | Limite do teste e concorrência: CT1-25 a CT1-31 e CT1-42, **num banco local** (conferir `DB_HOST`: o `mvn test` pode apontar para a EC2) | B1-9 |
| Q1-5 | QA | Isenção, migração, console e revisão: CT1-32 a CT1-36 | B1-2, B1-10 |
| Q1-6 | QA | Avisos e log: CT1-37 a CT1-40 (e-mail pela implementação de log; nada de envio real com dado real) | B1-11 |
| Q1-7 | QA | Conferência antes do go-live do cadastro: na EC2, `PLATFORM_FIRM_ID` preenchido, `TAXIFY_TRUSTED_PROXIES` com a sub-rede do compose, `TAXIFY_MAIL_LOG_BODY=false` e `TAXIFY_SIGNUP_ENABLED=false` até existir a versão real dos termos e o SES configurado; depois, que a versão configurada não seja `RASCUNHO`, que os três links abram e que um e-mail de verificação de teste chegue com o remetente `nao-responda@cshub.com.br` e DKIM/SPF válidos (fora do spam) | B1-5, I1-1 |

---

## 8. Fase 2: cobrança da assinatura via gateway

### Comparativo Asaas × Iugu

**Todos os valores estão "a confirmar"**: preços mudam, há promoções e condições por contrato. Consultei as fontes em
2026-11-26. A decisão final pede uma proposta comercial dos dois para o volume da CS Hub (P2-02).

| Critério | Asaas | Iugu | Fonte |
|---|---|---|---|
| Pix (por recebimento) | Tabela padrão de R$ 1,99, com R$ 0,99 promocional por 3 meses (levantamento de 16/10/2026). A página oficial em 26/11/2026 mostra R$ 1,59 e R$ 0,79. Há menção a Pix sem tarifa nas primeiras N entradas do mês, e as fontes divergem: 30 ou 100 | 0,99% do valor (plano Essencial). Para valores acima de cerca de R$ 200, fica mais caro que a tarifa fixa do Asaas | [Asaas: preços e taxas](https://www.asaas.com/precos-e-taxas), [Blog Asaas](https://blog.asaas.com/taxas-asaas/), [Quasa, 18/10/2026](https://quasa.io/pt/media/asaas-ou-iugu-tarifa-publicada-vence-ate-o-volume-pedir-proposta) |
| Boleto (por recebimento) | R$ 1,99 padrão (R$ 0,99 promocional). Na página oficial em 26/11/2026: R$ 1,59 e R$ 0,79. Só cobra se for pago | R$ 2,19 + R$ 0,40 de processamento | Idem |
| Cartão de crédito à vista | R$ 0,49 + 2,99% padrão (promoção R$ 0,49 + 1,99% por 3 meses). Parcelado em faixas, até 3,49%. Repasse em cerca de 32 dias por parcela, com antecipação de 1,25% ao mês | 3,34% + R$ 0,40 de processamento | Idem |
| Mensalidade | Sem mensalidade nem adesão | "Consulte" (não publicada) | Idem |
| Recorrência | Sim: assinaturas com ciclo e forma de pagamento; tarifa por cobrança recebida | Sim: planos, assinaturas e ciclos nativos | [Asaas: eventos de assinaturas](https://docs.asaas.com/docs/eventos-para-assinaturas), [Iugu SaaS](https://www.iugu.com/iugu-saas) |
| Webhooks | Sim. **Não assina o payload com HMAC**: envia o `authToken` escolhido na criação no header `asaas-access-token` (de 32 a 255 caracteres, diferente da API key). Recomenda validar o header e restringir aos IPs oficiais | Sim ("gatilhos"). **Não encontrei na documentação pública** como a iugu autentica o webhook (header, token ou HMAC). A confirmar com a iugu | [Asaas: webhooks](https://docs.asaas.com/docs/receba-eventos-do-asaas-no-seu-endpoint-de-webhook), [Asaas: FAQ webhooks](https://docs.asaas.com/docs/duvidas-frequentes-webhooks), [Hookdeck: Asaas](https://hookdeck.com/webhooks/platforms/guide-to-asaas-webhooks-features-and-best-practices) |
| Sandbox | Sim, ambiente separado do de produção (webhooks configurados à parte; confirmar na documentação oficial) | Tokens de API do tipo Teste, Produção ou Mestre. Os tokens criados pelo painel exigem aprovação de um administrador | [Hookdeck: Asaas](https://hookdeck.com/webhooks/platforms/guide-to-asaas-webhooks-features-and-best-practices), [Iugu: autenticação](https://dev.iugu.com/reference/authentication) |
| Split | Existe (não detalhado nas fontes). **Não é necessário** neste caso, porque a CS Hub recebe 100% | Planos "Essencial + Split" e "Motor iugu" | [Quasa](https://quasa.io/pt/media/asaas-ou-iugu-tarifa-publicada-vence-ate-o-volume-pedir-proposta) |
| NFS-e da CS Hub | **Nativa**: emissão automática por assinatura, R$ 0,49 por nota (padrão). Exige conta PJ, inscrição municipal e prefeitura homologada para webservice | **Não encontrei emissão nativa**. A emissão costuma ser por terceiro (ex.: NFe.io via Pluga, que exige certificado A1) | [Asaas: nota fiscal](https://asaas.com/nota-fiscal), [Asaas: docs de notas fiscais](https://docs.asaas.com/docs/notas-fiscais-2.md), [NFe.io + iugu](https://nfe.io/docs/integracoes/plataformas/pluga/iugu/) |
| Checkout e cartão sem passar pelo Taxify | Fatura ou link de pagamento hospedado pelo gateway (a confirmar a tokenização no front) | Fatura hospedada e tokenização por JS (a confirmar) | Documentação de cada um |

**Leitura objetiva:**
- Para uma mensalidade típica de SaaS B2B, o Pix e o boleto saem mais baratos no Asaas pela tabela publicada, porque
  a iugu cobra Pix em percentual e boleto com processamento.
- O Asaas tem NFS-e nativa por assinatura, o que resolve a H14 sem mais um fornecedor.
- O ponto fraco do Asaas é a autenticação do webhook por token fixo (sem HMAC). Isso exige as defesas da F2-RN-05.
- **Recomendação preliminar: Asaas**, sujeita à proposta comercial e à confirmação da NFS-e para a prefeitura de
  Caruaru (P2-02, P2-07).

### Regras de negócio

- **F2-RN-01 Catálogo Taxify.** `platform_plans` tem nome, descrição, preço (`decimal(15,2)`), ciclo, `max_companies`,
  `max_users` (as faixas, usadas na fase 3) e `active` (vendável). Leitura e escrita só do admin da plataforma. Um
  plano com assinatura não pode ser excluído (409 `error.platform.plan.in.use`), só marcado como não vendável. A
  lista de planos, faixas e preços vem de P2-03. O **preço fica no Taxify** e é enviado ao gateway na criação ou
  alteração da assinatura (o Asaas não tem objeto "plano"). Mudar o preço do catálogo **não** muda as assinaturas em
  curso (P2-05).
- **F2-RN-02 Porta neutra.** Uma interface `SaasPaymentGateway` (criar cliente, criar, alterar e cancelar assinatura,
  consultar cobrança, validar webhook), com uma implementação por gateway e escolha em `TAXIFY_PAYMENT_GATEWAY`. É o
  mesmo padrão de `integration/billing/BillingProvider`. É uma interface separada, porque são papéis diferentes
  (seção 1).
- **F2-RN-03 Contratação.** O ADMIN do escritório (em teste, bloqueado ou ativo) escolhe o plano e a forma de
  pagamento (PIX, BOLETO, CREDIT_CARD) em `POST /billing-taxify/subscribe`.
  - O backend cria o cliente no gateway com o CNPJ, o e-mail financeiro e o telefone do escritório, coletados no
    cadastro da fase 1 (F1-RN-02). Para os escritórios migrados sem esses dados, a contratação exige que sejam
    preenchidos antes.
  - Depois cria a assinatura no gateway e devolve o link da primeira fatura.
  - A `firm_subscription` guarda `gateway_customer_id`, `gateway_subscription_id` e `platform_plan_id`.
  - Contratar **durante o teste** cobra a primeira fatura no fim do teste ou na hora, conforme P2-04.
  - Quando a primeira fatura é paga, a situação vira `ACTIVE`.
- **F2-RN-04 Cartão nunca passa pelo Taxify.** O pagamento por cartão é feito **só** na página de fatura ou de
  checkout hospedada pelo gateway, ou por tokenização no navegador com o SDK do gateway, mandando ao backend só o
  token. O backend e os logs **nunca** recebem número, validade ou CVV. A regra é conferida em revisão e por teste
  (nenhum DTO tem campo de cartão). Objetivo: o menor escopo PCI possível (SAQ A; a confirmar com o gateway).
- **F2-RN-05 Webhook seguro e idempotente.** `POST /webhooks/payments/{gateway}` é público (`permitAll`) e roda em
  contexto de sistema. Ele só pode mexer em `firm_subscriptions`, `platform_invoices` e `payment_webhook_events`,
  nunca em dado de cliente do escritório (ver P-09 da spec de plataforma).
  1. **Autenticação:** no Asaas, o header `asaas-access-token` é comparado em tempo constante com o token guardado
     no ambiente (`ASAAS_WEBHOOK_TOKEN`, de 32 a 255 caracteres, nunca a API key). Na iugu, o mecanismo é confirmado
     com eles. Se falhar, a resposta é 401, e nada é gravado além de uma linha de log sem o payload.
  2. **Restrição de origem** pelos IPs oficiais do gateway no Caddy, se o gateway publicar a lista.
  3. **Idempotência:** `payment_webhook_events` com chave única (`gateway`, `event_id`). Um evento repetido responde
     200 sem reprocessar.
  4. **A fonte da verdade é a API, não o payload:** ao receber o evento, o backend consulta a cobrança no gateway
     pela API e aplica o estado lido. Isso resolve eventos fora de ordem e um token vazado.
  5. **Resposta rápida:** grava o evento, responde 200 e processa de forma assíncrona.
  6. **Segredos** (API key e token do webhook) ficam em variável de ambiente, nunca no repositório nem no log. Se
     forem guardados no banco, usam o `SecretCipher`.
- **F2-RN-06 Estado da fatura e da assinatura.** Os eventos de pagamento confirmado ou recebido marcam a fatura como
  `PAID`, e a assinatura `ACTIVE` volta a ter acesso. Fatura vencida e não paga: `OVERDUE`, e a assinatura fica
  `PAST_DUE`. Estorno ou chargeback: `REFUNDED` e tratamento manual (P2-06). O mapa exato dos eventos é fechado na
  implementação, com a documentação do gateway escolhido.
- **F2-RN-07 Régua de inadimplência** (todos os prazos vêm de P2-01):
  1. **Aviso antes:** `A` dias antes do vencimento, pelo gateway, com um banner no Taxify e um e-mail pelo
     `MailSender` da fase 1.
  2. **Vencimento:** fatura `OVERDUE`, assinatura `PAST_DUE`, banner "pagamento em atraso" para o ADMIN. O acesso
     continua.
  3. **Carência de `C` dias:** depois dela, a assinatura vira `BLOCKED` com o motivo `NON_PAYMENT` e cai em
     **somente leitura, com as capturas paradas** (F1-RN-08 a F1-RN-10, decisão 3 do Tony), incluindo o aviso dos
     90 dias da SEFAZ.
  4. **Reativação automática:** o webhook de pagamento (ou a conciliação) volta a assinatura para `ACTIVE` na hora,
     e as capturas retomam pela F1-RN-10. Meta: menos de 10 min depois da confirmação do gateway.
  5. **Cancelamento:** depois de `X` dias bloqueado sem pagamento, conforme P2-08.
  6. O admin da plataforma pode **adiar** o bloqueio de um escritório, com motivo e evento gravados.
- **F2-RN-08 Conciliação diária.** Um agendador consulta no gateway as cobranças das assinaturas ativas e corrige
  divergências (webhook perdido), gravando um evento `source = reconciliation`. Divergência corrigida vira log de
  aviso.
- **F2-RN-09 Troca de plano e de forma de pagamento.** O ADMIN troca o plano ou a forma de pagamento. A alteração é
  enviada ao gateway e vale a partir da próxima fatura. Pró-rata: P2-05. Um downgrade abaixo do uso segue a
  F3-RN-07.
- **F2-RN-10 Cancelamento pelo escritório.** `POST /billing-taxify/cancel` cancela a assinatura no gateway. O acesso
  segue até o fim do período pago, e depois a situação vira `CANCELED`, em somente leitura com as exportações que já
  existem (decisão 5 do Tony). A retenção segue P2-08.
- **F2-RN-11 NFS-e da CS Hub.** Cada fatura paga gera a NFS-e da CS Hub pelo gateway (se for o Asaas, a emissão
  automática da assinatura) ou por outro processo (P2-07). O Taxify guarda só o número e o status. O enquadramento
  fiscal do serviço **não é definido aqui** (P2-07).
- **F2-RN-12 LGPD com o gateway.** Só vão para o gateway os dados de cobrança do escritório (razão social, CNPJ,
  e-mail financeiro, telefone e endereço se o gateway exigir), nunca dado de empresa cliente. O gateway entra como
  suboperador na política de privacidade que o Tony está providenciando.

### Critérios de aceite

- **CA2-01 (F2-RN-03)** Dado um escritório em teste, quando o ADMIN contrata o plano "P" por boleto, então o cliente
  e a assinatura são criados no sandbox do gateway, a resposta traz o link da fatura, e a `firm_subscription` guarda
  os ids do gateway e o plano.
- **CA2-02 (F2-RN-04)** Quando o ADMIN escolhe cartão, então o navegador é levado à página do gateway (ou tokeniza
  com o SDK), e nenhuma requisição ao backend do Taxify carrega número ou CVV. Isso é conferido por inspeção do
  tráfego e pela ausência de campos de cartão nos DTOs.
- **CA2-03 (F2-RN-05)** Dado um webhook com o `asaas-access-token` errado, quando ele chega, então a resposta é 401,
  e nenhuma fatura muda. Com o token certo e o mesmo `event_id` enviado 3 vezes, a fatura muda uma vez só, e as 3
  respostas são 200.
- **CA2-04 (F2-RN-05)** Dado um webhook de pagamento confirmado com token válido, mas que a API do gateway diz que
  está pendente, quando ele é processado, então vale o estado da API: a fatura fica PENDING.
- **CA2-05 (F2-RN-07)** Dada uma fatura vencida há `C` + 1 dias, quando a régua roda, então o escritório fica
  `BLOCKED` com `NON_PAYMENT`, em somente leitura e com as capturas paradas. Quando o webhook de pagamento chega,
  então a situação volta a `ACTIVE`, e a próxima requisição de escrita passa.
- **CA2-06 (F2-RN-08)** Dado um pagamento confirmado no gateway cujo webhook não chegou, quando a conciliação roda,
  então a fatura vira PAID, e o escritório é reativado.
- **CA2-07 (F2-RN-10)** Dada uma assinatura paga até 2026-12-31, quando o ADMIN cancela em 2026-12-10, então o
  acesso continua até 2026-12-31, e em 2027-01-01 a situação é `CANCELED`, em somente leitura.
- **CA2-08 (F2-RN-12)** Quando o cliente é criado no gateway, então o payload enviado só tem os dados do escritório.

### Casos de teste (fase 2)

Todos no sandbox, com escritórios e CNPJs fictícios.

| # | Cenário | Esperado | RN |
|---|---|---|---|
| CT2-01 | Contratação por Pix no teste | Assinatura criada; fatura com QR; quando paga (simulação no sandbox), ACTIVE | F2-RN-03, F2-RN-06 |
| CT2-02 | Contratação por cartão | Checkout do gateway; o backend não recebe dado de cartão | F2-RN-04 |
| CT2-03 | Webhook sem token ou com token errado | 401; nada gravado | F2-RN-05 |
| CT2-04 | Webhook repetido 3 vezes | 1 processamento, 3 respostas 200 | F2-RN-05 |
| CT2-05 | Eventos fora de ordem (OVERDUE chega depois de RECEIVED) | O estado final é PAID (consultado na API) | F2-RN-05 |
| CT2-06 | Inadimplência completa | D0 OVERDUE (acesso normal) → D+C BLOCKED (somente leitura, capturas paradas) → pagamento → ACTIVE em menos de 10 min, e as capturas retomam | F2-RN-07 |
| CT2-07 | Bloqueio adiado pelo admin | Sem bloqueio no D+C; evento gravado | F2-RN-07 |
| CT2-08 | Webhook perdido | A conciliação corrige no dia seguinte | F2-RN-08 |
| CT2-09 | Fatura com valor zero ou cupom de 100% (se existir, P2-03) | Sem cobrança no gateway, ou cobrança tratada conforme decisão; o acesso não depende de pagamento | F2-RN-06 |
| CT2-10 | Estorno ou chargeback | REFUNDED, alerta ao admin, sem bloqueio automático (P2-06) | F2-RN-06 |
| CT2-11 | Upgrade no meio do ciclo | O gateway é atualizado; o valor vale na próxima fatura (ou pró-rata, P2-05) | F2-RN-09 |
| CT2-12 | Cancelamento | Acesso até o fim do período pago, depois CANCELED em somente leitura | F2-RN-10 |
| CT2-13 | NFS-e | Fatura paga gera a NFS-e (sandbox), e o número fica gravado | F2-RN-11 |
| CT2-14 | Log | Nenhuma linha de log com o token, a API key ou dado de cartão | F2-RN-04, F2-RN-05 |
| CT2-15 | Escritório migrado sem CNPJ | Contratação pede o CNPJ, o e-mail financeiro e o telefone antes de criar o cliente | F2-RN-03 |

### Tarefas (fase 2)

| # | Tipo | Tarefa | Depende de |
|---|---|---|---|
| B2-1 | Backend | `platform_plans` + CRUD `/platform/plans` (F2-RN-01) | P2-03 |
| B2-2 | Backend | `SaasPaymentGateway` + implementação do gateway escolhido (cliente, assinatura, cobrança) com testes contra um mock da API | P2-02 |
| B2-3 | Backend | Contratação, troca e cancelamento em `/billing-taxify/**`; `platform_invoices`; exigência dos dados do escritório para os migrados (F2-RN-03, F2-RN-09, F2-RN-10) | B2-1, B2-2 |
| B2-4 | Backend | Webhook: autenticação, idempotência, consulta à API, processamento assíncrono (F2-RN-05, F2-RN-06) | B2-2 |
| B2-5 | Backend | Régua de inadimplência e adiamento manual (F2-RN-07), usando o `FirmAccessService` e o `MailSender` da fase 1 | B2-4, P2-01 |
| B2-6 | Backend | Conciliação diária com o `IntegrationJobRunner` (F2-RN-08) | B2-2 |
| B2-7 | Backend | NFS-e pelo gateway (F2-RN-11) | P2-07 |
| B2-8 | Infra | Rota do webhook no Caddy, restrição de IP (se houver lista) e segredos no `.env` da EC2 (deploy só com aprovação do Tony) | B2-4 |
| F2-1 | Frontend | Tela "Assinatura do Taxify" para o ADMIN: plano atual, faturas com o link do gateway, contratar, trocar plano ou forma de pagamento, cancelar | B2-3 |
| F2-2 | Frontend | Banners da régua (a vencer, em atraso) e o botão "Pagar" na faixa de somente leitura | B2-5 |
| F2-3 | Frontend | Console: catálogo de planos; faturas e situação por escritório; adiar bloqueio | B2-1, B2-5 |
| Q2-1 | QA | CT2-01, CT2-02, CT2-11 a CT2-13, CT2-15 no sandbox | B2-3 |
| Q2-2 | QA | CT2-03 a CT2-05, CT2-14: segurança do webhook (inclui teste com token forjado e replay) | B2-4 |
| Q2-3 | QA | CT2-06 a CT2-10: régua, conciliação e casos de borda | B2-5, B2-6 |

---

## 9. Fase 3: limites por plano

A fase 1 já entrega a contagem, a trava contra concorrência, o 409 de limite, o bloqueio na importação da Acessórias
e o `skippedByLimit` (F1-RN-10a), hoje só com os limites do teste. A fase 3 **troca a fonte do limite** para o plano
da assinatura (3a) e acrescenta os módulos (3b).

### O que o código mostra (para esta fase)

- A empresa tem `active` (null/true/false), que pode ser editado no formulário (`mapper/CompanyMapper.java:124`) e
  que a Acessórias reativa (`AcessoriasImportService.java:211`).
- A consulta da assinatura vigente do Plano de honorário está repetida em 10 lugares (`FiscalXmlSyncScheduler`,
  `SefazDistScheduler`, `SefazManifestService`, `SefazNotesService`, `NfseAdnScheduler`, `AuditScheduler`,
  `FiscalXmlRectificationScheduler`, `CompanyCertificateService`, `PendencyFinder`, `BillingSyncService`).
- Não existe enum de módulo. Fronteira, retenções, pendências, auditoria e manifestação usam todas `INVOICES_VIEW` no
  front (`app.routes.ts:49-97`).

### Regras de negócio

- **F3-RN-01 Limite efetivo.**

  | Situação | Limite |
  |---|---|
  | TRIAL | Os limites de configuração da fase 1 (F1-RN-10a) |
  | Assinatura com plano | `max_companies`/`max_users` do plano (nulo = ilimitado; 0 ou negativo dá 400 `error.platform.plan.limit.invalid`) |
  | ACTIVE liberado à mão ou pela migração, sem plano | Ilimitado (proposta, P3-02) |
  | Escritório isento (F1-RN-12) | Ilimitado |
  | Bloqueado | Não se aplica: já está em somente leitura |

- **F3-RN-02 Contagem.** É a mesma da F1-RN-10a (P1-14).
- **F3-RN-03 Mensagem com plano.** O 409 de limite traz o `planName` do plano. Mensagem: "Seu plano permite {limite}
  empresas ativas e o escritório já tem {emUso}. Amplie o plano em Assinatura do Taxify."
- **F3-RN-04 Limite atingido: bloquear ou subir de faixa.** Com planos por faixa, há duas políticas possíveis
  (P3-03):
  - (a) **bloquear** com 409, que é o comportamento da fase 1;
  - (b) **subir automaticamente** para a faixa seguinte e cobrar a diferença. Nesse caso, o 409 vira "confirma a
    mudança para o plano X, de R$ Y?", e a troca segue a F2-RN-09.
- **F3-RN-05 Pontos de bloqueio.** São os mesmos da F1-RN-10a (empresa, reativação, Acessórias, usuário), agora para
  qualquer situação com limite efetivo, inclusive o agendador incremental da Acessórias (P3-04).
- **F3-RN-06 Nada é apagado por limite.** Nenhuma regra exclui, inativa ou desvincula nada.
- **F3-RN-07 Acima do limite (downgrade).** O downgrade abaixo do uso é aceito, com `overLimit = true` no console e no
  card. Novas criações ficam bloqueadas até `emUso < limite` (proposta; P3-03 decide se exige reduzir antes). A
  conversão do teste para um plano menor que o uso do teste segue a mesma regra.
- **F3-RN-08 Mudança vale na hora.** Upgrade, downgrade e vaga liberada valem na próxima requisição.
- **F3-RN-09 Uso visto pelo escritório.** `GET /firm-plan` (só ADMIN, P3-06) devolve o plano, os limites, o uso e
  `overLimit`. O console mostra as mesmas contagens (F1-RN-15).
- **F3-RN-10 Módulos (3b).** O plano tem uma lista de módulos (enum `TaxifyModule`, com os valores de P3-07). Um
  endpoint de módulo não liberado responde **403 `error.plan.module.disabled`**. A anotação `@RequiresModule` é
  avaliada junto com `@RequiresPermission` (precisa das duas), **a cada requisição**. O front esconde o menu e as
  rotas pelo `GET /firm-plan`. Os agendadores pulam o escritório sem o módulo. O teste e o isento têm todos os
  módulos (proposta, P3-07).
- **F3-RN-11 Serviço efetivo da empresa (3b).** Uma rotina por empresa roda só se o Plano de honorário tiver o serviço
  **e** o Plano Taxify tiver o módulo. As 10 consultas repetidas passam por um `EntitlementService` único, que também
  consulta a `FirmAccessService.allowsAutomaticRoutines` da fase 1. `MULTI_USER_ACCESS` e `REPORTS` saem do Plano de
  honorário se P3-08 aprovar.

### Critérios de aceite

- **CA3-01 (F3-RN-01, F3-RN-03)** Dado o plano "Básico" com `max_companies = 10` e 10 ativas, quando o ADMIN faz
  `POST /companies`, então a resposta é 409 com `limit = 10` e `planName = "Básico"`. Com 9, a resposta é 201.
- **CA3-02 (F3-RN-01)** Dado um escritório migrado ACTIVE sem plano, com 300 empresas, quando o ADMIN cria uma
  empresa, então a resposta é 201.
- **CA3-03 (F3-RN-07)** Dadas 30 ativas e um downgrade para o plano de 20, quando ele é gravado, então nada muda nas
  empresas, `overLimit = true`, e `POST /companies` responde 409 até o uso ficar abaixo de 20.
- **CA3-04 (F3-RN-08)** Dado o escritório no limite, quando o admin faz upgrade, então o `POST /companies` seguinte
  responde 201, sem novo login.
- **CA3-05 (F3-RN-07)** Dado o teste com 5 empresas, quando o escritório contrata um plano de 3, então a conversão é
  aceita com `overLimit = true`, e nada é inativado.
- **CA3-06 (F3-RN-10)** Dado o plano sem FRONTEIRA, quando um FISCAL chama um endpoint da Fronteira, então a resposta
  é 403 `error.plan.module.disabled`, e o menu não aparece.

### Casos de teste (fase 3)

| # | Cenário | Entrada | Esperado | RN |
|---|---|---|---|---|
| CT3-01 | Empresa N+1 no plano | "Básico" 10, uso 10 | 409, `planName="Básico"` | F3-RN-01, F3-RN-03 |
| CT3-02 | Plano ilimitado | `max_companies` nulo, uso 1.200 | 201 | F3-RN-01 |
| CT3-03 | Migrado sem plano | ACTIVE sem plano, 300 empresas | 201 | F3-RN-01 |
| CT3-04 | Downgrade acima do uso | 30 ativas, 50 → 20 | `overLimit`; 409 até o uso ficar em 19; depois 201 | F3-RN-07 |
| CT3-05 | Upgrade | 10/10, 10 → 20 | 201 sem relogar | F3-RN-08 |
| CT3-06 | Conversão do teste para plano menor | Teste com 5, plano de 3 | Aceito, `overLimit`, nada inativado | F3-RN-07 |
| CT3-07 | Acessórias pelo agendador com plano | Plano 10, uso 8, 5 novas pelo cron | 2 criadas, `skippedByLimit=3` | F3-RN-05 |
| CT3-08 | Usuário com plano | `max_users=3`, uso 3 | 409 `error.plan.limit.users` | F3-RN-05 |
| CT3-09 | Desativar e reativar usuário | Uso 3 → DELETE `u` → cria outro → tenta reativar `u` | 204; 201; reativar não existe hoje (o PUT responde 404 porque `checkExist` ignora excluídos; recriar com o mesmo login esbarra em `uk_users_login`) | F3-RN-02, P1-14 |
| CT3-10 | Bloqueado com plano | BLOCKED tenta criar | 403 `error.firm.access.blocked`, não 409 | F3-RN-01 |
| CT3-11 | Módulo desligado (3b) | Plano sem FRONTEIRA | 403 `error.plan.module.disabled`; menu escondido; o agendador do e-Fisco pula | F3-RN-10 |

### Tarefas (fase 3)

| # | Tipo | Tarefa | Depende de |
|---|---|---|---|
| B3-1 | Backend | `PlanLimitService` passa a ler o limite do plano (F3-RN-01) e o `planName`; `overLimit` | B1-9, B2-1 |
| B3-2 | Backend | Downgrade e conversão acima do uso (F3-RN-07); política da P3-03 | B3-1, P3-03 |
| B3-3 | Backend | `GET /firm-plan` e contagens com o plano no console (F3-RN-09) | B3-1 |
| B3-4 | Backend (3b) | `TaxifyModule`, `@RequiresModule`, `EntitlementService` no lugar das 10 consultas, agendadores por módulo (F3-RN-10, F3-RN-11) | P3-07 |
| F3-1 | Frontend | Card "Uso do plano" no modal do escritório (ADMIN) | B3-3 |
| F3-2 | Frontend | Mensagem do 409 com o plano e o link "Ampliar plano" | B3-1, F2-1 |
| F3-3 | Frontend | Renomear "Planos" para "Planos de honorário" | P3-05 |
| F3-4 | Frontend (3b) | `moduleGuard`, menu por módulo, módulos no formulário de plano do console | B3-4 |
| Q3-1 | QA | CT3-01 a CT3-10 | B3-1, B3-2 |
| Q3-2 | QA (3b) | CT3-11 | B3-4 |

---

## 10. Endpoints novos (resumo)

Base: `/taxify-service/api/v1`.

Convenção do projeto: endpoints de criação respondem **200**, não 201 (vale também para os textos das fases 2 e 3).

| Método | Caminho | Acesso | Fase |
|---|---|---|---|
| GET | `/signup/config` → `{available, termsVersion, termsUrl, privacyUrl, dpaUrl, trialDays, verificationHours}` | anônimo; o front esconde "Criar conta" com `available=false` | 1 |
| POST | `/signup` | anônimo, com limite por IP e trava de produção | 1 |
| POST | `/signup/verify`, `/signup/resend` | anônimo; o reenvio tem teto por hora, por dia e por IP (F1-RN-03) | 1 |
| POST | `/users` | **só ADMIN** (deixa de ser anônimo) | 1 |
| GET | `/firm-access` | logado (sempre liberado) | 1 |
| GET | `/platform/firms` | `@PlatformAdminOnly` | 1 |
| POST | `/platform/firms/{id}/access/{extend-trial\|convert-to-trial\|release\|suspend\|reactivate\|cancel\|mark-reviewed}` | `@PlatformAdminOnly` | 1 |
| GET | `/platform/firms/{id}/events` | `@PlatformAdminOnly` | 1 |
| PUT | `/platform/firms/{id}/contact` → `{documentNumber, billingEmail, contactPhone}` (nulo mantém o valor) | `@PlatformAdminOnly` (F1-RN-15) | 1 |
| GET, POST, PUT, DELETE | `/platform/plans[/{id}]` | `@PlatformAdminOnly` | 2 |
| GET, POST | `/billing-taxify` (situação e faturas), `/billing-taxify/subscribe`, `/billing-taxify/change`, `/billing-taxify/cancel` | ADMIN do escritório, liberado mesmo bloqueado | 2 |
| POST | `/webhooks/payments/{gateway}` | público + token/assinatura (F2-RN-05) | 2 |
| GET | `/firm-plan` | ADMIN do escritório | 3 |

Endpoints que já existiam e ganharam `@ReadOnlyOperation` na fase 1 estão na tabela da F1-RN-08 (inclui
`POST /fronteira/memoria/buscar` e `POST /fronteira/regras/testar`).

## 11. Configuração nova

| Variável | Fase | Padrão | Efeito |
|---|---|---|---|
| `TAXIFY_SIGNUP_ENABLED` | 1 | `false` | Liga o cadastro público (F1-RN-11) |
| `TAXIFY_TERMS_VERSION`, `TAXIFY_TERMS_URL`, `TAXIFY_PRIVACY_URL`, `TAXIFY_DPA_URL` | 1 | vazio | Versão e links dos termos. Uma versão começando com `RASCUNHO` mantém o cadastro fechado |
| `TAXIFY_TRIAL_DAYS` | 1 | `14` | Duração do teste (F1-RN-05) |
| `TAXIFY_TRIAL_MAX_COMPANIES` | 1 | `5` | Empresas em uso permitidas no teste (F1-RN-10a, decisão 13) |
| `TAXIFY_TRIAL_MAX_USERS` | 1 | `2` | Usuários permitidos no teste, contando o ADMIN (F1-RN-10a, decisões 13 e 14) |
| `TAXIFY_SIGNUP_MAX_PER_IP_PER_DAY` | 1 | `3` | Limite por IP (F1-RN-06) |
| `TAXIFY_TRUSTED_PROXIES` | 1 | vazio (= loopback e redes privadas) | Proxies cujo `X-Forwarded-For` é aceito (F1-RN-06). **Produção deve fixar a sub-rede do Docker Compose** |
| `TAXIFY_EMAIL_VERIFICATION_HOURS` | 1 | `24` | Validade do link de verificação, em horas a partir da emissão (F1-RN-03, decisão 16) |
| `TAXIFY_EMAIL_RESEND_PER_HOUR` | 1 | `3` | Reenvios do e-mail de verificação por login em 60 minutos (F1-RN-03, decisão 16) |
| `TAXIFY_EMAIL_RESEND_PER_DAY` | 1 | `5` | Reenvios por login por dia civil de Recife (F1-RN-03, decisão 17) |
| `TAXIFY_EMAIL_RESEND_PER_IP_PER_DAY` | 1 | `10` | Reenvios por IP por dia civil de Recife, somando os logins (F1-RN-03, decisão 17) |
| `TAXIFY_UNVERIFIED_PURGE_DAYS` | 1 | `7` | Dias até o expurgo do cadastro não verificado (F1-RN-03, decisão 16) |
| `TAXIFY_UNVERIFIED_PURGE_CRON` | 1 | `0 20 3 * * *` | Horário do expurgo dos não verificados (Recife) (F1-RN-03) |
| `TAXIFY_ACCESS_CRON` | 1 | `0 10 0 * * *` | Horário do agendador que grava BLOCKED e o evento (Recife) (F1-RN-07) |
| `TAXIFY_UNBLOCK_RECOVERY_MONTHS` | 1 | `3` | Quantos meses para trás, a partir do mês atual, a reativação recupera no SIEG (NF-e e NFS-e) e no e-Fisco. Alinhado a `FISCAL_XML_PURGE_RETENTION_MONTHS=3` (F1-RN-10, decisão 20) |
| `TAXIFY_TRIAL_NOTICE_CRON` | 1 | `0 0 9 * * *` | Horário dos e-mails de aviso (Recife) (F1-RN-16) |
| `TAXIFY_MAIL_FROM` | 1 | `nao-responda@cshub.com.br` | Remetente dos e-mails (F1-RN-17, decisão 16) |
| `TAXIFY_MAIL_PROVIDER` (`SES` ou `LOG`) | 1 | `LOG` | Envio de e-mail. Produção usa `SES` depois da I1-1 |
| `TAXIFY_MAIL_SES_REGION` | 1 | `us-east-2` (confirmar na I1-1) | Região da conta AWS onde o SES é configurado |
| `TAXIFY_MAIL_LOG_BODY` | 1 | `false` | A implementação de log escreve o corpo do e-mail (com o link). **Só desenvolvimento**; nunca ligado na EC2 (F1-RN-17) |
| `TAXIFY_PUBLIC_URL` | 1 | `https://taxify.cshub.com.br` | Base dos links dos e-mails (verificação e avisos) |
| `PLATFORM_FIRM_ID` | 1 | vazio | Escritório da CS Hub (admin da plataforma e isenção). **Pré-requisito de deploy**: sem ele no `.env` da EC2, ninguém é admin da plataforma |
| `PLATFORM_ADMIN_LOGINS` | 1 | vazio (sai o `admin`) | Logins do admin da plataforma |
| `TAXIFY_PAYMENT_GATEWAY`, `ASAAS_API_KEY`, `ASAAS_WEBHOOK_TOKEN`, `ASAAS_BASE_URL` (sandbox ou produção) | 2 | — | Gateway |

## 12. Fora do escopo

- Emissão da cobrança dos **clientes do escritório** (o Plano de honorário continua como está).
- Exportações novas para o escritório bloqueado: valem as que já existem (decisão 5).
- Captcha (decisão 8). Avisos por SMS ou WhatsApp. Novo aceite de termos quando a versão mudar.
- Integração do gateway com a Conta Azul da CS Hub (P2-09).
- Cupom, desconto, afiliados e split.
- Limites de volume: notas por mês, armazenamento, consultas ao e-Fisco.
- Inativar e reativar usuário: fica fora da fase 1 por decisão do Tony (decisão 14, P1-14). Continua só a exclusão
  definitiva.
- Reprocessamento à parte do período parado para auditoria, retificação, pendências, certidões e Conta Azul: elas
  voltam com a janela normal de cada uma (F1-RN-10). As capturas do SIEG e do e-Fisco **têm** recuperação dos meses
  bloqueados, até `TAXIFY_UNBLOCK_RECOVERY_MONTHS` (decisão 20).
- Exclusão definitiva dos dados depois da retenção, a não ser como tarefa separada depois de P2-08.
- Restrição de caracteres no nome do escritório (decisão 19).

## 13. Perguntas em aberto

### Fase 1: teste grátis e liberação

Respondidas (as RNs estão na seção 7):

| # | Resposta |
|---|---|
| P1-01 Prazo do teste | Respondida em 2026-11-26: 14 dias, com limite pequeno de empresas e usuários configurável (F1-RN-05, F1-RN-10a). Os valores estão em P1-13 |
| P1-02 Modo do cadastro | Respondida em 2026-11-26: teste direto, depois da verificação do e-mail (F1-RN-03) |
| P1-03 Efeito do bloqueio | Respondida em 2026-11-26: somente leitura para teste vencido, suspensão e inadimplência (F1-RN-08) |
| P1-04 Agendadores no bloqueio | Respondida em 2026-11-26: todas as capturas param; a SEFAZ retoma do último NSU; aviso dos 90 dias (F1-RN-09, F1-RN-10). As rotinas que não são captura estão em P1-15 |
| P1-05 Exportação | Respondida em 2026-11-26: valem as exportações que já existem, sem nada novo (F1-RN-08) |
| P1-06 Jurídico | Respondida em 2026-11-26: os documentos ainda não existem, e o Tony vai providenciar. O aceite é obrigatório, com versão, data e hora e IP, e o cadastro não abre em produção sem a versão real (F1-RN-11). **Pendência de go-live, não de código** |
| P1-07 Dados do cadastro | Respondida em 2026-11-26: CNPJ validado e único, e-mail financeiro separado e telefone/WhatsApp (F1-RN-02) |
| P1-08 Antiabuso | Respondida em 2026-11-26: verificação de e-mail e limite por IP (padrão 3 por dia), sem captcha (F1-RN-03, F1-RN-06). Os parâmetros do e-mail estão em P1-16 |
| P1-09 Isenção | Respondida em 2026-11-26: só a CS Hub; o admin pode estender o teste de um escritório (F1-RN-12, F1-RN-14) |
| P1-10 Escritórios existentes | Respondida em 2026-11-26: liberados sem prazo e revisados um a um no console (F1-RN-13, F1-RN-14) |
| P1-11 Console | Respondida em 2026-11-26: nome, e-mail e telefone só do ADMIN, mais as contagens; nenhum dado fiscal de cliente (F1-RN-15) |
| P1-12 Avisos | Respondida em 2026-11-26: faixa com os dias restantes e e-mails 3 dias e 1 dia antes e no vencimento (F1-RN-16) |
| P1-13 Valores do limite do teste | Respondida em 2026-11-26: 5 empresas e 2 usuários por padrão, configuráveis em `TAXIFY_TRIAL_MAX_COMPANIES` e `TAXIFY_TRIAL_MAX_USERS` (decisão 13, F1-RN-10a, seção 11) |
| P1-14 O que conta no limite | Respondida em 2026-11-26, proposta aceita inteira: empresa inativa não ocupa vaga; filial conta à parte (cada CNPJ completo é uma empresa, não pela raiz); o ADMIN conta como usuário; não se cria "inativar/reativar usuário" agora, continua só a exclusão (decisão 14, F1-RN-10a, seção 12) |
| P1-15 Rotinas que não são captura no bloqueio | Respondida em 2026-11-26: param todas (auditoria automática, retificação, pendências, certidões e sincronização da Conta Azul do próprio escritório) e voltam ao reativar (decisão 15, F1-RN-10) |
| P1-16 Parâmetros e infraestrutura do e-mail | Respondida em 2026-11-26: link vale 24 h; cadastro não verificado apagado em 7 dias; até 3 reenvios por hora; remetente `nao-responda@cshub.com.br`; a CS Hub configura o SES na AWS (domínio, DKIM/SPF, saída do sandbox) seguindo o passo a passo da I1-1 (decisão 16, F1-RN-03, F1-RN-17, seção 11) |

Decididas pelo Tony durante a implementação e a revisão (2026-11-26): teto diário de reenvio (decisão 17), busca da
memória da Fronteira liberada no bloqueio (decisão 18), nome do escritório sem restrição de caracteres (decisão 19) e recuperação dos meses bloqueados nas capturas por janela (decisão 20, QA-02).

Ainda em aberto: **nenhuma**. A fase 1 está pronta. Ficam só as pendências de go-live do cadastro público, que não são
de código: a versão real dos termos (F1-RN-11), o SES configurado (I1-1, incluindo a confirmação da região) e
`PLATFORM_FIRM_ID` e `TAXIFY_TRUSTED_PROXIES` no `.env` da EC2.

### Fase 2: cobrança
- **P2-01 Régua.** Quantos dias antes do vencimento avisar (`A`)? Qual é a carência até bloquear (`C`)? Em quanto
  tempo bloqueado o contrato é cancelado (`X`)? Quem pode adiar o bloqueio? E qual é o gatilho para automatizar (a
  partir de quantos escritórios pagantes)?
- **P2-02 Gateway.** Asaas ou Iugu? Pedir proposta comercial aos dois com o volume esperado. Há conta PJ da CS Hub em
  algum deles?
- **P2-03 Catálogo.** Quais são os planos, as faixas de empresas, os limites de usuários, os módulos, os preços e os
  ciclos (mensal ou anual)? Haverá desconto anual ou cupom?
- **P2-04 Contratação durante o teste.** A primeira cobrança é no ato ou no fim do teste?
- **P2-05 Troca de plano e reajuste.** Pró-rata no upgrade ou só na próxima fatura? Reajuste do catálogo vale para
  quem já é assinante?
- **P2-06 Estorno e chargeback.** Bloqueia na hora ou só gera alerta?
- **P2-07 NFS-e da CS Hub.** Quem emite (o gateway ou outro processo)? Qual é o enquadramento do serviço e a alíquota
  de ISS (questão fiscal da CS Hub)? A prefeitura de Caruaru é atendida pelo emissor do gateway?
- **P2-08 Retenção depois do cancelamento.** Por quanto tempo o escritório cancelado fica em somente leitura e os
  dados ficam guardados, e o que acontece depois (exclusão, anonimização, entrega ao escritório)? Precisa de
  validação do jurídico.
- **P2-09 Contabilidade da CS Hub.** Os recebimentos do gateway precisam ir para a Conta Azul da CS Hub?

### Fase 3: limites
- **P3-01** Movida para P1-14.
- **P3-02 Escritório liberado sem plano.** O escritório liberado à mão ou pela migração, sem plano, continua
  ilimitado (proposta) até ganhar um plano?
- **P3-03 Limite atingido e downgrade.** Bloquear a criação ou subir automaticamente de faixa e cobrar a diferença?
  No downgrade abaixo do uso: aceitar e só bloquear novas criações, ou exigir que o escritório reduza antes?
- **P3-04 Acessórias acima do limite.** A regra da fase 1 (importar o que cabe e pular o excedente) vale também para
  os planos pagos?
- **P3-05 Nome na tela.** Renomear os "Planos" atuais para "Planos de honorário"?
- **P3-06 Quem vê o uso.** Só o ADMIN ou todos? Avisar perto do limite (ex.: aos 90%)?
- **P3-07 Módulos vendáveis.** Qual é a lista (ex.: Notas, Auditoria, Fronteira, Certidões, Retenções, Pendências,
  Manifestação, Apuração, Relatórios, NFS-e)? O teste tem todos os módulos? As capturas por empresa passam a depender
  do plano do escritório?
- **P3-08 Serviços sem uso.** `MULTI_USER_ACCESS` e `REPORTS` saem do Plano de honorário?
