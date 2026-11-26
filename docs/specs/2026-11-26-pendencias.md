# Pendências da carteira (as-built)

## Status
**Implementado** (roadmap M13). Spec retroativa: descreve o código como está em 2026-11-26, sem propor mudança.

- Commits de referência: `ba03f1d` (feat: painel de pendências (M13) com geração automática e saúde das integrações), `6eadd7a` (perf: índices de pendências (empresa, status)), `77b7680` (tipos da certidão SEFAZ-PE).
- Migrations: `V35__Pendencies` (tabela `pendencies`), `V36__CaptureRuns` (histórico das coletas), `V54__PendenciesCompanyStatusIndex`.
- Backend: `rest/PendencyResource`, `service/pendency/PendencyFinder`, `PendencyService`, `PendencyScheduler`, `domain/pendency/Pendency`, `enumerated/PendencyType|PendencySeverity|PendencyStatus`.
- Frontend: `features/pendencies` (rota `/pendencias`), `core/services/pendency.service.ts`, `core/models/pendency.model.ts`; atalho na home e no menu lateral.

## Problema e objetivo
Cada módulo do Taxify descobre um problema diferente (certificado vencendo, captura falhando, nota divergente, retenção indevida, certidão vencida, achado de auditoria). Sem um lugar só, o analista teria de abrir cada tela, empresa por empresa. A tabela única de pendências junta tudo por empresa e por prioridade, fecha sozinha o que deixou de existir e guarda quem fechou à mão e por quê.

Como medir (não há métrica implementada): total de pendências abertas por severidade (`GET /pendencias/resumo`) e o tempo entre `first_seen_at` e `closed_at`.

## Usuários e cenário
- **Analista Fiscal** (`INVOICES_VIEW`): abre o painel de manhã e filtra por severidade, tipo, competência e empresa. Clica na pendência para ir à nota, à manifestação, à auditoria ou à ficha da empresa.
- **Analista com `INVOICES_AUDIT`**: resolve, desconsidera (sempre com comentário) ou reabre uma pendência.
- **Sistema**: recalcula as pendências de todas as empresas ativas de hora em hora (minuto 40) e também na hora, depois de gravar uma certidão.

## Histórias de usuário
- Como analista Fiscal, quero ver numa tela só o que falta em cada empresa da carteira, da mais grave para a menos grave, para atacar primeiro o que trava a operação.
- Como analista Fiscal, quero que a pendência feche sozinha quando a causa some, para não ter de limpar a lista à mão.
- Como analista Fiscal, quero desconsiderar uma pendência com justificativa, para que ela não volte a cada hora enquanto a causa continua lá.
- Como analista Fiscal, quero ver a saúde das coletas (SEFAZ, NFS-e Nacional, certidão SEFAZ-PE) dos últimos 7 dias, para saber se a falta de nota é problema de captura.
- Como analista Fiscal, quero forçar a atualização das pendências de uma empresa, para conferir na hora se o que corrigi resolveu.

## Tipos de pendência e de onde vêm

