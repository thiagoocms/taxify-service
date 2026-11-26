# Integrações do escritório: SIEG, Acessórias, Conta Azul e e-CPF (as-built)

> Spec retroativa. Descreve o que **já está no código** em 2026-11-26, não uma feature nova.
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend).
> Pacote base do backend: `br.com.cshub.taxify` (abreviado `…`).
> Relacionadas: [plataforma, acesso e empresas](2026-11-26-plataforma-acesso-e-empresas.md) (e-CPF do contador, RN-39)
> e [planos, assinaturas e cobrança](2026-11-26-planos-assinaturas-e-cobranca.md) (uso da Conta Azul).

## Status

**Implementado.**

- Migration: `V13__FirmIntegrations` (credenciais e estado dos jobs por escritório e tipo).
- Backfill em startup: `config/FirmIntegrationMigration` (move as chaves antigas de `accounting_firms` para
  `firm_integrations`).
- Commit de referência: `62f0b33` (timeouts dos clientes Feign no prefixo `spring.cloud.openfeign.client.config`,
  incluindo o `acessoriasClient`).
- O uso da chave SIEG na captura de XML e do e-CPF no e-Fisco/Fronteira fica nas specs desses módulos. Aqui está só a
  configuração.

## Problema e objetivo

O escritório usa sistemas externos que já têm dado que o Taxify precisa. Sem integração, alguém redigita:

- **SIEG:** hub de XML. A chave de API do escritório permite baixar as notas das empresas.
- **Acessórias:** cadastro das empresas clientes, responsáveis por departamento e regime. A importação cria e atualiza a
  carteira e vincula os responsáveis, sem cadastro manual.
- **Conta Azul:** contratos e honorários (OAuth2). Detalhes de uso na spec de cobrança.
- **e-CPF do contador:** acesso ao e-Fisco PE pelas empresas vinculadas ao contador.

Objetivo: cada escritório configura as próprias credenciais num único lugar. As credenciais nunca voltam inteiras para
a tela, e as importações rodam em segundo plano sem duplicar.

Como medir, com o que já existe: o resumo da importação do Acessórias (`AcessoriasImportResultDTO`) traz recebidas,
criadas, atualizadas, sem mudança, vínculos criados, ignoradas, responsáveis não encontrados e erros.

## Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| ADMIN do escritório | Cola as chaves SIEG e Acessórias, conecta a Conta Azul, envia o e-CPF e dispara a importação do Acessórias | Implantação; depois, quando uma chave vence ou é trocada |
| Qualquer usuário logado | Vê quais integrações estão configuradas (as telas usam isso para mostrar botões) | Sempre que abre telas que dependem disso |
| Agendador | Importação incremental do Acessórias às 03:00 | Diário |

## Histórias de usuário

- H1. Como ADMIN, quero salvar a chave de API da SIEG e do Acessórias e ver só os últimos 4 caracteres, para configurar sem expor a chave.
- H2. Como ADMIN, quero remover uma chave, para desligar a integração.
- H3. Como ADMIN, quero conectar a Conta Azul pelo login dela numa janela pop-up, para autorizar o Taxify sem informar a senha da Conta Azul.
- H4. Como ADMIN, quero importar as empresas do Acessórias em segundo plano e ver o resumo, para montar a carteira sem digitar.
- H5. Como ADMIN, quero que a importação vincule cada empresa aos colaboradores que são responsáveis por ela no Acessórias, para que a carteira de cada analista já venha pronta.
- H6. Como ADMIN, quero ver os usuários do Acessórias ao lado do usuário Taxify com o mesmo e-mail, para saber quem ainda precisa de conta.
- H7. Como ADMIN, quero que a importação rode sozinha todo dia trazendo só o que mudou, para manter o cadastro atualizado.

## Regras de negócio

### Credenciais por escritório

- **RN-01 Uma linha por escritório e tipo.** `firm_integrations` tem chave única (`accounting_firm_id`, `type`). Os tipos
  são SIEG, ACESSORIAS e CONTA_AZUL. Fonte: `db/migration/V13__FirmIntegrations.java:35`,
  `enumerated/IntegrationType.java`.
