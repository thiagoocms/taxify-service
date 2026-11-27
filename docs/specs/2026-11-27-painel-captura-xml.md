# Painel de controle da captura de XML

> Feature nova, pedida pelo Tony em 2026-11-27. É uma tela de controle que mostra, por empresa, se a captura de documentos
> fiscais está andando: o que já foi importado, o que ainda falta, de qual fonte e por que falhou.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base:
> `br.com.cshub.taxify` (abreviado `…`).
> Depende de:
> - [Captura de XML: SIEG e SEFAZ](2026-11-26-captura-xml-sieg-e-sefaz.md): `fiscal_xml_sync_control`, RN-01 a RN-33;
> - [NFS-e](2026-11-26-nfse-notas-de-servico.md): captura de NFS-e pelo SIEG e pelo ADN (`nfse_adn_control`);
> - [Taxify SaaS, fase 1](2026-11-26-taxify-saas-assinatura-do-escritorio.md): situação de acesso (F1-RN-07), somente
>   leitura (F1-RN-08), rotinas paradas no bloqueio (F1-RN-10), recuperação dos meses bloqueados, console da plataforma
>   (F1-RN-15) e `@PlatformAdminOnly`;
> - [Plataforma, acesso e empresas](2026-11-26-plataforma-acesso-e-empresas.md): perfis, permissões e carteira;
> - [Arquitetura do backend](2026-11-26-arquitetura-tecnica-backend-e-infra.md), seção 3: convenções e formato único de
>   erro `{timestamp, status, code, message, details}`;
> - commit `36be0c4`: a fila da SIEG persistida em `fiscal_xml_sync_control.last_attempt_at` (V56.1).
>
> **Nenhuma regra fiscal é criada aqui.** "Em dia", "atrasada" e os limites do "Sincronizar agora" são decisões de
> produto. Ficam em parâmetros configuráveis, e o padrão de cada um está em "Perguntas em aberto" com a recomendação.

## Status

**Em especificação.** Uma pergunta bloqueia só a **visão da plataforma** (P-CP-01: mostrar o nome das empresas
clientes no console, o que é uma exceção à F1-RN-15). As outras têm recomendação, e o desenvolvimento pode seguir com
ela. A visão do escritório e a gravação da última falha não dependem da P-CP-01.

---

## 1. Estado atual (confirmado no código em 2026-11-27)

### Captura SIEG (NF-e recebidas e emitidas, eventos, NFS-e e recuperação)

| Item | Hoje | Fonte |
|---|---|---|
| Rodízio | Fila persistida: tentativa mais antiga primeiro (nunca tentada na frente), depois a verificação real mais antiga (nunca verificada na frente) e depois o id. Reserva atômica por `last_attempt_at` para duas instâncias | `service/FiscalXmlSyncScheduler.java:76-81`, `:259-294`; `repository/FiscalXmlSyncControlRepository.java:53-62` |
| Ritmo em produção | `FISCAL_XML_SYNC_CRON=0 0/5 * * * *`, `FISCAL_XML_SYNC_BATCH_SIZE=2` | `docker-compose.yml:65-68` |
| Limite do SIEG | 2 requisições por minuto num limitador **em memória, único para a aplicação inteira** (não é por chave de API nem por escritório). A thread do agendador fica parada esperando vaga | `integration/sieg/SiegRateLimiter.java` |
| Custo por empresa | 2 a 8 chamadas: contar e baixar de cada lado (o lado "em dia hoje" é pulado sem chamada), eventos 1 vez por dia por lado, NFS-e 1 vez por dia e um passo da recuperação dos meses bloqueados | `FiscalXmlSyncScheduler.java:296-304`, `:374-396` |
| Quem entra | Assinatura vigente (a de `start_date` mais recente) com `FISCAL_XML_IMPORT` + `SIEG_INTEGRATION`, de escritório liberado pelo `RoutineGate`. Toda empresa ganha linha de controle, mesmo fora do plano. **Empresa inativa não é filtrada** | `FiscalXmlSyncScheduler.java:139-149`, `:182-218` |
| Contadores | Recebidas: `total_sieg`, `total_importado`, `ultima_verificacao_em`. Emitidas: `issued_total_sieg`, `issued_total_imported`, `issued_checked_at`. "Importado" conta as notas do lado no mês **de qualquer origem** (SIEG, SEFAZ ou upload) | `domain/xml/FiscalXmlSyncControl.java`; `service/FiscalXmlIntegrationService.java:223-249` |
| Verificação real | `getVerifiedAt()` = a mais antiga das duas verificações; nula enquanto o lado emitido nunca foi verificado | `FiscalXmlSyncControl.java:81-90` |
| Diário | `received_events_checked_on`, `issued_events_checked_on`, `service_invoices_checked_on`: o dia só é marcado se a etapa deu certo | `FiscalXmlSyncScheduler.java:327-372` |
| Falha | **Só vai para o log.** Nada é gravado por empresa ou por etapa. Uma falha num lado aborta o resto da empresa naquela tentativa (o outro lado, os eventos e a NFS-e não rodam) | `FiscalXmlSyncScheduler.java:165-171` |
| Erro HTTP do SIEG | `SiegClient` lança `SiegIntegrationException("error.sieg.integration.failed")` **sem o status HTTP** e grava no log os primeiros 500 caracteres do corpo da resposta | `integration/sieg/SiegClient.java:75-78` |
| Fuso | `last_attempt_at` é gravado no `Clock` de Recife. `ultima_verificacao_em` e `issued_checked_at` usam `LocalDateTime.now()` (fuso da JVM), e o "hoje" do agendador usa `LocalDate.now()` | `FiscalXmlSyncScheduler.java:101-102`, `:143`; `FiscalXmlIntegrationService.java:230`, `:248` |
| Tela | **Não existe.** `GET /fiscal-xml-sync-control` (`INVOICES_VIEW`) devolve só o lado recebido, filtra em memória e nenhuma tela o usa (pergunta 6 da spec de captura) | `rest/FiscalXmlSyncControlResource.java`; `service/FiscalXmlSyncControlService.java` |
| Diagnóstico | Só pelo SQL manual `scripts/sql/diagnostico-captura-nfe.sql` (só leitura; não é alterado por esta spec) | — |

### Captura direta (SEFAZ e ADN)

| Item | Hoje | Fonte |
|---|---|---|
| SEFAZ (NFeDistribuicaoDFe) | De hora em hora, aos 15 minutos. Entra a empresa com certificado A1 da raiz do CNPJ no escritório, UF coberta e `FISCAL_XML_IMPORT` + `SEFAZ_INTEGRATION`. `sefaz_dist_control`: `last_run_at`, `last_status` (OK, SEM_MOVIMENTO, ERRO), `last_detail` (texto livre com `cStat + xMotivo` ou a mensagem da pré-condição), `next_query_at` | `service/SefazDistScheduler.java:94-125`; `domain/xml/SefazDistControl.java` |
| NFS-e Nacional (ADN) | De hora em hora, aos 25 minutos, com a mesma regra de certificado e `NFSE_NATIONAL_INTEGRATION`. `nfse_adn_control` com os mesmos campos | `service/NfseAdnScheduler.java`; `domain/xml/NfseAdnControl.java` |
| Certificado | `company_certificates.valid_until` (obrigatório) | `domain/certificate/CompanyCertificate.java:49` |
| Histórico | `capture_runs` só para SEFAZ, ADN e certidão. O SIEG não grava | spec de captura, "Fora do escopo" |

### Documentos

- `fiscal_xml_documents.source_system` (primeira origem: `SIEG`, `SEFAZ_DIST`, `UPLOAD`), `created_date`. Índices só
  por `(company_id, issue_date)` e `(company_id, direction, issue_date)`. **Não há índice por origem**
  (`db/migration/V16__FiscalXmlDocuments.java:97-99`).
- `service_invoices.source_system`, `found_in_sieg_at`, `found_in_adn_at`. Índice `(company_id, issue_date)`
  (`V37__ServiceInvoices.java`, `V42__ServiceInvoiceSources.java`).

### Acesso

- Console da plataforma: `GET /platform/firms`, `@PlatformAdminOnly`. Pela F1-RN-15 (decisão 11), ele **nunca traz
  nome, CNPJ ou dado fiscal das empresas clientes** (`service/firmaccess/PlatformFirmService.java:52-57`).
- Admin da plataforma: perfil ADMIN, login em `PLATFORM_ADMIN_LOGINS` e escritório `PLATFORM_FIRM_ID`
  (`common/PlatformAdminService.java`).
- Bloqueio: GET liberado e POST de escrita com 403 `error.firm.access.blocked` (F1-RN-08); `/platform/**` sempre
  liberado para o admin da plataforma (F1-RN-09); capturas automáticas paradas (F1-RN-10).
- Perfis (`enumerated/AccessProfile.java`): `INVOICES_SYNC` só no FISCAL; `INVOICES_VIEW` em FISCAL, ACCOUNTING e
  MANAGEMENT. O ADMIN tem todas as permissões.
- Carteira: `TenantAccessService.accessibleCompanyIds` e `restrictToUserCompanies` (`common/TenantAccessService.java:84-125`).

### Migrations

A última é `V75__PendencyAlertItems` (a V67 não existe; a V56.1 é a da fila). Próximos números livres: **V76** em diante.
Conferir na hora de implementar.

---

## 2. Problema e objetivo

**Problema.** A captura pelo SIEG ficou presa em cerca de 18 empresas, e ninguém percebeu. A causa (o cursor em memória
voltava ao início a cada deploy) foi corrigida no `36be0c4`, mas continua sem existir um lugar onde se veja que uma
empresa não está sendo importada. Hoje, para saber se as notas de uma empresa estão completas, o analista abre o SIEG ou
pede para alguém rodar o SQL de diagnóstico no banco. As falhas ficam só no log da EC2.

**Objetivo.** Uma tela que responde, em poucos segundos e sem SQL:
1. quais empresas estão com a captura em dia, quais têm pendência, quais nunca foram tentadas, quais falharam e quais
   estão fora do plano;
2. de cada empresa, o que foi importado, de que tipo (NF-e recebida, NF-e emitida, eventos, NFS-e) e de qual fonte
   (SIEG, SEFAZ, ADN);
3. por que a última tentativa falhou, com um código seguro;
4. quanto tempo a fila leva para dar a volta e quando a empresa será atendida de novo.

| Como medir | Meta |
|---|---|
| Execuções do SQL de diagnóstico pedidas ao suporte | 0 depois do go-live |
| Tempo até perceber uma captura parada: diferença entre a primeira falha (`last_failure_at` / `last_attempt_at` antigo) e o primeiro acesso ao painel com aquela empresa filtrada | Menos de 1 dia útil |
| Empresas com direito à captura SIEG em `NUNCA_TENTADA` ou `ATRASADA` por mais de 48 h | 0 |
| Conferência em paralelo: nas 4 primeiras semanas, os números do painel batem com o SQL de diagnóstico rodado no mesmo momento (Q-CP-4) | 100% das empresas |

## 3. Usuários e cenário

