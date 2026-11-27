# Esqueci a senha, troca de senha e aviso de pendência alta por e-mail

> Feature nova, pedida pelo Tony em 2026-11-27. São duas entregas independentes na mesma spec:
> - **Feature 1:** "Esqueci a senha" na tela de login e troca da própria senha pelo usuário logado.
> - **Feature 3:** aviso por e-mail de pendência alta.
>
> A numeração (1 e 3) é a do pedido do Tony. A feature 2 não faz parte desta spec.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base:
> `br.com.cshub.taxify` (abreviado `…`).
> Depende de:
> - [Taxify SaaS, fase 1](2026-11-26-taxify-saas-assinatura-do-escritorio.md): `MailSender` (SES/LOG), `FirmMailTemplates`,
>   token de uso único da verificação, `ClientIpResolver`, situação de acesso e `RoutineGate`;
> - [Pendências](2026-11-26-pendencias.md): tabela `pendencies`, severidades, `PendencyScheduler`;
> - [Plataforma, acesso e empresas](2026-11-26-plataforma-acesso-e-empresas.md): usuários, perfis, carteira, JWT;
> - [Arquitetura do backend](2026-11-26-arquitetura-tecnica-backend-e-infra.md), seção 3: convenções e formato único de
>   erro `{timestamp, status, code, message, details}`.
>
> Nenhuma regra **fiscal** é criada aqui. As regras são de segurança, de produto e de comunicação. As que dependiam de
> decisão do Tony foram decididas em 2026-11-27 (seção 10, "Decisões do Tony"). Prazos e limites continuam parâmetros
> configuráveis, com o valor decidido como padrão.

## Status

**Pronta para implementação.** O Tony aceitou em 2026-11-27 todas as recomendações (P-SN-01 a P-SN-12 e P-AV-01 a
P-AV-11), exatamente como propostas (seção 10). **O go-live dos e-mails depende da saída do SES do sandbox.**

- As duas features podem ser implementadas e ir para produção **desligadas** (`TAXIFY_PASSWORD_RESET_ENABLED=false` e
  `TAXIFY_PENDENCY_ALERTS_ENABLED=false`).
- **Go-live com cliente real depende da saída do SES do sandbox** (I1-1 da fase 1). No sandbox, o SES só entrega para
  endereços verificados e tem cota pequena. Um "Esqueci a senha" ligado no sandbox responderia 202 e o e-mail nunca
  chegaria para o usuário comum (ver SN-RN-14 e AV-RN-13).
- A troca de senha pelo usuário logado (SN-RN-09) **não depende do SES** e pode ser ligada antes. O aviso de "senha
  alterada" é que depende.
- **Pré-requisitos de go-live do aviso de pendência** (além do SES), antes de ligar `TAXIFY_PENDENCY_ALERTS_ENABLED`:
  - o time Fiscal validar as severidades ALTA dos tipos de pendência (tabela da seção 1; pergunta 5 da spec de
    pendências);
  - constar na política de privacidade e no acordo de operador que o nome das empresas clientes sai por e-mail
    (AV-RN-12).

---

## 1. Estado atual (confirmado no código em 2026-11-27)

### Senha e sessão

| Item | Hoje | Fonte |
|---|---|---|
| "Esqueci a senha" | Não existe. A tela só diz para pedir ao administrador | `taxify-front/src/app/features/auth/auth.component.ts:150`, `auth.component.html:68-73` |
| Troca pelo próprio usuário | Não existe. `PUT /users/{id}` é `@AdminOnly` | `rest/UserResource.java:30-34` |
| ADMIN redefine a senha de outro | `PUT /users/{id}` com `password` preenchido. Em branco mantém a atual | `validation/UserValidation.java:129-135` |
| Regra de senha no backend | Obrigatória na criação e no máximo **72 bytes** UTF-8 (limite do BCrypt; 400 `error.user.password.invalid`). Sem tamanho mínimo | `UserValidation.java:35`, `:63-66`, `:188-193` |
| Regra de senha no front | Mínimo de 6 caracteres e no máximo 72 bytes, só no cadastro | `auth.component.ts:189` |
| Senha com `:` | O login faz `split(":")` e usa só o segundo pedaço. Senha com `:` é aceita no cadastro e nunca autentica (P-10 da spec de plataforma) | `service/AuthService.java:39-44` |
| Login | Único na plataforma, **contando os excluídos** (`uk_users_login`) | `domain/user/User.java:22`, `UserValidation.java:177-186` |
| E-mail do usuário | Obrigatório (`@NotBlank`), **sem formato validado e sem unicidade** (só índice `idx_users_email`). Dois usuários, do mesmo escritório ou de escritórios diferentes, podem ter o mesmo e-mail | `User.java:23`, `:54-55`, `dto/user/UserDTO.java` |
| JWT | RS256, stateless, validade nominal de 2 h. `iat`/`exp` calculados com `LocalDateTime.now()` e offset fixo `-03:00`; com a JVM em UTC, o token nasce com `iat` 3 h no futuro e vale cerca de 5 h (P-02 da spec de plataforma, R-07 da arquitetura) | `service/TokenService.java:29-56` |
| Revogação de sessão | **Não existe.** Nada é conferido no banco a cada requisição além do usuário que o `CurrentUserService` lê. Um JWT continua válido até expirar, mesmo depois de troca de senha ou exclusão do usuário | `config/SecurityConfig.java:52`, `common/CurrentUserService.java:57-67` |
| JWT de usuário excluído | `CurrentUserService` devolve vazio, e o `TenantAccessService` trata vazio como "sistema" (sem restrição de tenant) (R-02 da arquitetura) | `common/TenantAccessService.java:48-57` |

### E-mail (fase 1)

- `MailSender` com `SesMailSender` (SES v2, role IAM da EC2) e `LogMailSender` (nunca envia), escolhidos por
  `TAXIFY_MAIL_PROVIDER`. Remetente `TAXIFY_MAIL_FROM` = `nao-responda@cshub.com.br`. Texto simples, UTF-8. A falha
  vira `MailSendException` só com classe, código, status e requestId, nunca com os endereços
  (`integration/mail/SesMailSender.java`).
- Padrão de token de uso único em `service/firmaccess/EmailVerificationService.java`: 32 bytes aleatórios em
  base64url, só o SHA-256 gravado (`token_hash` único), `expires_at`, `used_at`, marca de uso atômica (`markUsed`),
  invalidação dos links abertos a cada reenvio, limites por hora, por dia (dia civil de Recife) e por IP, resposta
  202 que não revela a conta, log `signup.resend.limited` sem dado pessoal e limpeza do IP depois do uso.
- `TrialNoticeScheduler` é o molde de agendador de e-mail: `AtomicBoolean` contra execução dupla, chave única que
  torna o envio idempotente e falha de envio que não grava nada (tenta de novo depois).
- Rotas anônimas ficam em `SecurityConfig` (`permitAll`) e levam `@AllowedWhenBlocked` (ex.: `rest/SignupResource.java`).

### Pendências

- Severidades: `ALTA`, `MEDIA`, `BAIXA`, `INFO` (`enumerated/PendencySeverity.java`). Nenhum tipo usa `INFO`.
- Tipos com severidade **ALTA** hoje (`enumerated/PendencyType.java`, `service/audit/AuditRuleDefinition.java`):

  | Tipo | Quando é ALTA | Objeto | Volume esperado por empresa |
  |---|---|---|---|
  | `CERTIFICATE_EXPIRED` | Sempre | COMPANY | 0 ou 1 |
  | `SEFAZ_CAPTURE_ERROR` | Sempre | COMPANY | 0 ou 1 |
  | `NFSE_CAPTURE_ERROR` | Sempre | COMPANY | 0 ou 1 |
  | `MANIFESTATION_DEADLINE` | Sempre (prazo entre 0 e 15 dias) | NOTE ou NOTE_KEY | **até 200** (uma por nota) |
  | `CERTIDAO_PE_POSITIVA` | Quando positiva. Com efeito de negativa a mesma chave fica BAIXA | COMPANY | 0 ou 1 |
  | `CERTIDAO_PE_VENCIDA` | Sempre | COMPANY | 0 ou 1 |
  | `AUDIT_FINDING` | Quando a regra é ALTA: `NUMERO_DUPLICADO`, `ALIQUOTA_INTERESTADUAL`, `RBT12_SUBLIMITE`, `RBT12_LIMITE` (as demais são MÉDIA ou BAIXA) | COMPANY ou NOTE | poucas por competência |

- A severidade é gravada na pendência (`pendencies.severity`) a cada sincronização e pode mudar na mesma chave (caso
  da certidão).
- Uma pendência é uma **causa** (chave natural única por empresa). Ela fecha sozinha quando a causa some e reabre,
  com `first_seen_at` novo, quando a causa volta. Fechada à mão com a causa presente, continua fechada (RN-04 e RN-05
  da spec de pendências).
- `PendencyScheduler` roda de hora em hora no minuto 40 e já respeita o bloqueio do escritório pelo `RoutineGate`
  (`service/pendency/PendencyScheduler.java:73-78`).
