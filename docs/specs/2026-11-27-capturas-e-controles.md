# Capturas e controles: correções da captura, economia no SIEG e tabelas de controle

> Spec irmã da [remodelagem dos documentos fiscais](2026-11-27-remodelagem-documentos-fiscais.md), escrita pelo
> po-taxify em 2026-11-27 a partir de uma análise de leitura do código pedida pelo Tony. Os pontos foram conferidos no
> código (arquivo e linha abaixo). O texto está em português e os identificadores em inglês.
>
> **Por que uma spec irmã e não uma seção da remodelagem:** a remodelagem é uma troca de modelo **sem mudança de
> comportamento**, e a paridade antigo x novo é o teste dela. Os itens daqui **mudam comportamento** (o que o SIEG
> baixa, quando, com que fuso, em que transação). Misturar os dois tira o oráculo da remodelagem e aumenta a janela de
> risco. Separados, o B1 sai já, como correção pequena, e o resto entra numa sequência de releases curtas depois da
> remodelagem, cada uma medida pelo contador de chamadas (A5).

## 0. Status

| Item | Situação |
|---|---|
| **B1** | **Correção urgente**, recomendada antes da remodelagem (P-FD-07 da remodelagem). Feita na branch `fix/sieg-call-savings` (com reconciliação, contador `sieg.requests{purpose}` e a contagem de NFS-e reaproveitada, que cobre parte do A1 e do A5). Entra no master antes da troca única |
| **Provedor plugável** (decisão do Tony, 2026-11-27) | Vai para a **fase P** da remodelagem (seção 7B de lá), que absorve aqui o **T1, o T5, o D1/D2 e o C1**, além da forma genérica do B1, do A1, do A3 e do A5 (`provider.requests{provider, purpose}`). Nenhum item desta spec pode cravar "SIEG" no núcleo (FD-RN-41) |
| E1, E2, H2 | Próxima etapa depois da fase P. Precisam de detalhamento (RN, CA e CT) antes do dev |
| A2, H1 | Próxima etapa, medida pelo `provider.requests` |
| T1 `capture_stream`, T5 `provider_request_budget`/`provider_rate_slots` | Na fase P da remodelagem, **antes da fase 2 da captura ampliada** (NFC-e) |
| T2 `capture_period_counts`, T4 `fiscal_document_sources`, G1 expurgo | **Entram na remodelagem** (seções 3.10, 3.11 e FD-RN-16 de lá) |
| Mantidas como estão | `capture_priority_requests`, `pendency_alert_*`, `firm_notifications`, `stock_credit_surveys`, `audit_runs`, `recovery_tasks` (com expurgo próprio) |

Nenhum item traz regra fiscal nova.

## 1. Problemas encontrados (conferidos no código)

### 1.1 Bugs e riscos (P0)

| Id | Problema | Onde | Efeito | Correção proposta |
|---|---|---|---|---|
| **B1** | O `Skip` do SIEG é o total de notas da empresa na janela **de qualquer fonte** | `FiscalXmlIntegrationService:270` (`countByCompany_IdAndOwnIssueAndIssueDateBetween`), usado como skip em `FiscalXmlSyncScheduler:518` | Se a SEFAZ ou o upload trouxe notas, o SIEG pula posições que nunca baixou. A página vazia ainda limpa a pendência (`:198-201`, `:275-277`). **Nota perdida sem aviso** | `Skip` = notas da janela e do lado **vistas pelo SIEG** (`found_in_sieg_at` preenchido hoje; `fiscal_document_sources` depois da remodelagem). Mais uma passada de reconciliação com `Skip = 0` quando o importado pelo SIEG fica abaixo do total do SIEG depois de uma página vazia, em vez de limpar a pendência |
| **C1** | Chamada HTTP e espera do limitador (até 30 s) dentro de `@Transactional` | `FiscalXmlIntegrationService:133, 147, 164` (e as buscas de eventos); `ServiceInvoiceService:77` (até ~80 chamadas, ~40 min com a conexão presa) | Conexão do pool presa, transação longa, lock de linha segurado | Baixar fora da transação; gravar cada página numa transação curta |
| **E1** | SEFAZ e SIEG gravando a mesma chave ao mesmo tempo derrubam a página inteira | `persistAll` (`:449-466`): `exists` e depois `save`, sem tratar a duplicidade por nota; o arquivo é gravado (`:460`) antes da linha | A página falha e é refeita; arquivo órfão no storage | Tratar a violação da chave única por nota (`DuplicateKeys`), como "já existia"; gravar a linha e o arquivo na ordem que não deixa órfão (ou limpar o órfão) |
| **E2** | NFS-e ABRASF (chave sintética `ABRASF-<município>-<prestador>-<número>`, `NfseXml:179`) e a mesma nota pelo ADN (chave nacional) viram duas linhas | `NfseXml:179`, `uk_service_invoices_company_key` | Dupla contagem em retenções e relatórios | Chave natural secundária (emitente, número, município, competência) para casar as duas; a decisão de qual fica é do detalhamento (P-CC-02) |
| **D1** | Limitador do SIEG (2 req/min) só em memória, por instância | `integration/sieg/SiegRateLimiter` (`synchronized`, `ArrayDeque`) | Com duas instâncias, o limite global vira 4 req/min | Janela por minuto no banco (`provider_rate_slots`) ou `GET_LOCK` por provedor |
| **D2** | SEFAZ e ADN protegidos só por `AtomicBoolean` | `SefazDistScheduler:45`, `NfseAdnScheduler:41` | Duas instâncias consultam o mesmo NSU: rejeição 656 e bloqueio temporário do CNPJ na SEFAZ | Reserva por empresa com `UPDATE` condicional; `routine_runs.run_date` (V82, único por rotina e dia) passa a `run_slot`, para permitir rodadas por hora, com retenção |
| **H2** | Três fusos misturados | `FiscalXmlSyncScheduler:167` (`LocalDate.now()` da JVM, UTC no container); `FiscalXmlIntegrationService:850-856` (janela do SIEG em UTC) x contagem local em `IssuePeriod`; SEFAZ e ADN com o relógio da JVM | Virada de mês 3 h antes; pendência que nunca fecha na fronteira do mês | Política: instantes em UTC, datas de negócio em Recife, `Clock` injetado em toda a captura (o `ClockConfig` já existe). P-CC-01 |
| **H1** | O SIEG só olha a janela do mês corrente | `FiscalXmlSyncScheduler:516-525` | NF-e emitida no dia 30 que chega ao SIEG no dia 4 fica de fora | Manter o mês anterior aberto nos primeiros dias, com linha própria em `capture_period_counts` (a mesma ideia do CX-RN-26 da NFC-e) |