| Papel | O que faz | Frequência |
|---|---|---|
| ADMIN do escritório | Vê todas as empresas do escritório, filtra as que têm problema, abre o detalhe e pede "Sincronizar agora" | Diário, de manhã; e quando um analista reclama de nota faltando |
| Analista Fiscal (perfil FISCAL, `INVOICES_SYNC`) | Vê só as empresas da carteira dele, sem o botão (recomendação P-CP-02) | Antes de começar a conferência de uma empresa |
| Admin da plataforma (CS Hub) | Vê todas as empresas de todos os escritórios, a saúde da fila e pede "Sincronizar agora" para qualquer empresa de escritório liberado | Diário; e sempre depois de um deploy |
| Agendador SIEG | Atende primeiro os pedidos de "Sincronizar agora" e grava o resultado de cada etapa | A cada 5 minutos |
| Agendadores SEFAZ e ADN | Gravam o resultado da etapa com código seguro | De hora em hora |

## 4. Histórias de usuário

- H-CP-1. Como ADMIN do escritório, quero ver de relance quantas empresas estão em dia, com pendência, nunca tentadas, com erro e fora do plano, para saber se posso confiar nas notas do Taxify hoje.
- H-CP-2. Como ADMIN, quero ver por empresa quantas NF-e recebidas e emitidas o SIEG informa no mês e quantas já estão no Taxify, para saber se falta nota.
- H-CP-3. Como ADMIN, quero ver quando chegou a última nota de cada tipo e de cada fonte, para perceber uma fonte que parou de entregar.
- H-CP-4. Como ADMIN, quero ver o motivo da última falha em linguagem simples, para corrigir o que é do escritório (chave do SIEG, certificado) sem abrir chamado.
- H-CP-5. Como ADMIN, quero colocar uma empresa no início da fila, para não esperar 12 a 20 horas quando sei que chegou nota nova.
- H-CP-6. Como analista Fiscal, quero ver a situação da captura das empresas da minha carteira, para saber se posso começar a conferência.
- H-CP-7. Como admin da plataforma, quero ver a fila de todos os escritórios e saber se ela parou, para não repetir o caso das 18 empresas.
- H-CP-8. Como CS Hub, quero que o painel não exponha CNPJ, token nem a mensagem crua do provedor, e que um escritório nunca veja empresa de outro, para cumprir a LGPD e o sigilo profissional.

---

## 5. Regras de negócio

### Quem vê e o que pode fazer

- **CP-RN-01 Escopo da visão do escritório.** `GET /capture-panel`:
  - **ADMIN:** todas as empresas não excluídas do próprio escritório (decisão do Tony);
  - **usuário com `INVOICES_SYNC`** (perfil FISCAL): só as empresas da carteira (`UserCompany` ativo), sem o botão
    "Sincronizar agora" (recomendação P-CP-02);
  - **demais perfis** (ACCOUNTING, MANAGEMENT, FINANCIAL, PAYROLL): 403;
  - o escritório vem **sempre do usuário logado**, nunca de parâmetro. Um contexto sem escritório (token de usuário
    excluído tratado como "sistema", R-02 da arquitetura) responde 403, e não "todas as empresas";
  - o filtro da carteira é aplicado **na consulta** (`company_id in (…)`), e não em memória depois;
  - empresa de outro escritório em qualquer rota com `{companyId}`: 404 (regra de tenant que já existe).
- **CP-RN-02 Escopo da visão da plataforma.** `GET /platform/capture-panel`, só `@PlatformAdminOnly` (403 para os
  demais, inclusive o ADMIN de escritório):
  - todas as empresas não excluídas de todos os escritórios (decisão do Tony), com o nome do escritório e filtro por
    escritório;
  - **dados da empresa cliente mostrados:** nome e o número do estabelecimento do CNPJ (`/0001`, `/0002`, para separar
    matriz e filial com o mesmo nome). **Nunca** o CNPJ inteiro, valores, chaves de acesso, nomes de terceiros ou
    `last_detail`. Isso é uma exceção à F1-RN-15 e depende da P-CP-01;
  - cada consulta grava o log `capture.panel.viewed scope=platform by={userId} firm={firmId|all}` (sem nome de empresa).
- **CP-RN-03 Escritório bloqueado.** Com o escritório em `BLOCKED`, `SUSPENDED` ou `CANCELED`:
  - o painel abre (é GET, F1-RN-08), com a faixa de bloqueio que já existe, e todas as empresas ficam na situação
    `BLOQUEADA` (CP-RN-07);
  - "Sincronizar agora" do escritório responde 403 `error.firm.access.blocked` (o filtro de somente leitura já faz isso);
  - "Sincronizar agora" da plataforma para empresa de escritório bloqueado responde 409
    `error.capture.priority.firm.blocked`. A captura está parada pela F1-RN-10: o caminho é reativar o escritório pelo
    console, e a recuperação dos meses bloqueados cuida do período.

### Situação calculada

- **CP-RN-04 Relógio do painel.** "Agora" e "hoje" são de `America/Recife` (o `Clock` do `ClockConfig`). As datas e
  horas da resposta saem em Recife, com o fuso explícito (ISO 8601 com `-03:00`).
- **CP-RN-05 Canais.** Cada empresa tem três canais de captura, cada um com a própria situação:

  | Canal | Tem direito quando | Fonte dos dados |
  |---|---|---|
  | `SIEG` | Regra RN-01 da captura: assinatura vigente (a de `start_date` mais recente, a **mesma** regra do `FiscalXmlSyncScheduler#hasFiscalXmlImportEntitlement`) com `FISCAL_XML_IMPORT` + `SIEG_INTEGRATION` | `fiscal_xml_sync_control`, `capture_stage_status` |
  | `SEFAZ` | RN-19 da captura: `FISCAL_XML_IMPORT` + `SEFAZ_INTEGRATION` e UF coberta por `SEFAZ_DIST_UFS`. O certificado **não** entra no direito: sem certificado, a empresa tem direito e fica com erro (`CERT_MISSING`) | `sefaz_dist_control`, `company_certificates`, `capture_stage_status` |
  | `ADN` | `FISCAL_XML_IMPORT` + `NFSE_NATIONAL_INTEGRATION`. Certificado como na SEFAZ | `nfse_adn_control`, `company_certificates`, `capture_stage_status` |

  A regra de direito fica num ponto só (um método compartilhado com os agendadores ou uma consulta testada contra
  eles). O painel nunca pode dizer "fora do plano" para uma empresa que o agendador atende, nem o contrário.
- **CP-RN-06 Situação do canal SIEG.** Vale a **primeira** linha que se aplica:

  | # | Situação | Condição | Motivo mostrado |
  |---|---|---|---|
  | 1 | `BLOQUEADA` | O `RoutineGate` não libera o escritório (BLOCKED, SUSPENDED, CANCELED, PENDING_VERIFICATION) | "Escritório em somente leitura: capturas paradas" |
  | 2 | `FORA_DO_PLANO` | Sem direito (CP-RN-05) | "O plano vigente não tem importação de XML pelo SIEG" ou "Sem assinatura vigente" |
  | 3 | `NUNCA_TENTADA` | `last_attempt_at` nulo | "Ainda não foi atendida pela fila. Posição: {n}" |
  | 4 | `COM_ERRO` | Alguma etapa SIEG (CP-RN-10) com `last_outcome = FAILURE` | A mensagem do código da etapa mais recente com falha (CP-RN-11) |
  | 5 | `ATRASADA` | `verifiedAt` nulo ou mais antigo que `TAXIFY_CAPTURE_PANEL_SIEG_FRESH_HOURS` (padrão **24 h**, P-CP-03); **ou** `received_events_checked_on`, `issued_events_checked_on` ou `service_invoices_checked_on` antes de ontem | "Sem verificação completa há {X} h" / "Nunca verificada por completo" / "Eventos ou NFS-e sem varredura desde {data}" |
  | 6 | `PENDENTE` | Algum lado verificado **no mês corrente** com `importado < total SIEG` | "Faltam {total − importado} NF-e recebidas / emitidas" |
  | 7 | `EM_DIA` | Nenhuma das anteriores | — |

  - `verifiedAt` é o `FiscalXmlSyncControl#getVerifiedAt()` (a mais antiga das duas verificações).
  - O `total SIEG = 0` com `importado = 0`, verificado dentro da janela, é `EM_DIA`.
  - `importado > total SIEG` (notas que vieram da SEFAZ ou de upload e o SIEG não tem) não é pendência: é `EM_DIA` e a
    tela mostra os dois números (CP-RN-09).
  - Um lado cuja última verificação foi no mês anterior não conta para `PENDENTE`: os contadores dele são do mês
    anterior. A tela mostra o mês a que eles se referem.
- **CP-RN-07 Situação dos canais SEFAZ e ADN.**

  | # | Situação | Condição |
  |---|---|---|
  | 1 | `BLOQUEADA` | Escritório não liberado pelo `RoutineGate` |
  | 2 | `FORA_DO_PLANO` | Sem direito (CP-RN-05). Na SEFAZ, a UF fora de `SEFAZ_DIST_UFS` tem o motivo próprio "UF fora da captura SEFAZ automática" |
  | 3 | `COM_ERRO` | Sem certificado A1 da raiz do CNPJ no escritório (`CERT_MISSING`, calculado pelo painel), **ou** `last_status = ERRO` |
  | 4 | `NUNCA_TENTADA` | Sem linha de controle ou `last_run_at` nulo |
  | 5 | `ATRASADA` | `last_run_at` mais antigo que `TAXIFY_CAPTURE_PANEL_DIRECT_FRESH_HOURS` (padrão **3 h**, P-CP-03) |
  | 6 | `EM_DIA` | `last_status` OK ou SEM_MOVIMENTO, dentro da janela |

  - O código da falha vem de `capture_stage_status` (CP-RN-10). Linha com `last_status = ERRO` gravada **antes** do
    deploy, sem linha de etapa, mostra o código `LEGACY_ERROR`: "Erro registrado antes do painel. Veja a aba
    Certificado da empresa." O painel **nunca** mostra `last_detail`.
  - Certificado com vencimento em até 30 dias aparece como aviso ("vence em {dd/MM/aaaa}"), sem mudar a situação. O
    vencido já é `COM_ERRO` pelo `last_status` e já gera a pendência `CERTIFICATE_EXPIRED`.
- **CP-RN-08 Situação geral da empresa.**
  - `BLOQUEADA` se o escritório está bloqueado;
  - `FORA_DO_PLANO` se nenhum dos três canais tem direito;
  - senão, a **pior** situação entre os canais com direito, nesta ordem: `COM_ERRO` > `NUNCA_TENTADA` > `ATRASADA` >
    `PENDENTE` > `EM_DIA`.
  - A situação de cada canal também vai na resposta, para o filtro por canal (CP-RN-13).
  - Empresa inativa (`active = false`) aparece só com o filtro "incluir inativas" e não entra no resumo (P-CP-07).

### O que a tela mostra por empresa

