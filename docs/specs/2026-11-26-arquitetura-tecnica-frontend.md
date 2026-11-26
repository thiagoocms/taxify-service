# Arquitetura técnica do frontend (taxify-ui), como está hoje

- **Data:** 2026-11-26
- **Tipo:** documentação do que existe (as-built). Nenhum código foi alterado.
- **Repositório:** `C:\workspace\taxify-front` (Angular). O histórico tem 82 commits, de 2026-09-11 a 2026-11-24 (último: `a613d99`, certidão SEFAZ-PE e pedido de revisão da Fronteira).
- **Referências:** os caminhos abaixo são relativos à raiz do `taxify-front`, no formato `arquivo:linha`.

---

## 1. Stack e versões

| Item | Versão / escolha | Onde |
|---|---|---|
| Angular (core, router, forms, animations) | `^19.2.x` | `package.json:13-20` |
| Angular CLI / build | `@angular-devkit/build-angular ^19.2.27`, builder `application` (esbuild) | `package.json:31-32`, `angular.json:20` |
| TypeScript | `~5.7.2`, `strict` ligado e `strictTemplates` | `package.json:41`, `tsconfig.json:7,25` |
| RxJS / zone.js | `~7.8.0` / `~0.15.0` (a app usa zone, não é zoneless) | `package.json:26,28`, `app.config.ts:42` |
| Biblioteca de UI | **Bootstrap 5.3.8**, só o CSS (sem ng-bootstrap). Por cima dele há um design system próprio em `shared/components` | `package.json:24`, `angular.json:35` |
| Ícones | Font Awesome 6 (`@fortawesome/angular-fontawesome 0.15`), com os ícones registrados no `FaIconLibrary` | `package.json:21-23`, `app.component.ts:91-98` |
| Notificações | `ngx-toastr ^19.1.0` (5s, canto superior direito, sem duplicar) | `package.json:27`, `app.config.ts:46-51` |
| JWT | `jwt-decode ^4.0.0`, só para ler os claims no cliente | `package.json:25`, `core/services/auth.service.ts:3` |
| Gráficos | **Nenhuma biblioteca de gráficos.** Relatórios e curva ABC são tabelas e cartões. | `package.json` |
| Fonte | Poppins pelo Google Fonts | `src/index.html:12` |
| Testes | Karma 6.4 + Jasmine 5.6 (configuração padrão do CLI, sem `karma.conf.js`) | `package.json:34-40`, `angular.json:109-130` |

## 2. Estrutura de pastas e convenções

```
src/app/
├── app.component.*      véu de carregando global e ConfirmDialog
├── app.config.ts        providers: locale pt-BR, router, http + interceptors, toastr
├── app.routes.ts        rotas raiz (lazy)
├── core/
│   ├── guards/          authGuard, permissionGuard(perm), adminGuard
│   ├── interceptors/    ngrok, auth (token e erros), action-loading (véu)
│   ├── models/          26 arquivos *.model.ts que espelham os DTOs da API
│   └── services/        25 serviços HTTP + utilitários (polling de job, pop-up da Conta Azul, empresa atual)
├── features/            uma pasta por domínio (fronteira, invoices, audit, pendencies, companies...)
└── shared/
    ├── components/      design system: button, input, table, modal, confirm-dialog, loader, page, pagination, toggle, step-indicator, month-nav, auth-side-panel
    ├── help/            conteúdo estático da Central de Ajuda (help-content.ts, 833 linhas)
    ├── layout/          shell, header, sidebar
    ├── styles/          parciais SCSS de padrões de lista e página
    └── utils/           máscara de documento, download de blob, formatação, conversão para número
```

Convenções observadas:

- **Tudo é standalone.** Não existe nenhum `@NgModule` nem `standalone: false`. São 55 componentes, todos carregados com `loadComponent`/`loadChildren` (`app.routes.ts:11,33,40...`).
- **Signals para o estado local e RxJS para o HTTP.** 42 arquivos usam `signal()`/`computed()`; os serviços HTTP devolvem `Observable` e os componentes fazem `.subscribe()` (159 ocorrências) e gravam o resultado em signals. Não há `toSignal`, `resource` nem `httpResource`.
- **Mistura de APIs antigas e novas.** Há `@Input()` decorado em 26 arquivos contra `input()`/`output()` em apenas 2. O controle de fluxo novo (`@if`) aparece em 48 templates, mas `*ngIf`/`*ngFor` continuam em `shared/components/input/input.html` e `shared/layout/sidebar/sidebar.component.html:37`. Só 1 componente usa `OnPush`.
- **Assinaturas.** A limpeza é feita com `takeUntilDestroyed` (8 usos) ou com o padrão `destroyed$`/`takeUntil` (`features/invoices/invoice-detail/invoice-detail.component.ts:134`, `invoice-xml-list.component.ts:114`). Muitos `.subscribe()` de chamada única ficam sem nenhuma das duas formas.
- **Formulários reativos** (`FormBuilder`, `nonNullable`) na maioria das telas (25 arquivos). `FormsModule`/`ngModel` também aparece (39 arquivos), sobretudo nos filtros.
- **Serviços HTTP.** Há um serviço por recurso, `@Injectable({providedIn:'root'})`, com `baseUrl = \`${environment.apiUrl}/<recurso>\`` (ex.: `core/services/fronteira.service.ts:9`). A URL base vem de `environment.apiUrl` (`src/environments/environment.prod.ts:3` = `/taxify-service/api/v1`, relativa). A paginação usa `buildParams(pageable, filter)` (`core/services/user.service.ts`, reaproveitada em `accounting-firm.service.ts:8`). Downloads usam `responseType: 'blob'` + `shared/utils/download-blob.ts`. Jobs longos (Acessórias, Conta Azul/cobrança) fazem polling a cada 3s com `pollUntilDone` (`core/services/sync-job-polling.ts:4-18`).
- **Estado compartilhado em serviços root com signals:** `AuthService` (token e claims), `CurrentCompanyService` (última empresa escolhida), `AccountingFirmService.currentFirm`, `IntegrationService.integrations`, `InvoiceStateService.search` (preserva a busca de Notas ao voltar do detalhe, `features/invoices/invoice-state.service.ts:27-30`) e `LoadingOverlayService`.
- **Idioma do código:** os comentários estão em português, com alguns trechos antigos em inglês (`auth.interceptor.ts:13,70`). As rotas usam nomes em português.

## 3. Mapa de rotas

Todas as rotas da área autenticada ficam dentro do `ShellComponent` (header + sidebar), com `authGuard` (`app.routes.ts:31-36`). A coluna "Permissão" mostra o que o guard da rota exige. ADMIN passa em qualquer `permissionGuard` (`auth.service.ts:84-86`). Na coluna de endpoints, os caminhos são relativos a `/taxify-service/api/v1`.

