# Plataforma: acesso, escritórios, usuários e empresas (as-built)

> Spec retroativa. Descreve o que **já está no código** em 2026-11-26, não uma feature nova.
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend).
> Pacote base do backend: `br.com.cshub.taxify` (abreviado `…`).

## Status

**Implementado.**

- Migrations: `V1__AccountingFirms`, `V2__Documents`, `V3__Users`, `V4__UserAccessProfiles`, `V5__Companies`,
  `V6__UsersCompanies`, `V7__CompanyTaxRegimes`, `V28__CompanyCertificates`, `V51__FirmCertificates`
  (todas em `src/main/java/db/migration`, no padrão `TableMigration`: cria a tabela ou completa o que falta).
- Backfills em startup (`ApplicationRunner`): `config/AccessProfileMigration`, `config/CompanyTaxRegimeBackfill`.
- Commits de referência: `a682e36` (admin da plataforma: `PLATFORM_ADMIN_LOGINS` e claim `platformAdmin`),
  `6eadd7a` (usuário lido uma vez por requisição e carteira numa consulta só), `77b7680` (último commit do período).

## Problema e objetivo

O Taxify é SaaS multiescritório. Cada escritório contábil (tenant) cadastra a carteira de empresas clientes e a equipe.
O módulo resolve:

1. **Isolamento entre escritórios.** Um escritório nunca enxerga dado de outro. Quando tenta, recebe 404, não 403, para
   não revelar que o recurso existe.
2. **Acesso por função.** O ADMIN do escritório faz tudo. Os demais colaboradores recebem perfis fixos (Fiscal, Contábil,
   Financeiro, DP, Gestão), e cada perfil libera um conjunto de permissões. Um não-admin só vê as empresas da própria
   carteira (vínculo usuário × empresa).
3. **Cadastro da empresa pronto para as rotinas fiscais.** Documento, UF, IE por UF, CNAE, regime tributário com
   histórico mensal e certificado A1 (e-CNPJ) para as capturas na SEFAZ.
4. **Regras gerais protegidas.** Só o administrador da plataforma (a CS Hub) altera o que vale para todos os escritórios.

Como medir (não há métrica instrumentada no código): nenhum vazamento entre tenants nos testes de acesso e nenhum
endpoint sem regra de acesso (teste-guarda `PermissionAuthorizationManagerTest#noEndpointIsLeftWithoutAnAccessRule`).

## Usuários e cenário

| Papel | Quem é | O que faz aqui | Frequência |
|---|---|---|---|
| Dono do escritório (ADMIN) | Quem fez o cadastro público | Cria a conta, a equipe, os vínculos, as empresas e envia os certificados A1 | Na implantação e quando entra empresa ou colaborador |
| Colaborador (EMPLOYEE/USER) com perfis | Analista fiscal, contábil, financeiro | Consulta as empresas da sua carteira e usa os módulos liberados pelos perfis | Diário |
| Admin da plataforma | Login ADMIN listado em `PLATFORM_ADMIN_LOGINS` | Altera regras gerais (tabela de ICMS do Fronteira e regras da auditoria) | Eventual |
| Sistema (agendadores) | Jobs sem usuário na requisição | Lê e grava sem restrição de tenant | Contínuo |

## Histórias de usuário

- H1. Como dono de escritório, quero criar minha conta pelo formulário público, para começar a usar o Taxify sem depender da CS Hub.
- H2. Como usuário, quero entrar com login e senha e receber um token, para usar as telas liberadas para mim.
- H3. Como ADMIN, quero cadastrar colaboradores com um ou mais perfis de acesso, para que cada um veja só o módulo dele.
- H4. Como ADMIN, quero vincular colaboradores às empresas (carteira), para que cada analista veja só os clientes que atende.
- H5. Como ADMIN, quero que o escritório nunca fique sem administrador, para não perder a gestão da conta.
- H6. Como ADMIN, quero cadastrar e editar empresas (documento, endereço, IE por UF, CNAE, regime), para que as rotinas fiscais tenham os dados certos.
- H7. Como ADMIN, quero registrar o histórico do regime tributário mês a mês, para que cada competência seja apurada com o regime vigente nela.
- H8. Como ADMIN, quero completar o cadastro da empresa pelos dados da última nota emitida por ela, para não digitar endereço, CNAE e IE.
- H9. Como ADMIN, quero enviar o certificado A1 da empresa, para que o Taxify capture as notas direto na SEFAZ. Filiais usam o da matriz.
- H10. Como ADMIN, quero enviar o e-CPF do contador do escritório, para que o Taxify entre no e-Fisco PE pelas empresas da carteira.
- H11. Como CS Hub (admin da plataforma), quero ser o único a alterar as regras gerais, para que um escritório não mude as regras dos outros.

## Regras de negócio

### Autenticação