- **CP-RN-09 Linha e detalhe.** Linha da tabela (um GET paginado). O detalhe abre ao clicar, com a memória de cada
  número:

  | Bloco | Campo | Origem | Memória mostrada |
  |---|---|---|---|
  | Empresa | Nome, situação geral e motivo; escritório (só plataforma); "inativa" | `companies`, CP-RN-08 | — |
  | Fila SIEG | Última tentativa; próxima tentativa estimada (CP-RN-12); pedido de "Sincronizar agora" em aberto | `last_attempt_at`, `capture_priority_requests` | "Posição {n} na fila; estimativa {dd/MM HH:mm}" |
  | Fila SIEG | Última verificação completa | `verifiedAt` | "A mais antiga entre recebidas ({hora}) e emitidas ({hora})" |
  | NF-e recebidas | Importadas / total SIEG, mês | `total_importado`, `total_sieg`, `data_emissao_inicio/fim`, `ultima_verificacao_em` | "{importado} notas no Taxify (qualquer origem) emitidas de {início} a {fim}; o SIEG informou {total} na verificação de {data hora}" |
  | NF-e emitidas | Idem | `issued_*` | Idem |
  | Eventos | Último dia varrido, por lado | `received_events_checked_on`, `issued_events_checked_on` | "Cancelamentos dos últimos 3 dias relidos em {data}" |
  | NFS-e SIEG | Último dia verificado | `service_invoices_checked_on` | — |
  | Última nota gravada | Por tipo e origem: NF-e SIEG, NF-e SEFAZ, NF-e upload, NFS-e SIEG, NFS-e ADN, NFS-e upload | `max(created_date)` por `(company_id, source_system)` em `fiscal_xml_documents` e `service_invoices`, só para as empresas da página | "Nunca" quando não há |
  | SEFAZ | Certificado (próprio ou da matriz), vencimento, última consulta, situação, próxima consulta permitida | `company_certificates.valid_until`, `sefaz_dist_control.last_run_at`, `last_status`, `next_query_at` | — |
  | ADN | Última consulta, situação, próxima consulta permitida | `nfse_adn_control` | — |
  | Falhas | Por etapa: última falha (quando, código, mensagem), se já se resolveu, falhas seguidas, aviso | `capture_stage_status` | "Falhou 3 vezes seguidas; último sucesso em {data hora}" |
  | Recuperação | Meses bloqueados ainda em recuperação | `recovery_tasks` com `company_id` e status aberto | "Recuperando 2026-10 e 2026-11" |

  - "Importado" é sempre rotulado como "no Taxify (qualquer origem)". É a mesma contagem do RN-09 da captura.

### Última falha (a parte nova de verdade)

- **CP-RN-10 Resultado por etapa.** Cada etapa grava o resultado **ao terminar**, numa linha por
  `(company_id, stage)` em `capture_stage_status`:

  | Etapa (`stage`) | Onde |
  |---|---|
  | `SIEG_RECEIVED` | `syncSide` RECEIVED (contar e baixar) |
  | `SIEG_ISSUED` | `syncSide` ISSUED |
  | `SIEG_EVENTS_RECEIVED`, `SIEG_EVENTS_ISSUED` | `refreshEventsOncePerDay` (só quando roda, uma vez por dia) |
  | `SIEG_NFSE` | `syncServiceInvoicesOncePerDay` (só quando roda) |
  | `SIEG_RECOVERY` | `recoverBlockedMonths` (só quando há passo a fazer) |
  | `SEFAZ_DIST` | `SefazDistService.capture`, inclusive as pré-condições (RN-21 da captura) |
  | `NFSE_ADN` | `NfseAdnService.capture` |

  - Sucesso: `last_outcome = SUCCESS`, `last_outcome_at = last_success_at = agora`, `consecutive_failures = 0`. O
    `failure_code` e o `last_failure_at` anteriores **ficam** (a tela mostra "última falha em … (resolvida)").
  - Falha: `last_outcome = FAILURE`, `last_outcome_at = last_failure_at = agora`, `failure_code`, `provider_status`
    e `consecutive_failures + 1`.
  - Aviso (a etapa terminou, mas com ressalva): `last_outcome = SUCCESS` com `warning_code` e `warning_count`. O aviso é
    apagado no próximo sucesso sem ressalva.
  - Etapa pulada sem chamada (lado em dia, eventos já varridos hoje) **não grava nada**.
  - A gravação acontece numa transação própria (`REQUIRES_NEW`). Falha ao gravar o resultado só gera log e nunca
    derruba a captura nem desfaz notas gravadas.
  - **Sem mudança de comportamento da captura:** a falha de um lado continua abortando o resto da empresa naquela
    tentativa. As etapas que não rodaram não gravam nada.
- **CP-RN-11 Código seguro.** O `failure_code` sai de uma lista fechada. Ele é escolhido pelo **tipo** do erro (o código
  da `SiegIntegrationException`, o status da `FeignException` ou da resposta, a classe da causa) e **nunca** pelo texto
  da mensagem:
  - `provider_status` só aceita dígitos, no máximo 4 (status HTTP ou `cStat`). Qualquer outra coisa é descartada;
  - nada de corpo de resposta, `xMotivo`, mensagem de exceção, CNPJ, chave de acesso, token ou chave de API em coluna,
    resposta da API ou log desta feature;
  - a mensagem mostrada vem do i18n do backend (`capture.failure.{código}`), com no máximo `{status}`, `{count}` ou uma
    data vinda de outra tabela.

  | Código | Tipo | Quando | Mensagem (pt) |
  |---|---|---|---|
  | `SIEG_API_KEY_MISSING` | falha | `error.sieg.api.key.missing` | "O escritório não tem a chave da API do SIEG cadastrada em Integrações." |
  | `SIEG_AUTH` | falha | HTTP 401 ou 403 | "O SIEG recusou a chave da API do escritório (HTTP {status}). Confira a chave em Integrações." |
  | `SIEG_RATE_LIMITED` | falha | HTTP 429 | "O SIEG recusou por excesso de requisições. A próxima volta tenta de novo." |
  | `SIEG_REJECTED` | falha | Outro 4xx (menos o 404 de página vazia, que não é erro) | "O SIEG recusou a consulta (HTTP {status})." |
  | `SIEG_UNAVAILABLE` | falha | 5xx | "O SIEG respondeu com erro (HTTP {status}). A próxima volta tenta de novo." |
  | `SIEG_TIMEOUT` | falha | Tempo de conexão ou de leitura esgotado | "O SIEG não respondeu a tempo." |
  | `SIEG_CONNECTION` | falha | Falha de rede (conexão recusada, DNS, TLS) | "Não foi possível conectar ao SIEG." |
  | `SIEG_INVALID_RESPONSE` | falha | `success = false`, corpo vazio ou pacote que não abre | "O SIEG devolveu uma resposta que o Taxify não conseguiu ler." |
  | `SIEG_COUNT_FAILED` | aviso | `contar-xmls` falhou e o download seguiu (RN-07 da captura) | "A contagem do SIEG falhou. As notas foram baixadas, mas o total do SIEG não foi atualizado." |
  | `XML_PARSE_ERRORS` | aviso | `parseErrors > 0` no resultado | "{count} XML(s) não puderam ser lidos nesta volta." |
  | `XML_STORAGE_FAILED` | falha | `error.sieg.xml.storage.failed` | "Falha ao guardar o XML no armazenamento do Taxify." |
  | `COMPANY_WITHOUT_CNPJ` | falha | Empresa sem CNPJ | "A empresa não tem CNPJ cadastrado." |
  | `COMPANY_WITHOUT_UF` | falha | SEFAZ: sem UF | "A UF da empresa não está cadastrada." |
  | `CERT_MISSING` | falha | Sem A1 da raiz do CNPJ (calculado pelo painel ou pela pré-condição) | "Não há certificado A1 desta empresa ou da matriz." |
  | `CERT_EXPIRED` | falha | Certificado vencido | "O certificado A1 venceu em {dd/MM/aaaa}." (data lida de `company_certificates`) |
  | `CERT_FILE_MISSING` | falha | Arquivo não encontrado | "O arquivo do certificado não foi encontrado. Envie o certificado de novo." |
  | `CERT_INVALID` | falha | Certificado não abre | "O certificado A1 não pôde ser aberto. Envie o certificado de novo." |
  | `SEFAZ_REJECTED` | falha | `cStat` diferente de 137, 138 e 656 | "A SEFAZ recusou a consulta (cStat {status})." |
  | `SEFAZ_CONSUMO_INDEVIDO` | aviso | `cStat` 656 (RN-24 da captura) | "A SEFAZ pediu para esperar 1 hora antes de consultar de novo." |
  | `SEFAZ_TIMEOUT` / `SEFAZ_CONNECTION` | falha | Tempo esgotado / falha de rede | "A SEFAZ não respondeu a tempo." / "Não foi possível conectar à SEFAZ." |
  | `ADN_REJECTED` | falha | Erro HTTP do ADN | "O Ambiente Nacional da NFS-e recusou a consulta (HTTP {status})." |
  | `ADN_TIMEOUT` / `ADN_CONNECTION` | falha | Tempo esgotado / falha de rede | "O Ambiente Nacional não respondeu a tempo." / "Não foi possível conectar ao Ambiente Nacional." |
  | `INTERNAL_ERROR` | falha | Qualquer outro erro | "Erro interno do Taxify nesta etapa. O suporte da CS Hub vê o detalhe no log." |
  | `LEGACY_ERROR` | falha | Só leitura: SEFAZ ou ADN com `ERRO` gravado antes do deploy (CP-RN-07) | "Erro registrado antes do painel. Veja a aba Certificado da empresa." |

  - Para separar os códigos HTTP, a `SiegIntegrationException` passa a carregar o status HTTP da resposta (hoje ela é
    lançada sem ele em `SiegClient.java:77`).
  - O log técnico de cada falha continua existindo, com o id da empresa e o código. A mensagem e a pilha da exceção
    continuam no log como hoje (ver P-CP-09 sobre o corpo da resposta do SIEG que vai para o log).

### Resumo e fila

- **CP-RN-12 Resumo e estimativas.** No topo, calculado sobre o escopo (escritório, carteira ou plataforma, com os
  filtros de escritório e "incluir inativas", sem os de situação):
  - **contagem por situação geral:** em dia, pendente, atrasada, com erro, nunca tentada, fora do plano e bloqueada.
    Cada cartão aplica o filtro da situação ao clicar;
  - **última empresa atendida:** o maior `last_attempt_at` do escopo;
  - **saúde da fila SIEG (plataforma inteira):** "rodando" se o maior `last_attempt_at` entre as empresas com direito,
    em todos os escritórios, tem menos de `TAXIFY_CAPTURE_PANEL_STALLED_MINUTES` (padrão **30**); senão "parada desde
    {hora}". Com `FISCAL_XML_SYNC_ENABLED=false`: "captura SIEG desligada neste ambiente". O ADMIN vê só o estado
    (rodando, parada ou desligada), sem números de outros escritórios;
  - **volta observada:** `agora − menor last_attempt_at` entre as empresas com direito da fila (é a idade da primeira da
    fila, que é o tempo que ela levou para voltar). Se houver empresa com direito e `last_attempt_at` nulo, mostra
    "volta incompleta: {n} empresas nunca tentadas";
  - **volta teórica mínima:** `ceil(N / vagas_regulares) × intervalo`, em que:
    - `N` é o total de empresas com direito na fila (plataforma inteira);
    - `vagas_regulares = batch-size − vagas de prioridade em uso` (CP-RN-15);
    - `intervalo` é a diferença entre as duas próximas execuções de `FISCAL_XML_SYNC_CRON` (`CronExpression` do Spring).
    - Rótulo: "pelo menos {X} h. Cada empresa gasta de 2 a 8 chamadas, e o SIEG aceita 2 por minuto, então uma rodada
      pode passar do intervalo";
  - **próxima tentativa estimada** de cada empresa: `próxima execução do cron + floor(posição / vagas_regulares) ×
    intervalo`, em que `posição` é o índice (começando em 0) da empresa na ordem da fila (`queueOrder()`, a mesma do
    agendador). Rótulo "estimativa". A plataforma vê a posição; o ADMIN vê a posição e a data estimada.

