# Reforma Tributária: alíquotas de referência de IBS/CBS por ano, com status (F1a-0)

> Feature nova, Onda 1 da [avaliação do RECUPERAC RT33](2026-11-27-avaliacao-recuperac-rt33.md) (F1a-0). Escrita pelo
> po-taxify em 2026-11-27, com a decisão P-05 do Tony do mesmo dia.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base
> `br.com.cshub.taxify` (abreviado `…`).
>
> Depende de:
> - [Motor de conferência fiscal](2026-11-26-motor-de-conferencia-fiscal.md): RN-01 (contexto carregado uma vez), RN-05
>   (comparação exata) e a checagem `IBSCBS_RATE`;
> - [Taxify SaaS, fase 1](2026-11-26-taxify-saas-assinatura-do-escritorio.md): console da plataforma e
>   `@PlatformAdminOnly` (F1-RN-04, F1-RN-09, F1-RN-15);
> - [Levantamento do crédito de estoque](2026-11-27-credito-estoque-cbs-levantamento.md): endpoint
>   `GET /api/v1/reforma/recursos` (CE-RN-15), criado lá.

## Status e decisões

**Pronta para implementação.** Precisa estar em produção **antes de 01/01/2027**: sem ela, a checagem `IBSCBS_RATE`
para de rodar em silêncio na virada do ano.

| Decisão | Quem e quando | O que diz |
|---|---|---|
| P-05 | Tony, 2026-11-27 | Cada alíquota tem status: `LEI`, `PENDENTE` ou `ESTIMATIVA`. **Só a que tem ato oficial (`LEI`) gera achado**; as outras aparecem como referência. O Fiscal cadastra 2027 quando sair o ato |
| P-03 | Recomendação aceita | Dado de referência global: não entra em serviço do plano |
| P-07 (por analogia) | Tony, 2026-11-27 | Dado que vale para todos os escritórios é mantido pelo admin da plataforma no console |

**Regra fiscal.** Esta spec **não cadastra nenhuma alíquota nova**. A única linha existente (2026: CBS 0,9%, IBS
estadual 0,1%, IBS municipal 0%, semeada pela `V24__IbsCbsRates` com a referência "LC 214/2025") recebe o status `LEI`.
O artigo exato que fixa as alíquotas de 2026 **não foi conferido** nesta spec: fica como P-AL-01, para o Fiscal
completar o fundamento no console. As alíquotas de 2027 (o material cita CBS de 11,3468% calculada pelo TCU, pendente de
resolução do Senado, e redução de 0,1 p.p. em 2027-2028) **continuam a validar**: só entram como `LEI` com o ato
oficial publicado.

## 1. Problema e objetivo

**Problema.** `IbsCbsItemCheck` só compara `pCBS`, `pIBSUF` e `pIBSMun` quando o ano de emissão tem linha em
`ibs_cbs_rates` (`service/fiscalcheck/IbsCbsItemCheck.java:47`). Só existe 2026 e não há tela para cadastrar. Em
01/01/2027 a checagem deixa de rodar sem ninguém saber. E se alguém cadastrasse por SQL uma alíquota estimada, a
conferência apontaria `ERROR` em notas corretas.

**Objetivo.**
1. Cadastrar e editar as alíquotas de cada ano no console da plataforma, com status, fundamento e link do ato.
2. Só alíquota `LEI` gerar achado; `PENDENTE` e `ESTIMATIVA` aparecem como referência, sem achado.
3. Avisar (console e conferência) quando o ano corrente ou o seguinte não tem alíquota `LEI`, para a falta de conferência
   nunca mais ser silenciosa.

| Como medir | Meta |
|---|---|
| Dias de 2027 em que a conferência fica sem `IBSCBS_RATE` depois de publicado o ato oficial | ≤ 2 dias úteis (tempo de o Fiscal cadastrar) |
| Achados `IBSCBS_RATE` gerados por alíquota não `LEI` | 0 |

## 2. O que já existe