### 1.2 Desperdício de chamadas no SIEG

Estimativa da análise (a validar pelo A5): ≥ 8 chamadas fixas por empresa por dia, ≥ 2.450 por dia num teto de 2.880
(2 req/min × 1.440 min). Depois das correções, ~800 a 900 por dia.

| Id | Problema | Onde | Correção |
|---|---|---|---|
| A1 | NFS-e pelo SIEG todo dia, janela de 35 dias, rebaixando tudo | `FiscalXmlSyncScheduler:99, 446-456` | Usar a contagem de NFS-e que o `contar-xmls` já devolve e só baixar quando houver diferença (~1.000 chamadas/dia a menos, estimativa) |
| A2 | Varredura de eventos diária nos dois lados de todas as empresas | `refreshEventsOncePerDay` (`:476-496`) | Varrer o lado emitido só com nota emitida em [hoje − 2, hoje]; pular o recebido se a SEFAZ DF-e rodou bem nas últimas 24 h (~500/dia) |
| A3 | Lado com 0 notas é recontado a cada tick | `SideState.isUpToDate` exige `totalSieg > 0` (`:553-555`) | Considerar "em dia" o lado com 0 contado hoje |
| A5 | Ninguém sabe quantas chamadas cada finalidade gasta | | Contador Micrometer por finalidade (contagem, página, eventos, NFS-e) no interceptor do cliente SIEG. **Vem primeiro** |

### 1.3 Tabelas de controle

| Id | Tabela | O que junta | Quando |
|---|---|---|---|
| T1 | `capture_stream` (`company_id` ou `firm_id`, `provider_key` (valor aberto, não enum), `document_type`, `side`, `cursor_value` (NSU), `next_eligible_at`, `last_attempt_at`, `last_checked_on`, `last_outcome`, `failure_code`, `provider_status`; único por fluxo) | `fiscal_xml_sync_control` (fila e datas), `sefaz_dist_control`, `nfse_adn_control`, `capture_stage_status` e o `document_capture_status` proposto na captura ampliada. Sem `last_detail` em texto livre (hoje esse texto chega à tela pelo `PendencyService.health`) | Depois da remodelagem, antes da fase 2 da NFC-e |
| T2 | `capture_period_counts` | contadores do SIEG e `sieg_document_counts` | **Na remodelagem** |
| T4 | `fiscal_document_sources` | `found_in_*_at` | **Na remodelagem** |
| T5 | `provider_request_budget` (orçamento por dia, provedor, faixa e escopo) e `provider_rate_slots` (janela por minuto) | `sieg_request_budgets` da captura ampliada e o D1 | Com o T1 |

## 2. Ordem recomendada

| Onda | Itens | Por quê |
|---|---|---|
| 0 (agora) | **B1** no modelo atual (`fix/sieg-call-savings`) | Nota perdida em produção |
| 1 | Troca única da remodelagem, com T2, T4, G1 e o modelo neutro quanto ao provedor (FD-RN-40) | Decisão do Tony |
| 2 | **Fase P da remodelagem**: provedor plugável, com T1, T5, D1/D2, C1 e A1/A3/A5 em forma genérica | Decisão do Tony; segurança com duas instâncias; base da fase 2 da NFC-e |
| 3 | H2, E1 | Fuso e duplicidade por nota |
| 4 | A2, H1, E2 | Economia de chamadas, mês anterior, deduplicação de NFS-e |

## 3. Perguntas em aberto

| P | Pergunta | Recomendação | Impacto |
|---|---|---|---|
| P-CC-01 | Política de fuso (H2): instantes em UTC e datas de negócio em Recife, com migração das colunas de instante existentes? | Sim para código novo e para a captura; as colunas existentes continuam em Recife até um detalhamento próprio | Migrar colunas existentes mexe em muitas tabelas |
| P-CC-02 | E2: quando a mesma NFS-e chega pelo ABRASF e pelo nacional, qual fica? | Fica a nacional (chave oficial); a ABRASF vira origem dela em `fiscal_document_sources` | Muda contagens de retenção dos meses já capturados: pede conferência do Fiscal |
| P-CC-03 | A2: pular os eventos do lado recebido quando a SEFAZ DF-e rodou bem confia que a DF-e traz todos os eventos de terceiros? | Validar com uma semana de A5 e de comparação antes de desligar | Cancelamento de terceiro não visto |

## 4. Próximo passo
O po-taxify detalha a Onda 0 (B1) como spec curta de correção, com RN, CA e CT, e as Ondas 2 a 4 antes de cada
release. Esta spec é o índice e a justificativa.