| Rota | Componente | Guard / permissão | Endpoints consumidos (via serviço) |
|---|---|---|---|
| `/auth/login`, `/auth/register` | `features/auth/auth.component` (mesmo componente, abas diferentes) | nenhum (`app.routes.ts:9-12`, `auth.routes.ts:8-18`) | `POST /auth` (login); `POST /users` (cadastro público com `profile: 'ADMIN'`, `auth.component.ts:286`) |
| `/login`, `/register` | redirecionam para `/auth/*` | nenhum | — |
| `/integracoes/conta-azul/callback` | `features/integrations/conta-azul-callback` (fora do shell, abre no pop-up) | `authGuard` (`app.routes.ts:19-26`) | `POST /integrations/conta-azul/callback` |
| `/home` | `features/home` (atalhos filtrados por `can()`/`isAdmin`, `home.component.ts:107`) | `authGuard` | nenhum |
| `/cadastro/empresas` | `features/companies/company-list` (+ `company-form` e as abas regimes, certificado, certidão, assinaturas, cobrança, regras fiscais e conferência fiscal) | `COMPANIES_VIEW` (`cadastro.routes.ts:12-19`) | `/companies` (CRUD, `/{id}/regimes`, `/{id}/certificado`, `/{id}/sefaz/capturar`, `/{id}/sefaz/resumos`, `/{id}/nfse-nacional`, `/{id}/preencher-pelas-notas`), `/certidoes/carteira`, `/certidoes/empresa/{id}` (+ `emitir`, `pdf`), `/integrations/acessorias/import`, `/subscriptions`, `/plans`, `/billing/companies/{id}`, `/billing/charges`, `/product-fiscal-rules` (+ `/conferencia`, `/sugestoes`, `/produtos`, `/divergencias`) |
| `/cadastro/planos` | `features/plans/plan-list` + `plan-form` | `PLANS_VIEW` (`cadastro.routes.ts:20-25`) | `/plans` (CRUD) |
| `/cadastro/usuarios` | `features/users/user-list` + `user-form` + `user-links` | `adminGuard` (perfil ADMIN, `cadastro.routes.ts:26-31`) | `/users` (CRUD), `/users/{id}/companies` (vínculos), `/integrations/acessorias/users` |
| `/notas-fiscais` | `features/invoices/invoice-xml-list` | `INVOICES_VIEW` (`app.routes.ts:47-54`) | `POST /integrations/xmls` (busca), `POST /integrations/xmls/totais`, `POST /integrations/xmls/conferencia`, `POST /integrations/xmls/exportar/zip`, `/companies` |
| `/notas-fiscais/:id` | `features/invoices/invoice-detail` | `INVOICES_VIEW` (`app.routes.ts:95-102`) | `/integrations/xmls/{id}` (+ `/eventos`, `/produtos`, `/xml`, `/pdf`, `/conferencia`, `/retificar`), `/product-fiscal-rules`, `GET/POST /invoice-audit-log` |
| `/notas-servico` | `features/service-invoices` | `INVOICES_VIEW` (`app.routes.ts:55-60`) | `POST /notas-servico/buscar`, `/totais`, `GET /notas-servico/{id}` (+ `/xml`), `POST /notas-servico/sincronizar` |
| `/manifestacao` | `features/invoices/manifestation` | `INVOICES_VIEW` (`app.routes.ts:89-94`) | `GET/POST /integrations/xmls/manifestacao`, `/companies/{id}/sefaz/resumos` |
| `/auditoria` | `features/audit` | `INVOICES_VIEW` (`app.routes.ts:69-73`) | `/auditoria` (+ `/executar`, `/laudo.pdf`, `/laudo.csv`, `/regras`, `/regras/{code}/conferencia`, `/regras/{code}/ativa`), `/pendencias/{id}/resolver|desconsiderar|reabrir` |
| `/fronteira` | `features/fronteira` (+ `formula-editor`) | `INVOICES_VIEW` (`app.routes.ts:74-78`) | `/fronteira` (+ `/memoria`, `/memoria/buscar`, `/relatorio.{pdf,csv}`, `/pedido-revisao.{pdf,txt}`, `/extrato/pdf`, `/sincronizar`, `/importar`, `/regras`, `/regras/testar`, `/regras/{id}/conferencia`, `/regras/{id}/ativa`), `/integrations/efisco/certificado`, `/companies` |
| `/retencoes` | `features/retentions` | `INVOICES_VIEW` (`app.routes.ts:79-83`) | `GET /retencoes`, `/retencoes/csv`, `POST /retencoes/conferencia` |
| `/pendencias` | `features/pendencies` | `INVOICES_VIEW` (`app.routes.ts:84-88`) | `/pendencias` (+ `/resumo`, `/integracoes`, `/atualizar`, `/{id}/resolver|desconsiderar|reabrir`) |
| `/apuracao` | `features/assessment/tax-assessment` | `TAX_ASSESSMENT_VIEW` (`app.routes.ts:103-108`) | `GET /fiscal/apuracao?month=` |
| `/apuracao/:companyId` | `features/assessment/tax-assessment-detail` | `TAX_ASSESSMENT_VIEW` (`app.routes.ts:114-121`) | `GET /fiscal/apuracao/{id}`, `POST/DELETE /fiscal/apuracao/{id}/fechamento`, `PUT /fiscal/apuracao/{id}/guia` |
| `/relatorios` | `features/reports` | `TAX_ASSESSMENT_VIEW` (`app.routes.ts:109-113`) | `/relatorios/mensal`, `/mensal.pdf`, `/curva-abc`, `/simulacao` |
| `/ajuda`, `/ajuda/:id` | `features/help/help-center` (conteúdo estático) | só `authGuard` (`app.routes.ts:61-68`) | nenhum |
| `/companies`, `/planos`, `/usuarios` | redirecionam para `cadastro/*` (`app.routes.ts:123-125`) | — | — |
| `**` | redireciona para `''` → `/home` (ou login) (`app.routes.ts:128`) | — | — |