| Item | Onde |
|---|---|
| Tabela `ibs_cbs_rates` (`ano` PK, `aliquota_cbs`, `aliquota_ibs_uf`, `aliquota_ibs_mun`, decimal(7,4) not null) | `db/migration/V24__IbsCbsRates.java`; `domain/reform/IbsCbsRate.java` |
| Carga uma vez por empresa (`findAll`) | `service/fiscalcheck/FiscalCheckEngine.contextFor` |
| Checagem `IBSCBS_RATE` (`ERROR`, comparação exata, só CST 000 e 200) | `IbsCbsItemCheck.checkRates` |
| Console da plataforma e guarda | `security/PlatformAdminOnly`, `common/PlatformAdminService`; front em `features/platform/` (`/plataforma/escritorios`, `/plataforma/captura`) |
| Agrupador IBS/CBS da conferência | `taxify-front/src/app/features/companies/company-fiscal-conference/finding-groups.ts` |

Não existe: status, fundamento, link do ato, histórico de alterações e tela.

## 3. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| Admin da plataforma (CS Hub) | Cadastra o ano novo quando o ato sai; corrige status e fundamento | Poucas vezes por ano; pelo menos uma em dez/2026–jan/2027 |
| Fiscal de qualquer escritório (ADMIN, FISCAL, demais com acesso à conferência) | Vê a referência de cada ano e o status; vê o aviso quando o ano não tem `LEI` | Ao conferir notas |
| Motor de conferência | Usa só as linhas `LEI` | Sob demanda e na auditoria |

**Teste grátis e escritório bloqueado.** Não se aplicam ao cadastro (é da plataforma; `/platform/**` fica liberado ao
admin da plataforma, F1-RN-09). A leitura (`GET /reforma/aliquotas`) funciona igual em teste, ativo e bloqueado.

**Serviço do plano.** Nenhum (P-03): é dado de referência global e alimenta a conferência básica.

**Custo no SIEG: zero.**

## 4. Histórias

- H-AL-1. Como admin da plataforma, quero cadastrar as alíquotas de 2027 com o status e o ato, para a conferência
  continuar valendo na virada do ano.
- H-AL-2. Como admin da plataforma, quero registrar uma alíquota ainda pendente ou estimada sem que ela gere achado,
  para deixar a referência visível sem acusar erro em nota certa.
- H-AL-3. Como Fiscal, quero ver de onde vem a alíquota de referência e se ela já é lei, para confiar no achado.
- H-AL-4. Como Fiscal, quero ser avisado quando o ano da nota não tem alíquota oficial cadastrada, para saber que aquela
  checagem não rodou.
- H-AL-5. Como CS Hub, quero o histórico de quem mudou cada alíquota e quando, para responder a uma contestação.

## 5. Regras de negócio