| Tipo | Severidade | Origem | Condição (resumo) | Chave natural | Objeto | Competência |
|---|---|---|---|---|---|---|
| `CERTIFICATE_EXPIRED` | ALTA | `PendencyFinder.certificate` | Certificado A1 da empresa (ou da mesma raiz de CNPJ) com validade anterior a agora | `CERTIFICATE_EXPIRED:<id cert>` | COMPANY | — |
| `CERTIFICATE_EXPIRING` | MÉDIA | idem | Vence em menos de 30 dias | `CERTIFICATE_EXPIRING:<id cert>` | COMPANY | — |
| `SEFAZ_CAPTURE_ERROR` | ALTA | `capture` | Plano com importação de XML + SEFAZ e último status do controle de distribuição = ERRO | `...:<id empresa>` | COMPANY | — |
| `NFSE_CAPTURE_ERROR` | ALTA | `nfseCapture` | Plano com importação + NFS-e Nacional e último status do ADN = ERRO | `...:<id empresa>` | COMPANY | — |
| `CIENCIA_REFUSED` | MÉDIA | `ciencia` | SEFAZ + manifestação no plano, `sefaz.dist.auto-ciencia=true`, resumo sem XML com ciência em ERRO | `...:<chave>` | NOTE_KEY | mês de emissão |
| `MANIFESTATION_DEADLINE` | ALTA | `manifestation` | Mesmas condições de plano; prazo de manifestação entre 0 e 15 dias | `...:<chave>` | NOTE ou NOTE_KEY | mês de emissão |
| `SOURCE_ONLY_SIEG` | BAIXA | `sources` | SEFAZ + SIEG no plano; nota trazida só pelo SIEG há mais de 48h (janela de 90 dias) | `...:<chave ou id>` | NOTE | mês de emissão |
| `SOURCE_ONLY_SEFAZ` | BAIXA | `sources` | Nota entregue só pela SEFAZ há mais de 48h | `...:<chave ou id>` | NOTE | mês de emissão |
| `NOTE_DIVERGENT` | MÉDIA | `divergent` | Nota conferida com status DIVERGENT, emitida nos últimos 12 meses | `...:<chave ou id>` | NOTE | mês de emissão |
| `REGIME_MISSING` | MÉDIA | `regime` | Empresa sem `taxRegimeType` e sem regime vigente cadastrado | `...:<id empresa>` | COMPANY | — |
| `RETENTION_UNDUE` | MÉDIA | `retentions` | `RetentionCalculator` aponta retenção provavelmente indevida (competência atual e anterior) | `...:<tipo doc>:<id doc>` | NOTE ou SERVICE_NOTE | mês |
| `ISS_RETAINED_WITHOUT_VALUE` | BAIXA | `retentions` | ISS marcado como retido sem valor | idem | idem | mês |
| `CERTIDAO_PE_POSITIVA` | ALTA (BAIXA se efeito de negativa) | `CertidaoPendencies` | Ver spec de certidões, RN-12 | `...:<id certidão>` | COMPANY | — |
| `CERTIDAO_PE_VENCENDO` | MÉDIA | idem | Certidão vigente a 15 dias ou menos do vencimento | idem | COMPANY | — |
| `CERTIDAO_PE_VENCIDA` | ALTA | idem | Certidão vigente vencida | idem | COMPANY | — |
| `AUDIT_FINDING` | a da regra | `AuditService` (não pelo `PendencyFinder`) | Achado da auditoria tipo malha; ver spec de auditoria | `AUDIT:<regra>:<AAAA-MM>[:sufixo]` | COMPANY ou NOTE | competência auditada |

Ref.: `PendencyType.java:7-34`; `PendencyFinder.java:99-267`; `CertidaoPendencies.java`; `AuditService.java:281-290`.

## Regras de negócio

**RN-01 Pendência = causa.** Uma linha por empresa e por chave natural (`uk_pendencies_company_key`). A chave identifica a causa: certificado, nota, certidão, regra e mês da auditoria.
Ref.: `V35__Pendencies.java:3-7`, `:37`; `PendencyFinder.java:289-291`.

**RN-02 Leitura pura.** `PendencyFinder.find` só lê o estado atual da empresa e devolve os achados; quem grava é `PendencyService.sync`. As checagens de captura, ciência, manifestação e origens dependem dos serviços do plano vigente da empresa (assinatura com início ≤ hoje ≤ fim). Sem plano vigente, só rodam certificado, divergentes, regime, retenções e certidão.
Ref.: `PendencyFinder.java:99-127`, `:274-283`; `PlanServiceEnum.java:25-32`.

**RN-03 Limite por tipo.** No máximo 200 achados por tipo e por empresa a cada execução (`MAX_PER_TYPE`), nas consultas paginadas, na manifestação e nas retenções.
Ref.: `PendencyFinder.java:55-56`, `:195-197`, `:243-245`, `:285-287`.

**RN-04 Sincronização.** Para cada achado (chave repetida no mesmo lote é ignorada):
- chave nova → cria pendência ABERTA com `first_seen_at = agora`;
- chave conhecida → atualiza tipo, severidade, competência, objeto, título (até 200 caracteres), detalhe (até 1000), `last_seen_at = agora` e zera `absent_since`;
- chave conhecida, fechada, com `absent_since` preenchido (a causa tinha sumido e voltou) → **reabre** (status ABERTA, limpa fechamento, `first_seen_at = agora`).
Para cada pendência existente cuja chave não veio:
- se ABERTA → fecha como RESOLVIDA, `closed_by = "sistema"`, comentário "Resolvida automaticamente: a causa não existe mais.", `auto_closed = true`, `absent_since = agora`;
- se já fechada e `absent_since` vazio → só marca `absent_since = agora`.
Ref.: `PendencyService.java:87-158`.

**RN-05 Fechada à mão com a causa presente continua fechada.** Uma pendência resolvida ou desconsiderada pelo usuário, cuja causa continua existindo, **não reabre** (o `absent_since` está vazio). Ela só reabre se a causa sumir e depois voltar.
Ref.: `PendencyService.java:125-127`, `:151-154`; teste `aDismissedPendencyStaysClosedWhileItsCauseIsThere`.