### Filtros, ordem e paginação

- **CP-RN-13 Filtros.** Todos opcionais e combináveis:
  - `situacao` (uma ou mais): aplicada à situação geral; com `canal` informado, à situação daquele canal;
  - `canal`: `SIEG`, `SEFAZ` ou `ADN`;
  - `tipo`: `NFE_RECEBIDAS`, `NFE_EMITIDAS`, `EVENTOS`, `NFSE`. Mostra só as empresas cujo item daquele tipo está
    pendente, atrasado ou com erro (NF-e recebidas: `SIEG_RECEIVED` com falha ou o lado pendente ou atrasado; eventos:
    as duas etapas de eventos; NFS-e: `SIEG_NFSE` ou o canal ADN);
  - `busca`: parte do nome da empresa, sem diferenciar maiúsculas e acentos;
  - `incluirInativas` (padrão `false`);
  - `firmId` (só na plataforma).
  - Os filtros ficam na URL do front (`?situacao=COM_ERRO&canal=SIEG`) para o link poder ser compartilhado.
- **CP-RN-14 Ordem e paginação.**
  - Ordem padrão: situação geral (pior primeiro, na ordem da CP-RN-08) e, dentro dela, última tentativa mais antiga
    primeiro. Também por nome e por próxima tentativa.
  - `page` começa em 0, `size` com padrão 50 e máximo `TAXIFY_CAPTURE_PANEL_MAX_PAGE_SIZE` (padrão **100**). Acima
    disso: 400 `error.generic.bad.request`.
  - **Custo fixo, sem N+1.** A requisição faz um número fixo de consultas, que não cresce com o número de empresas:
    1. **uma** consulta agregada de projeção (sem entidade) para o escopo: empresa, escritório, `active`, as colunas de
       `fiscal_xml_sync_control`, o direito por canal (subconsultas da assinatura vigente e de `plan_services`), a
       existência de certificado da raiz do CNPJ, as colunas de `sefaz_dist_control` e de `nfse_adn_control` e o
       resultado das etapas (`capture_stage_status`, uma linha por empresa com as colunas por etapa ou um `group by`);
    2. a situação de acesso de cada escritório do escopo, calculada uma vez por escritório (o mesmo `RoutineGate`);
    3. a ordem da fila da plataforma, numa consulta leve (`id`, `last_attempt_at` e as duas verificações), só das
       empresas com direito;
    4. **só para as empresas da página:** última nota por origem (`group by company_id, source_system` com
       `company_id in (…)`, uma consulta em `fiscal_xml_documents` e uma em `service_invoices`), pedidos de prioridade
       abertos e recuperações abertas.
  - A situação, o resumo, o filtro, a ordem e o corte da página são feitos em memória sobre a projeção do passo 1. Isso
    atende a alguns milhares de empresas, como o console da fase 1 (ver Riscos). Meta: p95 abaixo de 2 s com 5.000
    empresas em 50 escritórios (Q-CP-2).
  - Índices novos para o passo 4: `fiscal_xml_documents (company_id, source_system, created_date)` e
    `service_invoices (company_id, source_system, created_date)`.

### "Sincronizar agora"

- **CP-RN-15 O que faz.** Coloca a empresa **no início da fila SIEG**. A captura **não** roda na requisição: hoje
  `POST /integrations/xmls/sincronizar` segura a requisição HTTP enquanto o limitador espera (minutos), e isso não se
  repete aqui.
  - Resposta 202 `{requestId, status: "PENDING", position, estimatedAt}`.
  - No agendador, a cada rodada, as primeiras `TAXIFY_CAPTURE_PRIORITY_SLOTS_PER_TICK` vagas do lote (padrão **1**) vão
    para os pedidos em aberto, do mais antigo para o mais novo. As outras vagas seguem a fila normal. As vagas de
    prioridade são no máximo `batch-size − 1`, para a fila normal nunca parar. Com `batch-size = 1`, a prioridade se
    alterna com a fila normal: uma rodada para o pedido, a seguinte para a fila.
  - A reserva da empresa continua atômica (`reserve`), e o pedido passa a `RESERVED` na mesma hora.
  - **Na tentativa prioritária, o "lado em dia não gasta requisição" (RN-05 da captura) não vale:** os dois lados
    contam de novo no SIEG. Sem isso, o botão não faria nada justamente quando o usuário sabe que chegou nota nova
    depois da última verificação. Eventos, NFS-e e recuperação seguem a regra diária de sempre.
  - Ao terminar, o pedido fica `SERVED` com `served_at`, e a empresa vai para o fim da fila normal (o mesmo
    `last_attempt_at` de sempre).
  - Só o canal SIEG. A SEFAZ já tem o "Capturar agora" na aba Certificado (RN-32 da captura), e o detalhe do painel
    leva para lá. O ADN não tem captura manual nesta spec.
- **CP-RN-16 Quem pode.**
  - **ADMIN do escritório:** `POST /capture-panel/companies/{companyId}/sync-now`, `@AdminOnly`, só empresa do próprio
    escritório (senão 404);
  - **admin da plataforma:** `POST /platform/capture-panel/companies/{companyId}/sync-now`, `@PlatformAdminOnly`,
    qualquer empresa;
  - **analista com `INVOICES_SYNC`:** não, na v1 (P-CP-02). O botão não aparece e a rota do escritório responde 403.
- **CP-RN-17 Quando é recusado.** As verificações seguem esta ordem, e a primeira que falha responde:

  | # | Situação | Resposta |
  |---|---|---|
  | 1 | Escritório bloqueado, rota do escritório | 403 `error.firm.access.blocked` (filtro da F1-RN-08) |
  | 2 | Escritório bloqueado, rota da plataforma | 409 `error.capture.priority.firm.blocked` |
  | 3 | Captura SIEG desligada (`FISCAL_XML_SYNC_ENABLED=false`) ou `TAXIFY_CAPTURE_PRIORITY_ENABLED=false` | 503 `error.capture.priority.unavailable` |
  | 4 | Empresa sem direito ao canal SIEG (CP-RN-05) | 409 `error.capture.priority.not.in.plan` |
  | 5 | Já existe pedido `PENDING` ou `RESERVED` para a empresa | **202** com o mesmo pedido (idempotente: nada muda e nada é contado) |
  | 6 | Pedido atendido há menos de `TAXIFY_CAPTURE_PRIORITY_COOLDOWN_MINUTES` (padrão **60**) | 429 `error.capture.priority.cooldown`, com `details.retryAt` |
  | 7 | O escritório já tem `TAXIFY_CAPTURE_PRIORITY_MAX_OPEN_PER_FIRM` pedidos em aberto (padrão **5**) | 429 `error.capture.priority.firm.open.limit`, com `details.limit` |
  | 8 | O escritório já fez `TAXIFY_CAPTURE_PRIORITY_PER_FIRM_PER_DAY` pedidos no dia civil de Recife (padrão **10**) | 429 `error.capture.priority.firm.daily.limit`, com `details.limit` e `details.retryAt` (00:00 do dia seguinte) |

  - Os limites 6 a 8 valem também para o admin da plataforma, contados no escritório da empresa (P-CP-05).
  - A concorrência (dois cliques ao mesmo tempo) é resolvida por chave única: a coluna `open_key` vale o `company_id`
    enquanto o pedido está aberto e nulo depois. O perdedor do `INSERT` lê o pedido aberto e responde 202 com ele.
- **CP-RN-18 Pedido que não é atendido.**
  - Escritório bloqueado ou empresa que perdeu o direito antes de ser atendida: na rodada seguinte, o pedido vira
    `CANCELED`. Não conta como atendido, e o cooldown não se aplica.
  - Pedido aberto há mais de `TAXIFY_CAPTURE_PRIORITY_EXPIRE_HOURS` (padrão **24**): vira `EXPIRED`.
  - A empresa reservada pela fila normal enquanto tem pedido aberto: o pedido é considerado atendido por essa tentativa
    (`SERVED`), e a contagem forçada (CP-RN-15) vale para ela.
- **CP-RN-19 Trilha.** Log `capture.priority.requested company={id} firm={id} by={userId} scope={firm|platform}` e
  `capture.priority.served company={id} request={id} outcome={SERVED|CANCELED|EXPIRED}`, sem nome nem CNPJ. O pedido
  guarda `requested_by` (id do usuário). A tela mostra "Pedido por {nome do usuário} às {HH:mm}" só para quem é do
  mesmo escritório. Na visão do escritório, um pedido da plataforma aparece como "Pedido pela CS Hub".
- **CP-RN-20 Limpeza.** Os pedidos com `requested_at` de mais de 90 dias são apagados pelo agendador de limpeza que
  já existe da captura (`FiscalXmlPurgeScheduler`) ou por um diário próprio. `capture_stage_status` não é apagado: é
  uma linha por empresa e etapa, sem dado sensível.

### Ajuste necessário no relógio

- **CP-RN-21 Datas da verificação em Recife.** `ultima_verificacao_em` e `issued_checked_at` passam a ser gravados com
  o `Clock` de Recife, como já é o `last_attempt_at`. Sem isso, com a JVM em UTC (R-07 da arquitetura), a verificação
  apareceria 3 h depois da tentativa e a CP-RN-06 erraria a janela. As linhas antigas se corrigem sozinhas na próxima
  verificação de cada empresa. O "hoje" da janela do mês (`LocalDate.now()` do agendador) **não** muda nesta spec: ele
  está ligado à pergunta 2 da spec de captura (fuso da janela), que é do Tony (P-CP-08).

---

## 6. Critérios de aceite

Cenário base (fictício). Hoje é sexta-feira, 2026-11-27, 10:00 em Recife. Produção com `cron 0 0/5 * * * *` e
`batch-size 2`.
- Escritório A ("Escritório Exemplo", ACTIVE): ADMIN `carla.admin`; `ana.fiscal` (FISCAL, carteira X e Y); `davi.gestao`
  (MANAGEMENT).
- Empresas do escritório A, todas com SIEG no plano:
  - X "Têxtil Exemplo Ltda" `/0001`;
  - Y "Confecção Modelo ME" `/0001`;
  - Z "Comércio Teste Ltda" `/0001`;
  - Z2 "Comércio Teste Ltda" `/0002`, filial.
- Escritório B ("Escritório Beta", BLOCKED desde 2026-11-20): empresa W.
- Admin da plataforma: `csadmin`.

- **CA-CP-01 (CP-RN-01)** Dado o cenário base, quando a Carla abre o painel, então ela vê X, Y, Z e Z2, e nenhuma
  empresa do escritório B. Quando a Ana abre, então ela vê só X e Y, sem o botão "Sincronizar agora". Quando o Davi
  chama `GET /capture-panel`, então a resposta é 403.