- **RN-01 Login.** `POST /auth` recebe `{ "auth": base64("login:senha") }`. O backend decodifica, separa por `:` e
  autentica no `AuthenticationManager` (senha com BCrypt). Login ou senha vazios: 400 `error.user.login.required`.
  Fonte: `service/AuthService.java:32-47`, `config/SecurityConfig.java:96-99`.
- **RN-02 Token JWT.** Assinado com RSA (`jwt.key.private`/`jwt.key.public`), emissor `taxify-service`, validade de 2 h.
  Claims: `sub` (login), `authorities` (tipo do usuário + permissões), `userId`, `profile`, `accessProfiles`,
  `permissions`, `tenantId` (id do escritório) e `platformAdmin`. Fonte: `service/TokenService.java:29-56`.
  O conversor lê as autoridades do claim `authorities`, sem prefixo: `config/SecurityConfig.java:86-94`.
- **RN-03 Erros de login.** Credencial inválida ou usuário inexistente respondem **403** (`BadCredentialsException` /
  `InternalAuthenticationServiceException`). Fonte: `rest/exception/GlobalExceptionHandler.java:94-100`.
- **RN-04 Sessão sem estado.** CSRF desligado, sessão `STATELESS`. Rotas públicas: `POST /auth`, `POST /users`
  (só anônimo ou ADMIN), Swagger e `/actuator/health`. Todo o resto exige usuário autenticado.
  Fonte: `config/SecurityConfig.java:51-78`.

### Cadastro público e usuários

- **RN-05 Cadastro público cria escritório.** `POST /users` sem usuário logado cria um `AccountingFirm` com o nome
  da pessoa e grava o usuário como **ADMIN**, seja qual for o perfil enviado no payload. Com um ADMIN logado, o novo
  usuário entra no escritório desse ADMIN. Um não-admin logado não cria usuário (bloqueado na `SecurityConfig`).
  Fonte: `validation/UserValidation.java:54-77`, `config/SecurityConfig.java:60-62`.
- **RN-06 Senha.** Obrigatória na criação (400 `error.user.password.required`) e gravada com BCrypt. Na edição, senha
  em branco mantém a atual. A API nunca devolve a senha. Fonte: `validation/UserValidation.java:57-60` e `:133-136`,
  `mapper/UserMapper.java:43`.
- **RN-07 Login único na plataforma.** Entre usuários não excluídos, login repetido gera 409
  `error.user.login.already.exists`. Fonte: `validation/UserValidation.java:170-177`.
- **RN-08 Último ADMIN.** Não dá para excluir, rebaixar nem marcar como excluído o último ADMIN do escritório
  (400 `error.user.last.admin`). Fonte: `validation/UserValidation.java:83-92` e `:124-126`, `service/UserService.java:41-44`.
- **RN-09 ADMIN não tem perfis.** Para ADMIN a lista de perfis de acesso é limpa na gravação. O ADMIN tem todas as
  permissões. Fonte: `validation/UserValidation.java:95-99`, `enumerated/AccessProfile.java:58-67`.
- **RN-10 Quem edita usuário.** `PUT` e `DELETE /users/{id}` só para ADMIN. `GET /users` e `GET /users/{id}` ficam
  abertos a qualquer logado, limitados ao próprio escritório. Fonte: `rest/UserResource.java:30-40`,
  `service/UserService.java:31-37` (`FirmScopedCrudService`).
- **RN-11 Documento do usuário.** O CPF/CNPJ do usuário é um `Document` com unicidade (número + tipo) **na plataforma
  inteira, incluindo registros excluídos** (409 `error.document.exist`). Na edição, troca o número se não houver outro
  igual. Fonte: `validation/DocumentValidation.java:53-75`, `repository/DocumentRepository.java:15-18`.

### Perfis e permissões

- **RN-12 Tipos de usuário.** `ADMIN`, `EMPLOYEE`, `USER` (`enumerated/UserProfileEnum.java`). Só ADMIN tem efeito
  especial. EMPLOYEE e USER valem pelos perfis de acesso.
- **RN-13 Perfis fixos e permissões.** Um colaborador pode ter vários perfis, e as permissões se somam.
  Fonte: `enumerated/AccessProfile.java:31-45`.

  | Perfil | Permissões |
  |---|---|
  | FISCAL | COMPANIES_VIEW, INVOICES_VIEW, INVOICES_SYNC, INVOICES_RECTIFY, INVOICES_AUDIT, FISCAL_RULES_VIEW, FISCAL_RULES_MANAGE, TAX_ASSESSMENT_VIEW, TAX_ASSESSMENT_CLOSE |
  | ACCOUNTING | COMPANIES_VIEW, INVOICES_VIEW, FISCAL_RULES_VIEW, TAX_ASSESSMENT_VIEW |
  | FINANCIAL | COMPANIES_VIEW, PLANS_VIEW, SUBSCRIPTIONS_VIEW, SUBSCRIPTIONS_MANAGE, BILLING_VIEW, BILLING_SYNC, BILLING_MANAGE |
  | PAYROLL | COMPANIES_VIEW |
  | MANAGEMENT | COMPANIES_VIEW, INVOICES_VIEW, FISCAL_RULES_VIEW, TAX_ASSESSMENT_VIEW, PLANS_VIEW, SUBSCRIPTIONS_VIEW, BILLING_VIEW |

