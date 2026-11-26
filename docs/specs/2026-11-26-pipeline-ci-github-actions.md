# Pipeline de CI no GitHub Actions (taxify-service e taxify-ui)

Data: 2026-11-26 · Tipo: infraestrutura (sem regra fiscal, sem mudança de código da aplicação)

## 1. Situação atual (Jenkins)

| Repositório | O que o `Jenkinsfile` faz | O que falta |
|---|---|---|
| taxify-service | `./mvnw test` num container `eclipse-temurin:25-jdk` → `docker build` → push `thiagoocms/taxify-service:<yyyy.MM.dd>.<build>` e `:latest` na master (credencial `dockerhub`). Parâmetro `SKIP_TESTS`. | Os testes rodam **sem banco próprio**: sem `.env`/variáveis, o Spring carrega o `application-default.yaml`, que aponta para o MySQL da EC2 (`taxify_dev`). Não roda em PR. Não faz deploy. |
| taxify-ui | `npm ci --legacy-peer-deps && npm run build -- --configuration production` (node:20) → `docker build` → push `thiagoocms/taxify-ui` na master. | Não roda testes nem lint. Não roda em PR. Não faz deploy. |

Nenhum dos dois faz deploy na EC2: o deploy continua manual e só com aprovação do Tony.

## 2. Decisão: Jenkins × GitHub Actions

**Recomendação: o Actions assume a verificação (PR e master) já agora; o Jenkins continua só publicando a imagem até o push pelo Actions ser ligado e validado. Depois disso, o Jenkins é desligado.**

- O Actions roda em todo PR, mostra o resultado no próprio PR e permite exigir o check antes do merge. O Jenkins só roda depois do merge.
- O banco de teste do Actions é um container descartável por execução. No Jenkins, isolar o banco exigiria Docker-in-Docker ou um MySQL fixo no agente.
- Não há servidor do Jenkins para manter (plugins, agente com Docker, credenciais).
- Para não ter dois publicadores da mesma tag `latest`, o push pelo Actions vem **desligado** (variável `DOCKERHUB_PUSH_ENABLED`). Quando for ligado, desative o stage de push do Jenkins (ou o job inteiro) no mesmo dia.
- Enquanto o Jenkins existir: **não rode o job do backend sem `.env` apontando para um banco local** (ou use `SKIP_TESTS`), porque hoje ele roda migrations e backfills no banco da EC2.

## 3. Pipeline do backend: `taxify/.github/workflows/ci.yml`

Gatilhos: `pull_request` para a master, `push` na master e `workflow_dispatch`. `concurrency` por workflow + ref com `cancel-in-progress`. `permissions: contents: read`.

1. **test** (ubuntu-24.04, 30 min)
   - Service `mysql:8.4` (a mesma imagem do `docker-compose.yml`), banco `taxify_ci`, usuário e senha fictícios, healthcheck `mysqladmin ping`.
   - `actions/setup-java@v4`: Temurin 25 com `cache: maven`.
   - Passo de proteção: apaga do checkout o `application-default.yaml` e o `.env` (como já faz o Dockerfile) e aborta se `DB_HOST` não for `127.0.0.1` ou o perfil não for `ci`.
   - `./mvnw -B -ntp verify`.
   - Se falhar: upload de `target/surefire-reports/` (e de `failsafe-reports`, se existir) como artefato, guardado por 14 dias.
2. **docker-build**: `docker/build-push-action@v6` com `push: false` e cache do GHA. Roda em PR e na master.
3. **docker-push** (desligado): só com `push` na master **e** `vars.DOCKERHUB_PUSH_ENABLED == 'true'`. Faz login com `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` e publica `<yyyy.MM.dd>.<run_number>` e `latest`. Usa o environment `dockerhub`, onde dá para pôr os secrets e, se quiser, exigir aprovação.

### Como o banco dos testes ficou isolado
- `SPRING_PROFILES_ACTIVE=ci`: com um perfil ativo, o Spring não carrega o `application-default.yaml`, que só vale para o perfil "default". Além disso, o arquivo é apagado do checkout.
- `DB_HOST=127.0.0.1`, `DB_PORT=3306`, `DB_NAME/USER/PASSWORD=taxify_ci` vêm do env do job. O `application.yaml` só lê `${DB_*}`, sem valor padrão.
- Se faltar alguma variável obrigatória, o contexto falha ao subir. Ele não cai em outro banco.