- **RN-02 Quais usam chave de API.** Só SIEG e ACESSORIAS aceitam `PUT /integrations/{type}/api-key`. CONTA_AZUL
  responde 400 `error.integration.api.key.not.supported`. Chave em branco **apaga** a chave. Fonte:
  `service/FirmIntegrationService.java:35` e `:115-122`.
- **RN-03 Chave mascarada.** A tela recebe `••••` mais os últimos 4 caracteres quando a chave tem mais de 8; caso
  contrário, só `••••`. A chave inteira nunca é devolvida. Fonte: `service/FirmIntegrationService.java:124-140`.
- **RN-04 "Configurada".** Uma integração conta como configurada se tiver chave de API, refresh token ou access token.
  Fonte: `domain/billing/FirmIntegration.java:150-152`.
- **RN-05 Lista completa.** `GET /integrations` devolve um status para **cada** tipo, configurado ou não, do escritório
  do usuário. Está aberto a qualquer logado. Fonte: `service/FirmIntegrationService.java:103-111`,
  `rest/FirmIntegrationResource.java:19-39`.
- **RN-06 Escrita só do ADMIN.** Toda escrita em `/integrations/**` é do ADMIN. `/integrations/conta-azul/**` e
  `/integrations/acessorias/**` são só do ADMIN inclusive na leitura. Fonte: `rest/FirmIntegrationResource.java:25`,
  `rest/ContaAzulResource.java:23`, `rest/AcessoriasResource.java:22`.
- **RN-07 Migração das chaves antigas.** Em todo startup, as chaves `sieg_api_key` e `acessorias_api_token` (e
  `acessorias_last_sync_at`) de `accounting_firms` viram linhas em `firm_integrations`, mas só se o escritório ainda não
  tiver a linha. Uma chave trocada pela tela nova nunca é sobrescrita. As colunas antigas ficam no banco para permitir
  rollback. Banco novo sem as colunas: a migração é pulada. Fonte: `config/FirmIntegrationMigration.java:51-95`.

### Conta Azul (OAuth2, authorization code)

- **RN-08 App único.** Um `client_id`/`client_secret` para o sistema todo. Sem `clientId`, `clientSecret` e
  `redirectUri`, a integração fica "não configurada" (502 `error.contaAzul.not.configured` ao conectar). Espaços e
  quebras de linha das variáveis são removidos. Fonte: `integration/contaazul/ContaAzulProperties.java:29-61`,
  `service/ContaAzulAuthService.java:180-184`.
- **RN-09 Início.** `GET /integrations/conta-azul/authorize-url` gera um `state` aleatório (24 bytes em hex), guarda-o
  na integração do escritório com a hora e monta a URL de login com `response_type=code`, `client_id`, `redirect_uri`,
  `state` e `scope`. Fonte: `service/ContaAzulAuthService.java:66-84`.
- **RN-10 Retorno.** A Conta Azul redireciona o navegador para a rota do front `/integracoes/conta-azul/callback`, que
  envia `code` e `state` em `POST /integrations/conta-azul/callback` com a sessão do usuário. O `state` precisa ser igual
  ao guardado (comparação em tempo constante) e ter menos de 10 min. Ele vale **uma vez só**: é apagado em qualquer
  resultado. Se for inválido, a resposta é 400 `error.contaAzul.state.invalid`. Se for válido, o código é trocado por
  tokens e `connected_at` é gravado. Fonte: `service/ContaAzulAuthService.java:42` e `:87-108`, `app.routes.ts:17-26`.
- **RN-11 Renovação do token.** O access token é renovado quando faltam menos de 120 s para expirar. O refresh token é
  substituído quando a Conta Azul devolve um novo, porque ela gira a cada renovação. Sem `expires_in`, vale 3600 s. A
  renovação é sincronizada por escritório dentro da instância. Sem refresh token, a resposta é 502
  `error.contaAzul.not.connected`. Fonte: `service/ContaAzulAuthService.java:43-45` e `:142-170`.
- **RN-12 Desconectar.** `DELETE /integrations/conta-azul/connection` limpa os tokens, `connected_at` e o `state`. A
  linha e a configuração de categorias continuam. Fonte: `service/ContaAzulAuthService.java:118-128`.

### Jobs em segundo plano (Acessórias e cobrança)

