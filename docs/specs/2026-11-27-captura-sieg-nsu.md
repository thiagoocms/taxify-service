# Captura SIEG por NSU (NF-e, NFC-e, CT-e, NFS-e e eventos)

> Feature nova, decidida pelo Tony em 2026-11-27 e escrita pelo po-taxify no mesmo dia. **Substitui as fases 2 (busca
> de NFC-e) e 3 (CT-e) da [Captura ampliada no SIEG](2026-11-27-captura-nfce-cte.md).** Daquela spec continuam valendo:
> a fase 1 (contagens), o modelo do CT-e (papéis e ligação com a NF-e) e as perguntas fiscais P-CX-02 e P-CX-10 a
> P-CX-13 (seção 2). Deixam de existir a faixa noturna, o orçamento diário de NFC-e/CT-e, a habilitação por empresa e
> o teto de empresas com NFC-e.
>
> Texto em português; identificadores em inglês.
>
> Depende de:
> - [Remodelagem dos documentos fiscais](2026-11-27-remodelagem-documentos-fiscais.md):
>   - troca única: `Nfe`, `Nfce`, `Nfse`, `Cte`, eventos e `fiscal_document_sources`;
>   - fase P, seção 7B: contrato `CaptureProvider`, `capture_stream`, `provider_rate_slots`, `firm_capture_providers`,
>     e o modo `NSU` do SIEG já previsto lá.
> - [Capturas e controles](2026-11-27-capturas-e-controles.md): B1, C1, E2.
>
> Migrations: depois da V89.

## 0. Status e decisões

| Item | Situação |
|---|---|
| Usar o NSU do SIEG para NF-e, NFC-e, CT-e, NFS-e e eventos | **Decidido pelo Tony** (2026-11-27) |
| Encaixe técnico | Modo `NSU` do provedor `SIEG` da fase P. Origem gravada `SIEG`, com `retrieval_mode = NSU`. Grava direto no modelo novo. Entra **depois da remodelagem e da fase P** |
| Implementação | **Pronta para implementar**, desligada (`TAXIFY_SIEG_NSU_ENABLED=false`), com as duas formas de distribuição (NSU por conta ou por empresa) atrás de uma configuração |
| **Ligar** em produção | Depende do **teste real** com a conta do Tony (D-NSU-01), que responde P-NSU-01 a P-NSU-07. NFC-e somando na apuração depende ainda da **P-CX-02** (Fiscal) |
| Desligar o modo atual (contagem + paginação) para captura diária | Depois do paralelo (NSU-RN-14), com aprovação do Tony |

**O que a documentação oficial do SIEG diz** (lida em 2026-11-27; o resto é pergunta):

| Ponto | Documentação | Fonte |
|---|---|---|
| `consultar-nsu` | `https://api.sieg.com/api/v1/consultar-nsu`: "consultar primeiro e ultimo NSU disponível com o intuito de informar o Range de arquivos para Download". **Não documentados:** o método HTTP, os parâmetros e os campos da resposta | `integracoes.sieg.com/clientes-sieg/docs/consultar-nsus` |
| `baixar-xml/nsu` | `https://api.sieg.com/api/v1/baixar-xml/nsu`, corpo `{"Nsu": 114}` ("Iniciado em 1"), resposta "Documento fiscal eletrônico com formato .ZIP". O método HTTP não aparece na página lida | `integracoes.sieg.com/sistema-externo/docs/baixar-xml-nsu-2` |
| Autenticação | as duas juntas: JWT (`Authorization: Bearer`, "identifica o sistema integrador") e `X-API-Key` ("identifica o usuário") | as duas páginas |
| Limite | 3.000 req/min por API Key; acima disso, HTTP 429, com "exponential backoff" recomendado | `baixar-xml-nsu-2` |
| Tipos | NF-e, NFC-e, CT-e, NFS-e, CF-e e eventos | `baixar-xml-nsu-2` |
| Alcance | "apenas os documentos disponíveis na nuvem nos últimos 90 dias" | `baixar-xml-nsu-2` |
| Lote `baixar-xml/nsu/lote` (até 50 NSUs) | **Não encontrado** na página lida (o endereço que tentei deu 404). Vem da mensagem do coordenador | a confirmar (P-NSU-04) |
| Escopo do NSU (conta/API key ou CNPJ) e emitidas x recebidas | **Não documentados** | P-NSU-01, P-NSU-02 |

## 1. Problema e objetivo

**Problema:**
- O modo atual do SIEG ("contar + baixar com `Skip`", 2 req/min) não comporta NFC-e e CT-e. A spec anterior precisava
  de faixa noturna, orçamento, teto por escritório e habilitação empresa a empresa.
- O `Skip` por posição já perdeu nota (B1).
- Os eventos exigem varredura diária própria (A2).

**Objetivo:**
- Uma captura incremental por cursor, NSU após NSU, que traga todos os tipos e os eventos na mesma sequência, sem
  janela de mês nem `Skip`.
- O modo atual fica só para a primeira carga e para meses fora dos 90 dias.

**Medidas:**

| Medida | Meta |
|---|---|
| Chamadas ao SIEG por dia, NSU + modo atual, x hoje | menor que hoje, medida por `provider.requests{provider=SIEG, purpose}` |
| Atraso entre o NSU disponível e o gravado (`nsuLag`) | < 15 min em 95% dos ticks |
| Chaves achadas pelo modo atual e não pelo NSU, no paralelo | zero (NSU-RN-14) |
| Notas perdidas por "página vazia" | zero (não há `Skip`) |