- **Não há nenhuma notificação**: a pendência só aparece no painel `/pendencias` (spec de pendências, "Fora do
  escopo").
- A tela `/pendencias` não lê filtros da URL (`pendencies.component.ts`). O link do e-mail precisa disso (F-AV-3).

---

## 2. Problema e objetivo

| Feature | Problema | Objetivo | Como medir |
|---|---|---|---|
| 1 | Cada senha esquecida vira chamado para o ADMIN do escritório ou para a CS Hub. O colaborador não consegue trocar a própria senha, e uma senha que vazou continua valendo até alguém agir | O próprio usuário recupera e troca a senha sem falar com ninguém, e a troca derruba as sessões antigas | Chamados de "esqueci a senha" no suporte da CS Hub: meta **0** no primeiro mês depois do go-live. Contagem dos logs `password.reset.completed` e `password.changed by=self` × `by=admin` |
| 3 | Pendência ALTA (certificado vencido, captura parada, prazo de manifestação, certidão positiva) só é vista por quem abre o painel. Ninguém é avisado | Quem cuida da empresa recebe, uma vez por dia útil, um resumo das pendências ALTA novas da sua carteira | Tempo entre `first_seen_at` e `closed_at` das pendências ALTA, 30 dias antes × 30 dias depois do go-live (dá para medir com a tabela `pendencies` que já existe). Pendências ALTA abertas há mais de 7 dias sem aviso enviado = 0 |

## 3. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| Qualquer usuário do escritório (ADMIN, EMPLOYEE, USER) | Pede o link na tela de login e define a senha nova; troca a própria senha em "Minha conta" | Eventual |
| ADMIN do escritório | Continua podendo redefinir a senha de um colaborador pela tela de usuários | Eventual |
| Analista com acesso às pendências (`INVOICES_VIEW`) | Recebe o resumo diário das pendências ALTA novas das empresas da carteira dele; liga ou desliga o aviso em "Minha conta" | Diário (dias úteis) |
| ADMIN do escritório | Recebe o resumo de todas as empresas do escritório (o ADMIN vê a carteira inteira) | Diário (dias úteis) |
| Agendadores | Resumo diário das pendências; limpeza dos links de senha | Diário |

## 4. Histórias de usuário

**Feature 1**
- H-SN-1. Como usuário que esqueceu a senha, quero receber no meu e-mail um link para definir uma senha nova, informando meu login ou meu e-mail, para voltar a trabalhar sem abrir chamado.
- H-SN-2. Como usuário logado, quero trocar minha senha informando a atual, para manter minha conta segura sem depender do ADMIN.
- H-SN-3. Como usuário, quero que trocar ou redefinir a senha derrube as outras sessões abertas, para que quem tenha minha senha antiga perca o acesso.
- H-SN-4. Como usuário, quero ser avisado por e-mail quando minha senha mudar, para perceber se não fui eu.
- H-SN-5. Como CS Hub, quero que o "Esqueci a senha" não revele quais logins e e-mails existem e não possa ser usado para disparar e-mail em massa, para proteger os escritórios e a reputação do SES.
- H-SN-6. Como ADMIN, quero continuar podendo definir uma senha nova para um colaborador, para resolver quem não tem acesso ao próprio e-mail.

**Feature 3**
- H-AV-1. Como analista Fiscal, quero receber de manhã um e-mail com as pendências ALTA que surgiram nas empresas da minha carteira, para agir antes de virar multa ou nota perdida.
- H-AV-2. Como analista Fiscal, quero ser avisado uma vez só de cada pendência, para o e-mail não virar ruído.
- H-AV-3. Como usuário, quero desligar esses e-mails, para controlar o que chega na minha caixa.
- H-AV-4. Como CS Hub, quero que o e-mail não traga dado fiscal do cliente além do necessário, para cumprir a LGPD e o sigilo profissional.
- H-AV-5. Como CS Hub, quero que escritório bloqueado não receba avisos e que o volume diário tenha teto, para respeitar a fase 1 e não queimar a cota do SES.

---

## 5. Feature 1: "Esqueci a senha" e troca de senha

### Regras de negócio

#### Pedido do link

- **SN-RN-01 Pedido anônimo.** `POST /auth/password/forgot { identifier }`, anônimo, com `@AllowedWhenBlocked`.
  - `identifier` é o **login ou o e-mail**. O backend remove espaços nas pontas e procura, entre os usuários **não
    excluídos**: (a) o usuário com `login` igual; (b) os usuários com `email` igual, sem diferenciar maiúsculas. A
    união das duas buscas são as "contas encontradas".
  - Resposta **sempre 202, sem corpo**, exista ou não conta, esteja ou não acima dos limites, e também com
    `identifier` vazio ou nulo. Nunca 404 nem 429. (Fonte: padrão de `POST /signup/resend`, F1-RN-03.)
  - **Mesmo tempo de resposta:** a busca, a gravação e o envio rodam **depois** da resposta, numa thread virtual.
    Assim a demora do SES não revela que a conta existe. O IP é resolvido antes, pelo `ClientIpResolver`.
  - Com `TAXIFY_PASSWORD_RESET_ENABLED=false`, a resposta é 503 `error.password.reset.unavailable`, e
    `GET /auth/password/config` devolve `{available:false}` (SN-RN-14).
- **SN-RN-02 Quem recebe o link.** Para cada conta encontrada (no máximo 5 por pedido, as de `created_date` mais
  recente; acima disso o log registra `password.reset.too.many.accounts` só com a contagem):
  - **recebe** se o usuário não está excluído, o escritório não está excluído e a situação de acesso do escritório
    **não** é `PENDING_VERIFICATION`. Isso inclui `TRIAL`, `ACTIVE`, `PAST_DUE`, `BLOCKED`, `SUSPENDED`, `CANCELED`,
    o escritório sem `firm_subscription` (tratado como `SUSPENDED`) e o isento;
  - **não recebe** se o escritório está em `PENDING_VERIFICATION`: essa conta ainda não tem login (F1-RN-03), e o
    caminho dela é "Reenviar e-mail de verificação". O log registra `password.reset.skipped reason=PENDING_VERIFICATION`
    sem login nem e-mail;
  - o admin da plataforma usa o mesmo fluxo.
  - **Um e-mail por conta**, enviado ao `email` daquela conta, com o login e o nome do escritório no corpo. Quem pediu
    pelo e-mail compartilhado por duas contas recebe dois e-mails, cada um com o seu link. Ver **P-SN-07**.
- **SN-RN-03 Token.** O mesmo padrão da verificação (F1-RN-03):
  - 32 bytes de `SecureRandom` em base64url. Só o SHA-256 (hex, 64) é gravado em `password_reset_tokens.token_hash`
    (único). O token em claro só existe no corpo do e-mail;
  - vale **uma vez** e por `TAXIFY_PASSWORD_RESET_MINUTES` minutos contados da emissão, padrão **60**
    (`expires_at = emissão + 60 min`);
  - cada pedido novo que gera link **invalida os links abertos** daquela conta (`invalidated_at = agora`);
  - o link é `{TAXIFY_PUBLIC_URL}/auth/redefinir-senha#token=<token>`. O token vai no **fragmento** (`#`), e não na
    query (`?`): o navegador não manda o fragmento ao servidor, então ele não aparece no log de acesso do nginx e do
    Caddy nem no `Referer`. (O link da verificação usa `?token=` e fica como está.)
- **SN-RN-04 Limites de envio.** Valem juntos, e o primeiro que estourar impede o envio para aquela conta. Acima de
  qualquer limite a resposta continua 202, nada é enviado e o log registra `password.reset.limited` sem login, e-mail
  nem IP:
  - **por hora, por conta:** no máximo `TAXIFY_PASSWORD_RESET_PER_HOUR` links nos últimos 60 minutos (padrão **3**);
  - **por dia, por conta:** no máximo `TAXIFY_PASSWORD_RESET_PER_DAY` links no dia civil de `America/Recife` (padrão
    **5**; zera às 00:00 de Recife);
  - **por dia, por IP:** no máximo `TAXIFY_PASSWORD_RESET_PER_IP_PER_DAY` links saídos do mesmo IP no dia civil de
    Recife (padrão **10**), somando todas as contas.
  - A contagem usa `password_reset_tokens` (sobrevive a restart). Pedido para conta inexistente não grava nada e não
    conta: não gera e-mail, então não serve para abuso.
  - Os limites são separados dos da verificação de e-mail (tabelas diferentes).

#### Definição da senha nova

- **SN-RN-05 Validar o link antes de digitar.** `POST /auth/password/reset/check { token }`, anônimo: 204 se o token
  existe, não foi usado, não foi invalidado e não venceu; senão 400 `error.password.reset.token.invalid`. Não consome
  o token. O front usa para mostrar "link vencido" antes de a pessoa digitar a senha.
- **SN-RN-06 Redefinir.** `POST /auth/password/reset { token, newPassword }`, anônimo, `@AllowedWhenBlocked`:
  - token desconhecido, usado, invalidado ou vencido (agora > `expires_at`): 400 `error.password.reset.token.invalid`;
  - usuário ou escritório excluído depois da emissão: 400 `error.password.reset.token.invalid` (mesma resposta);
  - `newPassword` fora da regra de senha (SN-RN-08): 400 com o código da regra. **O token não é consumido**, e a
    pessoa pode tentar de novo dentro da validade;
  - sucesso: 204. Na **mesma transação**: marca o token como usado de forma atômica (dois cliques ao mesmo tempo: só
    um passa; o outro recebe 400), grava a senha com BCrypt, `password_changed_at = agora`,
    `session_version = session_version + 1` (SN-RN-11), invalida os outros links abertos da conta e apaga os
    `requested_ip` dos links da conta (LGPD: o IP só servia para o limite). Depois do commit, envia o aviso de senha
    alterada (SN-RN-12);
  - não faz login automático. O front volta para o login com "Senha alterada. Entre com a nova senha.";
  - log: `password.reset.completed user={id}`.
- **SN-RN-07 Escritório bloqueado.** Redefinir funciona em `BLOCKED`, `SUSPENDED` e `CANCELED`. Depois do login, o
  usuário continua em somente leitura (F1-RN-08). Senha é credencial, não dado do escritório.

#### Regra de senha (vale para todos os caminhos)

- **SN-RN-08 Regra de senha.** Um validador único (`PasswordPolicy`) usado no cadastro (`POST /signup`), na criação e
  edição de usuário (`POST /users`, `PUT /users/{id}`), na redefinição (SN-RN-06) e na troca (SN-RN-09):
  - obrigatória: 400 `error.user.password.required` (já existe);
  - no máximo **72 bytes** em UTF-8: 400 `error.user.password.invalid` (já existe; limite do BCrypt);
  - no mínimo `TAXIFY_PASSWORD_MIN_LENGTH` caracteres, padrão **8**: 400 `error.user.password.too.short`
    (hoje o backend não tem mínimo e o front pede 6);
  - na troca e na redefinição, diferente da senha atual: 400 `error.user.password.same`;
  - sem regra de composição (maiúscula, símbolo): a recomendação atual de mercado é tamanho, não composição;
  - **senha com `:`** passa a funcionar: o login divide `login:senha` só no **primeiro** `:`.
  - **login novo** (cadastro e criação de usuário) não pode conter `:`: 400 `error.user.login.invalid.char` (código
    novo). Logins já gravados continuam valendo (decisão P-SN-10).
  - Senhas já gravadas continuam valendo, mesmo curtas. A regra só vale para senha nova.

#### Troca pelo usuário logado

- **SN-RN-09 Trocar a própria senha.** `PUT /users/me/password { currentPassword, newPassword }`, qualquer usuário
  logado (sem permissão específica; entra na lista de endpoints só com login do teste-guarda
  `PermissionAuthorizationManagerTest`), com `@AllowedWhenBlocked`:
  - senha atual errada: 400 `error.user.password.current.invalid` (400, e não 401/403, para o interceptor do front
    não deslogar);
  - **limite de erro:** depois de `TAXIFY_PASSWORD_CHANGE_MAX_FAILURES` erros de senha atual (padrão **5**) numa
    janela de 60 minutos, as tentativas seguintes respondem 429 `error.user.password.change.rate.limited` até a janela
    acabar, **mesmo com a senha certa**. O contador fica no usuário (sobrevive a restart) e zera numa troca certa;
  - senha nova fora da regra: 400 (SN-RN-08);
  - sucesso: 200 `{ token }`, um JWT novo para a sessão atual continuar. Grava a senha, `password_changed_at`,
    `session_version + 1` (as **outras** sessões caem, SN-RN-11), invalida os links de redefinição abertos e envia o
    aviso (SN-RN-12). Log: `password.changed user={id} by=self`.
- **SN-RN-10 ADMIN redefinindo a senha de outro usuário.** Continua por `PUT /users/{id}` com `password` preenchido
  (RN-06 e RN-10 da spec de plataforma). Muda só isto:
  - aplica a regra de senha (SN-RN-08, sem a checagem de "diferente da atual");
  - grava `password_changed_at`, faz `session_version + 1` (as sessões do colaborador caem) e envia o aviso ao
    colaborador (SN-RN-12). Log: `password.changed user={id} by=admin`;
  - **ADMIN trocando a própria senha** por `PUT /users/{id}` (id = o dele) com `password` preenchido: 400
    `error.user.password.use.self.change`. A própria senha só se troca por SN-RN-09, que exige a atual. Os demais
    campos do próprio cadastro continuam editáveis.
  - Continua valendo a fronteira do tenant: usuário de outro escritório responde 404.

#### Sessões

- **SN-RN-11 Revogação das sessões.** O JWT continua stateless na assinatura, mas passa a ser
  conferido contra o banco a cada requisição:
  - coluna nova `users.session_version int not null default 0` e claim novo `sv` no JWT (`TokenService`);
  - um filtro, depois da autenticação por bearer token, lê o usuário (o mesmo `CurrentUserService`, que já faz uma
    leitura por requisição) e responde **401 `error.auth.session.expired`** quando: o usuário não existe ou está
    excluído; ou o `sv` do token é diferente de `users.session_version`;
  - token sem o claim `sv` (emitido antes do deploy) vale como `sv = 0`, para o deploy não derrubar todo mundo;
  - o `session_version` sobe na redefinição por e-mail (SN-RN-06), na troca pelo usuário (SN-RN-09) e na troca pelo
    ADMIN (SN-RN-10);
  - **por que versão e não data:** comparar `iat` com `password_changed_at` não funciona hoje, porque o `iat` nasce
    3 h no futuro com a JVM em UTC (P-02 da spec de plataforma). A versão não depende do relógio;
  - efeito colateral desejado: o JWT de um **usuário excluído** deixa de funcionar na hora, o que fecha o risco R-02
    da arquitetura (usuário excluído tratado como "sistema", sem filtro de tenant);
  - falha ao ler o usuário numa requisição autenticada responde 401 (falha fechada), nunca "sistema".
  - O front trata 401 como hoje (desloga) e mostra "Sua sessão expirou. Entre de novo."

#### Avisos e trilha

- **SN-RN-12 Aviso de senha alterada.** Depois de qualquer troca (SN-RN-06, SN-RN-09, SN-RN-10),
  um e-mail ao `email` da conta: "A senha do seu login {login} no Taxify foi alterada em {dd/MM/aaaa HH:mm} (horário de
  Recife) {por você pela redefinição por e-mail | por você em Minha conta | pelo administrador do escritório}. Se não
  foi você, fale com o administrador do seu escritório." Sem a senha, sem link de ação. Falha de envio só gera log
  (`password.notice.failed user={id}` com o detalhe seguro da `MailSendException`); a troca não é desfeita.
  Desligável por `TAXIFY_PASSWORD_CHANGED_NOTICE_ENABLED`.