Telas fora do mapa de rotas:

- **Dados do escritório e integrações:** é um modal aberto pelo header (`shared/layout/header/header.component.ts:47-60`), sem guard e sem checagem de ADMIN no front. Usa `/accounting-firms`, `/integrations` (`PUT /integrations/{type}/api-key`), `/integrations/conta-azul/*`, `/billing/settings|categories|sync` e `/integrations/efisco/certificado`.
- **Controle por botão dentro das telas** (além do guard da rota):
  - `INVOICES_AUDIT`: auditar, conferir e importar (`audit.component.ts:58-59`, `fronteira.component.ts:118-119`, `invoice-xml-list.component.ts:150`, `pendencies.component.ts:78`, `retentions.component.ts:62`, `manifestation.component.ts:84`).
  - `INVOICES_SYNC`: sincronizar e emitir certidão (`invoice-xml-list.component.ts:215`, `service-invoices.component.ts:62`, `company-certidao.component.ts:115`).
  - `INVOICES_RECTIFY`: retificar (`invoice-detail.component.html:352,408`).
  - `TAX_ASSESSMENT_CLOSE`: fechar o mês (`tax-assessment-detail.component.ts:54`).
  - `FISCAL_RULES_MANAGE`, `SUBSCRIPTIONS_*`, `BILLING_*`: nas abas da empresa (`company-form.component.html:135-143`).
  - `isAdmin`: criar e editar empresa e plano (`company-list.component.ts:61,92,116`, `plan-list.component.ts:46,79`).

## 4. Autenticação e autorização no front

- **Login:** o front envia `POST /auth` com `{ auth: base64("login:senha") }` (`core/services/auth.service.ts:37-43`) e recebe `{ token, userId, userName }` (`core/models/auth.model.ts:5-9`).
- **Armazenamento:** o token, o userId e o userName ficam no **`localStorage`** (`taxify_token`, `taxify_user_id`, `taxify_user_name`, `auth.service.ts:9-11,100-107`) e são espelhados em signals. O logout apaga as três chaves (`auth.service.ts:45-52`). A última empresa escolhida fica no `sessionStorage` e é limpa quando o usuário sai (`core/services/current-company.service.ts:7,22-28`).
- **Claims lidos do JWT no cliente**, sem verificar a assinatura:
  - `profile`: ADMIN ou não, `auth.service.ts:54-69`.
  - `permissions`: `Set<Permission>`, `auth.service.ts:72`, com o tipo em `core/models/access.model.ts:2-17`.
  - `accessProfiles`: `auth.service.ts:75`.
  - `platformAdmin`: `auth.service.ts:81`.

  Regra central: `can(p) = isAdmin || permissions.has(p)` (`auth.service.ts:84-86`). `platformAdmin` só libera os botões de regras gerais: tabela de ICMS da Fronteira (`fronteira.component.ts:122`) e regras da auditoria (`audit.component.ts:61`).
- **Interceptors**, na ordem em que estão registrados (`app.config.ts:44`):
  1. `ngrokInterceptor`: põe `ngrok-skip-browser-warning: true` em **toda** requisição (`core/interceptors/ngrok.interceptor.ts:8-10`).
  2. `authInterceptor`: põe `Authorization: Bearer <token>` quando há token (`auth.interceptor.ts:40-43`) e trata os erros.
  3. `actionLoadingInterceptor`: liga o véu de carregando em POST/PUT/DELETE/PATCH que passam de 400ms, com uma lista de exceções (`action-loading.interceptor.ts:10-50`).
- **Guards:**
  - `authGuard` só verifica se **existe** token. Não olha o `exp` e manda para `/auth/login` quando não há (`core/guards/auth.guard.ts:5-15`).
  - `permissionGuard(p)` devolve `UrlTree('/home')` quando falta a permissão (`permission.guard.ts:10-17`).
  - `adminGuard` exige o perfil ADMIN (`permission.guard.ts:20-21`).
  - A sidebar esconde os links pelas mesmas regras (`shared/layout/sidebar/sidebar.component.ts:53-59`).