- **RN-14 Regra declarada no controller.** `@RequiresPermission(value, write)` e `@AdminOnly(writeOnly)` nos
  controllers. Ordem: anotação do método vence a da classe. Na classe, `value` vale para GET/HEAD/OPTIONS e `write`
  para POST/PUT/PATCH/DELETE (vazio = `value`). Mapping sem método conta como escrita. ADMIN sempre passa. Negado: 403
  `error.access.denied`. Fonte: `security/PermissionAuthorizationManager.java:46-103`,
  `config/MethodSecurityConfig.java:27-42`, `rest/exception/GlobalExceptionHandler.java:103-108`.
- **RN-15 Migração dos perfis.** No startup, se `user_access_profiles` estiver vazia, todo usuário não-admin e não
  excluído recebe FISCAL, porque antes dos perfis todo colaborador trabalhava no fiscal.
  Fonte: `config/AccessProfileMigration.java:40-53`.

### Multi-tenant e carteira

- **RN-16 Fronteira do tenant.** Todo `User`, `Company` e `Plan` pertence a um `AccountingFirm`. Ler ou gravar dado de
  outro escritório responde **404** (`error.Company.not.found` ou a mensagem de não encontrado da entidade).
  Fonte: `common/TenantAccessService.java:64-68`.
- **RN-17 Carteira do não-admin.** Um não-admin só acessa empresas com vínculo `UserCompany` ativo. O ADMIN vê todas as
  empresas do escritório. Empresa fora da carteira também responde 404. Fonte: `common/TenantAccessService.java:74-118`.
- **RN-18 Listas filtradas na consulta.** Entidades do escritório (`FirmScopedCrudService`) filtram por
  `accountingFirm.id`. Entidades da empresa (`CompanyScopedCrudService`) filtram por escritório e, para não-admin,
  por um `EXISTS` em `users_companies`. Fonte: `common/FirmScopedCrudService.java:39-52`,
  `common/CompanyScopedCrudService.java:40-54`, `common/TenantAccessService.java:125-142`.
- **RN-19 Contexto de sistema.** Sem usuário na requisição (agendadores e jobs), não há restrição de tenant.
  Fonte: `common/TenantAccessService.java:30-33` e `:48-51`, `common/CurrentUserService.java:13-17`.
- **RN-20 Usuário lido uma vez por requisição.** O usuário corrente fica em cache no escopo da requisição, com chave
  pelo login. Fonte: `common/CurrentUserService.java:34-47`.
- **RN-21 Vínculo usuário × empresa.** Chave composta (usuário, empresa) e um papel em texto livre (`role`, até 50).
  Criar e remover: só ADMIN. Listar: qualquer logado, limitado à sua carteira. O usuário e a empresa do vínculo
  precisam ser do mesmo escritório. Fonte: `rest/UserCompanyResource.java:14`,
  `validation/UserCompanyValidation.java:35-104`.
- **RN-22 Exclusão lógica.** Todo `delete` do CRUD genérico marca `deleted = true`. Listas escondem excluídos, a não
  ser que o filtro peça. Fonte: `common/AbstractCrudService.java:53-65` e `:129-133`.

### Escritório e admin da plataforma

- **RN-23 Escritório.** `GET /accounting-firms` devolve só o próprio escritório. Criar, editar e excluir: só ADMIN, e
  apenas o próprio escritório. O DTO tem só `id`, `name` e `deleted`. Fonte: `rest/AccountingFirmResource.java:15`,
  `validation/AccountingFirmValidation.java:35-62`, `dto/accountingfirm/AccountingFirmDTO.java`.
- **RN-24 Admin da plataforma.** É admin da plataforma quem tem perfil ADMIN **e** login (sem diferenciar
  maiúsculas/minúsculas) em `taxify.platform.admin-logins` (`PLATFORM_ADMIN_LOGINS`, separado por vírgula). Lista vazia:
  ninguém. Só esse usuário altera as regras gerais (os outros recebem 403). Vai no token como `platformAdmin`.
  Fonte: `common/PlatformAdminService.java:29-53`, `service/TokenService.java:50`.

### Empresas

- **RN-25 Acesso às empresas.** Leitura: `COMPANIES_VIEW`. Toda escrita em `/companies/**` é só do ADMIN: criar,
  editar, excluir, certificado A1, captura SEFAZ/NFS-e, regimes e "preencher pelas notas".
  Fonte: `rest/CompanyResource.java:43-44`.