- **RN-13 Um job por escritório e tipo.** Ver a spec de cobrança, RN-17: claim atômico, heartbeat a cada 60 s,
  abandono depois de 5 min sem heartbeat, progresso gravado a cada 2 s no máximo e resultado guardado em JSON. O job leva
  o contexto de segurança de quem clicou, e a auditoria mostra esse usuário. Fonte:
  `service/job/IntegrationJobRunner.java:29-108`.

### Importação do Acessórias

- **RN-14 Disparo.** `POST /integrations/acessorias/import` (ADMIN) começa uma importação **completa** em segundo plano
  (202). `GET` faz o acompanhamento. Fonte: `rest/AcessoriasResource.java:35-44`,
  `service/AcessoriasImportService.java:98-110`.
- **RN-15 Leitura da API.** São lidas as páginas de `/companies/ListAll/?departments&registrationData&stateRegistrations`
  até vir uma página vazia, com no máximo 1000 páginas. Na incremental, vai também `DtLastDH` com a data da última
  importação. Sem token: 502 `error.acessorias.api.token.missing`. 401/403: token inválido. 429: limite de requisições.
  Um `{"Erro": …}` com "encontrad"/"nenhum" é tratado como página vazia; com "token", como token inválido; qualquer
  outro é falha com a mensagem da API. Fonte: `integration/acessorias/AcessoriasClient.java:42` e `:64-146`.
- **RN-16 Identificação da empresa.** O identificador fica só com os dígitos: 14 = CNPJ, 11 = CPF. Qualquer outro valor
  (CAEPF etc.) é ignorado com "identificador não é CNPJ nem CPF". A busca é pelo documento **dentro do escritório**,
  incluindo empresas excluídas. Fonte: `service/AcessoriasImportService.java:146-155` e `:262-271` e `:345-352`.
- **RN-17 Criação.** Uma empresa que não existe no escritório só é criada se estiver "Ativa" no Acessórias. Caso
  contrário é ignorada com "inativa no Acessórias". Se o documento já existir na plataforma, em outro escritório ou
  excluído, é ignorada com "documento já cadastrado em outro escritório". Fonte:
  `service/AcessoriasImportService.java:157-171` e `:187-198`.
- **RN-18 Atualização.** Uma empresa existente e não excluída recebe do Acessórias: nome (razão; se vazia, fantasia; se
  vazia, identificador; até 150), fantasia, telefone, UF (maiúscula), rótulo do regime (que reinterpreta o regime
  estruturado, ver RN-30 da spec de plataforma), IEs (substitui a lista inteira), grupo, `active`
  ("Ativa" = true, senão false), cliente desde/até (`0000-00-00` = vazio) e `acessoriasId`. Só conta como "atualizada"
  se algo mudou. Uma empresa excluída no Taxify continua excluída e é ignorada com "excluída no Taxify". Fonte:
  `service/AcessoriasImportService.java:172-181` e `:201-216` e `:273-298`.
- **RN-19 Regime.** Depois de criar ou atualizar, uma mudança de regime vira um período que começa no **mês atual**.
  Fonte: `service/AcessoriasImportService.java:182-183`, `service/CompanyTaxRegimeService.java:92-103`.
- **RN-20 Responsáveis viram carteira.** Para cada e-mail de responsável dos departamentos (normalizado em minúsculas),
  procura-se um usuário Taxify não excluído do escritório com o mesmo e-mail. Se não houver, o e-mail vai para
  `responsiblesNotFound`, porque o usuário **nunca é criado** (falta CPF e senha). Se o vínculo já existir, mesmo
  excluído, nada muda e a remoção manual continua valendo. Caso contrário, é criado `UserCompany` com o papel igual aos
  nomes dos departamentos separados por vírgula (até 50; vazio vira "Responsável"). Fonte:
  `service/AcessoriasImportService.java:222-252`.
- **RN-21 Nada é apagado.** A importação nunca exclui nem desativa nada além de pôr `active = false` em empresas que
  estão inativas no Acessórias. Fonte: `service/AcessoriasImportService.java:54-58`.
- **RN-22 Erro por empresa.** Uma falha numa empresa vai para `errors` com o identificador, e a importação segue.
  **`last_sync_at` é gravado sempre**, mesmo com erros. Fonte: `service/AcessoriasImportService.java:128-138`.