- **Respostas HTTP de erro** (`auth.interceptor.ts:46-81`):
  - **401 no login:** toast "Login ou senha inválidos" (`:49-52`), e a tela mostra a mensagem perto do formulário (`auth.component.ts:182-186`).
  - **401 em qualquer outra chamada:** toast "Sessão expirada", `logout()` e navegação para `/auth/login` (`:54-59`). Não há refresh token.
  - **0 (sem conexão):** toast de erro de conexão (`:61-64`).
  - **403, 404, 409, 422 e 429:** toast de *warning* com a `message` da API ou um texto padrão (`:14-33,66-76`). **No 403 o front não redireciona nem desloga.** **No 404 não existe página "não encontrado"**: só o toast, e a tela fica vazia. Rota desconhecida cai no `**` e vai para `/home`.
  - **5xx:** toast de erro (`:77`).
  - Em todos os casos o erro é repassado (`throwError`) para a tela tratar também.
- **Cadastro público:** `POST /users` com `profile: 'ADMIN'` montado no cliente (`auth.component.ts:283-286`). Cabe ao backend não confiar nesse campo.

## 5. Estado, erros, i18n, acessibilidade e responsividade

- **Estado:** não há store global (NgRx ou similar). O estado vive em signals de componente e em serviços root (ver seção 2). A busca de Notas sobrevive à navegação pelo `InvoiceStateService`. A empresa atual pré-preenche os filtros pelo `CurrentCompanyService.bind()` (`current-company.service.ts:53-74`).
- **Carregando:**
  - Um véu único (`LoadingOverlayService`, `shared/components/loader/loading-overlay.service.ts:7-29`) é acionado na troca de rota (`app.component.ts:100-111`) e nas ações demoradas (o interceptor acima).
  - O `<app-loader>` cobre a tela, uma seção ou uma linha. A abertura do app mostra a logo animada direto no `index.html` (`src/index.html:16-42`).
- **Erros:**
  - O tratamento é centralizado em toast pelo interceptor. Várias telas também tratam no `error:` do subscribe (95 ocorrências de `error:`/`catchError` em `features`), e isso pode gerar **mensagem dupla** (toast + mensagem inline).
  - Chunk velho depois de um deploy: recarrega a página uma vez (`app.config.ts:17-36`).
  - Não há `ErrorHandler` global nem envio de erros para monitoramento.
- **i18n:** não existe. Os textos em pt-BR estão fixos nos templates. Locale `pt-BR` e moeda `BRL` vêm dos providers (`app.config.ts:15,40-41`), com `<html lang="pt-BR">` (`src/index.html:2`). O alvo `extract-i18n` existe no `angular.json:106-108`, mas não é usado.
- **Acessibilidade:**
  - **Parcial.** Há uso pontual de `aria-label` (38), `role="group"`/`tab`/`tablist`, `aria-live` (3), `aria-current`, `aria-busy` e `prefers-reduced-motion` (4 media queries, inclusive no `index.html:31`). O commit `de246ba` trouxe autocomplete e acessibilidade ao login e ao cadastro, com foco automático no primeiro campo (`auth.component.ts:148-150,170-178`).
  - Lacunas: o `Modal` não tem `role="dialog"`/`aria-modal`, não fecha no Escape e não prende o foco (`shared/components/modal/modal.html:1-20`). O `ConfirmDialog` tem Escape (`confirm-dialog.ts:34-35`).
- **Responsividade:**
  - Bootstrap e algumas media queries próprias (breakpoints de 575, 768, 900, 992 e 1000px, em 13 arquivos).
  - No celular, a sidebar vira gaveta com backdrop (`shared/layout/shell.component.html`, `shell.component.ts:8-18`). Login e cadastro têm versão de celular (`de246ba`).
  - As telas densas (Fronteira, detalhe da nota) foram pensadas para desktop.

## 6. Build, ambientes, proxy, Nginx e deploy

- **Ambientes:**
  - `environment.ts` (dev): `apiUrl` **absoluta** `http://localhost:5000/taxify-service/api/v1` (`src/environments/environment.ts:3`).
  - `environment.prod.ts`: relativa `/taxify-service/api/v1` (`environment.prod.ts:3`), trocada pelo `fileReplacements` do build de produção (`angular.json:44-49`).