- **RN-26 Empresa nasce no escritório do criador.** Na criação, o `accountingFirm` vem do usuário logado.
  Fonte: `validation/CompanyValidation.java:52-56`.
- **RN-27 Documento da empresa.** Obrigatório (`documentNumber`, `documentType`). É único na plataforma (ver RN-11) e
  **não muda na edição**: o `merge` não copia o documento. Fonte: `dto/company/CompanyDTO.java:13-16`,
  `mapper/CompanyMapper.java:101-128`.
- **RN-28 Normalização do cadastro.** CNAE principal, código IBGE e CEP guardam só dígitos. CNAEs secundários viram uma
  lista de dígitos separada por vírgula, sem repetição ("4781-4/00, 4782-2/01" vira "4781400,4782201"). Textos em
  branco viram nulo. Fonte: `mapper/CompanyMapper.java:38-48` e `:149-169`.
- **RN-29 Inscrição estadual por UF.** Guardada em `state_registration` como "IE/UF; IE/UF". "ISENT…" vira "ISENTO".
  Fora isso, ficam só os dígitos (até 20). Texto que não segue o formato (IE sem UF) fica como digitado. O texto todo
  tem no máximo 255 caracteres. Fonte: `domain/company/StateRegistration.java:22-57`, `mapper/CompanyMapper.java:30-36`.
- **RN-30 Regime: campos estruturados vencem o rótulo.** Com `taxRegimeType` informado, o rótulo é refeito a partir
  dele, a menos que o rótulo enviado já diga o mesmo. Só com o rótulo (importação), o texto é interpretado: "simples"
  vira SIMPLES_NACIONAL, "presumido" vira LUCRO_PRESUMIDO, "real" vira LUCRO_REAL, "mei"/"microempreendedor" vira MEI,
  e o resto vira OTHER. A atividade sai de "comércio", "serviço" e "indústria". O anexo do Simples nunca vem do
  rótulo. Fonte: `mapper/CompanyMapper.java:134-147`, `common/TaxRegimes.java:23-71`.
- **RN-31 Histórico de regime.** Cada período tem `validFrom` (1º dia do mês; nulo = "desde sempre"). O regime de um
  mês é o do último período iniciado até ele. Os campos de regime da empresa espelham o período vigente hoje, e um
  período futuro ainda não conta. `validUntil` é calculado como o mês anterior ao próximo período. O anexo de serviço
  só é guardado para SIMPLES_NACIONAL. Salvar com o mesmo `validFrom` substitui o período.
  Fonte: `service/CompanyTaxRegimeService.java:63-72`, `:128-137`, `:157-185`.
- **RN-32 Mudança de regime pelo formulário ou pela importação.** Depois de criar ou editar a empresa, se o regime for
  diferente do período vigente, o sistema grava um período a partir de `taxRegimeValidFrom` ("AAAA-MM", e o mês atual
  se vier vazio ou inválido). O primeiro regime da empresa vale "desde sempre". Fonte:
  `service/CompanyService.java:36-55`, `service/CompanyTaxRegimeService.java:92-103` e `:200-206`.
- **RN-33 Mês inválido no histórico.** No `POST /companies/{id}/regimes`, um `validFrom` fora do formato `AAAA-MM`
  responde 400 `error.assessment.month.invalid`. Sem `taxRegime`, responde 400 `error.taxRegime.required`.
  Fonte: `service/CompanyTaxRegimeService.java:65-68` e `:188-197`.
- **RN-34 Preencher pelas notas.** Usa a última NF-e de emissão própria (`ownIssue = true`, a mais recente por data de
  emissão). Só preenche campos **vazios**: endereço, município/IBGE, CEP, UF, telefone, CNAE principal e inscrição
  municipal. A IE da nota entra só se a empresa ainda não tiver IE para aquela UF e não tiver IE em texto legado.
  Sem nota, nada muda. Fonte: `service/CompanyRegistrationService.java:42-81`.

### Certificados A1

- **RN-35 A1 da empresa: validação.** O `.pfx` tem de abrir com a senha (400 `error.certificate.invalid`), estar
  dentro da validade (400 `error.certificate.expired`) e ter a mesma raiz de CNPJ (8 primeiros dígitos) da empresa
  (400 `error.certificate.other.company`). Arquivo vazio: 400 `error.certificate.empty`.
  Fonte: `service/CompanyCertificateService.java:90-107`.
- **RN-36 Guarda cifrada opcional.** Com `CERT_ENCRYPTION_KEY` (AES-256-GCM, 32 bytes em base64), o `.pfx` vai para o
  storage como `.pfx.enc` e a senha vai para o banco com o prefixo `enc:`. Sem a chave, o arquivo vai como `.pfx` e a
  senha como `plain:<senha>`, com um aviso no log. Os dois formatos continuam abrindo depois que a chave for
  configurada. Chave com tamanho errado derruba o startup. Fonte: `service/CompanyCertificateService.java:45-48`,
  `:109-117`, `:178-189`, `common/SecretCipher.java:32-42`.