- **RN-23 Agendador.** Roda pelo cron `ACESSORIAS_SYNC_CRON` (padrão `0 0 3 * * *`) em modo **incremental**, para
  escritórios não excluídos com integração ACESSORIAS configurada. Um escritório que falha não para os outros. Desligado
  com `ACESSORIAS_SYNC_ENABLED=false`. Fonte: `service/AcessoriasImportScheduler.java:34-48`,
  `service/FirmIntegrationService.java:83-87`.
- **RN-24 Usuários do Acessórias.** `GET /integrations/acessorias/users` lista os usuários do Acessórias com
  `taxifyUserId` quando existe um usuário Taxify do escritório com o mesmo e-mail. Ativos aparecem primeiro, depois a
  ordem é por nome. É só leitura. Fonte: `service/AcessoriasUserService.java:44-75`.

## Critérios de aceite

Os exemplos usam dados fictícios.

- **CA-01 (RN-02, RN-03)** Dada a chave SIEG `abcd1234efgh5678`, quando o ADMIN a salva, então `GET /integrations` traz
  SIEG com `configured = true` e a chave mostrada como `••••5678`.
- **CA-02 (RN-02)** Dada uma chave SIEG configurada, quando o ADMIN salva uma chave em branco, então SIEG fica com
  `configured = false`.
- **CA-03 (RN-02)** Quando alguém envia `PUT /integrations/CONTA_AZUL/api-key`, então a resposta é 400
  `error.integration.api.key.not.supported`.
- **CA-04 (RN-06)** Dado um usuário FISCAL, quando ele faz `GET /integrations`, então recebe 200. Quando faz
  `PUT /integrations/SIEG/api-key` ou `POST /integrations/acessorias/import`, então recebe 403.
- **CA-05 (RN-10)** Dado um `state` gerado há 11 min, quando o callback chega com esse `state`, então a resposta é 400
  `error.contaAzul.state.invalid`, e um segundo envio com o mesmo `state` também falha.
- **CA-06 (RN-11)** Dado um access token que expira em 60 s, quando uma chamada à Conta Azul precisa do token, então o
  token é renovado antes, e o novo refresh token fica gravado.
- **CA-07 (RN-16, RN-17)** Dada no Acessórias a empresa "11.111.111/0001-11" "Ativa" que não existe no Taxify, quando a
  importação roda, então a empresa é criada com documento `11111111000111` (CNPJ) e conta em `created`.
- **CA-08 (RN-17)** Dada uma empresa "Inativa" no Acessórias que não existe no Taxify, quando a importação roda, então
  ela não é criada e aparece em `skipped` com "inativa no Acessórias".
- **CA-09 (RN-17)** Dado o CNPJ 22.222.222/0001-22 já cadastrado em outro escritório, quando a importação o recebe,
  então ele vai para `skipped` com "documento já cadastrado em outro escritório".
- **CA-10 (RN-18)** Dada uma empresa existente marcada "Inativa" no Acessórias, quando a importação roda, então a
  empresa fica `active = false` e não é excluída.
- **CA-11 (RN-18)** Dada uma empresa excluída no Taxify que continua no Acessórias, quando a importação roda, então ela
  continua excluída e aparece em `skipped` com "excluída no Taxify".
- **CA-12 (RN-20)** Dada a empresa com departamentos "Fiscal" e "Contábil" cujo responsável é `ana@exemplo.com`, e o
  usuário Taxify com esse e-mail sem vínculo, quando a importação roda, então é criado o vínculo com o papel "Fiscal,
  Contábil".
- **CA-13 (RN-20)** Dado o responsável `novo@exemplo.com` sem usuário no Taxify, quando a importação roda, então ele
  aparece em `responsiblesNotFound` como "Nome <novo@exemplo.com>", e nenhum usuário é criado.
- **CA-14 (RN-20)** Dado um vínculo que o ADMIN removeu à mão (excluído), quando a importação roda de novo, então o
  vínculo não é recriado.
- **CA-15 (RN-15)** Dado um token do Acessórias inválido, quando a importação roda, então o job termina FAILED com a
  mensagem de token inválido.
- **CA-16 (RN-07)** Dado um banco antigo com `accounting_firms.sieg_api_key` preenchido e sem linha SIEG em
  `firm_integrations`, quando a aplicação sobe, então é criada a linha SIEG com essa chave. No próximo startup, nada muda.

## Endpoints da API