- **Dev server:** o `ng serve` usa a configuração `development` por padrão (`angular.json:96`). Aceita hosts do ngrok (`angular.json:83-87`) e configura o `proxy.conf.json` (`/taxify-service/**` → `localhost:5000`, `proxy.conf.json:2-6`). Como o dev usa URL absoluta, **o proxy não é usado na prática** e as chamadas vão direto para a porta 5000 (depende do CORS do backend).
- **Build de produção:** budgets de 750 kB (aviso) e 1,5 MB (erro) para o bundle inicial e de 8/16 kB por estilo de componente; `outputHashing: all` (`angular.json:50-62`). Saída em `dist/taxify-front/browser`.
- **Dockerfile** (multi-stage): `node:20-alpine` com `npm ci --legacy-peer-deps` e `npm run build -- --configuration production`, depois `nginx:1.27-alpine` com o `nginx.conf` e o `browser/` (`Dockerfile:1-14`).
- **Nginx** (`nginx.conf`):
  - `location ^~ /taxify-service/` faz proxy para `http://backend:5000/taxify-service/` (o nome do serviço no Compose), com timeouts de leitura e envio de 300s e `client_max_body_size 50m` (`:13-20`, commit `1d13675`).
  - Repassa `X-Forwarded-Proto` vindo do Caddy, que termina o HTTPS na frente, com fallback para `$scheme` (`:1-4,24-26`).
  - Arquivos com hash ficam em cache `immutable` por 1 ano e devolvem 404 quando não existem (`:32-35`).
  - `index.html` vai com `no-cache` e serve de fallback SPA (`:38-41`).
- **Jenkins** (`Jenkinsfile`): checkout, build Angular dentro de `node:20-alpine` com cache npm em volume (`:29-38`), `docker build` com as tags `yyyy.MM.dd.BUILD` e `latest` (`:19,40-44`) e push no Docker Hub só na `master` (ou sem branch) e com `PUSH_IMAGE` (`:46-63`). A credencial é `dockerhub` via Credentials Binding (`:54-55`).
  - O pipeline **não roda `ng test` nem lint**.
  - O deploy na EC2 não está no pipeline: a imagem é consumida pelo `docker-compose` do backend (`README.md:82`).
- `.dockerignore` exclui `node_modules`, `dist`, `.git`, `.claude` e `.agents` (`.dockerignore:1-8`).

## 7. Testes que existem e lacunas

- **Existem 5 specs, 114 linhas no total**, todas do tipo "should create":
  - `app.component.spec.ts`: cria o app e confere `title === 'taxify-front'` (`:17-21`), que bate com `app.component.ts:61`.
  - `shared/components/button/button.spec.ts`, `input/input.spec.ts`, `table/table.spec.ts` e `toggle/toggle.spec.ts`.
- **Lacunas:**
  - Nenhum teste de serviço HTTP (`HttpTestingController`), de guard, de interceptor ou de feature.
  - Nenhum teste de `AuthService.can()`/claims, nem de `CurrentCompanyService`.
  - Nenhum teste da Fronteira (editor de fórmula, memória de cálculo) nem da apuração.
  - Nenhum e2e (Cypress ou Playwright) nem lint configurado (não há `eslint` no `package.json`).
  - O CI não executa os testes. A verificação visual tem sido feita à mão (Chrome headless, segundo a memória do projeto).

## 8. Riscos e dívidas técnicas

1. **Token JWT no `localStorage`** (`auth.service.ts:18,101`): fica exposto a qualquer XSS. O código não usa `innerHTML`; o único `bypassSecurityTrustResourceUrl` serve para exibir o PDF de um blob local (`invoice-detail.component.ts:526`). Ainda assim, uma dependência comprometida leria o token.
2. **`authGuard` não confere a expiração** (`auth.guard.ts:9`). Com o token vencido, o usuário entra no shell e só cai para o login no primeiro 401, depois de ver um toast. Também não há refresh token.
3. **A autorização do front é cosmética.** Os claims são lidos sem validar a assinatura, então a segurança real depende 100% do backend. Dois pontos pedem confirmação lá:
   - o cadastro público envia `profile: 'ADMIN'` (`auth.component.ts:286`);
   - o modal "Dados do escritório" não tem gate no front (`header.component.ts:47`).