**RN-06 Escopo do gerador.** `refresh` sincroniza só os tipos `MANAGED` (todos menos `AUDIT_FINDING`). A auditoria usa `syncScoped`, restrito ao tipo `AUDIT_FINDING` e a uma competência, para não fechar achados de outros meses.
Ref.: `PendencyFinder.java:50-51`; `PendencyService.java:92-106`.

**RN-07 Fechamento manual.** Resolver ou desconsiderar exige comentário de 3 a 500 caracteres (`@NotBlank @Size(min=3,max=500)`), grava `closed_at`, `closed_by` (usuário logado) e `close_comment`, com `auto_closed=false`. Reabrir limpa o fechamento e reinicia `first_seen_at`. As três ações validam o acesso à empresa da pendência. Pendência inexistente ou excluída → 400.
Ref.: `PendencyDTOs.java:44-46`; `PendencyService.java:234-270`.

**RN-08 Escopo de leitura.** Com `companyId`: só essa empresa, com acesso validado. Sem `companyId`: as empresas **ativas** do escritório do usuário que ele pode ver. Usuário sem escritório → 400 "As pendências da carteira exigem um usuário de escritório - escolha uma empresa."
Ref.: `PendencyService.java:272-290`.

**RN-09 Lista.** Filtros opcionais: status (padrão ABERTA; `TODAS` = qualquer), severidade, tipo, competência `AAAA-MM`. Ordem: ALTA, MÉDIA, BAIXA, INFO e depois `first_seen_at` crescente (a mais antiga primeiro). Página padrão de 50, máximo de 200.
Ref.: `PendencyResource.java:38-50`; `PendencyRepository.java:26-44`; `PendencyService.java:160-170`.

**RN-10 Resumo.** Conta só as pendências ABERTAS, por tipo e severidade, com o total. Ordena pelos tipos com mais ALTAS e depois pelo total.
Ref.: `PendencyService.java:172-193`; `PendencyRepository.java:46-49`.

**RN-11 Saúde das integrações.** Para cada empresa do escopo: SEFAZ (controle de distribuição), NFS-e Nacional (controle ADN) e certidão SEFAZ-PE (última `capture_runs`), com a última execução, o status, o detalhe, a próxima consulta e as execuções e erros dos últimos 7 dias. As que estão em ERRO aparecem primeiro, depois a ordem é pelo nome da empresa.
Ref.: `PendencyService.java:195-232`.

**RN-12 Job.** Cron `pendencies.cron` (padrão `0 40 * * * *`, depois da captura SEFAZ do minuto 15), em thread virtual, sem duas execuções em paralelo. Percorre **todas as empresas não excluídas e ativas de todos os escritórios**. Erro numa empresa é logado e não para as outras. Ao final, apaga os `capture_runs` com mais de 90 dias.
Ref.: `PendencyScheduler.java:16-82`.

**RN-13 Atualizar agora.** `POST /pendencias/atualizar?companyId=` recalcula a empresa na hora e devolve `{abertas, emSegundoPlano:false}`. Sem `companyId`, dispara o mesmo job da RN-12 (todas as empresas de todos os escritórios) em segundo plano e devolve `{emSegundoPlano: true|false}` (`false` = já havia uma execução em andamento).
Ref.: `PendencyResource.java:82-90`.

**RN-14 Destino do clique (front).** NOTE → `/notas-fiscais/<id>`; SERVICE_NOTE → `/notas-servico`; NOTE_KEY → `/manifestacao`; `AUDIT_FINDING` → `/auditoria`; COMPANY → `/cadastro/empresas?empresa=<id>`.
Ref.: `pendencies.component.ts:261-279`.