Base: `/taxify-service/api/v1`.

| Método | Caminho | Permissão |
|---|---|---|
| GET | `/integrations` | logado |
| PUT | `/integrations/{SIEG\|ACESSORIAS}/api-key` (corpo `{ "apiKey": "…" }`) | ADMIN |
| GET | `/integrations/conta-azul/authorize-url` | ADMIN |
| POST | `/integrations/conta-azul/callback` (corpo `{ code, state }`) | ADMIN |
| GET | `/integrations/conta-azul/status` | ADMIN |
| DELETE | `/integrations/conta-azul/connection` | ADMIN |
| POST | `/integrations/acessorias/import` (202) | ADMIN |
| GET | `/integrations/acessorias/import` | ADMIN |
| GET | `/integrations/acessorias/users` | ADMIN |
| GET / POST / DELETE | `/integrations/efisco/certificado` | GET logado; escrita ADMIN (ver spec de plataforma, RN-39) |

### Telas do front

| Local | Componente | Observação |
|---|---|---|
| Modal "Dados do escritório" → Integrações | `features/accounting-firm/firm-integrations/firm-integrations.component.ts` | chaves SIEG e Acessórias (edição, remoção com confirmação), Conta Azul (pop-up com `BroadcastChannel`; se o pop-up for bloqueado, segue na mesma aba) e cobrança |
| Modal → e-Fisco | `features/accounting-firm/firm-integrations/efisco-certificate-card.component.ts` | upload e remoção do e-CPF |
| `/integracoes/conta-azul/callback` | `features/integrations/conta-azul-callback/conta-azul-callback.component.ts` | fora do layout, `authGuard` |
| `/cadastro/empresas` | `features/companies/company-list/company-list.component.ts:105-117` e `:193` | "Importar do Acessórias" só aparece para ADMIN e quando o Acessórias está configurado, com acompanhamento do job e resumo no fim |
| Cadastro de usuário | `features/users/user-form` | preenchido a partir da lista de usuários do Acessórias |
| Serviços HTTP | `core/services/integration.service.ts`, `acessorias.service.ts`, `conta-azul.service.ts`, `conta-azul-popup.ts`, `efisco.service.ts` | |

## Dados e integrações

| Tabela | Migration | Conteúdo |
|---|---|---|
| `firm_integrations` | V13 | `type`, `api_key` (TEXT), `access_token` (TEXT), `refresh_token` (TEXT), `token_expires_at`, `connected_at`, `oauth_state`, `oauth_state_created_at`, `fee_category_ids`, `last_sync_at` e o estado do job |
| `accounting_firms` (colunas legadas) | anteriores ao Flyway | `sieg_api_key`, `acessorias_api_token`, `acessorias_last_sync_at`, mantidas só para rollback |
| `companies.acessorias_id` | V5 | id da empresa no Acessórias, gravado só pela importação |
| `users_companies` | V6 | vínculos criados pela importação |

Sistemas externos:
- **Acessórias**: `https://api.acessorias.com` (`AcessoriasFeignClient`, Bearer com o token do escritório), endpoints
  `companies/ListAll` e `users/ListAll`. Timeouts de 15 s para conexão e 60 s para leitura.
- **Conta Azul**: login em `login.contaazul.com`, token e API em `api-v2.contaazul.com`.
- **SIEG**: só a chave é guardada aqui. O uso está na spec de captura de XML.

## Configuração

| Variável | Padrão | Efeito |
|---|---|---|
| `ACESSORIAS_SYNC_ENABLED` | `true` | liga ou desliga a importação diária |
| `ACESSORIAS_SYNC_CRON` | `0 0 3 * * *` | horário da importação incremental |
| `acessorias.base-url` | `https://api.acessorias.com` | fixo no `application.yaml` |
| `CONTA_AZUL_CLIENT_ID`, `CONTA_AZUL_CLIENT_SECRET` | vazio | app da Conta Azul |
| `CONTA_AZUL_REDIRECT_URI` | o `application.yaml` tem um host EC2 fixo como padrão; o compose passa vazio | precisa ser **idêntico** ao cadastrado no app: `https://DOMINIO/integracoes/conta-azul/callback` |
| `CONTA_AZUL_AUTHORIZE_URL`, `CONTA_AZUL_TOKEN_URL` | URLs públicas | valor em branco mantém o padrão |
| `spring.cloud.openfeign.client.config.acessoriasClient.*` | 15 s / 60 s | timeouts |