- **RN-37 Troca e remoção.** Novo upload substitui o registro e apaga o arquivo antigo. `DELETE` remove o registro
  (exclusão física) e o arquivo. Fonte: `service/CompanyCertificateService.java:121-144`.
- **RN-38 Filial usa o A1 da matriz.** Sem certificado próprio, a empresa usa o de outra empresa do **mesmo
  escritório** com a mesma raiz de CNPJ. Os válidos vêm primeiro, e entre eles o de maior validade. O status mostra
  `inherited = true` e qual é a empresa dona. Fonte: `service/CompanyCertificateService.java:152-169`,
  `dto/company/CompanyCertificateDTO.java`.
- **RN-39 e-CPF do contador.** É um por escritório (`/integrations/efisco/certificado`), com as mesmas validações de
  senha e validade e com a exigência de ser e-CPF (400 `error.certificate.not.ecpf`). O status traz o titular (CN sem o
  CPF), o CPF mascarado (`123.***.***-01`) e a última sincronização com o e-Fisco. GET aberto a qualquer logado;
  upload e remoção só para ADMIN. Fonte: `service/FirmCertificateService.java:59-168`,
  `rest/FirmCertificateResource.java:19-25`.

## Critérios de aceite

Refletem o comportamento atual. Os exemplos usam dados fictícios.

- **CA-01 (RN-01, RN-02)** Dado o usuário `ana.fiscal` com senha `Senha#123`, quando o front envia
  `POST /auth` com `auth = base64("ana.fiscal:Senha#123")`, então a resposta é 200 com `token`, `userId` e `userName`,
  e o token tem `tenantId` igual ao escritório dela e `permissions` com as permissões dos perfis dela.
- **CA-02 (RN-03)** Dada uma senha errada, quando o login é tentado, então a resposta é 403, e a tela de login mostra
  "Login ou senha incorretos".
- **CA-03 (RN-05)** Dado um visitante não logado, quando ele envia `POST /users` com `profile: "EMPLOYEE"`, então é
  criado um escritório novo com o nome dele, e o usuário fica como ADMIN desse escritório.
- **CA-04 (RN-05, RN-04)** Dado um usuário EMPLOYEE logado, quando ele envia `POST /users`, então a resposta é 403.
- **CA-05 (RN-08)** Dado um escritório com um único ADMIN, quando esse ADMIN tenta se excluir ou mudar o próprio tipo
  para EMPLOYEE, então a resposta é 400 com `error.user.last.admin`.
- **CA-06 (RN-07)** Dado que o login `joao` já existe em outro escritório, quando um ADMIN cria um colaborador com o
  login `joao`, então a resposta é 409.
- **CA-07 (RN-13, RN-14)** Dado um colaborador só com o perfil ACCOUNTING, quando ele tenta `POST /companies`, então a
  resposta é 403. E quando ele faz `GET /companies`, então recebe 200.
- **CA-08 (RN-16)** Dada a empresa X do escritório A, quando um usuário do escritório B faz `GET /companies/{X}`, então
  a resposta é 404, não 403.
- **CA-09 (RN-17)** Dado um colaborador FISCAL vinculado só à empresa X, quando ele lista `/companies`, então só X
  aparece. E quando ele pede `GET /companies/{Y}` de uma empresa do mesmo escritório sem vínculo, então a resposta é 404.
- **CA-10 (RN-24)** Dado `PLATFORM_ADMIN_LOGINS=csadmin` e o usuário `CsAdmin` com perfil ADMIN, quando ele faz login,
  então o token tem `platformAdmin = true`. Um ADMIN com outro login recebe `platformAdmin = false` e 403 ao alterar
  regra geral.
- **CA-11 (RN-27)** Dada uma empresa com CNPJ 11.111.111/0001-11, quando o ADMIN a edita enviando outro CNPJ, então o
  CNPJ gravado continua o original.
- **CA-12 (RN-11)** Dado que outro escritório já cadastrou o CNPJ 22.222.222/0001-22, mesmo que depois o tenha
  excluído, quando o ADMIN cadastra uma empresa com esse CNPJ, então a resposta é 409 `error.document.exist`.
- **CA-13 (RN-29)** Dado o campo IE "123.456-7/pe; isento/PB", quando a empresa é salva, então o valor gravado é
  "1234567/PE; ISENTO/PB".
- **CA-14 (RN-31, RN-32)** Dada uma empresa sem histórico com regime SIMPLES_NACIONAL, quando o ADMIN muda o regime
  para LUCRO_PRESUMIDO com `taxRegimeValidFrom = "2026-01"`, então o histórico fica com Simples "desde sempre" até
  2025-12 e Presumido a partir de 2026-01.