| RN | Regra |
|---|---|
| **AL-RN-01 Status** (P-05) | `status` ∈ {`LEI`, `PENDENTE`, `ESTIMATIVA`}. `LEI` = existe ato oficial publicado que fixa as alíquotas do ano. `PENDENTE` = aguardando ato (pode ter números de referência ou não). `ESTIMATIVA` = número de estudo ou projeção, sem ato |
| **AL-RN-02 Só `LEI` gera achado** (P-05) | `FiscalCheckEngine.contextFor` carrega **só** as linhas com `status = 'LEI'` e as três alíquotas preenchidas (uma consulta, como hoje). Linha `PENDENTE` ou `ESTIMATIVA` não entra no contexto: para o motor, o ano fica "sem referência" e `IBSCBS_RATE` não roda. A severidade e a mensagem de `IBSCBS_RATE` não mudam (`ERROR`, motor RN-12) |
| **AL-RN-03 Validação do cadastro** | `ano` de 2026 a 2040 (senão 400 `error.reform.rate.year.invalid`). Com `LEI` ou `ESTIMATIVA`, as três alíquotas são obrigatórias; com `PENDENTE`, cada uma é opcional. Cada alíquota informada: 0 ≤ x ≤ 100, no máximo 4 casas decimais, em % (ex.: `0.9000` = 0,9%); senão 400 `error.reform.rate.invalid` com `invalidField`. Com `LEI`: `fundamento` (texto, 1 a 500 caracteres, aparado) e `fonteUrl` (começa com `https://`, até 300 caracteres) obrigatórios; senão 400 `error.reform.rate.basis.required`. Nos outros status, os dois são opcionais com os mesmos limites. Os números são gravados sem arredondar (escala 4, `BigDecimal`); valor com mais de 4 casas é recusado, nunca arredondado |
| **AL-RN-04 Gravação e concorrência** | `PUT /platform/ibs-cbs-rates/{ano}` cria ou atualiza (upsert). Criação: corpo sem `version` (ou nulo); se o ano já existe, 409 `error.reform.rate.conflict` com `currentVersion`. Atualização: `version` igual ao gravado; senão 409 com `currentVersion`. Não há exclusão: para "tirar" um ano do motor, muda-se o status para `PENDENTE`. Cada gravação que muda algum campo grava um evento em `ibs_cbs_rate_changes` com antes e depois; gravação sem mudança devolve 200 e não grava evento |
| **AL-RN-05 Avisos** | Calculados a cada leitura, com a data de Recife. Para o ano corrente e o seguinte: sem linha ou sem linha `LEI`, gera aviso `SEM_LEI` ("{ano}: sem alíquota oficial cadastrada. A conferência não compara as alíquotas de IBS/CBS das notas desse ano."). Linha `LEI` com `fonteUrl` vazio não existe (AL-RN-03) |
| **AL-RN-06 Leitura pelos escritórios** | `GET /api/v1/reforma/aliquotas` devolve todas as linhas (todos os status), com fundamento e link: é texto de lei, sem dado de cliente. A conferência e o detalhe da nota usam essa resposta para (a) mostrar a referência do ano com o selo do status e (b) exibir o aviso da AL-RN-05 quando o período conferido cai num ano sem `LEI` |
| **AL-RN-07 Migração da linha de 2026** | A V83 grava em 2026: `status = 'LEI'`, `fundamento = 'LC 214/2025: alíquotas do ano-teste de 2026 (CBS 0,9%; IBS estadual 0,1%; IBS municipal 0%). Artigo a completar pelo Fiscal.'`, `fonte_url = 'https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm'`, `version = 0`. As alíquotas não mudam. O comportamento da conferência de 2026 fica idêntico ao de hoje |
| **AL-RN-08 Duas instâncias** | Não há rotina. O contexto do motor lê o banco a cada `contextFor` (sem cache), então uma alteração feita numa instância vale na próxima conferência da outra. A concorrência de escrita é resolvida pela `version` (AL-RN-04) |

## 6. Modelo de dados

Migrations reservadas (última hoje: V79; F5a reserva V80 a V82). Conferir na hora de implementar.

### V83__IbsCbsRateStatus (altera `ibs_cbs_rates`)

| Coluna | Tipo | Observação |
|---|---|---|
| `status` | varchar(12) | preenchida pela própria migration; a entidade trata como obrigatória |
| `fundamento` | varchar(500) | |
| `fonte_url` | varchar(300) | |
| `version` | int | 0 na linha de 2026 |
| `created_by`, `created_date`, `last_modified_by`, `last_modified_date` | padrão do projeto | `system` na linha de 2026 |
| `aliquota_cbs`, `aliquota_ibs_uf`, `aliquota_ibs_mun` | passam a aceitar nulo (`ALTER TABLE ... MODIFY`) | só `PENDENTE` grava nulo |

Seed da AL-RN-07 no `beforeKeys`, idempotente (`WHERE ano = 2026 AND status IS NULL`). Tabela pequena (1 linha por ano).

### V84__IbsCbsRateChanges

`id` varchar(36) PK, `ano` int not null, `acao` varchar(10) (`CRIADA`, `ALTERADA`), `status_antes`, `status_depois`
varchar(12), `cbs_antes`, `cbs_depois`, `ibs_uf_antes`, `ibs_uf_depois`, `ibs_mun_antes`, `ibs_mun_depois`
decimal(7,4), `fundamento_depois` varchar(500), `fonte_url_depois` varchar(300), `alterado_por` varchar(50) not null
(login), `alterado_em` datetime(6) not null (Recife) + auditoria. Índice (`ano`, `alterado_em`).

## 7. Contrato da API

Números de alíquota como decimal com 4 casas no JSON (`0.9000`). Data e hora ISO 8601 com o fuso de Recife.