- **SN-RN-13 Log sem dado pessoal.** Os logs desta feature levam só `user={id}` e o motivo. Nunca login, e-mail, IP,
  token ou senha (convenção 3.8.9 da arquitetura). `TAXIFY_MAIL_LOG_BODY` continua `false` na EC2, porque o corpo do
  e-mail de redefinição leva o token.
- **SN-RN-14 Ligar a feature.** `GET /auth/password/config` (anônimo) devolve `{available, resetMinutes, minLength}`.
  Com `available=false`, a tela de login mantém o texto de hoje ("Peça ao administrador…") e não mostra o formulário.
  `available` = `TAXIFY_PASSWORD_RESET_ENABLED`. **Na EC2, só ligar depois da saída do SES do sandbox** (é deploy de
  produção e exige aprovação do Tony). `minLength` alimenta a validação do front.
- **SN-RN-15 Limpeza.** Um agendador diário (`TAXIFY_PASSWORD_RESET_PURGE_CRON`, padrão `0 30 3 * * *`, Recife)
  apaga as linhas de `password_reset_tokens` com `created_date` de mais de 48 h. Os limites só olham as últimas 24 h, e
  o IP não fica guardado além disso.

### Critérios de aceite

- **CA-SN-01 (SN-RN-01, SN-RN-02, SN-RN-03)** Dado o usuário ativo `ana.fiscal` de um escritório `ACTIVE`, quando
  alguém envia `POST /auth/password/forgot {"identifier":"ana.fiscal"}`, então a resposta é 202 sem corpo, uma linha
  é gravada em `password_reset_tokens` só com o hash, e um e-mail sai de `nao-responda@cshub.com.br` para o e-mail da
  Ana, com o link `{TAXIFY_PUBLIC_URL}/auth/redefinir-senha#token=…`, o login `ana.fiscal` e a validade de 60 minutos.
- **CA-SN-02 (SN-RN-01)** Dado um login que não existe, quando o mesmo pedido é feito, então a resposta é 202 sem
  corpo, igual à do CA-SN-01, nenhum e-mail sai e nada é gravado.
- **CA-SN-03 (SN-RN-02)** Dado o e-mail `contato@escritorio-ficticio.com.br` usado pelos logins `ana.fiscal` e
  `ana.admin`, quando alguém pede pelo e-mail, então saem dois e-mails, cada um com o seu login e o seu link.
- **CA-SN-04 (SN-RN-04)** Dado que a conta já recebeu 3 links nos últimos 60 minutos, quando um 4º pedido chega,
  então a resposta é 202, nenhum e-mail sai, e o log registra `password.reset.limited` sem login, e-mail nem IP.
- **CA-SN-05 (SN-RN-03, SN-RN-06)** Dado um link emitido às 10:00, quando a senha é definida às 10:59, então a
  resposta é 204 e o login com a senha nova funciona. Quando o mesmo link é usado de novo, ou um link emitido às 10:00
  é usado às 11:01, então a resposta é 400 `error.password.reset.token.invalid`.
- **CA-SN-06 (SN-RN-03)** Dado que a conta pediu um link às 10:00 e outro às 10:05, quando o primeiro é usado, então
  a resposta é 400 `error.password.reset.token.invalid`, e o segundo continua valendo.
- **CA-SN-07 (SN-RN-06, SN-RN-08)** Dado um link válido, quando a senha nova tem 7 caracteres, então a resposta é 400
  `error.user.password.too.short` e o link **continua** valendo; com 8 caracteres, 204.
- **CA-SN-08 (SN-RN-11)** Dado que a Ana tem uma sessão aberta no navegador A, quando ela redefine a senha pelo link,
  então a próxima chamada do navegador A responde 401 `error.auth.session.expired`.
- **CA-SN-09 (SN-RN-02, SN-RN-07)** Dado um escritório `BLOCKED` (teste vencido), quando o usuário pede o link e
  redefine a senha, então tudo funciona, e depois do login o escritório continua em somente leitura.
- **CA-SN-10 (SN-RN-02)** Dado um escritório em `PENDING_VERIFICATION`, quando o ADMIN dele pede o link, então a
  resposta é 202 e nenhum e-mail sai.
- **CA-SN-11 (SN-RN-02, SN-RN-06)** Dado um usuário excluído, quando alguém pede o link pelo login dele, então nenhum
  e-mail sai. Dado um link emitido antes da exclusão, quando é usado depois, então a resposta é 400
  `error.password.reset.token.invalid`.
- **CA-SN-12 (SN-RN-09, SN-RN-11)** Dado que a Ana está logada nos navegadores A e B, quando ela troca a senha no A
  com a senha atual certa, então o A recebe 200 com um token novo e continua trabalhando, e o B recebe 401
  `error.auth.session.expired` na próxima chamada.
- **CA-SN-13 (SN-RN-09)** Dado a senha atual errada, quando a Ana tenta trocar, então a resposta é 400
  `error.user.password.current.invalid` e ela continua logada. Na 6ª tentativa dentro de 60 minutos, então a
  resposta é 429 `error.user.password.change.rate.limited`, mesmo com a senha certa.
- **CA-SN-14 (SN-RN-09)** Dado um escritório `SUSPENDED`, quando o usuário troca a própria senha, então a resposta é
  200 (não é 403 `error.firm.access.blocked`).
- **CA-SN-15 (SN-RN-10, SN-RN-11, SN-RN-12)** Dado o ADMIN definindo a senha do colaborador Bruno, quando grava,
  então as sessões do Bruno caem (401), e o Bruno recebe o e-mail "alterada pelo administrador do escritório".
- **CA-SN-16 (SN-RN-10)** Dado o ADMIN editando o próprio cadastro em `PUT /users/{id}` com `password` preenchido,
  quando grava, então a resposta é 400 `error.user.password.use.self.change`.
- **CA-SN-17 (SN-RN-08)** Dado o usuário com a senha `abc:12345`, quando ele entra, então o login funciona.
- **CA-SN-18 (SN-RN-11)** Dado um JWT emitido antes do deploy (sem `sv`) para um usuário com `session_version = 0`,
  quando ele chama a API, então a chamada passa até o token expirar.
- **CA-SN-19 (SN-RN-11)** Dado um usuário excluído pelo ADMIN com uma sessão aberta, quando essa sessão chama
  qualquer endpoint autenticado, então a resposta é 401.
- **CA-SN-20 (SN-RN-14)** Dado `TAXIFY_PASSWORD_RESET_ENABLED=false`, quando a tela de login abre, então
  `GET /auth/password/config` devolve `available=false`, o formulário não aparece, e `POST /auth/password/forgot`
  responde 503 `error.password.reset.unavailable`.
- **CA-SN-21 (SN-RN-13)** Dado qualquer cenário acima, quando os logs são lidos, então não aparecem login, e-mail, IP,
  token nem senha.

### Casos de teste (dados fictícios; hoje = 2026-11-27, horário de Recife)