### Testes e serviços externos
- Os testes que sobem o contexto inteiro são só `TaxifyApplicationTests` e `MethodSecurityWiringTest` (`@SpringBootTest`). São eles que aplicam Flyway e os backfills (`AccessProfileMigration`, `CompanyTaxRegimeBackfill`, `FirmIntegrationMigration`, `FiscalDirectionBackfill`, `FiscalXmlTaxDetailBackfill`), agora no banco vazio do CI.
- As outras 116 classes de teste, incluindo as de integração (SIEG, SEFAZ, ADN, e-Fisco, Acessórias, Conta Azul, S3), usam mocks do Mockito ou XML estático. Nenhum abre conexão de rede.
- Com o contexto inteiro, `@EnableScheduling` está ligado. Por isso o CI desliga todos os agendadores de duas formas: `*_ENABLED=false` e cron `"-"`, que é o valor do Spring que desativa o `@Scheduled`. Isso vale para a sincronização, a retificação e o expurgo de XML, a distribuição SEFAZ, a NFS-e ADN, a auditoria, as pendências, o billing, a Acessórias e as rotinas de certidão e de sync do e-Fisco (`EFISCO_*`, que o Spring lê pelo nome relaxado). As credenciais da SIEG são fictícias, `FISCAL_XML_STORAGE_TYPE=local` (sem S3) e o runner não tem credencial AWS.

## 4. Pipeline do frontend: `taxify-front/.github/workflows/ci.yml`

Mesmos gatilhos, `concurrency` e permissões.

1. **build**: `actions/setup-node@v4` com Node 20 (igual ao Dockerfile) e `cache: npm` → `npm ci --legacy-peer-deps` → lint, só se existir o script `lint` (hoje não existe, então o passo é pulado) → libera o sandbox do Chrome no Ubuntu 24.04 (`sysctl` do AppArmor) → `npx ng test --watch=false --browsers=ChromeHeadless --no-progress` → `npm run build -- --configuration production`.
2. **docker-build**: imagem sem push.
3. **docker-push**: desligado, com as mesmas condições do backend.

Validação local (2026-11-26): `ng test` em ChromeHeadless passou com 6 de 6 specs, sem `karma.conf` (usa o padrão do builder); o build de produção passou só com avisos de CSS do Bootstrap.

## 5. Secrets e variáveis

| Nome | Tipo | Onde | Quando |
|---|---|---|---|
| `DOCKERHUB_USERNAME` | secret | nos 2 repositórios (ou no environment `dockerhub`) | só quando o push for ligado |
| `DOCKERHUB_TOKEN` | secret | idem; access token do Docker Hub com escopo Read & Write, não a senha | idem |
| `DOCKERHUB_PUSH_ENABLED` | variável (`vars`) | nos 2 repositórios; `true` liga o push | depois de desligar o push do Jenkins |

Os testes não precisam de nenhum secret: banco e integrações usam valores fictícios.

## 6. Validação feita
- Os dois YAML passam no parse do PyYAML e no `actionlint` 1.7.7, sem apontamentos (o shellcheck não estava instalado, então os scripts `run:` não foram analisados).
- O backend **não** foi rodado localmente: o Docker Desktop estava parado e o `mvn test` local usaria o banco do `application-default.yaml`. A primeira execução real será no primeiro PR.

## 7. Riscos
- **Credenciais versionadas no git.** O `src/main/resources/application-default.yaml` é rastreado e contém host, usuário e senha do MySQL da EC2, o secret da SIEG e o secret da Conta Azul. Quem lê o repositório (e qualquer fork ou log) tem acesso a eles. Recomendação: rotacionar essas credenciais, tirar o arquivo do git (trocar por um `application-default.example.yaml`) e limpar o histórico. Isto não foi feito aqui, porque a tarefa era não mexer em segredos.
- Jenkins: o risco da seção 2 continua enquanto o Jenkins rodar testes sem banco isolado.
- O Node 20 está fora de suporte desde abril de 2026. Subir para 22 no Dockerfile e no workflow juntos.
- `mysql:8.4` no CI versus a versão do MySQL da EC2: confirmar que são iguais, para não aparecer diferença de SQL ou de autenticação.
- Os novos `@SpringBootTest` precisam continuar sem depender de rede. Um agendador novo precisa ter `enabled`/cron por variável e entrar no env do CI.
- `cancel-in-progress` na master pode cancelar um push de imagem no meio quando há dois merges seguidos. A execução mais nova publica de novo, então não há perda.

## 8. Fora do escopo
- Deploy na EC2: continua manual e só com aprovação do Tony.
- Análise estática (SpotBugs/Checkstyle/Sonar, CodeQL), cobertura (JaCoCo, karma-coverage) e scan de dependências e de imagem (Dependabot, Trivy).
- Lint do front: falta instalar o `angular-eslint` (`ng add @angular-eslint/schematics`).
- Proteção da branch master (check obrigatório, PR obrigatório).

## 9. Próximos passos recomendados
1. Commitar os workflows nos dois repositórios (via PR) e conferir a primeira execução.
2. **Prioridade:** rotacionar as credenciais do `application-default.yaml` e tirar o arquivo do git.
3. Ativar a proteção da master com os checks `Build e testes (mvn verify)` e `Testes e build de produção` como obrigatórios.
4. Cadastrar `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`, desligar o push do Jenkins, definir `DOCKERHUB_PUSH_ENABLED=true` e desativar o Jenkins.
5. Adicionar o `angular-eslint`, o JaCoCo e o Dependabot. Avaliar CodeQL.
6. Subir para Node 22 (Dockerfile + workflow).