## 2. O que continua da spec anterior

| Item | Situação |
|---|---|
| Fase 1 (contagens por tipo) | **Continua**, e as contagens vão para `capture_period_counts` (remodelagem). Elas vêm de graça das chamadas `contar-xmls` do modo atual e servem de **oráculo do paralelo** (NSU-RN-14). Quando o modo atual deixar de rodar para uma empresa, as contagens dela param, e o painel mostra o `nsuLag` |
| Modelo do CT-e | **Continua**: papéis (`cte_is_*`, CX-RN-31), tomador (CX-RN-32), valores e ICMS (CX-RN-33) e ligação com a NF-e (CX-RN-34, em `fiscal_document_references`, como previsto na remodelagem). O parser completo do CT-e é a tarefa B-NSU-06 |
| P-CX-02 (NFC-e soma na apuração?) | **Continua e bloqueia ligar o NSU** para escritório com varejo |
| P-CX-10 a P-CX-13 (uso fiscal do frete) | **Continuam**, sem mudança. O CT-e é capturado e mostrado e não entra em rotina fiscal (CX-RN-40) |
| Fases 2 e 3 (faixa, orçamento, `company_capture_settings`, `document_capture_status`, `sieg_request_budgets`, tetos, `TAXIFY_SIEG_NFCE_*`, `TAXIFY_SIEG_CTE_*`, P-CX-01, 03, 05 a 07 e 09) | **Substituídas** por esta spec |
| P-CX-04 (SEFAZ DF-e do CT-e) | Fora desta spec. O NSU do SIEG cobre o CT-e; a 3b só volta se o teste real mostrar lacuna |

## 3. Usuários e cenário

| Perfil | O que vê |
|---|---|
| FISCAL e ADMIN | NFC-e, CT-e, NFS-e e eventos chegando sozinhos, sem habilitar nada por empresa. No painel da captura, o canal SIEG mostra o modo (`PERIOD` ou `NSU`) e o atraso |
| Plataforma | Liga o NSU por escritório (`firm_capture_providers`, fase P) e vê só agregados (quantidades descartadas, CF-e ignorados, chamadas) |
| Escritório bloqueado | Rotinas paradas, como hoje. Ao desbloquear, o cursor continua de onde parou. Se o bloqueio passou de 90 dias, há lacuna (NSU-RN-09) |
| Teste grátis | O NSU segue a regra do SIEG no teste (P-NSU-08, recomendação: liberado, porque o custo por chamada é baixo) |

Frequência: um tick a cada `TAXIFY_SIEG_NSU_CRON` (padrão a cada 5 minutos), por fluxo.

## 4. Histórias
- Como FISCAL, quero que as NFC-e e os CT-e das minhas empresas cheguem sozinhos, para não depender de upload.
- Como FISCAL, quero que um cancelamento chegue e seja aplicado à nota em minutos, para não conferir nota cancelada.
- Como ADMIN, quero ver no painel se a captura por NSU está em dia, para confiar no que está no Taxify.
- Como plataforma, quero ligar o NSU escritório a escritório e comparar com o modo atual, para desligar o modo antigo
  com segurança.

## 5. Regras de negócio