| # | Cenário | Entrada | Esperado | RN |
|---|---|---|---|---|
| CT-SN-01 | Pedido por login | `identifier = "ana.fiscal"`, conta ativa, escritório ACTIVE, 10:00:00 | 202; 1 linha com `expires_at = 10:00:00 + 60 min = 11:00:00`; 1 e-mail (mock) com `#token=` | SN-RN-01, 03 |
| CT-SN-02 | Login inexistente | `identifier = "nao.existe"` | 202; 0 linhas; 0 e-mails; tempo de resposta no mesmo patamar do CT-SN-01 (processamento depois da resposta) | SN-RN-01 |
| CT-SN-03 | Identifier vazio ou nulo | `{}` e `{"identifier":"   "}` | 202; nada gravado | SN-RN-01 |
| CT-SN-04 | E-mail de duas contas | `identifier = "Contato@Escritorio-Ficticio.com.br"` (maiúsculas), contas `ana.fiscal` (escritório A) e `ana.admin` (escritório B) | 2 linhas; 2 e-mails, cada um com o login e o escritório certos | SN-RN-02 |
| CT-SN-05 | E-mail de 7 contas | mesmo e-mail em 7 usuários | 5 e-mails (as 5 contas mais recentes); log `password.reset.too.many.accounts count=7` | SN-RN-02 |
| CT-SN-06 | Limite por hora | pedidos às 10:00, 10:10, 10:20, 10:30 | 3 e-mails; o das 10:30 não sai; às 11:01 volta a sair | SN-RN-04 |
| CT-SN-07 | Teto diário por conta | pedidos às 08:00, 08:10, 08:20 (3), 09:30, 09:40 (5), 11:00 (6º) | 5 e-mails; o 6º não sai; às 00:00 de 28/11 zera | SN-RN-04 |
| CT-SN-08 | Teto por IP | 11 contas diferentes, mesmo IP, 1 pedido cada | 10 e-mails; o 11º não sai | SN-RN-04 |
| CT-SN-09 | Validade | link das 10:00; reset às 10:59:59 / às 11:00:01 | 204 / 400 `error.password.reset.token.invalid` | SN-RN-03, 06 |
| CT-SN-10 | Uso único e corrida | o mesmo token em duas requisições ao mesmo tempo | uma 204, a outra 400 | SN-RN-06 |
| CT-SN-11 | Novo link invalida o anterior | links às 10:00 (T1) e 10:05 (T2); reset com T1 | 400; T2 continua 204 | SN-RN-03 |
| CT-SN-12 | Senha curta | `newPassword = "Abc1234"` (7) | 400 `error.user.password.too.short`; token não consumido | SN-RN-08 |
| CT-SN-13 | Senha longa | `newPassword` = 37 vezes "ç" (74 bytes) | 400 `error.user.password.invalid` | SN-RN-08 |
| CT-SN-14 | Senha igual à atual | redefinir com a mesma senha | 400 `error.user.password.same` | SN-RN-08 |
| CT-SN-15 | Sessões caem | sessão aberta antes; reset; chamada com o token antigo | 401 `error.auth.session.expired`; login com a senha antiga: 403; com a nova: 200 | SN-RN-06, 11 |
| CT-SN-16 | Escritório BLOCKED | `blocked_reason = TRIAL_EXPIRED` | pedido e reset funcionam; depois do login, `POST` de escrita: 403 `error.firm.access.blocked` | SN-RN-07 |
| CT-SN-17 | Escritório sem `firm_subscription` | criado por `POST /accounting-firms` | recebe o link (tratado como SUSPENDED) | SN-RN-02 |
| CT-SN-18 | Cadastro não verificado | escritório PENDING_VERIFICATION | 202; 0 e-mails; log `password.reset.skipped reason=PENDING_VERIFICATION` | SN-RN-02 |
| CT-SN-19 | Usuário excluído | antes do pedido / entre o pedido e o uso | 0 e-mails / 400 no reset | SN-RN-02, 06 |
| CT-SN-20 | Escritório excluído | `accounting_firms.deleted = true` | 0 e-mails | SN-RN-02 |
| CT-SN-21 | Troca pelo usuário | atual certa, nova `"NovaSenha#2026"` | 200 `{token}`; o token novo tem `sv` = versão nova; outra sessão: 401 | SN-RN-09, 11 |
| CT-SN-22 | Senha atual errada | 5 erros às 10:00–10:05; 6ª tentativa às 10:06 com a certa; 7ª às 11:01 com a certa | 400 ×5; 429; 200 | SN-RN-09 |
| CT-SN-23 | Troca no escritório SUSPENDED | `PUT /users/me/password` | 200 | SN-RN-09 |
| CT-SN-24 | ADMIN redefine colaborador | `PUT /users/{bruno}` com `password = "Senha2026x"` | 200; sessões do Bruno: 401; e-mail ao Bruno; log `by=admin` | SN-RN-10, 12 |
| CT-SN-25 | ADMIN redefine colaborador com senha curta | `password = "abc"` | 400 `error.user.password.too.short` | SN-RN-08, 10 |
| CT-SN-26 | ADMIN muda a própria senha pela tela de usuários | `PUT /users/{próprio id}` com `password` | 400 `error.user.password.use.self.change`; com `password` em branco e o nome mudado: 200 | SN-RN-10 |
| CT-SN-27 | ADMIN de outro escritório | `PUT /users/{id de outro tenant}` | 404 (sem mudança) | SN-RN-10 |
| CT-SN-28 | Senha com `:` | login `teste.ponto`, senha `abc:12345` | login 200 | SN-RN-08 |
| CT-SN-29 | Token antigo sem `sv` | JWT emitido antes do deploy, `session_version = 0` | passa; depois de uma troca, 401 | SN-RN-11 |
| CT-SN-30 | Usuário excluído com sessão aberta | `DELETE /users/{id}` e chamada com o token dele | 401 (antes: passava sem filtro de tenant) | SN-RN-11 |
| CT-SN-31 | Aviso de senha alterada | os três caminhos | 1 e-mail cada, com o texto do caminho; falha do SES: troca mantida, log `password.notice.failed` | SN-RN-12 |
| CT-SN-32 | Feature desligada | `TAXIFY_PASSWORD_RESET_ENABLED=false` | config `available=false`; forgot 503 `error.password.reset.unavailable`; reset/check 503 | SN-RN-14 |
| CT-SN-33 | Limpeza | linhas de 25/11 e 27/11; agendador em 27/11 03:30 | apaga a de 25/11; mantém a de 27/11 | SN-RN-15 |
| CT-SN-34 | Log | CT-SN-01 a CT-SN-31 | nenhum login, e-mail, IP, token ou senha no log | SN-RN-13 |
| CT-SN-35 | Fragmento | abrir o link no navegador com o front servido pelo nginx | o log de acesso do nginx e do Caddy mostra `/auth/redefinir-senha` sem o token | SN-RN-03 |

### Endpoints

| Método | Caminho | Acesso | Corpo / resposta | RN |
|---|---|---|---|---|
| GET | `/auth/password/config` | anônimo | → `{available, resetMinutes, minLength}` | SN-RN-14 |
| POST | `/auth/password/forgot` | anônimo, `@AllowedWhenBlocked` | `{identifier}` → 202 sem corpo; 503 desligado | SN-RN-01 a 04 |
| POST | `/auth/password/reset/check` | anônimo, `@AllowedWhenBlocked` | `{token}` → 204 ou 400 `error.password.reset.token.invalid` | SN-RN-05 |
| POST | `/auth/password/reset` | anônimo, `@AllowedWhenBlocked` | `{token, newPassword}` → 204; 400 (token ou regra de senha) | SN-RN-06, 08 |
| PUT | `/users/me/password` | logado (qualquer perfil), `@AllowedWhenBlocked` | `{currentPassword, newPassword}` → 200 `{token}`; 400; 429 | SN-RN-09 |
| PUT | `/users/{id}` (já existe) | `@AdminOnly` | muda: regra de senha, `session_version`, aviso, recusa da própria senha | SN-RN-10 |

- As rotas anônimas entram no `permitAll` da `SecurityConfig`, ao lado de `/signup/**`. Elas **não** podem usar o
  `TenantAccessService` (contexto anônimo é tratado como sistema; P-09 da spec de plataforma): buscam o usuário direto
  no repositório.
- Todos os erros saem no formato único `{timestamp, status, code, message, details}`, com as mensagens novas em
  `i18n/errors_pt.properties` e `errors_en.properties`.

### Modelo de dados

Próximos números livres hoje: a última migration é `V70__RecoveryTasksAudit` (a V67 não existe). Conferir na hora.

| Tabela | Migration | Conteúdo |
|---|---|---|
| `users` (+colunas) | V71 | `session_version int not null default 0`; `password_changed_at datetime(6) null`; `password_fail_count int not null default 0` e `password_fail_window_start datetime(6) null` (SN-RN-09) |
| `password_reset_tokens` | V72 | `id`, `user_id` (FK `users`), `token_hash varchar(64)` (**único**), `expires_at`, `used_at`, `invalidated_at`, `requested_ip varchar(45) null`, `created_date` + auditoria e `deleted` conforme a convenção 3.8.1. Índices `(user_id, created_date)` e `(requested_ip, created_date)` |

### Configuração nova

| Variável | Padrão | Efeito |
|---|---|---|
| `TAXIFY_PASSWORD_RESET_ENABLED` | `false` | Liga o "Esqueci a senha" (SN-RN-14). Na EC2 só depois da saída do sandbox do SES |
| `TAXIFY_PASSWORD_RESET_MINUTES` | `60` (P-SN-01) | Validade do link |
| `TAXIFY_PASSWORD_RESET_PER_HOUR` | `3` (P-SN-02) | Links por conta em 60 minutos |
| `TAXIFY_PASSWORD_RESET_PER_DAY` | `5` (P-SN-02) | Links por conta no dia civil de Recife |
| `TAXIFY_PASSWORD_RESET_PER_IP_PER_DAY` | `10` (P-SN-02) | Links por IP no dia civil de Recife |
| `TAXIFY_PASSWORD_MIN_LENGTH` | `8` (P-SN-04) | Tamanho mínimo da senha nova |
| `TAXIFY_PASSWORD_CHANGE_MAX_FAILURES` | `5` | Erros de senha atual em 60 minutos antes do 429 |
| `TAXIFY_PASSWORD_CHANGED_NOTICE_ENABLED` | `true` (P-SN-08) | Aviso de senha alterada |
| `TAXIFY_PASSWORD_RESET_PURGE_CRON` | `0 30 3 * * *` (Recife) | Limpeza dos links com mais de 48 h |

### Tarefas