## Critérios de aceite (comportamento atual)
- **CA-01 (RN-04)** Dado uma empresa com o certificado A1 vencido ontem, quando o job roda, então existe uma pendência `CERTIFICATE_EXPIRED` ALTA aberta.
- **CA-02 (RN-04)** Dado a mesma pendência aberta, quando o job roda de novo e a causa continua, então não aparece uma segunda linha e `last_seen_at` é atualizado.
- **CA-03 (RN-04)** Dado uma pendência aberta, quando a causa some (certificado novo enviado), então na execução seguinte ela fica RESOLVIDA por "sistema", com `auto_closed=true`.
- **CA-04 (RN-05)** Dado uma pendência desconsiderada pelo usuário com a causa ainda presente, quando o job roda, então ela continua DESCONSIDERADA.
- **CA-05 (RN-04)** Dado uma pendência fechada cuja causa sumiu e depois voltou, quando o job roda, então ela volta a ABERTA e `first_seen_at` passa a ser a data da volta.
- **CA-06 (RN-07)** Dado um usuário com `INVOICES_AUDIT`, quando resolve com o comentário "ok", então recebe 400 (mínimo de 3 caracteres). Com "Certificado renovado", fecha e grava usuário e comentário.
- **CA-07 (RN-07)** Dado um usuário só com `INVOICES_VIEW`, quando tenta resolver, então recebe 403 e a tela não mostra as ações de fechar.
- **CA-08 (RN-08)** Dado um usuário sem escritório e sem `companyId`, quando abre o painel, então recebe 400 `error.pendency.firm.required`.
- **CA-09 (RN-08)** Dado uma empresa inativa, quando abro o painel da carteira, então as pendências dela não aparecem e o job não a recalcula.
- **CA-10 (RN-09)** Dado pendências ALTA de 10/11 e MÉDIA de 01/11, quando listo, então a ALTA vem primeiro.
- **CA-11 (RN-02)** Dado uma empresa cujo plano não tem SEFAZ, quando o controle de distribuição está em ERRO, então não nasce `SEFAZ_CAPTURE_ERROR`.
- **CA-12 (RN-06)** Dado um achado de auditoria aberto, quando o job de pendências roda, então o achado não é fechado.
- **CA-13 (RN-13)** Dado uma execução da carteira em andamento, quando clico "Atualizar agora" sem empresa, então recebo `emSegundoPlano=false` e nenhuma execução nova começa.

## Casos de teste (exemplos fictícios; hoje = 26/11/2026 10:00)
| # | Situação | Esperado |
|---|---|---|
| 1 | Certificado válido até 20/12/2026 | `CERTIFICATE_EXPIRING` MÉDIA "vence em 24 dia(s)" |
| 2 | Certificado válido até 30/12/2026 | Nada (30 dias ou mais) |
| 3 | Nota de entrada só no SIEG, importada em 25/11 08:00 | Nada (menos de 48h) |
| 4 | A mesma nota importada em 23/11 08:00, sem SEFAZ | `SOURCE_ONLY_SIEG` BAIXA |
| 5 | Prazo de manifestação em 05/12/2026 | `MANIFESTATION_DEADLINE` ALTA "vence em 9 dia(s)" |
| 6 | Prazo já vencido (−1 dia) | Nada (`diasRestantes < 0` é ignorado) |
| 7 | Nota DIVERGENT emitida em 10/2025 | Nada (fora dos 12 meses) |
| 8 | 350 notas divergentes no período | No máximo 200 pendências `NOTE_DIVERGENT` |
| 9 | Empresa sem regime | `REGIME_MISSING` MÉDIA |
| 10 | Resolver com comentário em branco | 400 |
| 11 | Desconsiderar e depois reabrir | ABERTA, sem comentário de fechamento |

## Endpoints da API
Base `/api/v1/pendencias` (classe com `@RequiresPermission(INVOICES_VIEW)`).

| Método | Caminho | Permissão | O que faz |
|---|---|---|---|
| GET | `/` `?companyId&status&severity&type&competence&page&size` | INVOICES_VIEW | Lista paginada (RN-09) |
| GET | `/resumo?companyId` | INVOICES_VIEW | Contadores das abertas (RN-10) |
| GET | `/integracoes?companyId` | INVOICES_VIEW | Saúde das coletas (RN-11) |
| POST | `/{id}/resolver` body `{comentario}` | INVOICES_AUDIT | Fecha como RESOLVIDA |
| POST | `/{id}/desconsiderar` body `{comentario}` | INVOICES_AUDIT | Fecha como DESCONSIDERADA |
| POST | `/{id}/reabrir` | INVOICES_AUDIT | Reabre |
| POST | `/atualizar?companyId` | INVOICES_VIEW | Recalcula agora (RN-13) |

## Telas
`/pendencias` (`PendenciesComponent`, guard `INVOICES_VIEW`):
- busca de empresa (vazia = carteira), chips de status (Abertas, Resolvidas, Desconsideradas, Todas) e de severidade, navegação por competência ("todas" ou mês), atalhos por tipo a partir do resumo;
- tabela: Severidade, Empresa, Pendência (título, detalhe e tipo), Competência, Desde, Ações (resolver, desconsiderar e reabrir só com `INVOICES_AUDIT`, com modal de comentário);
- botão "Atualizar agora" (visível para quem acessa a tela);
- tabela "Saúde das integrações": Empresa, Coleta, Última execução, Situação ("Notas recebidas", "Sem movimento", "Erro"; para a certidão, "Emitida" ou "Não emitida") e Execuções nos últimos 7 dias.