| Método e rota | Quem | Corpo e parâmetros | Respostas |
|---|---|---|---|
| `GET /api/v1/platform/ibs-cbs-rates` | `@PlatformAdminOnly` (outro usuário: 403, como o resto do console) | — | 200 `{anos: [linha], avisos: [aviso]}`, `anos` em ordem crescente |
| `PUT /api/v1/platform/ibs-cbs-rates/{ano}` | `@PlatformAdminOnly` | `{aliquotaCbs, aliquotaIbsUf, aliquotaIbsMun, status, fundamento, fonteUrl, version}` | 200 linha (criada ou alterada); 400 `error.reform.rate.year.invalid`, `error.reform.rate.invalid` (`invalidField`), `error.reform.rate.basis.required`, `error.reform.rate.status.invalid`; 409 `error.reform.rate.conflict` (`currentVersion`) |
| `GET /api/v1/platform/ibs-cbs-rates/{ano}/historico` | `@PlatformAdminOnly` | — | 200 lista de eventos, mais recente primeiro; 404 `error.reform.rate.not.found` se o ano não existe |
| `GET /api/v1/reforma/aliquotas` | qualquer usuário autenticado de escritório | — | 200 `{anos: [linha sem version e sem auditoria], avisos: [aviso]}` |

**Linha.** `ano`, `aliquotaCbs`, `aliquotaIbsUf`, `aliquotaIbsMun`, `status`, `geraAchado` (`true` só com `LEI` e as três
preenchidas), `fundamento`, `fonteUrl`, `version`, `alteradoPor`, `alteradoEm`.

**Aviso.** `{ano, tipo: "SEM_LEI", texto}`.

**Evento.** `acao`, `statusAntes`, `statusDepois`, `cbsAntes`, `cbsDepois`, `ibsUfAntes`, `ibsUfDepois`, `ibsMunAntes`,
`ibsMunDepois`, `fundamentoDepois`, `fonteUrlDepois`, `alteradoPor`, `alteradoEm`.

**Textos de erro (pt).**

| Code | Status | Texto |
|---|---|---|
| `error.reform.rate.year.invalid` | 400 | "Ano inválido. Use de 2026 a 2040." |
| `error.reform.rate.invalid` | 400 | "Alíquota inválida: use de 0 a 100, com até 4 casas decimais." |
| `error.reform.rate.basis.required` | 400 | "Alíquota com status Lei exige o fundamento e o link do ato oficial." |
| `error.reform.rate.status.invalid` | 400 | "Status inválido." |
| `error.reform.rate.conflict` | 409 | "Outra pessoa alterou este ano. Recarregue e tente de novo." |
| `error.reform.rate.not.found` | 404 | "Ano não cadastrado." |

## 8. Variáveis de ambiente

Nenhuma. A mudança não é arriscada: a linha de 2026 continua gerando os mesmos achados e anos novos só geram achado
quando o admin da plataforma cadastra com `LEI`.

## 9. Logs

| Evento | Nível | Campos |
|---|---|---|
| `ibs.cbs.rate.saved` | INFO | `ano`, `acao`, `statusAntes`, `statusDepois`, `userId` (nunca o fundamento inteiro) |
| `ibs.cbs.rate.conflict` | INFO | `ano`, `userId` |

## 10. Critérios de aceite

- **CA-AL-01 (AL-RN-07)** Dado o banco migrado, quando conferimos uma nota de 2026 com CST 000 e pCBS 1, então há
  `IBSCBS_RATE` com esperado 0.9, como antes da mudança.
- **CA-AL-02 (AL-RN-02)** Dado 2027 cadastrado como `ESTIMATIVA`, quando conferimos uma nota de 2027 com pCBS diferente,
  então não há `IBSCBS_RATE`.
- **CA-AL-03 (AL-RN-02)** Dado 2027 alterado para `LEI`, quando conferimos a mesma nota, então há `IBSCBS_RATE` `ERROR`
  com a mensagem atual citando 2027.
- **CA-AL-04 (AL-RN-03)** Dado um `PUT` com `LEI` sem `fonteUrl`, então 400 `error.reform.rate.basis.required` e nada é
  gravado.
- **CA-AL-05 (AL-RN-04)** Dado dois admins editando 2027 com a mesma `version`, então o segundo recebe 409 com
  `currentVersion`, e o histórico tem um evento só.