4. **Trilha de auditoria da nota gravada pelo cliente** (`POST /invoice-audit-log`, `invoice-detail.component.ts:754-760`): o conteúdo do registro vem do navegador.
5. **403 e 404 tratados só com toast**, sem página de "sem acesso" ou "não encontrado" (`auth.interceptor.ts:66-79`, `app.routes.ts:128`). Além disso, há o risco de mensagem duplicada (toast do interceptor + erro inline da tela).
6. **`ngrokInterceptor` ativo em produção** (`app.config.ts:44`): manda um header inútil em toda chamada. Os `allowedHosts` do ngrok continuam no `angular.json:83-87`.
7. **O dev ignora o `proxy.conf.json`**, porque `environment.ts` tem URL absoluta. O dev se comporta diferente da produção (CORS e mesma origem).
8. **Cobertura de testes praticamente nula, e o CI não testa.** Num módulo fiscal, regressões em cálculo exibido, permissões e filtros passam despercebidas.
9. **Componentes muito grandes:**
   - `fronteira.component.ts`: 929 linhas, mais 638 de HTML e 552 de SCSS;
   - `invoice-detail.component.ts`: 779 linhas, mais 831 de SCSS;
   - `invoice-xml-list.component.ts`: 589 linhas.

   O budget de estilo por componente (8/16 kB, `angular.json:57-61`) pode estourar.
10. **Convenções misturadas**: `@Input` com `input()`, `*ngIf` com `@if`, `takeUntil` com `takeUntilDestroyed` e muitos `subscribe` sem cancelamento. Quase nada usa `OnPush`.
11. **Documentação desatualizada:**
    - `doc-api.json` (OpenAPI de 2026-09-12) só tem cerca de 26 caminhos. Faltam Fronteira, auditoria, pendências, retenções, apuração, relatórios, certidões e cobrança.
    - `README.md:37-47` lista só 8 rotas.
    - `docs/casos-de-uso.md` cobre só autenticação, empresas e vínculos.

    O contrato real só existe nos serviços e nos models.
12. **Os models são escritos à mão** (26 arquivos), sem geração a partir do OpenAPI. Mudanças no backend quebram em tempo de execução, não na compilação.
13. **`npm ci --legacy-peer-deps`** (`Dockerfile:5`, `Jenkinsfile:34`): esconde conflitos de peer dependencies. As versões usam `^`, mas o `package-lock.json` é o que vale.
14. **Valores monetários e `BigDecimal`:** o front recebe números JSON e formata com os pipes de pt-BR. Existe `shared/utils/to-number.ts` para converter. Se a API mandar `BigDecimal` como `number`, pode haver perda de precisão em valores grandes, e nenhum teste cobre isso.

## 9. Perguntas em aberto

1. O backend ignora ou valida o `profile` enviado no `POST /users` público? Quem pode chamar `POST /users` sem token?
2. A troca de dados do escritório e das chaves de integração (`PUT /accounting-firms/{id}`, `PUT /integrations/{type}/api-key`) é restrita a ADMIN no backend? O front não restringe.
3. Queremos manter o token no `localStorage` ou migrar para cookie `HttpOnly` + `SameSite`? Faz sentido checar o `exp` no `authGuard` e ter refresh?
4. O `POST /invoice-audit-log` deveria ser gravado pelo próprio backend quando a ação acontece, em vez de o cliente enviar o registro?
5. O `ngrokInterceptor` e os `allowedHosts` do ngrok ainda são usados? Podem sair do build de produção?
6. Os valores monetários chegam como `number` ou como string no JSON? O front deveria tratar como string/decimal para não perder precisão?
7. O `doc-api.json` deve ser regenerado a partir do backend atual (springdoc) e passar a gerar os models do front?
8. O Jenkins deve rodar `ng test` (com Chrome headless) e lint antes de publicar a imagem?
9. Faltam uma página de "sem permissão" (403) e uma de "não encontrado" (404) no lugar do redirecionamento silencioso para `/home`?