## Dados e integrações
- **`pendencies`** (V35): `id`, `company_id` (FK), `type`, `severity`, `natural_key` (até 150), `competence` (`AAAA-MM`), `object_type` (COMPANY, NOTE, NOTE_KEY, SERVICE_NOTE), `object_id`, `title`, `detail`, `status` (ABERTA, RESOLVIDA, DESCONSIDERADA), `first_seen_at`, `last_seen_at`, `absent_since`, `closed_at`, `closed_by`, `close_comment`, `auto_closed`, `deleted` + colunas de auditoria. Índices: único `(company_id, natural_key)`, `(status, severity)` e, desde a V54, `(company_id, status, deleted)`.
- **`capture_runs`** (V36): histórico das coletas, lido pela saúde das integrações e apagado após 90 dias.
- Fontes lidas: certificados A1, `sefaz_dist_control`, controle do ADN de NFS-e, resumos da SEFAZ, documentos fiscais, regimes, assinaturas e planos, a tela de manifestação, `RetentionCalculator` e `certidoes_estaduais`.

## Configuração
| Chave (env) | Padrão | Efeito |
|---|---|---|
| `pendencies.enabled` (`PENDENCIES_ENABLED`) | `true` | Liga o job |
| `pendencies.cron` (`PENDENCIES_CRON`) | `0 40 * * * *` | De hora em hora, no minuto 40 |
| `sefaz.dist.auto-ciencia` (`SEFAZ_AUTO_CIENCIA`) | `true` | Condiciona `CIENCIA_REFUSED` e `MANIFESTATION_DEADLINE` |

Constantes no código: aviso do certificado com 30 dias, manifestação com 15 dias, 200 por tipo, divergentes dos últimos 12 meses, janela de 90 dias para "só no SIEG", 48h de comparação entre as fontes, saúde dos últimos 7 dias e retenção de `capture_runs` de 90 dias.

## Testes existentes
- `PendencyFinderTest`: nada faltando; certificado vencido ou vencendo; captura só com SEFAZ no plano; prazos de manifestação até 15 dias com manifestação no plano; empresa sem regime; origens só comparadas com SEFAZ e SIEG no plano.
- `PendencyServiceTest`: causa nova abre; causa conhecida não duplica; causa que some fecha sozinha; desconsiderada fica fechada enquanto a causa existe; causa que volta reabre; fechar exige acesso e grava quem e por quê; carteira exige usuário de escritório; contadores por tipo e severidade.
- Tipos da certidão: `CertidaoPendenciesTest`.

**Lacunas:** não há teste para `NFSE_CAPTURE_ERROR`, `CIENCIA_REFUSED`, `NOTE_DIVERGENT`, retenções, o limite de 200, `health()`, `PendencyScheduler` (incluindo a limpeza de `capture_runs`), `PendencyResource` (parse de status inválido, `TODAS`), nem o front.

## Fora do escopo / limitações conhecidas
- Sem notificação (e-mail ou push); a pendência só aparece no painel.
- Sem responsável nem prazo por pendência.
- `status` inválido na query (`?status=xyz`) gera `IllegalArgumentException` de `valueOf`, sem uma mensagem tratada.
- "Atualizar agora" sem empresa recalcula **todas as empresas de todos os escritórios**, e qualquer usuário com `INVOICES_VIEW` pode disparar isso. Não vaza dado entre escritórios, mas tem custo.
- O job e a tela usam o relógio do servidor (`LocalDateTime.now()`); o fuso não é configurado neste módulo.
- O título e o detalhe são atualizados mesmo com a pendência fechada (RN-04), mas ela não reabre.
- `INFO` existe como severidade, mas nenhum tipo atual a usa.

## Perguntas em aberto (para o Tony)
1. Uma pendência **resolvida à mão** com a causa ainda presente deve ficar fechada (como hoje) ou reabrir na próxima execução? Hoje "resolvida" e "desconsiderada" se comportam igual.
2. As janelas fixas no código (certificado com 30 dias, manifestação com 15 dias, divergentes de 12 meses, 48h entre SIEG e SEFAZ) são as que o time quer? O prazo de manifestação de 15 dias tem fonte normativa a citar?
3. "Atualizar agora" da carteira deveria se limitar ao escritório do usuário?
4. Faltam tipos que o roadmap prevê (DT-e/caixa postal, parcelamentos, Fronteira PE)? A Fronteira não gera pendência hoje.
5. As severidades padrão (por exemplo, `SOURCE_ONLY_*` BAIXA e `NOTE_DIVERGENT` MÉDIA) foram validadas pelo time Fiscal?