- **CA-15 (RN-34)** Dada uma empresa com endereço preenchido e CNAE vazio, quando o ADMIN aciona "preencher pelas
  notas", então só o CNAE (e outros campos vazios) é preenchido a partir da última NF-e emitida por ela. O endereço
  não muda.
- **CA-16 (RN-35)** Dado um A1 do CNPJ 33.333.333/0001-33, quando o ADMIN o envia para a empresa de CNPJ
  44.444.444/0001-44, então a resposta é 400 `error.certificate.other.company`.
- **CA-17 (RN-38)** Dada uma matriz 55.555.555/0001-55 com A1 válido e uma filial 55.555.555/0002-36 sem A1, quando
  alguém consulta `GET /companies/{filial}/certificado`, então `present = true`, `inherited = true` e a dona é a matriz.
- **CA-18 (RN-36)** Dado `CERT_ENCRYPTION_KEY` vazio, quando um A1 é enviado, então o arquivo é guardado como `.pfx`, a
  senha como `plain:…`, e o log registra um aviso. O status traz `encryptionConfigured = false`.
- **CA-19 (RN-39)** Dado um A1 e-CNPJ, quando o ADMIN o envia em `/integrations/efisco/certificado`, então a resposta
  é 400 `error.certificate.not.ecpf`.

## Endpoints da API

Base: `/taxify-service/api/v1`.

| Método | Caminho | Permissão | Observação |
|---|---|---|---|
| POST | `/auth` | público | RN-01 |
| GET | `/users`, `/users/{id}` | logado (mesmo escritório) | RN-10 |
| POST | `/users` | anônimo (cadastro público) ou ADMIN | RN-05 |
| PUT, DELETE | `/users/{id}` | ADMIN | RN-08, RN-10 |
| GET | `/users/{userId}/companies`, `/users/{userId}/companies/{id}` | logado (carteira) | RN-21 |
| POST, PUT, DELETE | `/users/{userId}/companies[/{id}]` | ADMIN | ver Pergunta P-01 |
| GET | `/accounting-firms`, `/accounting-firms/{id}` | logado (só o próprio) | RN-23 |
| POST, PUT, DELETE | `/accounting-firms[/{id}]` | ADMIN | RN-23 |
| GET | `/companies`, `/companies/{id}` | COMPANIES_VIEW | filtros: `search`, `uf`, `name`, `documentNumber` |
| POST, PUT, DELETE | `/companies[/{id}]` | ADMIN | RN-25 |
| GET | `/companies/{id}/regimes` | COMPANIES_VIEW | RN-31 |
| POST | `/companies/{id}/regimes` | ADMIN | RN-31, RN-33 |
| DELETE | `/companies/{id}/regimes/{regimeId}` | ADMIN | exclusão lógica do período |
| POST | `/companies/{id}/preencher-pelas-notas` | ADMIN | RN-34 |
| GET | `/companies/{id}/certificado` | COMPANIES_VIEW | RN-38 |
| POST (multipart `file`, `password`) | `/companies/{id}/certificado` | ADMIN | RN-35, RN-36 |
| DELETE | `/companies/{id}/certificado` | ADMIN | RN-37 |
| GET | `/integrations/efisco/certificado` | logado | RN-39 |
| POST (multipart), DELETE | `/integrations/efisco/certificado` | ADMIN | RN-39 |

Rotas de `/companies/{id}` documentadas em outras specs: `sefaz/capturar`, `sefaz/resumos`, `nfse-nacional`
(captura de XML).

### Telas do front (`taxify-front/src/app`)

| Rota ou local | Componente | Guarda |
|---|---|---|
| `/auth/login`, `/auth/register` | `features/auth/auth.component.ts` (login e cadastro em 3 etapas; senha com mínimo de 6 só no front) | pública |
| `/cadastro/empresas` | `features/companies/company-list`, `company-form`, `company-tax-regimes`, `company-certificate` | `permissionGuard('COMPANIES_VIEW')`; editar/excluir/nova só aparecem para ADMIN |
| `/cadastro/usuarios` | `features/users/user-list`, `user-form`, `user-links` | `adminGuard` |
| Modal "Dados do escritório" (avatar no cabeçalho) | `shared/layout/header`, `features/accounting-firm/accounting-firm-form`, `firm-integrations/efisco-certificate-card.component.ts` | nenhuma (abre para qualquer logado) |
| Guards e sessão | `core/guards/auth.guard.ts`, `core/guards/permission.guard.ts`, `core/services/auth.service.ts` (token no `localStorage`, `can()` = ADMIN ou permissão do token), `core/interceptors/auth.interceptor.ts` (Bearer; 401 desloga) | |

## Dados e integrações