- **CA-AL-06 (AL-RN-05)** Dado a data de Recife 2026-12-15 e só 2026 com `LEI`, então `avisos` traz `SEM_LEI` para 2027;
  em 2027-01-02, sem linha de 2027 e sem linha de 2028, traz os dois.
- **CA-AL-07 (AL-RN-06)** Dado um FISCAL conferindo o período 2027-01, com 2027 sem `LEI`, então a conferência mostra o
  aviso de que as alíquotas de IBS/CBS de 2027 não estão sendo comparadas.
- **CA-AL-08 (acesso)** Dado o ADMIN de um escritório comum, quando chama `PUT /platform/ibs-cbs-rates/2027`, então 403
  e nada muda.

## 11. Casos de teste

As alíquotas de 2027 abaixo são **números fictícios de teste**, escolhidos para não coincidir com nenhum valor citado
no material.

| CT | Dado | Esperado | RN |
|---|---|---|---|
| CT-AL-01 | Banco migrado | 2026: `LEI`, CBS 0.9000, IBS UF 0.1000, IBS Mun 0.0000, `version = 0`, `geraAchado = true` | 07 |
| CT-AL-02 | Nota de 2026-12-31 23:59 (gravada), CST 000, pCBS 0.9, pIBSUF 0.1, pIBSMun 0 | Sem `IBSCBS_RATE` | 02 |
| CT-AL-03 | Nota de 2027-01-01 00:00, CST 000, pCBS 5.0; sem linha 2027 | Sem `IBSCBS_RATE`; `avisos` com `SEM_LEI` de 2027 | 02, 05 |
| CT-AL-04 | 2027 `ESTIMATIVA` com CBS 8.8000, IBS UF 0.1000, IBS Mun 0.0000; nota do CT-AL-03 | Sem `IBSCBS_RATE`; `geraAchado = false` | 02 |
| CT-AL-05 | 2027 mudado para `LEI` (mesmos números, fundamento e `fonteUrl` fictícios `https://exemplo.test/ato`) | `IBSCBS_RATE` `ERROR`: "Alíquota de CBS 5% diferente da referência de 2027 (8.8%)" | 02 |
| CT-AL-06 | Nota de 2027 com CST 410 (não tributado pelo motor) e pCBS 5 | Sem `IBSCBS_RATE` (só CST 000 e 200, regra atual) | 02 |
| CT-AL-07 | `PUT` 2027 `PENDENTE` só com CBS 8.8000 | 200; IBS nulos; `geraAchado = false` | 03 |
| CT-AL-08 | `PUT` com CBS 0.12345 | 400 `error.reform.rate.invalid`, `invalidField = aliquotaCbs` | 03 |
| CT-AL-09 | `PUT` com CBS -1 e com 100.0001 | 400 nos dois | 03 |
| CT-AL-10 | `PUT /2025` e `PUT /2041` | 400 `error.reform.rate.year.invalid` | 03 |
| CT-AL-11 | `PUT` `LEI` com `fonteUrl = http://exemplo.test` | 400 `error.reform.rate.basis.required` | 03 |
| CT-AL-12 | `PUT` de criação de 2026 (já existe) sem `version` | 409, `currentVersion = 0` | 04 |
| CT-AL-13 | Dois `PUT` de 2027 com `version = 1`, simultâneos | Um 200 (`version = 2`), outro 409 | 04 |
| CT-AL-14 | `PUT` igual ao gravado | 200; nenhum evento novo | 04 |
| CT-AL-15 | `PUT` que muda 2027 de `ESTIMATIVA` para `LEI` | Evento `ALTERADA` com `statusAntes = ESTIMATIVA`, `statusDepois = LEI`, login e hora de Recife | 04 |
| CT-AL-16 | Instância A grava 2027 `LEI`; conferência roda na instância B logo depois | B já usa 2027 | 08 |
| CT-AL-17 | Clock de Recife 2026-12-31 23:59 e 2027-01-01 00:00, com JVM em UTC | Avisos de {2026, 2027} e de {2027, 2028}, respectivamente | 05 |
| CT-AL-18 | ADMIN de escritório comum chama `GET /platform/ibs-cbs-rates` | 403 | acesso |
| CT-AL-19 | Usuário de escritório bloqueado chama `GET /reforma/aliquotas` | 200 | 06 |
| CT-AL-20 | Contagem de consultas do `contextFor` | Continua 2 (regras e alíquotas) | 02 |