| # | Área | Tarefa | Depende de |
|---|---|---|---|
| B-SN-1 | Backend | `PasswordPolicy` único (obrigatória, mínimo, 72 bytes, diferente da atual) usado em `UserValidation`, `SignupService`, reset e troca; códigos novos em i18n; `AuthService` dividindo `login:senha` só no primeiro `:`; recusa de `:` em login novo (SN-RN-08) | P-SN-04, P-SN-10 |
| B-SN-2 | Backend | V71 (`session_version`, `password_changed_at`, contadores) + claim `sv` no `TokenService` + filtro de sessão depois do bearer (401 `error.auth.session.expired`, usuário excluído, falha fechada, token sem `sv` = 0) + testes, incluindo o fechamento do R-02 (SN-RN-11) | P-SN-03 |
| B-SN-3 | Backend | V72 + `PasswordResetService` (emissão, hash, invalidação, limites por conta, dia e IP, processamento depois da resposta), `PasswordResetResource` com `config`, `forgot`, `reset/check` e `reset`; `permitAll` e `@AllowedWhenBlocked`; modelo de e-mail com o link `#token=` (SN-RN-01 a 07, 14) | B-SN-1, B-SN-2 |
| B-SN-4 | Backend | `PUT /users/me/password` com limite de erro e token novo na resposta; inclusão no teste-guarda das rotas só com login e no `@AllowedWhenBlocked` (SN-RN-09) | B-SN-1, B-SN-2 |
| B-SN-5 | Backend | `PUT /users/{id}`: regra de senha, `session_version`, recusa da própria senha (SN-RN-10) | B-SN-1, B-SN-2, P-SN-09 |
| B-SN-6 | Backend | Aviso de senha alterada nos três caminhos (SN-RN-12) e agendador de limpeza (SN-RN-15) | B-SN-3 |
| F-SN-1 | Frontend | Tela de login: "Esqueceu a senha?" abre o formulário de login/e-mail quando `available=true` (senão mantém o texto de hoje); mensagem fixa depois do envio: "Se existir uma conta com esses dados, enviamos um link para o e-mail cadastrado. Ele vale por {resetMinutes} minutos." | B-SN-3 |
| F-SN-2 | Frontend | Rota `/auth/redefinir-senha`: lê o token do **fragmento**, remove o fragmento da barra de endereço (`history.replaceState`), chama `reset/check`, mostra "link vencido" com o atalho para pedir outro, campos senha + confirmação com `minLength` da config e 72 bytes; sucesso volta ao login com aviso | B-SN-3 |
| F-SN-3 | Frontend | "Minha conta" no menu do avatar: troca de senha (atual, nova, confirmação), troca do token salvo pelo da resposta, mensagens de 400 e 429; liberada no escritório bloqueado | B-SN-4 |
| F-SN-4 | Frontend | Interceptor: 401 `error.auth.session.expired` desloga com "Sua sessão expirou. Entre de novo."; formulário de usuário do ADMIN: mostrar o erro `error.user.password.use.self.change` e o mínimo da senha; cadastro público usando `minLength` | B-SN-2, B-SN-5 |
| Q-SN-1 | QA | CT-SN-01 a CT-SN-34 com `TAXIFY_MAIL_PROVIDER=LOG` (sem dado real; e-mails e logins fictícios) | B-SN-1 a B-SN-6 |
| Q-SN-2 | QA | Teste de concorrência do CT-SN-10 e de sessões em dois navegadores (CT-SN-15, CT-SN-21) | B-SN-2, B-SN-3 |
| Q-SN-3 | QA | Antes do go-live na EC2: SES fora do sandbox; `TAXIFY_MAIL_LOG_BODY=false`; CT-SN-35 (token fora dos logs de acesso); um e-mail real de redefinição chegando fora do spam com DKIM/SPF válidos | I1-1, B-SN-3 |

---

## 6. Feature 3: aviso de pendência alta por e-mail

### Regras de negócio

- **AV-RN-01 O que é "pendência alta".** Uma pendência entra no aviso quando, no momento do
  resumo, **todas** valem:
  - `severity = ALTA` (a severidade gravada na pendência, que é a do tipo ou, na auditoria, a da regra; ver a tabela
    da seção 1);
  - `status = ABERTA` e `deleted = false`;
  - a empresa não está excluída e não está inativa (`active` nulo ou `true`), como no painel (RN-08 da spec de
    pendências);
  - o tipo está em `TAXIFY_PENDENCY_ALERT_TYPES` (lista; **vazia = todos os tipos**).
  - Não crio severidade nova nem mudo a severidade de nenhum tipo. Quem decide o que é ALTA continua sendo o código
    das pendências, e a validação das severidades pelo time Fiscal é a pergunta 5 da spec de pendências.
- **AV-RN-02 Quem recebe.** Um usuário é **elegível** quando:
  - não está excluído, e o escritório dele não está excluído;
  - tem a permissão `INVOICES_VIEW` (a mesma do painel `/pendencias`). O ADMIN tem todas;
  - tem e-mail preenchido;
  - não desligou o aviso (AV-RN-08).
  - **Empresas do usuário:** ADMIN = todas as empresas do escritório; os outros = a carteira (`UserCompany` ativo), a
    mesma regra do `TenantAccessService` (RN-17 da spec de plataforma). Um usuário nunca recebe pendência de empresa
    que ele não vê no painel, e **nunca** de outro escritório.
- **AV-RN-03 Frequência: resumo diário.** Um agendador
  (`TAXIFY_PENDENCY_ALERT_CRON`, padrão `0 50 7 * * MON-FRI`, zona `America/Recife`) monta **no máximo um e-mail por
  usuário por dia**, com as pendências que entraram desde o último aviso dele. Às 07:50 a rodada das pendências das
  07:40 já terminou na maioria dos dias. Sem envio na hora na v1. Sábado e domingo não há rodada; a de segunda leva o
  que surgiu no fim de semana. Feriado não é tratado (sai e-mail normalmente).
- **AV-RN-04 Uma vez por episódio (deduplicação).** Cada pendência é avisada **uma vez a cada usuário** por
  "episódio". O episódio é identificado por `pendencies.first_seen_at`, que só muda quando a pendência reabre (RN-04 da
  spec de pendências). Assim:
  - a pendência que continua aberta dias seguidos não é avisada de novo;
  - a que fecha sozinha e depois volta (`first_seen_at` novo) é avisada de novo;
  - a que fecha **antes** do resumo (a causa sumiu, ou alguém resolveu ou desconsiderou) não é avisada;
  - a que era BAIXA e virou ALTA na mesma chave (certidão positiva que perde o efeito de negativa) é avisada na
    primeira vez em que aparece como ALTA;
  - desconsiderada à mão com a causa presente continua fechada (RN-05) e não é avisada.
  - O registro fica em `pendency_alert_items` com a chave única (`pendency_id`, `user_id`, `episode_started_at`).
- **AV-RN-05 Sem e-mail vazio.** Usuário sem nenhuma pendência nova no dia não recebe nada.
- **AV-RN-06 Conteúdo do e-mail.** Texto simples, como os da fase 1:
  - **Assunto:** "Taxify: {N} pendência(s) alta(s) nova(s) na sua carteira". Sem nome de empresa no assunto.
  - **Corpo:**
    - "Olá, {primeiro nome}." e o nome do escritório;
    - para cada empresa com pendência nova, em ordem alfabética: o **nome da empresa** (até 60 caracteres) e, por
      tipo, o **rótulo do tipo** (`PendencyType.label`; na auditoria, "Auditoria tipo malha") e a **quantidade** de
      pendências novas. Exemplo: "Têxtil Exemplo Ltda: Prazo de manifestação perto do fim (12); Certificado A1 vencido (1)";
    - no máximo `TAXIFY_PENDENCY_ALERT_MAX_COMPANIES_LISTED` empresas (padrão **30**), depois "e mais {X} empresa(s)";
    - o total de pendências ALTA abertas na carteira dele hoje (novas e antigas);
    - o link `{TAXIFY_PUBLIC_URL}/pendencias?severidade=ALTA&status=ABERTA`;
    - o rodapé: "Você recebe este resumo porque tem acesso às pendências destas empresas no Taxify. Para desligar,
      entre em Minha conta > Avisos por e-mail: {TAXIFY_PUBLIC_URL}/minha-conta".
  - **Nunca** vão no e-mail: CNPJ, CPF, inscrição estadual, chave de acesso, número ou série de nota, nome de
    emitente ou destinatário, valores, o `title` e o `detail` da pendência (eles podem trazer número de nota e nome de
    terceiro), nem o comentário de fechamento.
- **AV-RN-07 Escritório bloqueado.** O resumo é rotina automática: usa o `RoutineGate` (`FirmAccessService`, F1-RN-10)
  e não roda para escritório em `BLOCKED`, `SUSPENDED`, `CANCELED` ou `PENDING_VERIFICATION`. Escritório em `TRIAL` e o
  isento recebem. Na reativação nada é enviado de forma retroativa: o primeiro resumo depois
  dela leva as pendências ALTA abertas ainda não avisadas (como durante o bloqueio as pendências não foram
  recalculadas, não há acúmulo do período).
- **AV-RN-08 Ligar e desligar.**
  - Cada usuário liga ou desliga o próprio aviso em "Minha conta" (`PUT /users/me/notification-settings`). A gravação
    é liberada no escritório bloqueado (`@AllowedWhenBlocked`): é preferência pessoal, não dado do escritório.
  - Sem registro, vale `TAXIFY_PENDENCY_ALERT_DEFAULT_ON` (padrão `true`: todo elegível recebe até desligar).
  - O ADMIN **não** liga nem desliga o aviso de outro usuário na v1.
  - Desligar e ligar de novo não manda o que passou: o próximo resumo leva só as pendências ALTA **abertas** ainda não
    avisadas àquele usuário (pode incluir as que surgiram enquanto estava desligado e continuam abertas).
- **AV-RN-09 Teto de volume.**
  - Por usuário: no máximo 1 resumo por dia (chave única `user_id`, `reference_date` em `pendency_alert_digests`).
  - Global: no máximo `TAXIFY_PENDENCY_ALERT_MAX_PER_DAY` resumos por dia somando todos os escritórios (padrão
    **200**). Acima do teto, o resumo fica `CAPPED`, nada é marcado como avisado, e as pendências vão no resumo do dia
    útil seguinte. Log: `pendency.alert.capped count={n}`.
  - **Ordem dentro do teto:** primeiro quem está há mais tempo sem receber resumo (nunca recebeu vem antes), para
    ninguém ficar sempre de fora.
  - **Ritmo:** entre um envio e outro, espera de `TAXIFY_MAIL_SEND_INTERVAL_MS` (padrão **1000**, o ritmo de 1 por
    segundo do sandbox), para não estourar a taxa de envio do SES. Ajustar depois de conhecer a cota de produção.
  - O teto deve deixar folga para os e-mails transacionais (verificação, senha, avisos do teste). Recomendação: teto ≤
    metade da cota diária do SES da conta.
- **AV-RN-10 Envio e falha.** Para cada usuário elegível com pendência nova, em ordem:
  1. reserva o resumo do dia inserindo `pendency_alert_digests` com `status = SENDING` (a chave única impede que duas
     instâncias ou duas rodadas mandem o mesmo resumo);
  2. envia pelo `MailSender`, com um destinatário só (o e-mail do usuário; nunca a lista de vários usuários no "Para");
  3. sucesso: `status = SENT`, `sent_at`, e grava os `pendency_alert_items` das pendências do e-mail;
  4. falha (`MailSendException` ou outra): `status = FAILED`, nada em `pendency_alert_items`, log
     `pendency.alert.failed user={id}` com o detalhe seguro. As pendências entram no resumo do dia útil seguinte. Não
     há nova tentativa no mesmo dia na v1.
  - Um resumo que ficou `SENDING` (a aplicação caiu no meio) conta como `FAILED` na rodada seguinte.
  - Falha num usuário não para os outros (convenção 3.8.8). A rodada tem `enabled`, `cron` e trava contra execução em
    paralelo (`AtomicBoolean`).