| Tabela | Migration | Conteúdo |
|---|---|---|
| `accounting_firms` | V1 | escritório (tenant): `name`, `billing_provider` |
| `documents` | V2 | CPF/CNPJ/RG/CNH (`type` texto; antes era ORDINAL) |
| `users` | V3 | `login` único (`uk_users_login`), `document_id` único, `profile`, `accounting_firm_id` |
| `user_access_profiles` | V4 | PK (`access_profile`, `user_id`) |
| `companies` | V5 | cadastro, `document_id` único (`uk_companies_document`), `acessorias_id`, regime (rótulo + estruturado) |
| `users_companies` | V6 | PK (`company_id`, `user_id`), `role` |
| `company_tax_regimes` | V7 | histórico (`valid_from`, `tax_regime`, `business_activity`, `simples_service_annex`) |
| `company_certificates` | V28 | um por empresa (`uk_company_certificates_company`), `file_path`, `password_encrypted`, `cnpj`, validade |
| `firm_certificates` | V51 | um por escritório, `cpf`, validade, `last_sync_*` do e-Fisco |

Todas as entidades têm auditoria (`created_by`, `created_date`, `last_modified_*`) e `deleted`.
Storage dos `.pfx`: `FiscalXmlFileStorage.storeCertificate` (S3 ou disco local, conforme `FISCAL_XML_STORAGE_TYPE`).

## Configuração

| Variável / propriedade | Efeito |
|---|---|
| `jwt.key.private` / `jwt.key.public` | `classpath:app.key` / `classpath:app.pub` (arquivos em `src/main/resources`) |
| `PLATFORM_ADMIN_LOGINS` (`taxify.platform.admin-logins`) | logins do admin da plataforma. No `application.yaml` o padrão é vazio, e no `docker-compose.yml` é **`admin`** |
| `CERT_ENCRYPTION_KEY` (`taxify.secrets.key`) | chave AES-256 dos A1 e senhas. Opcional, e com tamanho errado o startup falha |
| `FISCAL_XML_STORAGE_TYPE`, `FISCAL_XML_S3_*` | onde ficam os `.pfx` |
| `JPA_DDL_AUTO` | padrão `validate` (o schema é do Flyway) |

## Testes existentes

- Segurança e permissões: `security/PermissionAuthorizationManagerTest` (divisão leitura × escrita, método vence classe,
  guarda "nenhum endpoint sem regra"), `security/MethodSecurityWiringTest` (proxy real do `CompanyResource`),
  `enumerated/AccessProfileTest`.
- Tenant: `common/TenantAccessServiceTest`, `common/PlatformAdminServiceTest`, `config/AuditorAwareImplTest`,
  `config/CorsFilterTest`.
- Usuários e vínculos: `validation/UserValidationTest`, `validation/UserCompanyValidationTest`, `mapper/UserMapperTest`,
  `mapper/UserCompanyMapperTest`.
- Empresas: `validation/CompanyValidationTest`, `validation/DocumentValidationTest`, `mapper/CompanyMapperTest`,
  `mapper/DocumentMapperTest`, `search/CompanySpecificationTest`, `service/CompanyTaxRegimeServiceTest`,
  `service/CompanyRegistrationServiceTest`, `common/TaxRegimesTest`, `common/DocumentNumbersTest`.
- Certificados: `service/CompanyCertificateServiceTest`, `integration/sefaz/A1CertificateTest`, `common/SecretCipherTest`.

**Lacunas de teste:**
- Não há teste de `AuthService` (decodificação base64, senha com `:`) nem de `TokenService` (claims, expiração, fuso).
- Não há teste de `FirmCertificateService` (e-CPF, máscara do CPF, `holderOf`).
- Não há teste de `AccountingFirmValidation` nem do fluxo `POST /accounting-firms`.
- Não há teste de `AccessProfileMigration`.
- Não há teste de integração HTTP do cadastro público (`POST /users` anônimo × ADMIN × EMPLOYEE) na `SecurityConfig`.
- Não há teste de `GET/DELETE /users/{userId}/companies/{id}` (conversão da chave composta, ver P-01).
- O guarda `noEndpointIsLeftWithoutAnAccessRule` lista as exceções abertas, mas `FirmCertificateResource#status` não
  está na lista, e o `GET` dele não tem regra (ver P-08).

## Fora do escopo / limitações conhecidas

- Não há troca de senha por e-mail nem "esqueci a senha". O front só mostra a quem pedir (`auth.component.ts:97`).
- Não há refresh token. Quando o token expira, o front recebe 401 e desloga.
- O colaborador não edita o próprio perfil ou senha (`PUT /users/{id}` é só ADMIN).
- Não há política de senha no backend. O mínimo de 6 caracteres existe só no front.
- O CNPJ da empresa não pode ser corrigido depois de criado (RN-27).
- O CORS libera qualquer origem (`config/CorsFilter.java:21`, `Access-Control-Allow-Origin: *`).
- Os perfis de acesso são fixos no código. Não há perfil customizável por escritório.
- `CompanyAuthority` (`domain/company/CompanyAuthority.java`) existe, mas não é usado na autorização.