## Testes existentes

- `service/FirmIntegrationServiceTest` (máscara, tipos com chave), `config/FirmIntegrationMigrationTest`.
- `service/ContaAzulAuthServiceTest` (state, renovação), `integration/contaazul/ContaAzulBillingProviderTest`.
- `service/AcessoriasImportServiceTest`, `integration/acessorias/AcessoriasClientTest` (paginação, `{"Erro"}`, códigos HTTP).
- `service/job/IntegrationJobRunnerTest`, `config/FeignTimeoutsConfigTest`.

**Lacunas de teste:**
- `AcessoriasUserService` não tem teste.
- `AcessoriasImportScheduler` não tem teste.
- Não há teste da conferência de acesso (ADMIN × não-admin) dos endpoints de integração. Só o guarda genérico de
  anotações cobre isso.
- Não há teste do componente de callback da Conta Azul no front.
- Não há teste de que a importação grava `last_sync_at` mesmo com erros (RN-22), nem de que a incremental pode pular
  empresas que falharam (ver P-03).

## Fora do escopo / limitações conhecidas

- A importação não cria usuários, só vínculos.
- Não há sincronização de volta para o Acessórias, a Conta Azul ou a SIEG.
- Não há rotação nem expiração controlada das chaves de API. Elas valem até alguém trocar.
- A renovação de token da Conta Azul é sincronizada só dentro da instância. Com mais de uma instância, duas renovações
  simultâneas podem invalidar o refresh token.
- CAEPF e identificadores estrangeiros do Acessórias são ignorados.

## Perguntas em aberto

- **P-01 Credenciais sem criptografia.** `api_key`, `access_token` e `refresh_token` ficam em texto puro em
  `firm_integrations`, enquanto os certificados A1 usam `SecretCipher` quando `CERT_ENCRYPTION_KEY` existe. Pelo sigilo
  dos dados de cliente, essas credenciais deveriam usar a mesma criptografia?
- **P-02 Colunas legadas com chaves em claro.** `accounting_firms.sieg_api_key` e `acessorias_api_token` continuam
  preenchidas para permitir rollback. Quando elas podem ser limpas ou removidas?
- **P-03 `last_sync_at` gravado mesmo com erro.** Na importação incremental seguinte, as empresas que falharam e não
  mudaram no Acessórias não voltam (`DtLastDH`). A cobrança faz o contrário (RN-22 da spec de cobrança). Qual
  comportamento vale?
- **P-04 A importação sobrescreve o que foi digitado.** Nome, UF, telefone, grupo, IEs (lista inteira) e o rótulo do
  regime, que redefine o regime estruturado e a atividade, vêm sempre do Acessórias. Uma IE acrescentada à mão ou pelo
  "preencher pelas notas" some se o Acessórias não a tiver. Qual sistema é a fonte da verdade para cada campo?
- **P-05 Regime importado começa no mês atual.** Uma mudança de regime vista na importação abre um período no mês da
  importação, não na data real da mudança. A apuração de meses anteriores pode usar o regime errado. Aceitável?
- **P-06 Mensagem de "documento já cadastrado em outro escritório".** O resumo da importação revela ao ADMIN que o CNPJ
  pertence a outro escritório da plataforma. Isso contradiz a regra de não revelar dado de outro tenant (404 em vez de
  403). A mensagem pode ficar?
- **P-07 Redirect URI padrão no `application.yaml`.** O padrão aponta para um host EC2 fixo, e o compose sobrescreve com
  vazio, o que deixa a Conta Azul "não configurada". Remover o padrão do yaml?
- **P-08 Status da Conta Azul só para ADMIN.** `GET /integrations/conta-azul/status` é ADMIN, mas o modal abre para
  todos e chama esse endpoint. Para não-admin, a chamada volta 403. Liberar a leitura ou esconder a seção?
- **P-09 Erros 502 para falta de configuração.** Token do Acessórias ausente e Conta Azul não configurada respondem 502
  (`AcessoriasIntegrationException`/`ContaAzulIntegrationException`), embora o problema seja de configuração do
  escritório (seria 400 ou 409). O front mostra "Erro no servidor". Mudar?