| RN | Regra |
|---|---|
| **NSU-RN-01 Encaixe** | O NSU é o modo `NSU` do provedor `SIEG` (`retrieval = CURSOR`, eventos `INLINE`, tipos `NFE`, `NFCE`, `CTE`, `NFSE`; `CFE` declarado como "recebido e ignorado"). Ele vale para o escritório que tem a linha (`SIEG`, modo `NSU`) em `firm_capture_providers` com `TAXIFY_SIEG_NSU_ENABLED=true`. Nada de "SIEG" no núcleo (FD-RN-41): a estratégia é a `CursorStrategy` da fase P |
| **NSU-RN-02 Escopo do cursor** (P-NSU-01, decidido no teste real) | `TAXIFY_SIEG_NSU_SCOPE` aceita dois valores, e os dois são implementados. **`ACCOUNT`:** o NSU é da API key, ou seja, do escritório (a API key do SIEG é por escritório, `firm_integrations`). Há **um** `capture_stream` por escritório (`scope_type = FIRM`, `document_type = '*'`, `side = '*'`), e a distribuição segue o NSU-RN-03. **`COMPANY`:** o NSU é por CNPJ. Há um `capture_stream` por empresa (`scope_type = COMPANY`), e a chamada leva a identificação da empresa no formato que o teste mostrar. Mesmo assim, o NSU-RN-03 confere que o documento é da empresa (defesa contra resposta trocada) |
| **NSU-RN-03 Distribuição por empresa** | Para cada documento recebido, o núcleo extrai os documentos (CNPJ/CPF) dos participantes. NF-e e NFC-e: `emit`, `dest` e `autXML` não contam. CT-e: `emit`, `rem`, `exped`, `receb`, `dest`, `toma3`/`toma4`. NFS-e: prestador, tomador e intermediário. Eventos: a chave do documento e o CNPJ do autor. Depois, casa esses documentos com as empresas **ativas do escritório** (índice por `accounting_firm_id` + CNPJ). Grava uma linha por empresa que casou, como hoje (V16: uma nota entre duas empresas do escritório gera duas linhas). Se **nenhuma empresa casa**, o documento é **descartado sem gravar arquivo nem linha** e só entra na contagem `unmatched` (NSU-RN-12): é dado de quem não é cliente, e não fica no Taxify. Evento sem documento conhecido fica guardado pela chave só se o CNPJ do autor ou a chave casarem com empresa do escritório (FD-RN-07); senão, é descartado e contado |
| **NSU-RN-04 Tipos** | NF-e (modelo 55) vira `Nfe`; NFC-e (65) vira `Nfce`; CT-e (57) vira `Cte`; CT-e OS (67) e outros fora do modelo são ignorados e contados `unsupported`. NFS-e (nacional ou ABRASF) vira `Nfse`; a duplicidade ABRASF x nacional segue o E2 da spec irmã. CF-e (SAT/MF-e) é ignorado: não grava e conta `unsupported_cfe`. Evento vira `FiscalDocumentEvent`. Arquivo que não abre ou não é XML fiscal conta `parse_error` e não para o lote |
| **NSU-RN-05 Gravação** | Pela ingestão genérica da remodelagem (`FiscalDocumentIngestionService`): invariantes, chave única por empresa (idempotência, FD-RN-12), duplicidade tratada **por documento** (E1), arquivo gravado **depois** da linha garantida (ou limpo se a linha perder a corrida), origem `SIEG` com `retrieval_mode = NSU` em `fiscal_document_sources` (upsert, FD-RN-31) |
| **NSU-RN-06 Eventos** | Cancelamento registrado (110111, 110112), carta de correção e manifestações chegam na mesma sequência e são aplicados a qualquer tipo pela regra de eventos da remodelagem (FD-RN-07): ligam ao documento da empresa pela chave e aplicam `cancel(eventAt)`. Evento que chega antes do documento fica guardado e é aplicado quando o documento chega. Com isso, a varredura diária de eventos do modo atual (A2) deixa de ser necessária para a empresa com o NSU ligado (NSU-RN-15) |
| **NSU-RN-07 Ciclo do tick** | Por fluxo reservado: (1) `consultar-nsu` no máximo a cada `TAXIFY_SIEG_NSU_RANGE_CHECK_MINUTES` (padrão 15), gravando `provider_first_cursor`, `provider_last_cursor` e `provider_range_checked_at`; (2) se `cursor_value ≥ provider_last_cursor`, o fluxo está em dia e sai sem outra chamada; (3) senão, pede de `cursor_value + 1` até `min(provider_last_cursor, cursor_value + TAXIFY_SIEG_NSU_BATCH_SIZE)`, pelo lote se existir (P-NSU-04) ou NSU a NSU; (4) grava o que veio e avança o cursor (NSU-RN-08); (5) repete enquanto houver tempo no tick (`TAXIFY_SIEG_NSU_TICK_BUDGET_SECONDS`, padrão 60) e o limite permitir. Toda chamada HTTP fica **fora de transação** (C1) |
| **NSU-RN-08 Cursor, reserva e duas instâncias** | **Reserva do fluxo:** `UPDATE capture_stream SET lease_owner = :instance, lease_until = :now + lease WHERE id = :id AND (lease_until IS NULL OR lease_until < :now)`; 0 linhas afetadas = outra instância tem o fluxo. **Avanço:** na **mesma transação curta** que grava os documentos do lote, `UPDATE capture_stream SET cursor_value = :new WHERE id = :id AND cursor_value = :expected AND lease_owner = :instance`. Se afetar 0 linhas, a transação é desfeita e o lote é refeito no próximo tick, sem duplicar nada (idempotência por chave). O cursor só avança até o **maior NSU contíguo** processado. Um NSU que falhou deixa o cursor antes dele |
| **NSU-RN-09 Lacuna (90 dias)** | Se `cursor_value + 1 < provider_first_cursor`, os NSUs entre os dois expiraram no SIEG. O fluxo grava `failure_code = NSU_GAP` e a faixa perdida (do cursor até o primeiro disponível). Depois, cria tarefas de recuperação pelo modo atual (`recovery_tasks`, com `provider_key = SIEG`) para os meses de emissão afetados, por empresa (o modo atual ainda alcança esses meses), e reposiciona o cursor em `provider_first_cursor − 1`. O escritório vê o aviso de lacuna no painel |
| **NSU-RN-10 NSU que não vem** | NSU pedido e não devolvido (vazio, 404 ou o equivalente que o teste mostrar, P-NSU-05) é tentado de novo até `TAXIFY_SIEG_NSU_MAX_RETRIES` (padrão 5) ticks. Depois, é pulado com `warning_code = NSU_SKIPPED`, contado, e o cursor avança. A quantidade de NSUs pulados por dia aparece no painel da plataforma (agregado) |
| **NSU-RN-11 Limite** | `provider_rate_slots` com escopo `CREDENTIAL` (a API key do escritório) e teto `TAXIFY_SIEG_NSU_REQUESTS_PER_MINUTE` (padrão **600**, um quinto dos 3.000 documentados, por prudência até o teste). HTTP 429: backoff exponencial (1, 2, 4... até 64 s) com jitter, sem segurar transação, e `warning_code = RATE_LIMITED`. Se o SIEG disser que o limite do NSU é o mesmo dos endpoints atuais (P-NSU-06), o escopo e o teto mudam só na configuração |
| **NSU-RN-12 Contadores** | Por fluxo e dia de Recife, em `capture_stream_daily_stats`: `requests`, `fetched`, `stored_new`, `duplicated`, `events_stored`, `unmatched`, `unsupported_cfe`, `unsupported_other`, `parse_errors` e `skipped_nsu`. Upsert atômico (`INSERT ... ON DUPLICATE KEY UPDATE x = x + :n`), seguro com duas instâncias. Sem CNPJ, chave ou valor |
| **NSU-RN-13 Primeira carga e meses antigos** | Empresa nova ou escritório que acabou de ligar o NSU: (1) o cursor nasce em `provider_first_cursor − 1`, ou seja, tudo o que o SIEG ainda tem dos últimos 90 dias; (2) o que for anterior a isso vem pelo **modo atual** (recuperação por página, já existente) para os meses que o escritório pedir, como hoje. O modo atual continua para meses com mais de 90 dias e para as lacunas do NSU-RN-09 |
| **NSU-RN-14 Paralelo e critério para desligar o modo atual** | Ao ligar o NSU num escritório, o modo atual **continua** por `TAXIFY_SIEG_NSU_PARALLEL_DAYS` (padrão 30) dias. Os dois gravam no mesmo lugar sem duplicar (chave única). Cada um marca a própria origem (`SIEG` + `PERIOD` ou `SIEG` + `NSU`, `fiscal_document_sources.retrieval_mode`), e é isso que permite comparar. **Comparação diária** por empresa, mês, tipo e lado, nos últimos 90 dias: (a) achadas só pelo modo atual há mais de 24 h; (b) achadas só pelo NSU; (c) contagem do `contar-xmls` x entregues pelo NSU. O painel mostra (a), (b) e (c). **Critério para desligar** o modo atual na captura diária do escritório: 30 dias seguidos com (a) = 0 em todas as empresas, nenhuma lacuna sem explicação e (c) fechando. A troca é por escritório, aprovada pelo Tony. Depois dela, o modo atual fica só para o NSU-RN-13 e o NSU-RN-09 |
| **NSU-RN-15 O que o NSU desliga** | Com o NSU ligado e fora do paralelo, o modo atual deixa de fazer, para o escritório: a captura diária de NF-e recebida e emitida, a varredura de eventos (A2) e a busca de NFS-e pelo SIEG (A1). O ADN e a SEFAZ DF-e continuam como fontes oficiais (confronto). Durante o paralelo, nada é desligado |
| **NSU-RN-16 Volume e NFC-e** | O NSU traz **todas** as NFC-e das empresas varejistas. O volume e o armazenamento crescem (estimativa na seção 7). NFC-e gravada entra nas consultas de `GoodsInvoice` (apuração, auditoria, relatórios) como hoje entra a NFC-e de upload. **Por isso, ligar o NSU em escritório com varejo depende da P-CX-02.** Enquanto ela não for respondida, o NSU só é ligado em escritórios sem NFC-e emitida no SIEG (fase 1: `nfce_count = 0` no lado emitido) |
| **NSU-RN-17 Isolamento** | O fluxo de um escritório só grava em empresas daquele escritório (NSU-RN-03). Documento de empresa de outro escritório que por acaso venha na resposta é tratado como `unmatched`. Rotas e telas seguem a regra de carteira de hoje (404 fora da carteira) |
| **NSU-RN-18 Escritório bloqueado** | O `RoutineGate` para o fluxo. Nada é pedido, e o cursor fica onde está. Ao desbloquear, continua; com mais de 90 dias parado, aplica o NSU-RN-09 |
| **NSU-RN-19 Fuso** | Instantes (`lease_until`, `provider_range_checked_at`) em UTC, pela política H2 da spec irmã; o dia dos contadores e o mês das comparações em Recife. O mês do documento é o de `issue_date` como no XML, igual ao modo atual |
| **NSU-RN-20 Credenciais e privacidade** | JWT do integrador (o mesmo `SiegTokenService` de hoje, com `client-id`/`client-secret` da plataforma; se o NSU exigir outro, é a P-NSU-07) + API key do escritório. Nada disso vai para log. Texto de erro do SIEG não é gravado nem mostrado: só os códigos da lista fechada (NSU-RN-21) |
| **NSU-RN-21 Códigos de falha** (`CaptureFailureCode`, lista fechada) | `NSU_GAP`, `NSU_SKIPPED`, `RATE_LIMITED`, `PROVIDER_AUTH_FAILED` (401/403), `PROVIDER_UNAVAILABLE` (5xx/timeout), `PROVIDER_BAD_RESPONSE` (ZIP inválido), `CREDENTIAL_MISSING` |