## 12. Tarefas por agente

O contrato está fechado: backend e frontend podem rodar em paralelo.

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| B-AL-1 | backend | V83 e V84; `IbsCbsRate` com os campos novos e `@Version`; repositório com `findByStatus(LEI)`; `contextFor` passa a carregar só `LEI` com as três alíquotas | — |
| B-AL-2 | backend | Serviço e validação (AL-RN-03, 04), eventos, avisos (AL-RN-05) com o `Clock` de Recife; rotas da plataforma e `GET /reforma/aliquotas`; mensagens pt | B-AL-1 |
| F-AL-1 | frontend | Console: página `/plataforma/aliquotas-ibs-cbs` (menu da plataforma), tabela por ano com selo do status, faixa com os avisos, formulário de edição (status, três alíquotas, fundamento, link), histórico por ano; 409 recarrega e avisa | contrato |
| F-AL-2 | frontend | Conferência da empresa e detalhe da nota: aviso da AL-RN-05 quando o período (ou a data da nota) cai num ano sem `LEI`; no detalhe do grupo IBS/CBS da nota, "Referência de {ano}: CBS x%, IBS UF y%, IBS Mun z% ({status})" com link do ato | contrato |
| Q-AL-1 | QA | CT-AL-01 a 20 por HTTP real, no MySQL local; virada de ano com Clock fixo | B-AL-2, F-AL-1, F-AL-2 |
| V-AL-1 | Fiscal | Completar o fundamento de 2026 com o artigo (P-AL-01) e, quando sair o ato, cadastrar 2027 como `LEI` | go-live |

## 13. Riscos

- **Alíquota cadastrada errada como `LEI`** gera `ERROR` em toda nota do ano. Mitigação: histórico com antes e depois,
  link obrigatório do ato, e a mensagem do achado mostra o ano e o valor esperado.
- **Ato de 2027 publicado tarde** (o material cita prazo do Senado até 15/12/2026, a validar): enquanto não houver
  `LEI`, a conferência de 2027 não compara alíquotas, mas agora com aviso visível (AL-RN-05).
- **Redução de alíquota por ano** (o material cita 0,1 p.p. em 2027–2028, a validar) entra no número cadastrado; o motor
  compara a alíquota nominal com a de referência do ano, como hoje. Se o ato definir a referência de outra forma, a
  regra do motor muda numa spec própria.

## 14. Pré-requisitos de go-live

1. Q-AL-1 aprovado.
2. Deploy na EC2 antes de 01/01/2027, com a aprovação do Tony.
3. Pelo menos um login de admin da plataforma ativo para quem vai cadastrar (P-AL-02).

## 15. Fora do escopo

- Alíquotas por UF ou município. Se o IBS de um ano passar a ter alíquota própria de cada ente (a confirmar na
  legislação quando chegar a hora), é outra spec.
- Alíquotas reduzidas por cClassTrib (vem da tabela oficial, F1a/F1b).
- Projeção de carga (F8).

## 16. Perguntas em aberto

Nenhuma bloqueia.

| P | Pergunta | Opções | Recomendação | Impacto se for diferente |
|---|---|---|---|---|
| P-AL-01 | Qual o artigo da LC 214/2025 que fixa as alíquotas de 2026 (fundamento da linha 2026)? | o Fiscal informa e edita no console | O Fiscal completa no console depois do deploy; até lá vale o texto da AL-RN-07 | Nenhum no código |
| P-AL-02 | "O Fiscal cadastra 2027": quem, no sistema? | (a) o admin da plataforma (`@PlatformAdminOnly`), dando login de admin da plataforma a quem do Fiscal da CS Hub fizer o cadastro; (b) permissão nova para um usuário FISCAL da CS Hub | (a): é o mesmo caminho das regras gerais da auditoria e das tabelas oficiais (P-07), sem permissão nova | (b) exige permissão nova e guarda própria, e abre a edição de dado global a um perfil de escritório |
| P-AL-03 | Quando o ato de 2027 sair, a CS Hub avisa os escritórios? | aviso no sistema; nada | Nada além do aviso `SEM_LEI` sumir; reavaliar se houver demanda | — |