- **CA-CP-02 (CP-RN-02)** Dado o `csadmin`, quando ele abre `/plataforma/captura`, então vê as empresas dos escritórios A e B
  com o nome do escritório, o nome da empresa e o estabelecimento (`/0001`, `/0002`), sem CNPJ completo. O log
  registra `capture.panel.viewed scope=platform`. Quando a Carla chama `GET /platform/capture-panel`, então a resposta
  é 403.
- **CA-CP-03 (CP-RN-06)** Dado X com recebidas 40/40 e emitidas 12/12 verificadas hoje às 08:10, eventos e NFS-e
  varridos hoje e nenhuma falha, quando o painel abre, então X está `EM_DIA`.
- **CA-CP-04 (CP-RN-06)** Dado Y com recebidas 180 importadas de 200 informadas pelo SIEG, verificadas hoje às 09:00,
  quando o painel abre, então Y está `PENDENTE` com "Faltam 20 NF-e recebidas", e o detalhe mostra "180 notas no Taxify
  (qualquer origem) emitidas de 01/11/2026 a 27/11/2026; o SIEG informou 200 na verificação de 27/11/2026 09:00".
- **CA-CP-05 (CP-RN-06)** Dado Z com verificação completa em 26/11 08:00 (26 h atrás), quando o painel abre, então Z
  está `ATRASADA` com "Sem verificação completa há 26 h".
- **CA-CP-06 (CP-RN-06, CP-RN-12)** Dado a situação do bug de 2026-11: 300 empresas com direito, 282 com `last_attempt_at`
  nulo e 18 atendidas nas últimas 2 h, quando o `csadmin` abre o painel, então o resumo mostra 282 "nunca tentadas" e
  "volta incompleta: 282 empresas nunca tentadas".
- **CA-CP-07 (CP-RN-10, CP-RN-11)** Dado que o SIEG responde HTTP 503 em `baixar-xmls` das recebidas de X às 09:40,
  quando o painel abre, então X está `COM_ERRO` na etapa `SIEG_RECEIVED`, com "O SIEG respondeu com erro (HTTP 503). A
  próxima volta tenta de novo.", e `consecutive_failures = 1`. Quando a tentativa seguinte dá certo, então X sai de
  `COM_ERRO`, e o detalhe mostra "última falha em 27/11 09:40 (resolvida)".
- **CA-CP-08 (CP-RN-11)** Dado que o SIEG responde HTTP 400 com um corpo que contém um CNPJ e um token fictícios,
  quando a falha é gravada, então `capture_stage_status` tem `failure_code = SIEG_REJECTED` e
  `provider_status = 400`, e nem o banco, nem a resposta do painel, nem o log `capture.*` contêm o CNPJ ou o token.
- **CA-CP-09 (CP-RN-07)** Dado X com SEFAZ no plano e nenhum certificado da raiz no escritório, quando o painel abre,
  então o canal SEFAZ de X está `COM_ERRO` com `CERT_MISSING`. Dado um certificado vencido em 20/11/2026 e
  `last_status = ERRO`, então o canal mostra "O certificado A1 venceu em 20/11/2026."
- **CA-CP-10 (CP-RN-07)** Dado uma linha de `sefaz_dist_control` com `last_status = ERRO` gravada antes do deploy e sem
  linha de etapa, quando o painel abre, então o canal mostra `LEGACY_ERROR`, e o `last_detail` não aparece em lugar
  nenhum da resposta.
- **CA-CP-11 (CP-RN-08)** Dado X com SIEG `EM_DIA`, SEFAZ `COM_ERRO` e ADN fora do plano, quando o painel abre, então a
  situação geral de X é `COM_ERRO`. Com o filtro `canal=SIEG&situacao=EM_DIA`, então X aparece.
- **CA-CP-12 (CP-RN-03)** Dado o escritório B bloqueado, quando o `csadmin` abre o painel, então W está `BLOQUEADA`.
  Quando o ADMIN do B abre o painel, então ele vê W como `BLOQUEADA`, com a faixa de somente leitura, e o
  "Sincronizar agora" responde 403 `error.firm.access.blocked`. Quando o `csadmin` pede "Sincronizar agora" para W,
  então a resposta é 409 `error.capture.priority.firm.blocked`.
- **CA-CP-13 (CP-RN-15)** Dado Z na posição 150 da fila, quando a Carla pede "Sincronizar agora" às 10:01, então a
  resposta é 202 com `position = 0` e `estimatedAt = 10:05`. Na rodada das 10:05, Z é atendida numa das 2 vagas, e a
  outra vaga vai para a primeira da fila normal.
- **CA-CP-14 (CP-RN-15)** Dado X com recebidas 40/40 verificadas hoje às 08:10, quando a Carla pede "Sincronizar agora",
  então na rodada seguinte o `contar-xmls` das recebidas é chamado mesmo assim. Se o SIEG informar 41, a nota nova é
  baixada.
- **CA-CP-15 (CP-RN-17)** Dado um pedido de Z ainda `PENDING`, quando a Carla clica de novo, então a resposta é 202 com
  o mesmo `requestId`, e o contador do dia não sobe. Dado o pedido de Z atendido às 10:05, quando ela pede de novo às
  10:30, então a resposta é 429 `error.capture.priority.cooldown` com `retryAt = 11:05`.
- **CA-CP-16 (CP-RN-17)** Dado que o escritório A já fez 10 pedidos hoje, quando a Carla faz o 11º, então a resposta é
  429 `error.capture.priority.firm.daily.limit` com `limit = 10`.
- **CA-CP-17 (CP-RN-17)** Dado uma empresa do escritório A sem SIEG no plano, quando a Carla pede "Sincronizar agora",
  então a resposta é 409 `error.capture.priority.not.in.plan`.
- **CA-CP-18 (CP-RN-17)** Dado dois `POST sync-now` de Z ao mesmo tempo, quando os dois são processados, então existe
  um só pedido aberto, e as duas respostas são 202 com o mesmo `requestId`.
- **CA-CP-19 (CP-RN-18)** Dado um pedido de Z feito às 10:01 e o escritório A bloqueado às 10:03, quando a rodada das
  10:05 roda, então o pedido fica `CANCELED`, e Z não é capturada.
- **CA-CP-20 (CP-RN-12)** Dado o maior `last_attempt_at` da plataforma às 09:20, quando o painel abre às 10:00, então a
  saúde da fila mostra "parada desde 09:20". Dado `FISCAL_XML_SYNC_ENABLED=false`, então mostra "captura SIEG
  desligada neste ambiente".
- **CA-CP-21 (CP-RN-14)** Dado 5.000 empresas em 50 escritórios (fictícias), quando o `csadmin` pede a página 0 com
  `size = 50`, então o número de consultas SQL da requisição é o mesmo que com 50 empresas, e o p95 fica abaixo de 2 s.
  Dado `size = 500`, então a resposta é 400.
- **CA-CP-22 (CP-RN-09)** Dado X com a última NF-e do SIEG gravada em 27/11 08:11, a última da SEFAZ em 26/11 15:15 e
  nenhuma NFS-e do ADN, quando o detalhe abre, então ele mostra as duas datas e "Nunca" para NFS-e ADN.
- **CA-CP-23 (CP-RN-10)** Dado o `contar-xmls` falhando e o `baixar-xmls` dando certo, quando a etapa termina, então
  ela fica `SUCCESS` com aviso `SIEG_COUNT_FAILED`, e a situação segue os contadores (o total do SIEG não muda).
- **CA-CP-24 (CP-RN-10)** Dado uma falha ao gravar `capture_stage_status` (banco indisponível só para essa tabela, em
  teste), quando a etapa termina, então as notas baixadas continuam gravadas, e a captura segue para a próxima empresa.
- **CA-CP-25 (CP-RN-05)** Dado qualquer empresa, quando o painel diz que ela tem direito ao canal SIEG, então o
  agendador também a considera elegível, e vice-versa (teste com o mesmo conjunto de assinaturas, inclusive duas
  vigentes sobrepostas).

## 7. Casos de teste (dados fictícios; hoje = 2026-11-27 10:00 Recife)