## 6. Contrato da API

As rotas são as da fase P mais estes acréscimos. Todas respeitam a regra de escritório e carteira; fora dela, 404.

| Rota | Quem | Mudança |
|---|---|---|
| `GET /api/v1/capture-panel` e `/companies/{id}` (existentes) | como hoje | **Aditivo.** O canal `SIEG` ganha `mode` (`PERIOD`, `NSU` ou `PARALLEL`) e, com NSU, `nsu: {lagNsu, lastSuccessAt, gap: {from, to} \| null, parallel: {onlyPeriod, onlyNsu, providerCount, nsuDelivered} \| null}`. `lagNsu` = `provider_last_cursor − cursor_value`; com escopo `ACCOUNT`, ele é do escritório e se repete em todas as empresas. Os campos atuais não mudam. Ordenação e paginação, como hoje |
| `GET /api/v1/capture-panel/nsu-comparison` | ADMIN do escritório (escritório inteiro) e FISCAL (carteira) | `period` (`yyyy-MM`, obrigatório), `companyId` (opcional), `documentType`, `side`, `page` (0), `size` (50, máx. 200), `sort` (`onlyPeriod,desc` padrão; desempate `companyId`). 200: página de `{companyId, companyName, documentType, side, period, onlyPeriod, onlyNsu, providerCount, nsuDelivered, status}` com `status` ∈ `MATCHING`, `NSU_MISSING`, `NSU_EXTRA`, `NOT_IN_PARALLEL`. 400 `error.capture.filter.invalid` (`invalidField`) |
| `GET /api/v1/capture-panel/nsu-comparison/keys` | ADMIN e FISCAL (carteira) | `companyId` (obrigatório), `period`, `documentType`, `side`, `kind` (`ONLY_PERIOD` ou `ONLY_NSU`), `page`, `size` (50, máx. 200), ordenado por `issueDate,asc`. 200: página de `{documentId, accessKey, number, issueDate, firstSeenAt}`. A chave aparece porque é da carteira do próprio usuário. 404 fora da carteira |
| `GET /api/v1/platform/capture-panel/nsu` | `@PlatformAdminOnly` | `date` (padrão hoje). 200: **só agregados**, sem escritório nem empresa: `{date, firmsOnNsu, firmsInParallel, requests, fetched, storedNew, unmatched, unsupportedCfe, skippedNsu, gaps}` |
| `PUT /api/v1/platform/firms/{firmId}/capture-providers` (fase P) | `@PlatformAdminOnly` | aceita `mode` (`PERIOD`, `NSU`) e `parallelUntil` (data). 409 `error.capture.nsu.fiscal.pending` quando o escritório tem NFC-e emitida e a P-CX-02 está pendente (`TAXIFY_SIEG_NSU_NFCE_ALLOWED=false`) |