- **AV-RN-11 Um usuário, um e-mail.** O resumo é por usuário, e não por endereço. Dois usuários com o mesmo e-mail
  recebem dois resumos. Usuários de escritórios diferentes **nunca** são juntados num e-mail só.
- **AV-RN-12 Log e LGPD.** Logs só com ids (`user`, `firm`, `digest`) e contagens. Nunca e-mail, nome de empresa ou
  CNPJ. O e-mail ao próprio usuário com o nome das empresas da carteira dele é tratamento de dado do cliente do
  escritório: precisa constar na política de privacidade e no acordo de operador (F1-RN-11 da fase 1, em elaboração
  pelo Tony).
- **AV-RN-13 Ligar a feature.** `TAXIFY_PENDENCY_ALERTS_ENABLED` (padrão `false`). **Na EC2, só ligar depois da saída
  do sandbox do SES** e com aprovação do Tony. Para testar com o SES ainda no sandbox, a variável nova
  `TAXIFY_MAIL_RECIPIENT_ALLOWLIST` (domínios ou endereços separados por vírgula; vazia = sem filtro) faz o
  `MailSender` descartar, antes de chamar o SES, todo destinatário fora da lista (log `mail.recipient.skipped count={n}`,
  sem o endereço). Ela vale para todos os e-mails do Taxify.
  - **Pré-requisitos de go-live (decisão do Tony, 2026-11-27).** Além do SES fora do sandbox, a variável só é ligada
    depois que: (a) o time Fiscal validar as severidades ALTA dos tipos de pendência (tabela da seção 1); e (b) a
    política de privacidade e o acordo de operador disserem que o nome das empresas clientes sai por e-mail (AV-RN-12).
- **AV-RN-14 Limpeza.**
  - `pendency_alert_items` de pendência fechada há mais de 30 dias é apagado (se ela reabrir, o `first_seen_at` é
    novo, então nada é perdido).
  - `pendency_alert_digests` com mais de 365 dias é apagado.
  - Roda junto com o agendador do resumo, depois do envio.

### Critérios de aceite

Cenário base (fictício): escritório "Escritório Exemplo" (ACTIVE) com as empresas X ("Têxtil Exemplo Ltda"), Y
("Confecção Modelo ME") e Z ("Comércio Teste Ltda"). Usuários: ADMIN `carla.admin`; `ana.fiscal` (FISCAL, carteira X e
Y); `bruno.dp` (PAYROLL, sem `INVOICES_VIEW`, carteira Z). Hoje = terça-feira, 2026-12-01, rodada das 07:50.

- **CA-AV-01 (AV-RN-01, AV-RN-02, AV-RN-06)** Dado em X um `CERTIFICATE_EXPIRED` ALTA aberto desde 30/11 10:40, em Y
  12 `MANIFESTATION_DEADLINE` ALTA abertos e um `NOTE_DIVERGENT` MÉDIA, e em Z um `SEFAZ_CAPTURE_ERROR` ALTA, quando a
  rodada roda, então a Carla recebe um e-mail com 14 pendências novas em 3 empresas, a Ana recebe um com 13 em 2
  empresas (X e Y), o Bruno não recebe nada, e nenhum e-mail menciona a pendência MÉDIA.
- **CA-AV-02 (AV-RN-04)** Dado o CA-AV-01 já enviado, quando a rodada roda de novo no mesmo dia ou no dia seguinte sem
  pendência nova, então nenhum e-mail sai.
- **CA-AV-03 (AV-RN-04)** Dado o `SEFAZ_CAPTURE_ERROR` de Z avisado em 01/12, fechado sozinho em 02/12 e aberto de novo
  em 03/12 (`first_seen_at` novo), quando a rodada de 04/12 roda, então a Carla é avisada de novo.
- **CA-AV-04 (AV-RN-04)** Dado uma pendência ALTA desconsiderada pela Ana às 07:30, quando a rodada das 07:50 roda,
  então ela não aparece em nenhum e-mail.
- **CA-AV-05 (AV-RN-04)** Dado `CERTIDAO_PE_POSITIVA` de X com severidade BAIXA (efeito de negativa) em 01/12, que passa
  a ALTA em 02/12 na mesma chave, quando a rodada de 03/12 roda, então ela é avisada uma vez.
- **CA-AV-06 (AV-RN-06)** Dado qualquer e-mail de resumo, quando o texto é conferido, então não há CNPJ, chave de
  acesso, número de nota, nome de emitente, valor, `title` nem `detail` de pendência, e o assunto não tem nome de
  empresa.
- **CA-AV-07 (AV-RN-07)** Dado o escritório em `BLOCKED`, quando a rodada roda, então ninguém dele recebe e-mail. Dado
  que ele foi reativado, quando a rodada do dia útil seguinte roda, então as pendências ALTA abertas não avisadas são
  enviadas.
- **CA-AV-08 (AV-RN-08)** Dado que a Ana desligou o aviso, quando a rodada roda, então ela não recebe nada e a Carla
  recebe normalmente.
- **CA-AV-09 (AV-RN-09)** Dado `TAXIFY_PENDENCY_ALERT_MAX_PER_DAY=2` e 3 usuários elegíveis com pendência nova, quando a
  rodada roda, então 2 resumos ficam `SENT` e 1 fica `CAPPED`; na rodada seguinte, o `CAPPED` sai primeiro.
- **CA-AV-10 (AV-RN-10)** Dado o SES falhando para a Ana, quando a rodada roda, então o resumo dela fica `FAILED`, as
  pendências não ficam marcadas como avisadas e saem no resumo do dia útil seguinte, e a Carla recebe o dela.
- **CA-AV-11 (AV-RN-10)** Dado duas rodadas ao mesmo tempo (duas instâncias), quando as duas processam a Ana, então só
  um e-mail sai.
- **CA-AV-12 (AV-RN-03)** Dado uma pendência ALTA nova no sábado, quando chega segunda às 07:50, então ela vai no resumo
  de segunda; no sábado e no domingo não sai e-mail.
- **CA-AV-13 (AV-RN-13)** Dado `TAXIFY_PENDENCY_ALERTS_ENABLED=false`, quando o horário da rodada chega, então nada é
  enviado nem gravado. Dado `TAXIFY_MAIL_RECIPIENT_ALLOWLIST=cshub.com.br`, quando a rodada roda, então só destinatários
  `@cshub.com.br` recebem.
- **CA-AV-14 (AV-RN-02)** Dado a empresa Y inativa, quando a rodada roda, então as pendências dela não aparecem.

### Casos de teste (dados fictícios; cenário base dos critérios)

| # | Cenário | Entrada | Esperado | RN |
|---|---|---|---|---|
| CT-AV-01 | Resumo por carteira | cenário do CA-AV-01 | Carla: 3 empresas, 14 novas (X: certificado vencido 1; Y: prazo de manifestação 12; Z: captura SEFAZ com erro 1); Ana: 2 empresas, 13; Bruno: nada | AV-RN-01, 02, 06 |
| CT-AV-02 | Total da carteira | Ana com 13 novas + 4 ALTA abertas já avisadas ontem | e-mail com "13 novas" na lista e "17 pendências altas abertas na sua carteira" | AV-RN-06 |
| CT-AV-03 | Repetição no mesmo dia | rodar duas vezes às 07:50 e 08:50 | 1 e-mail por usuário; `pendency_alert_digests` com 1 linha por usuário e dia | AV-RN-04, 09 |
| CT-AV-04 | Sem novidade | dia seguinte, nenhuma pendência nova | 0 e-mails; nenhuma linha de resumo | AV-RN-05 |
| CT-AV-05 | Fechada antes | ALTA aberta às 05:40 e fechada sozinha às 06:40 | não aparece | AV-RN-04 |
| CT-AV-06 | Reabertura | avisada em 01/12; fechada 02/12; reaberta 03/12 | avisada de novo em 04/12 | AV-RN-04 |
| CT-AV-07 | Mudança de severidade | certidão BAIXA em 01/12, ALTA em 02/12, mesma chave | avisada em 03/12 (1 vez) | AV-RN-04 |
| CT-AV-08 | Filtro de tipos | `TAXIFY_PENDENCY_ALERT_TYPES=CERTIFICATE_EXPIRED,SEFAZ_CAPTURE_ERROR` | Carla recebe 2 (X e Z); Y fica de fora | AV-RN-01 |
| CT-AV-09 | Auditoria ALTA | `AUDIT_FINDING` da regra `NUMERO_DUPLICADO` (ALTA) e da regra `CFOP_DESTINO` (MÉDIA) em X | só a primeira, com o rótulo "Auditoria tipo malha" | AV-RN-01, 06 |
| CT-AV-10 | Muitas empresas | 45 empresas com pendência nova para a Carla | 30 empresas listadas + "e mais 15 empresa(s)" | AV-RN-06 |
| CT-AV-11 | Conteúdo sigiloso | pendência `MANIFESTATION_DEADLINE` com `title` "NF 1234 de Fornecedor Fictício" | o e-mail não traz "1234" nem "Fornecedor Fictício" | AV-RN-06 |
| CT-AV-12 | Bloqueado | escritório `SUSPENDED` | 0 e-mails; log do `RoutineGate` | AV-RN-07 |
| CT-AV-13 | Em teste | escritório `TRIAL` | recebe | AV-RN-07 |
| CT-AV-14 | Desligado | Ana com o aviso desligado | Ana 0; Carla 1 | AV-RN-08 |
| CT-AV-15 | Religado | Ana desliga em 01/12 e religa em 05/12; 3 ALTA surgiram em 02/12 e continuam abertas | recebe as 3 em 08/12 (segunda) | AV-RN-08 |
| CT-AV-16 | Padrão desligado | `TAXIFY_PENDENCY_ALERT_DEFAULT_ON=false`, ninguém configurou | 0 e-mails | AV-RN-08 |
| CT-AV-17 | Teto global | teto 2; 3 elegíveis; nenhum recebeu antes | 2 `SENT`, 1 `CAPPED`; no dia seguinte o `CAPPED` vai primeiro | AV-RN-09 |
| CT-AV-18 | Ritmo | 5 resumos, intervalo 1000 ms | a rodada leva pelo menos 4 s | AV-RN-09 |
| CT-AV-19 | Falha do SES | `MailSender` mock lança `MailSendException` para a Ana | Ana `FAILED`, 0 itens; Carla `SENT` | AV-RN-10 |
| CT-AV-20 | Queda no meio | resumo da Ana em `SENDING` de ontem | tratado como `FAILED`; as pendências vão hoje | AV-RN-10 |
| CT-AV-21 | Concorrência | duas chamadas de `sendDue()` ao mesmo tempo | 1 e-mail por usuário | AV-RN-10 |
| CT-AV-22 | Fim de semana | pendência nova no sábado 05/12 | sai na segunda 07/12 | AV-RN-03 |
| CT-AV-23 | Empresa inativa e excluída | Y inativa; Z excluída | não aparecem | AV-RN-01 |
| CT-AV-24 | Usuário excluído ou sem e-mail | Ana excluída | 0 e-mails para ela | AV-RN-02 |
| CT-AV-25 | Outro escritório | escritório B com pendências ALTA | nenhum usuário do escritório A as recebe | AV-RN-02 |
| CT-AV-26 | Mesmo e-mail em dois usuários | Ana e Carla com o mesmo endereço | 2 e-mails | AV-RN-11 |
| CT-AV-27 | Desligado e allowlist | `TAXIFY_PENDENCY_ALERTS_ENABLED=false` / allowlist `cshub.com.br` | nada / só `@cshub.com.br` | AV-RN-13 |
| CT-AV-28 | Limpeza | item de pendência fechada há 31 dias; resumo de 366 dias | apagados | AV-RN-14 |
| CT-AV-29 | Log | todos acima | sem e-mail, nome de empresa ou CNPJ no log | AV-RN-12 |