## Perguntas em aberto

Inconsistências encontradas no código. Não foram resolvidas aqui.

- **P-01 Vínculo usuário × empresa por id.** `UserCompanyResource` herda `GET/PUT/DELETE /{id}` com `id` do tipo
  `UserCompanyId` (chave composta). O front chama `DELETE /users/{userId}/companies/{companyId}`
  (`core/services/user-company.service.ts:37-39`), mas não há conversor de String para `UserCompanyId` nem `@InitBinder`.
  Remover vínculo pela tela funciona hoje?
- **P-02 Fuso do token.** `TokenService` usa `LocalDateTime.now()` e converte com offset fixo `-03:00`
  (`TokenService.java:30-40`). Se a JVM do container roda em UTC (o compose não define `TZ`), `iat`/`exp` ficam 3 h
  adiantados e o token vale na prática cerca de 5 h em vez de 2 h. Qual é a validade desejada?
- **P-03 `PLATFORM_ADMIN_LOGINS=admin` por padrão no compose.** Como o login é único na plataforma e o cadastro é
  público, quem se cadastrar primeiro com o login `admin` vira admin da plataforma onde a variável não foi definida.
  Manter esse padrão?
- **P-04 Chave privada do JWT no classpath.** `src/main/resources/app.key` não está no `.gitignore`. A chave está
  versionada? Se estiver, qualquer pessoa com acesso ao repositório consegue emitir tokens válidos.
- **P-05 Unicidade global de documento inclusive excluídos.** `countByNumberAndTypeNative` conta registros excluídos e
  de todos os escritórios. Duas consequências: (a) uma empresa excluída não pode ser recadastrada; (b) o 409 revela a
  outro escritório que aquele CNPJ já existe na plataforma, o que contradiz a regra do "404 em vez de 403". É o comportamento desejado?
- **P-06 Login único e exclusão lógica.** A validação ignora usuários excluídos, mas a constraint `uk_users_login` vale
  para todos. Recriar um login de usuário excluído deve dar erro 500 de constraint. Confirmar.
- **P-07 `POST /accounting-firms`.** Um ADMIN consegue criar um escritório "solto", sem usuário. E o modal "Dados do
  escritório" chama `create` quando não encontra o escritório (`accounting-firm-form.component.ts:53-55`). Esse
  endpoint deveria existir? E `DELETE /accounting-firms/{id}` permite ao ADMIN marcar o próprio escritório como
  excluído. É intencional?
- **P-08 Teste-guarda vs. `FirmCertificateResource#status`.** Pela lógica de `requirementFor`, o `GET` desse recurso
  fica sem regra, e o nome não está em `OPEN_TO_LOGGED_IN_USERS`. Então `noEndpointIsLeftWithoutAnAccessRule` deveria
  falhar. Ele está passando? Se estiver, por quê?
- **P-09 Contexto anônimo tratado como sistema.** `CurrentUserService` considera "sem usuário" tanto o job quanto a
  chamada anônima (o `anonymousUser` não existe no banco), e `TenantAccessService` não restringe nada nesse caso. Hoje
  só `POST /users` e `POST /auth` são anônimos. Isso fica documentado como premissa: nenhum endpoint novo pode ser
  `permitAll`?
- **P-10 Senha com `:`.** `AuthService` faz `split(":")` e usa só a segunda parte. Uma senha com `:` é truncada no
  login e nunca autentica, embora o cadastro aceite. Corrigir ou proibir `:` na senha?
- **P-11 Modal do escritório para não-admin.** O avatar abre "Dados do escritório" para qualquer usuário, inclusive a
  seção de integrações e cobrança. Para quem não é ADMIN, as gravações voltam 403, e `GET /billing/settings` exige
  `BILLING_SYNC`. Esconder o modal para não-admin?
- **P-12 Upload de A1 na tela da empresa.** `company-certificate.component.ts` não verifica se o usuário é ADMIN, mas o
  backend exige. Um FISCAL deveria poder enviar ou trocar o A1, ou só o ADMIN?
- **P-13 `AccessProfileMigration` roda enquanto a tabela estiver vazia.** Se nenhum usuário da plataforma tiver perfil
  (por exemplo, todos com perfis removidos de propósito), cada restart dá FISCAL a todos os não-admins. Restringir a
  uma única execução?
- **P-14 Permissões dos módulos novos.** Fronteira, retenções, pendências, auditoria e manifestação usam
  `INVOICES_VIEW` no front (`app.routes.ts`). Não há permissão própria para eles no enum `Permission`. Isso é
  definitivo ou haverá permissões por módulo?