| Code | Status | Texto (pt) |
|---|---|---|
| `error.capture.nsu.fiscal.pending` | 409 | "A captura por NSU ainda não pode ser ligada para escritórios com NFC-e. Falta a confirmação do Fiscal." |
| `error.capture.filter.invalid` | 400 | "Filtro inválido." (existente, se já houver; senão, novo) |

## 7. Modelo de dados

**Acréscimos à remodelagem (antes da V86 ir para produção, sem custo):**
- `fiscal_document_sources.retrieval_mode varchar(10) not null`, com os valores `PERIOD`, `NSU`, `CURSOR` e `NONE`
  (upload).
- A chave única passa a `uk_fiscal_document_sources (document_id, source, retrieval_mode)`.
- O `Skip` do modo atual conta só `source = 'SIEG' AND retrieval_mode = 'PERIOD'`, e esse é o motivo de o modo fazer
  parte da origem. Uma nota entregue pelo NSU não ocupa posição na lista do `baixar-xmls`. Contá-la no `Skip` repetiria
  o B1.
- O confronto entre fontes conta documentos distintos por `source`.
- Cópia na V87: `SIEG` vai com `PERIOD`, `SEFAZ_DFE` e `NFSE_ADN` com `CURSOR`, e `UPLOAD` com `NONE`.
- A spec da remodelagem foi atualizada com isso.

**`capture_stream` (T1, fase P): colunas que o NSU exige**

| Coluna | Tipo | Nota |
|---|---|---|
| `scope_type` | varchar(10) not null | `FIRM` ou `COMPANY` |
| `scope_id` | varchar(36) not null | id do escritório ou da empresa |
| `provider_key` | varchar(30) not null | `SIEG` |
| `retrieval_mode` | varchar(10) not null | `NSU` |
| `document_type`, `side` | varchar(10) not null | `*` no escopo `FIRM` |
| `cursor_value` | bigint | último NSU processado de forma contígua |
| `provider_first_cursor`, `provider_last_cursor` | bigint | do `consultar-nsu` |
| `provider_range_checked_at` | datetime(6) | UTC |
| `lease_owner` | varchar(60) | instância |
| `lease_until` | datetime(6) | UTC |
| `parallel_until` | date | fim do paralelo |
| `gap_from`, `gap_to` | bigint | lacuna aberta (NSU-RN-09) |
| `failure_code`, `warning_code` | varchar(40) | lista fechada |
| `last_success_at`, `last_failure_at`, `last_attempt_at` | datetime(6) | |
| auditoria, `deleted`, `version` | | |

Único: `uk_capture_stream (scope_type, scope_id, provider_key, retrieval_mode, document_type, side)`. Índice de fila:
`idx_capture_stream_queue (provider_key, retrieval_mode, lease_until)`.

**Tabela nova: `capture_stream_daily_stats`** (`V90__CaptureStreamDailyStats`, ou o próximo número livre depois da V89
e das migrations da fase P)

| Coluna | Tipo |
|---|---|
| `id` | varchar(36) PK |
| `stream_id` | varchar(36) not null, FK `capture_stream` |
| `stat_date` | date not null (Recife) |
| `requests`, `fetched`, `stored_new`, `duplicated`, `events_stored`, `unmatched`, `unsupported_cfe`, `unsupported_other`, `parse_errors`, `skipped_nsu` | int not null, padrão 0 |
| `created_date`, `last_modified_date` | datetime(6) |