### Endpoints

| Método | Caminho | Acesso | Corpo / resposta | RN |
|---|---|---|---|---|
| GET | `/users/me/notification-settings` | logado | → `{pendencyHighDigest: {enabled, eligible}}`. `eligible=false` quando o usuário não tem `INVOICES_VIEW` (o front mostra a opção desabilitada, com o motivo) | AV-RN-02, 08 |
| PUT | `/users/me/notification-settings` | logado, `@AllowedWhenBlocked` | `{pendencyHighDigest: true|false}` → 200 com o mesmo corpo do GET | AV-RN-08 |

Não há endpoint para disparar o resumo à mão na v1.

### Modelo de dados

| Tabela | Migration | Conteúdo |
|---|---|---|
| `user_notification_settings` | V73 | `id`, `user_id` (FK), `type varchar(40)` (hoje só `PENDENCY_HIGH_DIGEST`), `enabled bit`, auditoria e `deleted`; **único** (`user_id`, `type`) |
| `pendency_alert_digests` | V74 | `id`, `accounting_firm_id`, `user_id`, `reference_date date` (dia civil de Recife), `status` (SENDING, SENT, FAILED, CAPPED), `companies_count`, `pendencies_count`, `sent_at`, auditoria; **único** (`user_id`, `reference_date`); índice (`user_id`, `sent_at`) para a ordem do teto |
| `pendency_alert_items` | V74 | `id`, `digest_id` (FK), `pendency_id` (FK), `user_id`, `episode_started_at datetime(6)` (cópia de `pendencies.first_seen_at`), `created_date`; **único** (`pendency_id`, `user_id`, `episode_started_at`) |

Nenhuma coluna nova em `pendencies`.

### Configuração nova

| Variável | Padrão | Efeito |
|---|---|---|
| `TAXIFY_PENDENCY_ALERTS_ENABLED` | `false` | Liga o resumo (AV-RN-13). Na EC2 só depois da saída do sandbox do SES |
| `TAXIFY_PENDENCY_ALERT_CRON` | `0 50 7 * * MON-FRI` (Recife) (P-AV-04, P-AV-10) | Horário do resumo |
| `TAXIFY_PENDENCY_ALERT_TYPES` | vazio = todos (P-AV-01) | Tipos que entram no aviso |
| `TAXIFY_PENDENCY_ALERT_DEFAULT_ON` | `true` (P-AV-03) | Aviso ligado para quem nunca configurou |
| `TAXIFY_PENDENCY_ALERT_MAX_PER_DAY` | `200` (P-AV-07) | Teto global de resumos por dia |
| `TAXIFY_PENDENCY_ALERT_MAX_COMPANIES_LISTED` | `30` | Empresas listadas no corpo |
| `TAXIFY_MAIL_SEND_INTERVAL_MS` | `1000` | Intervalo entre envios em rotinas de lote |
| `TAXIFY_MAIL_RECIPIENT_ALLOWLIST` | vazio = sem filtro | Só envia aos domínios ou endereços da lista (todos os e-mails do Taxify) |

### Tarefas

| # | Área | Tarefa | Depende de |
|---|---|---|---|
| B-AV-1 | Backend | V73 + `GET/PUT /users/me/notification-settings` com `eligible` e `@AllowedWhenBlocked`; padrão por `TAXIFY_PENDENCY_ALERT_DEFAULT_ON` (AV-RN-08) | P-AV-03 |
| B-AV-2 | Backend | V74 + `PendencyAlertService`: elegíveis por escritório, carteira, pendências ALTA abertas não avisadas no episódio, filtro de tipos (AV-RN-01, 02, 04, 05) | B-AV-1, P-AV-01, P-AV-02 |
| B-AV-3 | Backend | `PendencyAlertMailTemplate` (assunto, corpo, limite de empresas, nada sigiloso) + testes do conteúdo (AV-RN-06) | P-AV-05 |
| B-AV-4 | Backend | `PendencyAlertScheduler`: cron em Recife, `RoutineGate`, reserva `SENDING`, envio de um destinatário por vez com intervalo, `SENT`/`FAILED`/`CAPPED`, teto global e ordem, limpeza (AV-RN-03, 07, 09, 10, 14) | B-AV-2, B-AV-3 |
| B-AV-5 | Backend | `TAXIFY_MAIL_RECIPIENT_ALLOWLIST` e `TAXIFY_MAIL_SEND_INTERVAL_MS` no `MailSender` (decorador sobre SES e LOG) (AV-RN-09, 13) | — |
| F-AV-1 | Frontend | "Minha conta" > "Avisos por e-mail": chave "Resumo diário de pendências altas" com o texto do que chega e quando; desabilitada com o motivo quando `eligible=false` | B-AV-1, F-SN-3 |
| F-AV-2 | Frontend | Rota `/minha-conta` (o link do rodapé do e-mail), com o login antes quando não houver sessão | F-SN-3 |
| F-AV-3 | Frontend | `/pendencias` lendo `?severidade=` e `?status=` da URL para aplicar os filtros ao abrir | — |
| Q-AV-1 | QA | CT-AV-01 a CT-AV-29 com `TAXIFY_MAIL_PROVIDER=LOG` e `Clock` fixo, só dado fictício | B-AV-1 a B-AV-5 |
| Q-AV-2 | QA | Revisão do texto real de 3 resumos (pequeno, médio e com 45 empresas) contra AV-RN-06, com o Tony | B-AV-3 |
| Q-AV-3 | QA | Antes do go-live na EC2: SES fora do sandbox, cota de produção conhecida e o teto ajustado (≤ metade da cota), lista de supressão de bounces e reclamações ligada na conta SES, um resumo real chegando fora do spam; severidades ALTA validadas pelo time Fiscal; política de privacidade e acordo de operador citando o envio do nome das empresas por e-mail (AV-RN-13) | I1-1, B-AV-4 |

---

## 7. Dados e integrações

- **Amazon SES.** Reaproveita o `MailSender` da fase 1 (remetente `nao-responda@cshub.com.br`, texto simples, UTF-8).
  Para o go-live das duas features, a I1-1 da fase 1 precisa estar concluída e ganha três itens:
  - saída do sandbox (no sandbox só há entrega para endereços verificados, com cota diária e taxa por segundo baixas;
    conferir os números no console da conta);
  - lista de supressão da conta ligada para bounce e reclamação;
  - a cota diária e a taxa de produção anotadas, para ajustar `TAXIFY_PENDENCY_ALERT_MAX_PER_DAY` e
    `TAXIFY_MAIL_SEND_INTERVAL_MS`.
- **Tabelas lidas:** `users`, `user_companies`, `user_access_profiles`, `accounting_firms`, `firm_subscriptions`,
  `companies`, `pendencies`.
- **Tabelas novas:** `password_reset_tokens`, `user_notification_settings`, `pendency_alert_digests`,
  `pendency_alert_items` (seções 5 e 6).
- **Sem integração fiscal nova.** Nada de e-Fisco, SEFAZ ou XML nesta spec.

## 8. Requisitos de acesso, LGPD e sigilo

- A redefinição por e-mail entrega acesso a dados fiscais de clientes a quem controla a caixa de e-mail cadastrada. O
  e-mail do usuário passa a ser credencial: o ADMIN deve manter o e-mail de cada colaborador correto (texto de ajuda
  no formulário de usuário, F-SN-4).
- Tokens: só em hash, uso único, validade curta, fora dos logs de acesso (fragmento) e fora do log da aplicação.
- IP de quem pede o link: guardado no máximo 48 h, só para o limite por IP (SN-RN-15).
- Resumo de pendências: só para usuário que já vê aquelas empresas no painel, um destinatário por e-mail, sem
  identificador fiscal. O nome da empresa cliente sai por e-mail para o próprio analista do escritório (P-AV-05).
- Nada de dado real em fixture, log ou commit: todos os exemplos desta spec são fictícios.

## 9. Fora do escopo

- Bloqueio de conta por tentativas erradas no `POST /auth` (força bruta no login). Só a troca logada tem limite de erro.
- Autenticação em dois fatores, login social e SSO.
- Refresh token e "lembrar de mim".
- Correção do fuso do JWT (P-02 da spec de plataforma). A revogação por versão (SN-RN-11) funciona com ou sem essa
  correção.
- Revogar sessões quando o ADMIN muda o perfil ou as permissões de um usuário (o `session_version` permite isso
  depois).
- Troca do e-mail pelo próprio usuário e verificação do e-mail de colaborador.
- Aviso na hora (por evento) e aviso de pendência MÉDIA, BAIXA ou INFO.
- Lembrete de pendência ALTA que continua aberta (P-AV-06).
- Push, WhatsApp e notificação dentro do sistema.
- Link de descadastro sem login e cabeçalho `List-Unsubscribe` (P-AV-11).
- ADMIN configurando o aviso dos colaboradores (P-AV-08).
- Calendário de feriados.
- E-mail em HTML.

---

## 10. Decisões do Tony (2026-11-27)

O Tony aceitou **todas** as recomendações, exatamente como propostas. Nenhuma é regra fiscal.