| # | Cenário | Entrada | Esperado | RN |
|---|---|---|---|---|
| CT-CP-01 | Em dia | X: recebidas 40/40 e emitidas 12/12 às 08:10; eventos R/E e NFS-e em 27/11 | SIEG `EM_DIA`; geral `EM_DIA` | CP-RN-06 |
| CT-CP-02 | Pendente | Y: recebidas `total_sieg = 200`, `total_importado = 180`, 09:00 | `PENDENTE`, "Faltam 20 NF-e recebidas" (200 − 180 = 20) | CP-RN-06 |
| CT-CP-03 | Importado maior que o SIEG | `total_sieg = 30`, `total_importado = 35` (5 vieram da SEFAZ) | `EM_DIA`; tela "35 no Taxify (qualquer origem) / 30 no SIEG" | CP-RN-06, 09 |
| CT-CP-04 | Mês sem nota | `total_sieg = 0`, `total_importado = 0`, verificados às 09:30 | `EM_DIA` | CP-RN-06 |
| CT-CP-05 | Janela de 24 h | `verifiedAt` = 26/11 10:30 (23 h 30) / 26/11 09:30 (24 h 30) | `EM_DIA` / `ATRASADA` "há 24 h" | CP-RN-06 |
| CT-CP-06 | Nunca verificada por completo | `issued_checked_at` nulo, `last_attempt_at` 27/11 09:00 | `ATRASADA` "Nunca verificada por completo" | CP-RN-06 |
| CT-CP-07 | Eventos parados | Tudo em dia, `issued_events_checked_on = 25/11` | `ATRASADA` "Eventos ou NFS-e sem varredura desde 25/11/2026" | CP-RN-06 |
| CT-CP-08 | Virada do mês | Em 01/12 02:00: recebidas 180/200 verificadas em 30/11 23:00 | Não é `PENDENTE` (contadores de nov/2026, mostrados com o mês); `EM_DIA` pela janela de 24 h | CP-RN-06 |
| CT-CP-09 | Nunca tentada | `last_attempt_at` nulo; posição 37 | `NUNCA_TENTADA` "Posição: 38" (posição 37 contada a partir de 0) | CP-RN-06, 12 |
| CT-CP-10 | Falha 5xx e recuperação | `SIEG_RECEIVED` FAILURE `SIEG_UNAVAILABLE` 503 às 09:40; depois SUCCESS às 22:00 | `COM_ERRO`, depois sai; `consecutive_failures` 1 → 0; `last_failure_at` mantido | CP-RN-10 |
| CT-CP-11 | Falhas seguidas | 3 falhas `SIEG_TIMEOUT` em voltas seguidas | `consecutive_failures = 3`; "Falhou 3 vezes seguidas; último sucesso em 24/11 14:05" | CP-RN-10 |
| CT-CP-12 | Sem chave do SIEG | Escritório sem `FirmIntegration` SIEG; 4 empresas | As 4 `COM_ERRO` `SIEG_API_KEY_MISSING`; resumo "com erro: 4" | CP-RN-11 |
| CT-CP-13 | 401 / 429 / 404 vazio | Respostas HTTP 401, 429 e 404 "Nenhum arquivo XML localizado" | `SIEG_AUTH` 401; `SIEG_RATE_LIMITED`; o 404 não é falha (SUCCESS) | CP-RN-11 |
| CT-CP-14 | Corpo sensível | HTTP 400 com corpo contendo `12345678000190` e `token=abc123` (fictícios) | `SIEG_REJECTED`, `provider_status = 400`; nenhuma ocorrência de `12345678000190` nem `abc123` no banco, na resposta ou no log `capture.*` | CP-RN-11 |
| CT-CP-15 | `provider_status` inválido | Status recebido "4O0" ou "12345" | Gravado nulo | CP-RN-11 |
| CT-CP-16 | Aviso de contagem | `contar-xmls` lança erro; `baixar-xmls` traz 50 | SUCCESS com `warning_code = SIEG_COUNT_FAILED`; `total_sieg` inalterado | CP-RN-10, 11 |
| CT-CP-17 | Aviso de parse | `parseErrors = 2` | SUCCESS com `XML_PARSE_ERRORS`, `warning_count = 2`, "2 XML(s) não puderam ser lidos" | CP-RN-11 |
| CT-CP-18 | Lado em dia não grava | Recebidas 40/40 hoje (pulado) | Nenhuma escrita em `SIEG_RECEIVED` | CP-RN-10 |
| CT-CP-19 | Falha aborta o resto | `SIEG_RECEIVED` falha | `SIEG_ISSUED`, eventos e NFS-e sem escrita nessa tentativa (como hoje) | CP-RN-10 |
| CT-CP-20 | SEFAZ sem certificado | SEFAZ no plano, UF PE coberta, sem A1 da raiz | SEFAZ `COM_ERRO` `CERT_MISSING` | CP-RN-07 |
| CT-CP-21 | SEFAZ com certificado da matriz | Filial Z2 sem A1 e matriz Z com A1 válido até 15/03/2027 | Certificado "da matriz", vencimento 15/03/2027 | CP-RN-07, 09 |
| CT-CP-22 | Certificado perto do fim | `valid_until = 20/12/2026` (23 dias) | Aviso "vence em 20/12/2026"; a situação não muda | CP-RN-07 |
| CT-CP-23 | SEFAZ atrasada | `last_run_at = 27/11 06:15` (3 h 45) | SEFAZ `ATRASADA` | CP-RN-07 |
| CT-CP-24 | SEFAZ 656 | `cStat` 656 | SUCCESS com aviso `SEFAZ_CONSUMO_INDEVIDO`; não é `COM_ERRO` | CP-RN-11 |
| CT-CP-25 | SEFAZ rejeição | `cStat` 593 (qualquer um diferente de 137, 138 e 656) | `SEFAZ_REJECTED`, `provider_status = 593`; `xMotivo` não aparece | CP-RN-11 |
| CT-CP-26 | Erro antigo | `last_status = ERRO` antes do deploy, sem etapa | `LEGACY_ERROR` | CP-RN-07 |
| CT-CP-27 | UF fora | `SEFAZ_DIST_UFS=PE`, empresa da PB | SEFAZ `FORA_DO_PLANO` "UF fora da captura SEFAZ automática" | CP-RN-07 |
| CT-CP-28 | Situação geral | SIEG `PENDENTE`, SEFAZ `ATRASADA`, ADN sem direito | Geral `ATRASADA` | CP-RN-08 |
| CT-CP-29 | Sem nenhum canal | Plano sem `FISCAL_XML_IMPORT` | Geral `FORA_DO_PLANO` | CP-RN-08 |
| CT-CP-30 | Bloqueado | Escritório B BLOCKED | W `BLOQUEADA` nos 3 canais e no geral | CP-RN-03, 06, 07 |
| CT-CP-31 | Inativa | Y `active = false` | Fora da lista e do resumo; aparece com `incluirInativas=true` | CP-RN-08 |
| CT-CP-32 | Volta teórica | N = 300, `batch-size` 2, sem prioridade, intervalo 5 min | `ceil(300 / 2) × 5 = 150 × 5 = 750 min` = "pelo menos 12 h 30 min" | CP-RN-12 |
| CT-CP-33 | Volta teórica com prioridade | N = 300, 1 vaga de prioridade em uso | `ceil(300 / 1) × 5 = 1.500 min` = "pelo menos 25 h" | CP-RN-12 |
| CT-CP-34 | Volta observada | Menor `last_attempt_at` com direito = 26/11 16:00 | "18 h" (27/11 10:00 − 26/11 16:00) | CP-RN-12 |
| CT-CP-35 | Próxima tentativa | Empresa na posição 41 (contada a partir de 0), 2 vagas regulares, próxima execução 10:05 | `10:05 + floor(41 / 2) × 5 min = 10:05 + 100 min = 11:45` | CP-RN-12 |
| CT-CP-36 | Fila parada | Maior `last_attempt_at` da plataforma = 09:20; limite 30 min | "parada desde 09:20" | CP-RN-12 |
| CT-CP-37 | Fila rodando | Maior `last_attempt_at` = 09:55 | "rodando" | CP-RN-12 |
| CT-CP-38 | Escopo do FISCAL | Ana, carteira X e Y | 2 empresas; resumo só delas; `sync-now` 403 | CP-RN-01, 16 |
| CT-CP-39 | Sem escritório no contexto | Token de usuário excluído (antes do SN-RN-11) | 403; nunca "todas as empresas" | CP-RN-01 |
| CT-CP-40 | Outro escritório | Carla: `POST /capture-panel/companies/{W}/sync-now` | 404 | CP-RN-01, 16 |
| CT-CP-41 | Sincronizar agora | Z na posição 150, pedido às 10:01 | 202 `position = 0`, `estimatedAt = 10:05`; atendida às 10:05; pedido `SERVED` | CP-RN-15 |
| CT-CP-42 | Contagem forçada | X 40/40 hoje; pedido; SIEG informa 41 | `contar-xmls` chamado; 1 nota nova; 41/41 | CP-RN-15 |
| CT-CP-43 | Idempotência | Dois cliques com o pedido `PENDING` | Mesmo `requestId`; contador do dia = 1 | CP-RN-17 |
| CT-CP-44 | Concorrência | Dois POST simultâneos | Um pedido aberto (chave `open_key`); duas respostas 202 iguais | CP-RN-17 |
| CT-CP-45 | Cooldown | Atendido às 10:05; novo pedido às 10:30 / às 11:06 | 429 `retryAt = 11:05` / 202 | CP-RN-17 |
| CT-CP-46 | Teto diário | 10 pedidos no dia; 11º às 16:00 | 429 `error.capture.priority.firm.daily.limit`, `limit = 10`, `retryAt = 28/11 00:00` | CP-RN-17 |
| CT-CP-47 | Teto de abertos | 5 pedidos abertos de empresas diferentes; 6º | 429 `error.capture.priority.firm.open.limit`, `limit = 5` | CP-RN-17 |
| CT-CP-48 | Fora do plano | Empresa sem `SIEG_INTEGRATION` | 409 `error.capture.priority.not.in.plan` | CP-RN-17 |
| CT-CP-49 | Desligado | `TAXIFY_CAPTURE_PRIORITY_ENABLED=false` | 503 `error.capture.priority.unavailable` | CP-RN-17 |
| CT-CP-50 | Plataforma e bloqueio | `csadmin`, empresa W | 409 `error.capture.priority.firm.blocked` | CP-RN-03, 17 |
| CT-CP-51 | Cancelado no bloqueio | Pedido às 10:01; bloqueio às 10:03; rodada das 10:05 | `CANCELED`; sem captura; sem cooldown | CP-RN-18 |
| CT-CP-52 | Expirado | Pedido `PENDING` de 26/11 09:00 (25 h) | `EXPIRED` | CP-RN-18 |
| CT-CP-53 | Fila normal pega antes | Empresa com pedido aberto reservada pela fila normal | Pedido `SERVED`; contagem forçada aplicada | CP-RN-18 |
| CT-CP-54 | Lote de 1 | `batch-size = 1`, 3 pedidos abertos | Rodadas: pedido, fila, pedido, fila, pedido | CP-RN-15 |
| CT-CP-55 | Trilha | Pedido da Carla; pedido do `csadmin` | Logs `capture.priority.*` só com ids; a Carla vê "Pedido pela CS Hub" no segundo | CP-RN-19 |
| CT-CP-56 | Limpeza | Pedidos de 25/08/2026 e de 27/11/2026 | O de agosto (mais de 90 dias) é apagado; o de novembro fica | CP-RN-20 |
| CT-CP-57 | Última nota por origem | NF-e: SIEG 27/11 08:11, SEFAZ_DIST 26/11 15:15; NFS-e: nenhuma ADN | As duas datas; "Nunca" para NFS-e ADN | CP-RN-09 |
| CT-CP-58 | Recuperação | `recovery_tasks` abertas de 2026-10 e 2026-11 para X | "Recuperando 2026-10 e 2026-11" | CP-RN-09 |
| CT-CP-59 | Consultas fixas | 50 e 5.000 empresas, página de 50 | Mesmo número de consultas (contado com o `statistics` do Hibernate ou um proxy de datasource) | CP-RN-14 |
| CT-CP-60 | Página grande | `size = 101` | 400 `error.generic.bad.request` | CP-RN-14 |
| CT-CP-61 | Filtro por tipo | `tipo=EVENTOS` | Só empresas com etapa de eventos com falha ou atrasada | CP-RN-13 |
| CT-CP-62 | Busca | `busca=textil` | Encontra "Têxtil Exemplo Ltda" | CP-RN-13 |
| CT-CP-63 | Direito igual ao agendador | Duas assinaturas vigentes sobrepostas: a mais nova sem SIEG e a mais antiga com SIEG | Painel e agendador dizem "sem direito" | CP-RN-05 |
| CT-CP-64 | Fuso da verificação | JVM em UTC; verificação às 10:00 de Recife | `ultima_verificacao_em = 10:00` (não 13:00) | CP-RN-21 |
| CT-CP-65 | Falha ao gravar a etapa | Exceção no `save` de `capture_stage_status` | As notas ficam gravadas; log `capture.stage.record.failed company={id}`; a próxima empresa é processada | CP-RN-10 |

---

## 8. Endpoints

Base: `/taxify-service/api/v1`. Erros no formato único `{timestamp, status, code, message, details}`, com os textos em
`i18n/errors_pt.properties` e `errors_en.properties`.

| Método | Caminho | Acesso | Corpo / resposta | RN |
|---|---|---|---|---|
| GET | `/capture-panel` | `@RequiresPermission(INVOICES_SYNC)` (ADMIN: escritório inteiro; demais: carteira). Liberado no bloqueio (GET) | Query: `situacao`, `canal`, `tipo`, `busca`, `incluirInativas`, `sort`, `page`, `size` → `{summary, queue, page: {content, totalElements, totalPages, number, size}}` | CP-RN-01, 06 a 14 |
| GET | `/capture-panel/companies/{companyId}` | Idem, com a regra de tenant (404) | Detalhe completo da empresa (CP-RN-09) | CP-RN-09 |
| POST | `/capture-panel/companies/{companyId}/sync-now` | `@AdminOnly`. Bloqueado no somente leitura (escrita) | → 202 `{requestId, status, position, estimatedAt}`; 403, 404, 409, 429, 503 | CP-RN-15 a 17 |
| GET | `/platform/capture-panel` | `@PlatformAdminOnly` | As mesmas queries mais `firmId` → o mesmo corpo, com `firmId` e `firmName` em cada linha | CP-RN-02 |
| GET | `/platform/capture-panel/companies/{companyId}` | `@PlatformAdminOnly` | Detalhe (sem CNPJ completo) | CP-RN-02, 09 |
| POST | `/platform/capture-panel/companies/{companyId}/sync-now` | `@PlatformAdminOnly` | Como o do escritório; 409 `error.capture.priority.firm.blocked` | CP-RN-03, 15 a 17 |