Único `uk_capture_stream_daily_stats (stream_id, stat_date)`. Retenção: 400 dias, apagados pela rotina de limpeza
da fase P.

**Índice de distribuição:** a distribuição (NSU-RN-03) procura empresa por `accounting_firm_id` + CNPJ. Usa o índice
de `companies` por documento se existir (conferir); senão, um cache em memória por tick, com as empresas ativas do
escritório carregadas numa consulta só (sem N+1).

**Volume e custo (hipótese fictícia, para dimensionar):**

| Hipótese | Conta | Resultado |
|---|---|---|
| Carteira de 306 empresas, 100 NF-e + 30 eventos por empresa por mês, e 20 varejistas com 3.000 NFC-e por mês | 306 × 130 + 20 × 3.000 | ~100 mil NSUs por mês |
| Com lote de 50 | 100.000 ÷ 50 | ~2.000 chamadas por mês, mais `consultar-nsu` (1 a cada 15 min por fluxo: 96 por dia por escritório) |
| NSU a NSU (sem lote) | 100.000 | ~3.300 por dia, ~2,3 por minuto: muito abaixo de 600/min |
| Armazenamento das NFC-e | 60 mil NFC-e × ~5 itens = 300 mil itens por mês × ~570 B | ~170 MB por mês de itens, mais os XMLs no S3 |

## 8. Variáveis de ambiente

| Variável | Padrão | Quem liga |
|---|---|---|
| `TAXIFY_SIEG_NSU_ENABLED` | `false` | Tony |
| `TAXIFY_SIEG_NSU_SCOPE` | `ACCOUNT` (a confirmar no teste real, P-NSU-01) | dev, com aprovação |
| `TAXIFY_SIEG_NSU_CRON` | `0 */5 * * * *` (Recife) | dev |
| `TAXIFY_SIEG_NSU_BATCH_SIZE` | `50` (com lote) ou `20` NSUs por ciclo sem lote | dev |
| `TAXIFY_SIEG_NSU_REQUESTS_PER_MINUTE` | `600` | Tony |
| `TAXIFY_SIEG_NSU_RANGE_CHECK_MINUTES` | `15` | dev |
| `TAXIFY_SIEG_NSU_TICK_BUDGET_SECONDS` | `60` | dev |
| `TAXIFY_SIEG_NSU_LEASE_SECONDS` | `300` | dev |
| `TAXIFY_SIEG_NSU_MAX_RETRIES` | `5` | dev |
| `TAXIFY_SIEG_NSU_PARALLEL_DAYS` | `30` | Tony |
| `TAXIFY_SIEG_NSU_NFCE_ALLOWED` | `false` (vira `true` com a P-CX-02 respondida) | Tony |

## 9. Logs
- `capture.nsu.tick`: `streamId`, escopo, NSU inicial e final, quantidades (as do NSU-RN-12) e ms.
- `capture.nsu.gap`: `streamId` e a faixa de NSU (números de sequência, que não são dado de cliente).
- `capture.nsu.lease.lost` e `capture.nsu.cursor.conflict`: `streamId`.
- Nunca: CNPJ, chave, nome, valor, corpo da resposta do SIEG ou a API key.

## 10. Critérios de aceite

| CA | Dado / Quando / Então | RN |
|---|---|---|
| CA-NSU-01 | Dado o escritório F1 com NSU ligado (`ACCOUNT`), cursor 100 e `consultar-nsu` = 1..130, quando o tick roda, então pede 101 a 130, grava os documentos das empresas de F1 e o cursor vai a 130 | 07, 08 |
| CA-NSU-02 | Dado um documento cujo emitente é E1 e o destinatário é E2, ambas de F1, então há duas linhas (uma por empresa), cada uma com a origem `SIEG`/`NSU` | 03, 05 |
| CA-NSU-03 | Dado um documento sem nenhuma empresa de F1 entre os participantes, então nada é gravado (nem arquivo) e `unmatched` soma 1 | 03, 12 |
| CA-NSU-04 | Dado um CF-e na sequência, então não é gravado, `unsupported_cfe` soma 1 e o cursor passa por ele | 04 |
| CA-NSU-05 | Dado um cancelamento de NF-e de E1 já gravada, quando chega pelo NSU, então a nota fica `CANCELED` com `canceled_at` do evento; se a nota ainda não chegou, o evento fica guardado e é aplicado quando ela chega | 06 |
| CA-NSU-06 | Dadas duas instâncias no mesmo tick, então só uma reserva o fluxo; se a reserva expirar no meio e a outra avançar o cursor, a primeira não grava o cursor e não duplica documento | 08 |
| CA-NSU-07 | Dado que a instância cai depois de gravar os documentos e antes de avançar o cursor (na mesma transação), então nada fica gravado pela metade, e o próximo tick refaz o lote | 08 |
| CA-NSU-08 | Dado um cursor 100 e `consultar-nsu` = 250..400, então o fluxo grava `NSU_GAP` 101..249, cria as tarefas de recuperação dos meses afetados e reposiciona o cursor em 249 | 09 |
| CA-NSU-09 | Dado o SIEG respondendo 429, então a instância espera com backoff fora de transação, registra `RATE_LIMITED` e não passa de `TAXIFY_SIEG_NSU_REQUESTS_PER_MINUTE` contando as duas instâncias | 11 |
| CA-NSU-10 | Dado o paralelo, quando o modo atual acha uma chave que o NSU não trouxe em 24 h, então a comparação mostra `NSU_MISSING` com a chave na lista `ONLY_PERIOD` | 14 |
| CA-NSU-11 | Dado o paralelo, então o `Skip` do modo atual conta só as origens `SIEG`/`PERIOD`: notas trazidas pelo NSU não mudam a página pedida | 7 (modelo), 14 |
| CA-NSU-12 | Dado um FISCAL sem a empresa E2 na carteira, quando pede a comparação ou as chaves de E2, então recebe 404 | 17 |
| CA-NSU-13 | Dado F2 bloqueado, então nenhum NSU é pedido, e ao desbloquear o cursor continua | 18 |
| CA-NSU-14 | Dado F1 com NFC-e emitida e `TAXIFY_SIEG_NSU_NFCE_ALLOWED=false`, quando a plataforma tenta ligar o NSU, então recebe 409 `error.capture.nsu.fiscal.pending` | 16 |
| CA-NSU-15 | Dado o escopo `COMPANY`, então há um fluxo por empresa e a distribuição ainda descarta documento de outro CNPJ | 02, 03 |
| CA-NSU-16 | Dado o NSU 120 sem resposta por 5 ticks, então ele é pulado com `NSU_SKIPPED`, contado, e o cursor segue | 10 |