| # | Decisão | RN |
|---|---|---|
| P-SN-01 | Link de redefinição vale **60 minutos** (configurável) | SN-RN-03 |
| P-SN-02 | **3 por hora e 5 por dia por conta, 10 por dia por IP**, sempre com 202 | SN-RN-04 |
| P-SN-03 | Redefinição, troca pelo usuário e troca pelo ADMIN **derrubam as sessões abertas**, pela versão de sessão (fecha também o R-02) | SN-RN-11 |
| P-SN-04 | **Mínimo de 8 caracteres e máximo de 72 bytes**, sem regra de composição, senha nova diferente da atual; vale para toda senha nova, as atuais continuam valendo | SN-RN-08 |
| P-SN-05 | Usuário de escritório bloqueado ou somente leitura **pode** redefinir e trocar a senha | SN-RN-02, 07, 09 |
| P-SN-06 | Cadastro em `PENDING_VERIFICATION` **não** recebe link (202 sem envio) | SN-RN-02 |
| P-SN-07 | E-mail em várias contas: **um e-mail por conta**, no máximo 5 por pedido | SN-RN-02 |
| P-SN-08 | Aviso de "senha alterada" **em toda troca**, nos três caminhos, sem link de ação | SN-RN-12 |
| P-SN-09 | ADMIN **não** troca a própria senha pela tela de usuários; só pela troca com a senha atual | SN-RN-10 |
| P-SN-10 | Login divide `login:senha` só no **primeiro** `:`; `:` proibido em login novo | SN-RN-08 |
| P-SN-11 | Admin da plataforma usa o **mesmo fluxo** | SN-RN-02 |
| P-SN-12 | Ligar o "Esqueci a senha" na EC2 **só depois da saída do SES do sandbox**; a troca logada pode ir antes | SN-RN-14 |
| P-AV-01 | "Pendência alta" = **toda pendência aberta com severidade ALTA**, lista de tipos configurável. Go-live só depois de o time Fiscal validar as severidades ALTA | AV-RN-01, AV-RN-13 |
| P-AV-02 | Recebem **todos com `INVOICES_VIEW`, das empresas da carteira**; o ADMIN de todas as empresas do escritório | AV-RN-02 |
| P-AV-03 | **Ligado para todos os elegíveis, com opt-out** em Minha conta | AV-RN-08 |
| P-AV-04 | **Resumo diário às 07:50**, um e-mail por usuário | AV-RN-03 |
| P-AV-05 | Conteúdo = **nome da empresa + tipo + quantidade**; sem CNPJ, nota, chave, terceiro ou valor. Go-live só depois de constar na política de privacidade e no acordo de operador | AV-RN-06, AV-RN-12, AV-RN-13 |
| P-AV-06 | **Sem reaviso na v1**: uma vez por episódio | AV-RN-04 |
| P-AV-07 | **Teto de 200 resumos por dia** no total, os de fora na frente no dia seguinte; ajustar para no máximo metade da cota do SES | AV-RN-09 |
| P-AV-08 | ADMIN **não** liga nem desliga o aviso dos colaboradores na v1 | AV-RN-08 |
| P-AV-09 | Escritório em **teste grátis recebe** | AV-RN-07 |
| P-AV-10 | **Só dias úteis** (segunda a sexta), sem calendário de feriados | AV-RN-03 |
| P-AV-11 | **Sem link de descadastro sem login** na v1 | AV-RN-06 |

As duas features **nascem desligadas** (`TAXIFY_PASSWORD_RESET_ENABLED=false` e `TAXIFY_PENDENCY_ALERTS_ENABLED=false`).

**Pré-requisitos de go-live do aviso de pendência** (acrescentados pelo Tony), antes de ligar
`TAXIFY_PENDENCY_ALERTS_ENABLED`:
- o time Fiscal validar as severidades ALTA dos tipos de pendência;
- constar na política de privacidade e no acordo de operador que o nome das empresas clientes sai por e-mail.

### Perguntas em aberto

Nenhuma.

### Respondidas

As perguntas abaixo foram respondidas pelo Tony em 2026-11-27: a recomendação de cada uma foi aceita como está.

#### Feature 1

| # | Pergunta | Recomendação (aceita) |
|---|---|---|
| P-SN-01 | Quanto tempo vale o link de redefinição? | **60 minutos**, configurável. É o bastante para abrir o e-mail e curto se o e-mail vazar. (A verificação do cadastro vale 24 h porque não dá acesso a dado.) |
| P-SN-02 | Quais limites de envio? | Os mesmos números do reenvio da verificação: **3 por hora e 5 por dia por conta, 10 por dia por IP**, sempre com 202 |
| P-SN-03 | A redefinição e a troca derrubam as sessões abertas? Hoje o JWT é stateless e vale até expirar (cerca de 5 h na prática) | **Sim**, pela versão de sessão no usuário e no token (SN-RN-11), em reset, troca pelo usuário e troca pelo ADMIN. Custa uma leitura do usuário por requisição, que o `CurrentUserService` já faz, e de quebra fecha o risco R-02 (token de usuário excluído) |
| P-SN-04 | Qual a regra mínima de senha no backend? Hoje não há mínimo (o front pede 6 só no cadastro) | **Mínimo de 8 caracteres e no máximo 72 bytes**, sem regra de composição, e senha nova diferente da atual. Vale para toda senha nova (cadastro, ADMIN, reset, troca); as senhas atuais continuam valendo |
| P-SN-05 | Usuário de escritório bloqueado ou somente leitura pode redefinir e trocar a senha? | **Sim.** Senha é credencial, não dado do escritório; o usuário continua só com leitura depois de entrar. Bloquear isso viraria chamado de suporte justamente no escritório que precisa regularizar |
| P-SN-06 | Cadastro ainda não verificado (`PENDING_VERIFICATION`) recebe link de senha? | **Não.** Responde 202 e não envia: essa conta ainda não tem login, e o caminho é "Reenviar e-mail de verificação" |
| P-SN-07 | Pedido pelo e-mail que está em várias contas (o e-mail não é único): o que enviar? | **Um e-mail por conta**, cada um com o login e o escritório, no máximo 5 contas por pedido. Ninguém recebe link de conta que não tem aquele e-mail |
| P-SN-08 | Enviar e-mail de "sua senha foi alterada" em toda troca? | **Sim**, nos três caminhos, sem link de ação. É a forma de o usuário perceber uma troca que não fez |
| P-SN-09 | O ADMIN pode trocar a **própria** senha pela tela de usuários, sem informar a atual? | **Não.** A própria senha só pela troca com a senha atual (SN-RN-09). Para outros usuários, o ADMIN continua como hoje |
| P-SN-10 | Senha com `:` (P-10 da spec de plataforma): hoje é aceita e nunca autentica | **Corrigir o login** para dividir só no primeiro `:`. Sem isso, a tela nova aceitaria uma senha que não entra. Recomendo também proibir `:` em login novo |
| P-SN-11 | O admin da plataforma (CS Hub) também redefine a senha por e-mail? | **Sim, mesmo fluxo.** O risco (e-mail da CS Hub comprometido) se mitiga com o aviso de senha alterada; 2FA fica para depois |
| P-SN-12 | Quando ligar na EC2? | **Só depois da saída do SES do sandbox.** Antes disso o 202 enganaria o usuário. A troca logada (SN-RN-09) pode ir antes, porque não depende de e-mail |

#### Feature 3

| # | Pergunta | Recomendação (aceita) |
|---|---|---|
| P-AV-01 | O que é "pendência alta"? | **Toda pendência aberta com severidade ALTA**, como o sistema já classifica: certificado vencido, captura SEFAZ ou NFS-e com erro, prazo de manifestação (até 15 dias), certidão PE positiva ou vencida e os achados de auditoria das regras ALTA. Lista de tipos configurável. Peço ao time Fiscal que confirme as severidades (pergunta 5 da spec de pendências), porque elas passam a gerar e-mail |
| P-AV-02 | Quem recebe? | **Todo usuário com acesso às pendências (`INVOICES_VIEW`), só das empresas da carteira dele**; o ADMIN recebe de todas as empresas do escritório |
| P-AV-03 | O aviso vem ligado ou desligado? | **Ligado para todos os elegíveis, e cada um desliga em Minha conta.** O pedido é justamente "ninguém é avisado". Alternativa mais conservadora: ligado só para o ADMIN |
| P-AV-04 | Na hora ou resumo? | **Resumo diário às 07:50, um e-mail por usuário.** Um prazo de manifestação gera até 200 pendências por empresa, e e-mail por evento viraria ruído e queimaria a cota do SES |
| P-AV-05 | O que o e-mail pode mostrar? | **Nome da empresa, tipo da pendência e quantidade.** Sem CNPJ, nota, chave, terceiro ou valor. Sem o nome da empresa, o analista teria de abrir o painel para saber de quem é. Alternativa mais restrita: só os totais e o link |
| P-AV-06 | Avisar de novo uma pendência ALTA que continua aberta? | **Não na v1:** uma vez por episódio (de novo só se fechar e voltar). Um lembrete semanal das ALTA abertas há mais de 7 dias pode vir depois, se o time sentir falta |
| P-AV-07 | Qual teto diário de e-mails? | **200 resumos por dia no total**, com os que ficaram de fora na frente no dia seguinte. Ajustar para no máximo metade da cota de produção do SES quando ela for conhecida |
| P-AV-08 | O ADMIN pode ligar ou desligar o aviso dos colaboradores? | **Não na v1.** Cada usuário decide o seu. Se o time pedir, entra depois |
| P-AV-09 | Escritório em teste grátis recebe o aviso? | **Sim.** As rotinas rodam no teste, e o aviso ajuda a mostrar valor durante os 14 dias |
| P-AV-10 | Fim de semana e feriado? | **Só de segunda a sexta**; a segunda leva o fim de semana. Sem calendário de feriados na v1 (no feriado sai e-mail normalmente) |
| P-AV-11 | Precisa de link para desligar sem login? | **Não na v1.** O destinatário é usuário interno do escritório, e o link do rodapé leva a Minha conta. Se as reclamações no SES subirem, entra um link de um clique com token |

## 11. Riscos

- **E-mail vira credencial.** Quem controla a caixa de e-mail do usuário consegue a senha. O e-mail do colaborador
  não é verificado hoje e pode estar errado. Mitigação: aviso de senha alterada, texto de ajuda no formulário do ADMIN,
  e o ADMIN continua podendo redefinir.
- **Sandbox do SES.** Ligar qualquer das duas features antes da saída do sandbox faz e-mails sumirem sem erro visível
  para o usuário. As duas nascem desligadas (SN-RN-14, AV-RN-13).
- **Leitura extra por requisição.** O filtro de sessão (SN-RN-11) depende de uma leitura do usuário por requisição.
  Hoje ela já existe na maioria das rotas (`CurrentUserService`), mas passa a ser obrigatória em todas.
- **Logout geral indesejado.** Se o filtro de sessão tiver um erro, todos caem. Mitigação: token sem `sv` vale como 0
  (CA-SN-18) e teste de integração HTTP antes do deploy.
- **Ruído.** Se as severidades ALTA não estiverem calibradas, o resumo vira spam e as pessoas desligam. Mitigação:
  P-AV-01 com o time Fiscal, resumo diário e deduplicação por episódio.
- **Dado de cliente por e-mail.** O nome das empresas sai da plataforma por e-mail. Mitigação: só para quem já vê
  aquelas empresas, um destinatário por mensagem, nenhum identificador fiscal, e menção na política de privacidade.