Esboço do corpo (os nomes valem como contrato para o front):

```json
{
  "summary": {
    "byStatus": {"EM_DIA": 210, "PENDENTE": 31, "ATRASADA": 40, "COM_ERRO": 9, "NUNCA_TENTADA": 6, "FORA_DO_PLANO": 4, "BLOQUEADA": 0},
    "lastAttemptAt": "2026-11-27T09:58:12-03:00"
  },
  "queue": {
    "health": "RUNNING",
    "stalledSince": null,
    "observedLapMinutes": 1080,
    "neverAttempted": 0,
    "theoreticalLapMinutes": 750,
    "nextTickAt": "2026-11-27T10:05:00-03:00"
  },
  "page": {
    "content": [{
      "companyId": "…", "companyName": "Têxtil Exemplo Ltda", "establishment": "0001", "active": true,
      "firmId": null, "firmName": null,
      "status": "PENDENTE", "statusReason": "Faltam 20 NF-e recebidas",
      "channels": {
        "SIEG": {"status": "PENDENTE", "lastAttemptAt": "…", "verifiedAt": "…", "queuePosition": 41, "nextAttemptEstimate": "…",
                 "received": {"imported": 180, "totalSieg": 200, "from": "2026-11-01", "to": "2026-11-27", "checkedAt": "…"},
                 "issued": {"imported": 12, "totalSieg": 12, "from": "2026-11-01", "to": "2026-11-27", "checkedAt": "…"},
                 "eventsCheckedOn": {"received": "2026-11-27", "issued": "2026-11-27"}, "serviceInvoicesCheckedOn": "2026-11-27",
                 "priorityRequest": null},
        "SEFAZ": {"status": "EM_DIA", "certificate": {"present": true, "fromHeadOffice": false, "validUntil": "2027-03-15"},
                  "lastRunAt": "…", "nextQueryAt": "…"},
        "ADN": {"status": "FORA_DO_PLANO", "reason": "…"}
      },
      "lastFailure": {"stage": "SIEG_RECEIVED", "at": "…", "code": "SIEG_UNAVAILABLE", "providerStatus": "503",
                      "message": "O SIEG respondeu com erro (HTTP 503). A próxima volta tenta de novo.", "resolved": false,
                      "consecutiveFailures": 1}
    }],
    "totalElements": 300, "totalPages": 6, "number": 0, "size": 50
  }
}
```

- A visão do escritório nunca traz `firmId`/`firmName` de outro escritório. A da plataforma nunca traz o CNPJ completo.
- Os endpoints novos entram no teste-guarda `noEndpointIsLeftWithoutAnAccessRule` e no `theConsoleIsForThePlatformAdminOnly`
  (`PermissionAuthorizationManagerTest`).
- `GET /fiscal-xml-sync-control` fica como está nesta spec (P-CP-10).

## 9. Modelo de dados

| Tabela | Migration | Conteúdo |
|---|---|---|
| `capture_stage_status` | V76 | `id`, `company_id` (FK `companies`), `stage varchar(30)`, `last_outcome varchar(10)` (SUCCESS, FAILURE), `last_outcome_at datetime(6)`, `last_success_at datetime(6) null`, `last_failure_at datetime(6) null`, `failure_code varchar(40) null`, `provider_status varchar(4) null`, `warning_code varchar(40) null`, `warning_count int null`, `consecutive_failures int not null default 0`, auditoria e `deleted` (convenção 3.8.1). **Único** `(company_id, stage)`. Gravação por upsert tolerante a chave duplicada (como o `seed` do agendador) |
| `capture_priority_requests` | V77 | `id`, `company_id` (FK), `accounting_firm_id` (FK), `requested_by varchar(36)` (id do usuário), `requested_scope varchar(10)` (FIRM, PLATFORM), `requested_at datetime(6)`, `status varchar(10)` (PENDING, RESERVED, SERVED, CANCELED, EXPIRED), `served_at datetime(6) null`, `open_key varchar(36) null` (= `company_id` enquanto PENDING ou RESERVED; nulo depois), auditoria. **Único** `open_key`. Índices `(accounting_firm_id, requested_at)`, `(status, requested_at)`, `(company_id, served_at)` |
| `fiscal_xml_documents` (+índice) | V78 | `idx_fxd_company_source_created (company_id, source_system, created_date)` |
| `service_invoices` (+índice) | V78 | `idx_service_invoices_company_source_created (company_id, source_system, created_date)` |

- Nenhuma coluna nova em `fiscal_xml_sync_control`. A ordem da fila passa a considerar os pedidos de
  `capture_priority_requests` (CP-RN-15).
- Os dois índices da V78 são criados com DDL online do MySQL 8 (`ALGORITHM=INPLACE, LOCK=NONE`). Conferir o tamanho
  das tabelas na EC2 antes do deploy (Riscos).
- Opcional, se a P-CP-06 for aceita: V79 com `received_prev_month char(7)`, `received_prev_total_sieg`,
  `received_prev_imported`, `issued_prev_month`, `issued_prev_total_sieg` e `issued_prev_imported` em
  `fiscal_xml_sync_control`.

## 10. Configuração nova

| Variável | Padrão | Efeito |
|---|---|---|
| `TAXIFY_CAPTURE_PANEL_SIEG_FRESH_HOURS` | `24` (P-CP-03) | Janela de "em dia" do canal SIEG |
| `TAXIFY_CAPTURE_PANEL_DIRECT_FRESH_HOURS` | `3` (P-CP-03) | Janela de "em dia" da SEFAZ e do ADN (rodam de hora em hora) |
| `TAXIFY_CAPTURE_PANEL_STALLED_MINUTES` | `30` | Fila SIEG "parada" (precisa ser maior que o intervalo do cron mais a duração de uma rodada) |
| `TAXIFY_CAPTURE_PANEL_MAX_PAGE_SIZE` | `100` | Tamanho máximo da página |
| `TAXIFY_CAPTURE_PRIORITY_ENABLED` | `true` | Liga o "Sincronizar agora" |
| `TAXIFY_CAPTURE_PRIORITY_SLOTS_PER_TICK` | `1` (P-CP-04) | Vagas do lote para pedidos (no máximo `batch-size − 1`) |
| `TAXIFY_CAPTURE_PRIORITY_COOLDOWN_MINUTES` | `60` (P-CP-05) | Espera entre dois pedidos atendidos da mesma empresa |
| `TAXIFY_CAPTURE_PRIORITY_MAX_OPEN_PER_FIRM` | `5` (P-CP-05) | Pedidos abertos ao mesmo tempo por escritório |
| `TAXIFY_CAPTURE_PRIORITY_PER_FIRM_PER_DAY` | `10` (P-CP-05) | Pedidos por escritório no dia civil de Recife |
| `TAXIFY_CAPTURE_PRIORITY_EXPIRE_HOURS` | `24` | Pedido aberto que expira |

Entram no `application.yaml` com o padrão e no `docker-compose.yml`/`docker-compose.aws.yml` só se o valor mudar.

## 11. Tarefas

Cada tarefa é entregável sozinha. A ordem sugerida é B-CP-1 → B-CP-2 → B-CP-3 → B-CP-4, com o front a partir do contrato
da seção 8.

| # | Área | Tarefa | Depende de |
|---|---|---|---|
| B-CP-1 | Backend | `ultima_verificacao_em` e `issued_checked_at` gravados com o `Clock` de Recife (`FiscalXmlIntegrationService`), com teste de JVM em UTC (CP-RN-21, CT-CP-64) | — |
| B-CP-2 | Backend | V76 + `CaptureStageRecorder` (upsert em `REQUIRES_NEW`, sucesso, falha, aviso, `consecutive_failures`, falha ao gravar só no log) + `CaptureFailureClassifier` (lista fechada da CP-RN-11, sem ler mensagem, `provider_status` só com dígitos) + status HTTP na `SiegIntegrationException` + instrumentação das etapas SIEG no `FiscalXmlSyncScheduler` (lados, eventos, NFS-e, recuperação), sem mudar o fluxo da captura. Testes CT-CP-10 a 19 e 65 | — |
| B-CP-3 | Backend | Instrumentação de `SefazDistService` e `NfseAdnService` com os códigos de pré-condição, `cStat`, HTTP, tempo e rede (CT-CP-24 a 26). `last_detail` continua como está | B-CP-2 |
| B-CP-4 | Backend | `CaptureSituationRules`, uma função pura com testes unitários de todas as linhas das CP-RN-06 a 08. `CapturePanelQueryService`: a projeção agregada, o direito compartilhado com os agendadores (CT-CP-63), o `RoutineGate` por escritório, a ordem da fila, o resumo, as estimativas, o filtro, a ordem e a página em memória, e os detalhes da página em consultas agrupadas. V78 (índices). Endpoints `GET /capture-panel`, `GET /capture-panel/companies/{id}` e as rotas `GET` da plataforma, com o log de acesso da plataforma; teste-guarda; teste de número fixo de consultas (CT-CP-59) | B-CP-2, P-CP-01 (só as rotas da plataforma) |
| B-CP-5 | Backend | V77 + `CapturePriorityService` (verificações da CP-RN-17 na ordem, chave `open_key`, idempotência, `retryAt`) + os dois `POST sync-now` + mudança na fila do `FiscalXmlSyncScheduler` (vagas de prioridade, alternância com lote 1, contagem forçada, `SERVED`/`CANCELED`/`EXPIRED`) + limpeza de 90 dias + logs da CP-RN-19. Testes CT-CP-41 a 56 | B-CP-4 |
| B-CP-6 | Backend | (Só se a P-CP-06 for aceita.) V79 + gravação dos contadores do mês anterior na virada, em `updateSyncControl`, e exibição "nov/2026 fechou com 20 NF-e recebidas não importadas" | P-CP-06 |
| F-CP-1 | Frontend | Rota `/captura` (`permissionGuard('INVOICES_SYNC')`), item "Captura de XML" no menu; cartões do resumo clicáveis; saúde da fila (rodando, parada, desligada); volta observada e teórica com o texto da CP-RN-12; filtros na URL; tabela paginada no servidor; legenda das situações | contrato da seção 8 |
| F-CP-2 | Frontend | Detalhe da empresa (painel lateral) com a memória da CP-RN-09: contadores com a janela e "no Taxify (qualquer origem)", última nota por origem, SEFAZ e ADN com o link para a aba Certificado, falhas por etapa (resolvida ou não), recuperação em andamento | F-CP-1 |
| F-CP-3 | Frontend | Botão "Sincronizar agora" só para ADMIN e plataforma, com confirmação ("A empresa vai para o início da fila. Estimativa: {hora}"); estado "na fila" com a posição; mensagens de 403, 409, 429 (com `retryAt`) e 503; botão escondido no escritório bloqueado | B-CP-5 |
| F-CP-4 | Frontend | Rota `/plataforma/captura` (`platformAdminGuard`), o mesmo componente com coluna e filtro de escritório e sem CNPJ; item no menu do console | F-CP-1, P-CP-01 |
| Q-CP-1 | QA | CT-CP-01 a CT-CP-65 com dados fictícios, SIEG, SEFAZ e ADN simulados (WireMock ou mocks dos clientes) e `Clock` fixo | B-CP-1 a B-CP-5 |
| Q-CP-2 | QA | Desempenho: massa fictícia de 5.000 empresas em 50 escritórios num **MySQL local** (nunca no banco da EC2: o `mvn test` usa o datasource do `application-default`, conferir o `DB_HOST`), medir p95 e contar consultas (CA-CP-21) | B-CP-4 |
| Q-CP-3 | QA | Segurança: isolamento entre escritórios (404/403), carteira do FISCAL, rotas da plataforma, ausência de CNPJ completo na visão da plataforma, ausência de `last_detail`, corpo de resposta do provedor, token e chave de API no banco, na resposta e nos logs `capture.*` (CT-CP-14, 38 a 40) | B-CP-2 a B-CP-5 |
| Q-CP-4 | QA | Paralelo de 4 semanas: toda segunda-feira, comparar o painel com o resultado do `scripts/sql/diagnostico-captura-nfe.sql` rodado no mesmo minuto (só leitura) para 20 empresas, e registrar as diferenças com a explicação | go-live |