## 11. Casos de teste

Dados fictícios. F1 com E1 (CNPJ 11.222.333/0001-81) e E2 (12.345.678/0001-95). Fornecedor fora da carteira com o
CNPJ fictício 11.444.777/0001-61.

| CT | Cenário | Esperado | CA |
|---|---|---|---|
| CT-NSU-01 | Cursor 100; NSUs 101 a 110: 6 NF-e de E1, 1 NFC-e de E2, 1 CT-e com E1 tomadora, 1 evento de cancelamento de uma NF-e de E1 e 1 CF-e | 6 `Nfe` + 1 `Nfce` + 1 `Cte` (`TAKER`, `INBOUND`) gravados; evento aplicado; CF-e ignorado; cursor 110; estatísticas: `fetched` 10, `stored_new` 8, `events_stored` 1, `unsupported_cfe` 1 | 01, 04, 05 |
| CT-NSU-02 | NF-e do fornecedor fora da carteira para um CNPJ que não é de F1 | `unmatched` 1; nenhum arquivo no storage | 03 |
| CT-NSU-03 | NF-e de E1 para E2 | 2 linhas: E1 `ISSUER`/`OUTBOUND` e E2 `RECIPIENT`/`INBOUND` | 02 |
| CT-NSU-04 | Duas instâncias; A reserva às 21:00:00 UTC com lease de 300 s; B tenta às 21:01 | B não reserva; às 21:05:01, se A não renovou, B reserva, e um avanço de A depois disso afeta 0 linhas | 06 |
| CT-NSU-05 | Queda entre a gravação e o commit | Nada gravado; o próximo tick grava os mesmos 10, sem duplicar | 07 |
| CT-NSU-06 | Cursor 100, faixa 250..400; emissões dos NSUs perdidos em set e out/2026 | `NSU_GAP` 101..249; `recovery_tasks` para 2026-09 e 2026-10 por empresa; cursor 249 | 08 |
| CT-NSU-07 | 429 três vezes seguidas | Esperas de ~1, 2 e 4 s (com jitter); sucesso na 4ª; `RATE_LIMITED` registrado | 09 |
| CT-NSU-08 | Paralelo: o modo atual traz K1 de E1 às 10:00; o NSU não traz até 10:00 do dia seguinte | `NSU_MISSING` em (E1, mês, NFE, RECEIVED) com `onlyPeriod` = 1 e K1 na lista | 10 |
| CT-NSU-09 | Paralelo: E1 com 37 notas recebidas no mês, 30 pelo modo atual e 7 só pelo NSU | `Skip` do modo atual = **30**, não 37 | 11 |
| CT-NSU-10 | Virada de mês: NF-e emitida 31/10/2026 23:50 (Recife) e recebida pelo NSU em 01/11 | Conta em 2026-10 na comparação; o contador diário fica em 01/11 | — |
| CT-NSU-11 | F1 com uma NFC-e emitida no mês (fase 1) e a P-CX-02 pendente | 409 ao ligar | 14 |
| CT-NSU-12 | Escopo `COMPANY`: o fluxo de E1 recebe um documento cujo único participante é E2 | Vai para `unmatched` no fluxo de E1 (defesa); E2 recebe o documento pelo próprio fluxo | 15 |

## 12. Tarefas

| Tarefa | Agente | Entrega | Depende de |
|---|---|---|---|
| D-NSU-01 | Tony + dev | Teste real com a conta do Tony, só leitura: `consultar-nsu` e alguns NSUs; responder P-NSU-01 a 07; registrar a forma da resposta **sem guardar XML de cliente** fora do Taxify | — |
| B-NSU-01 | backend | Acréscimo `retrieval_mode` em `fiscal_document_sources` (na remodelagem, antes da V86 ir para produção) | remodelagem |
| B-NSU-02 | backend | Modo `NSU` do `SiegCaptureProvider`: `consultar-nsu`, `baixar-xml/nsu` (e lote, se existir), leitura do ZIP (um ou vários XMLs, eventos), 429 com backoff | fase P, D-NSU-01 |
| B-NSU-03 | backend | Distribuição por empresa (NSU-RN-03) para os dois escopos, com o cache de empresas por escritório | B-NSU-02 |
| B-NSU-04 | backend | `CursorStrategy` com reserva, avanço condicional, lacuna, NSU pulado, contadores (`capture_stream_daily_stats`) | fase P |
| B-NSU-05 | backend | Paralelo e comparação (NSU-RN-14), `Skip` por `retrieval_mode = PERIOD`, desligar o modo atual por escritório (NSU-RN-15), rotas da seção 6 | B-NSU-04 |
| B-NSU-06 | backend | Parser completo do CT-e (modelo da spec anterior, CX-RN-31 a 34) e `fiscal_document_references` | remodelagem |
| F-NSU-01 | frontend | Painel: modo, `nsuLag`, lacuna e o resumo do paralelo; tela de comparação com a lista de chaves | B-NSU-05 |
| F-NSU-02 | frontend | Console da plataforma: modo `NSU`, `parallelUntil` e o painel agregado | B-NSU-05 |
| Q-NSU-1 | QA | CT-NSU-01 a 12 com um SIEG falso (WireMock) que reproduz a forma confirmada no D-NSU-01, com duas instâncias reais no local | B-NSU-05 |
| Q-NSU-2 | QA | Paralelo real num escritório piloto por 30 dias, com o relatório do critério do NSU-RN-14 para o Tony | go-live |

## 13. Riscos
- **O escopo do NSU ser diferente do esperado.** Mitigação: os dois escopos estão implementados, e o teste real
  decide.
- **NSU sem emitidas ou sem recebidas** (P-NSU-02). Nesse caso, o modo atual continua para o lado que faltar. O
  NSU-RN-15 só desliga o que o paralelo comprovar.
- **90 dias.** Escritório parado ou bloqueado por mais tempo tem lacuna. O NSU-RN-09 recupera pelo modo atual, ao custo
  de 2 req/min.
- **Volume de NFC-e no armazenamento e na apuração.** Mitigação: NSU-RN-16 e P-CX-02.
- **Documento de não cliente.** É descartado sem gravar (NSU-RN-03). Mesmo assim, passa em memória pelo Taxify. A
  política de privacidade deve citar que a captura recebe e descarta documentos de terceiros (P-NSU-09).
- **Custo ou plano do SIEG para o NSU desconhecido** (P-NSU-03).

## 14. Fora do escopo
- CF-e (só contado).
- CT-e OS (modelo 67).
- Uso fiscal do CT-e (P-CX-10 a 13).
- SEFAZ DF-e do CT-e (P-CX-04).
- Mudança no ADN e na SEFAZ DF-e de NF-e.

## 15. Perguntas em aberto

### Bloqueiam ligar (respondidas no teste real, D-NSU-01, com a conta do Tony)
| P | Pergunta | Opções | Recomendação | Impacto |
|---|---|---|---|---|
| **P-NSU-01** | O NSU é da API key (conta, escritório) ou de cada CNPJ? | `ACCOUNT`; `COMPANY` | Implementar os dois; padrão `ACCOUNT` (a doc diz "para a empresa autenticada" pela API key) | Muda o número de fluxos e a distribuição; o código já cobre os dois |
| **P-NSU-02** | A sequência traz as emitidas **e** as recebidas? E o CT-e como tomador? | as duas; só recebidas; só emitidas | Testar com uma empresa que emite e recebe | Lado ausente continua no modo atual |
| **P-NSU-03** | O NSU tem custo ou exige plano diferente no SIEG? | | Confirmar com o SIEG antes de ligar | Custo por escritório; pode entrar no plano |
| **P-NSU-04** | Formato do ZIP (um XML ou vários; eventos junto) e existência e limite do lote | | Tratar os dois formatos de ZIP; usar o lote se existir | Muda o `BATCH_SIZE` e o número de chamadas |
| **P-NSU-05** | Campos e método do `consultar-nsu`; resposta para NSU inexistente ou expirado | | Registrar no D-NSU-01 | Define o NSU-RN-09 e o NSU-RN-10 |
| **P-NSU-06** | O limite de 3.000/min do NSU é separado do limite de 2/min dos endpoints atuais? | separado; compartilhado | Tratar como separado, com escopo `CREDENTIAL`, e teto prudente de 600 | Compartilhado: o NSU disputa com o modo atual, e o teto precisa ser bem menor |
| **P-NSU-07** | O JWT do integrador que usamos hoje (`SiegTokenService`) vale para o NSU? | | Testar | Se não valer, é preciso pedir credencial nova ao SIEG |

### Têm recomendação aceitável
| P | Pergunta | Recomendação | Se for diferente |
|---|---|---|---|
| P-NSU-08 | NSU no teste grátis? | Liberado: o custo por chamada é baixo, e a captura é a vitrine do teste | Bloqueado: o teste mostra menos |
| P-NSU-09 | Citar na política de privacidade o descarte de documentos de terceiros | Sim, numa linha | Risco de questionamento LGPD |
| P-NSU-10 | Prazo do paralelo | 30 dias (um fechamento) | Menos tempo, menos evidência |
| (herdada) P-CX-02 | NFC-e capturada soma na apuração? | Somar a partir do mês em que o NSU foi ligado, com paralelo do Fiscal | "Não somar" exige filtro por modelo (regra fiscal nova) |