---

## 12. Dados e integrações

- **Sem integração externa nova.** Nenhuma chamada nova ao SIEG, à SEFAZ ou ao ADN. O "Sincronizar agora" só muda a
  ordem da fila. A única chamada a mais é o `contar-xmls` forçado da tentativa prioritária, dentro do mesmo limitador
  de 2 por minuto.
- **Tabelas lidas:** `companies`, `accounting_firms`, `firm_subscriptions`, `subscriptions`, `plans`, `plan_services`,
  `fiscal_xml_sync_control`, `sefaz_dist_control`, `nfse_adn_control`, `company_certificates`, `fiscal_xml_documents`
  (só o máximo de `created_date` por origem), `service_invoices` (idem), `recovery_tasks`, `users` e `user_companies`.
- **Tabelas novas:** `capture_stage_status`, `capture_priority_requests` (seção 9).
- **Escritas novas na captura:** o resultado de cada etapa (CP-RN-10) e o estado do pedido de prioridade
  (CP-RN-15 e CP-RN-18). Nenhuma mudança na janela, na paginação, na deduplicação ou nos contadores da captura.

## 13. Requisitos de acesso, LGPD e sigilo

- Isolamento por `accounting_firm` em todas as rotas do escritório, com o escritório tirado do usuário logado e a
  carteira aplicada na consulta (CP-RN-01). Contexto sem escritório: 403.
- A visão da plataforma é a primeira a mostrar nomes de empresas clientes fora do escritório: só para
  `@PlatformAdminOnly`, sem CNPJ completo e com log de acesso (CP-RN-02, P-CP-01). Precisa constar no acordo de operador
  que a CS Hub, como operadora, vê o nome das empresas clientes para dar suporte à captura.
- A tabela de falhas guarda só códigos de uma lista fechada e números de status (CP-RN-11): nada de mensagem do
  provedor, CNPJ, chave, token ou chave de API.
- Logs da feature só com ids e códigos.
- Nada de dado real em fixture, log ou commit: todos os exemplos são fictícios. A massa de desempenho é gerada.

## 14. Fora do escopo

- Pendência nova `SIEG_CAPTURE_ERROR` e aviso por e-mail de captura parada (P-CP-11).
- Histórico de falhas (só a última por etapa). O `capture_runs` continua só para SEFAZ, ADN e certidão.
- Mudar o fluxo da captura: a falha de um lado continuar abortando o resto da empresa, tirar as empresas inativas da
  fila (P-CP-07), o limitador do SIEG por chave de API em vez de global, várias instâncias dividindo o limite.
- "Sincronizar agora" para SEFAZ (já existe na aba Certificado) e para o ADN.
- Escolher o período da sincronização manual (meses anteriores): continua o `POST /integrations/xmls/sincronizar`, sem tela.
- O fuso da janela do mês do agendador (pergunta 2 da spec de captura; P-CP-08).
- Exportar o painel em CSV ou PDF.
- Remover ou mudar o `GET /fiscal-xml-sync-control` (P-CP-10).
- CT-e e NFC-e (o SIEG captura só NF-e na rotina).

---

## 15. Perguntas em aberto

Nenhuma delas é regra fiscal. Todas são decisões de produto ou de acesso.

### Bloqueiam (só a visão da plataforma)

| # | Pergunta | Recomendação |
|---|---|---|
| P-CP-01 | A F1-RN-15 (decisão 11) diz que o console da plataforma **nunca** traz nome, CNPJ ou dado fiscal das empresas clientes. A decisão nova ("a plataforma vê todas as empresas de todos os escritórios") é uma exceção a isso. Confirma a exceção, e que o acordo de operador e a política de privacidade passam a citá-la? | **Sim, com limite:** nome da empresa e número do estabelecimento (`/0001`) para distinguir matriz e filial, contagens de notas e datas. Sem CNPJ completo, sem valores, sem chave e sem nome de terceiro, com log de cada acesso. **Sem a confirmação**, a visão da plataforma sai com os dados agregados por escritório (contagens por situação e saúde da fila), sem a lista de empresas. A visão do escritório e o resto da spec não dependem desta pergunta |

### Têm recomendação aceitável (o desenvolvimento segue com ela)

| # | Pergunta | Recomendação |
|---|---|---|
| P-CP-02 | Além do ADMIN, outros perfis veem o painel? E pedem "Sincronizar agora"? | **Ver, sim, quem tem `INVOICES_SYNC` (perfil FISCAL), só a carteira.** É o analista que precisa saber se pode começar a conferência. **Pedir, não, na v1:** só o ADMIN e a plataforma, porque a fila e o limite do SIEG são compartilhados. ACCOUNTING e MANAGEMENT ficam de fora (403). Se o time pedir, o FISCAL passa a pedir com os mesmos limites do escritório |
| P-CP-03 | Qual janela define "em dia"? | **SIEG: verificado nas últimas 24 h**, porque a volta leva de 12 a 20 h. Com uma janela menor, metade da carteira apareceria atrasada o tempo todo. **SEFAZ e ADN: 3 h**, porque rodam de hora em hora. Os dois ficam configuráveis. Se a volta encurtar, a janela do SIEG baixa |
| P-CP-04 | Quantas vagas do lote vão para "Sincronizar agora"? | **1 das 2.** Isso dá no máximo 12 pedidos por hora na plataforma inteira, e a fila normal nunca para. O custo é que a volta normal fica até 2 vezes mais lenta enquanto houver pedido |
| P-CP-05 | Limites anti-abuso do "Sincronizar agora" | **1 pedido aberto por empresa; 60 min entre dois atendimentos da mesma empresa; 5 abertos e 10 por dia por escritório.** Valem também para a plataforma, contados no escritório da empresa: é a mesma fila, e um pedido da CS Hub tira vaga dos outros do mesmo jeito |
| P-CP-06 | Guardar os contadores do mês anterior? Hoje, na virada do mês, a rotina começa o mês novo do zero, e as notas que faltaram no mês anterior não aparecem em lugar nenhum (só o mês corrente é sincronizado) | **Sim, numa tarefa à parte (B-CP-6):** 6 colunas, gravadas na virada, e a tela diz "nov/2026 fechou com 20 NF-e recebidas não importadas". É o caso em que uma nota some de vez sem ninguém ver. Buscar essas notas de novo é outra decisão (fora do escopo) |
| P-CP-07 | Empresa inativa: aparece no painel? E deveria sair da fila do SIEG? Hoje o agendador não filtra empresa inativa e gasta chamadas com ela | **No painel:** escondida por padrão, com o filtro "incluir inativas", e fora do resumo. **Na fila:** recomendo tirar, numa mudança à parte, porque libera vaga para as ativas. Fica fora desta spec |
| P-CP-08 | O "hoje" e a janela do mês do agendador usam o fuso da JVM, e as notas emitidas depois das 21 h do último dia caem no mês seguinte (pergunta 2 da spec de captura) | **Não mexer aqui.** O painel usa Recife e só corrige o horário gravado na verificação (CP-RN-21). O fuso da janela é decisão do Tony na spec de captura |
| P-CP-09 | O `SiegClient` grava no log os primeiros 500 caracteres do corpo de erro do SIEG, que podem trazer CNPJ | **Parar de gravar o corpo** e manter só o status e o código. É uma linha, e dá para entrar no B-CP-2 se o Tony aceitar |
| P-CP-10 | O `GET /fiscal-xml-sync-control` (sem tela, só o lado recebido) fica? | **Fica como está nesta spec** e é marcado como obsoleto. Pode ser removido numa limpeza depois do go-live do painel (responde também a pergunta 6 da spec de captura) |
| P-CP-11 | Avisar quando a captura parar (pendência `SIEG_CAPTURE_ERROR` ou e-mail para a CS Hub)? | **Não na v1.** Primeiro o painel e o código seguro. Na v2, uma pendência ALTA para a empresa com 3 falhas seguidas e um e-mail para a CS Hub quando a fila ficar "parada", usando o resumo da spec de avisos |

## 16. Riscos

- **Filtro e página em memória.** A projeção cobre alguns milhares de empresas, como o console da fase 1. Acima de cerca
  de 20 mil, a situação calculada precisa virar coluna gravada (ou visão materializada) para filtrar no SQL. A
  Q-CP-2 mede o limite.
- **Índices em tabela grande.** A V78 cria índice em `fiscal_xml_documents`, que é a maior tabela. DDL online, mas
  conferir o tamanho e rodar fora do horário de trabalho. É deploy de produção e exige aprovação do Tony.
- **Volta mais lenta com prioridade.** Cada pedido tira uma vaga da fila normal. Os tetos da CP-RN-17 seguram o efeito,
  e o resumo mostra a volta teórica já com a vaga de prioridade.
- **Limitador global.** O limite de 2 chamadas por minuto vale para a aplicação inteira, para todos os escritórios.
  Com muitos escritórios na plataforma, a volta passa de dias, e o painel vai mostrar isso. Resolver o limite por chave
  de API é outra feature.
- **Duas instâncias.** O limitador é em memória: duas instâncias dobram as chamadas e podem levar 429 do SIEG. O
  painel mostra `SIEG_RATE_LIMITED`, mas não resolve.
- **Datas antigas em UTC.** Até a próxima verificação de cada empresa depois do deploy da B-CP-1, `ultima_verificacao_em`
  pode estar 3 h adiantada e uma empresa pode aparecer "em dia" por até 3 h a mais.
- **Erros antigos sem código.** SEFAZ e ADN com `ERRO` antes do deploy mostram `LEGACY_ERROR` até a próxima execução
  (no máximo 1 h).
