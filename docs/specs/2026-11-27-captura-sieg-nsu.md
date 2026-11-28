# Captura SIEG por NSU (NF-e, NFC-e, CT-e, NFS-e e eventos)

> Feature nova, decidida pelo Tony em 2026-11-27 e escrita pelo po-taxify no mesmo dia. **Substitui as fases 2 (busca
> de NFC-e) e 3 (CT-e) da [Captura ampliada no SIEG](2026-11-27-captura-nfce-cte.md).** Daquela spec continuam valendo:
> a fase 1 (contagens), o modelo do CT-e (papéis e ligação com a NF-e, adaptados ao modelo novo na seção 5A) e as
> perguntas fiscais P-CX-10 a P-CX-13 (seção 2). A P-CX-02 foi **fechada pelo Tony em 2026-11-28** (NFC-e soma). Deixam
> de existir a faixa noturna, o orçamento diário de NFC-e/CT-e, a habilitação por empresa e o teto de empresas com NFC-e.
>
> **Atualização de 2026-11-28 (decisões do Tony):** P-NSU-03 fechada (o SIEG não cobra por volume); P-NSU-09 fechada
> (descarte em memória, texto da política e dos termos na seção 5B); P-CX-02 fechada (NFC-e soma já, com regra contra
> dupla contagem, NSU-RN-30); piloto numa empresa da própria CS Hub, indicada pelo Tony quando a implementação estiver
> pronta (NSU-RN-35); telas de NFC-e e CT-e entram nesta entrega (seção 5A).
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
| Implementação | **Liberada logo após o deploy da remodelagem** (e da fase P, de que o NSU depende), desligada (`TAXIFY_SIEG_NSU_ENABLED=false`). **Escopo `ACCOUNT` (NSU da API key do escritório), confirmado no teste real; o Tony confirmou que cada escritório do SaaS terá a própria conta e API key no SIEG (um fluxo de NSU por escritório).** O escopo `COMPANY` sai da implementação e fica registrado só como alternativa. **Inclui as telas de NFC-e e CT-e** (seção 5A) |
| Teste real (D-NSU-01) | **Feito em 2026-11-27**, na EC2, com as credenciais da aplicação e só leitura. Respondeu P-NSU-01, 04, 05, 06 e 07 e P-NSU-02 em parte (seção 0.1) |
| P-NSU-03 (cobrança) | **Fechada pelo Tony (2026-11-28): o SIEG não cobra por volume** |
| P-NSU-09 (privacidade) | **Fechada pelo Tony (2026-11-28):** documento sem empresa cadastrada é descartado em memória (NSU-RN-03, NSU-RN-36); texto da política e dos termos na seção 5B, publicado pelo Tony (D-NSU-02) |
| P-CX-02 (NFC-e soma?) | **Fechada pelo Tony (2026-11-28): soma já** na apuração, na auditoria e nos relatórios, com a regra contra dupla contagem (NSU-RN-30). A trava `TAXIFY_SIEG_NSU_NFCE_ALLOWED` e o code `error.capture.nsu.fiscal.pending` saem |
| **Ligar** em produção | **Só depende da empresa do piloto**, que o Tony indica quando a implementação estiver pronta (NSU-RN-35, Q-NSU-2). A P-NSU-02 (emitidas e recebidas da mesma empresa) é verificada no paralelo do piloto e só decide **desligar o modo atual**, não ligar o NSU. Ligar para outros escritórios, depois do piloto, exige o texto da seção 5B publicado (D-NSU-02). A P-NSU-11 não bloqueia (há caminho alternativo) |

### 0.1 Resultado do teste real (2026-11-27)

| Ponto | Resultado |
|---|---|
| Autenticação (P-NSU-07) | O JWT de integrador que já usamos (`create-jwt` com `client-id`/`client-secret`, `SiegTokenService`) funciona nas rotas de NSU junto com a `X-API-Key` do escritório. **Respondida** |
| `consultar-nsu` (P-NSU-05) | `GET /api/v1/consultar-nsu` devolve `{IsSuccess, ErrorMessage, Code, StatusCode, Data: {PrimeiroNsu: int, UltimoNsu: int}, IsFailure}`. Faixa no dia: 563.888 a 1.214.885, cerca de 651 mil documentos em 90 dias. **Respondida** (falta só a resposta para NSU expirado, que o NSU-RN-10 trata de forma genérica) |
| Escopo (P-NSU-01) | O NSU é **da conta** (API key do escritório), não do CNPJ: numa amostra de 5 documentos apareceram 7 CNPJs/CPFs distintos. Cerca de 7,2 mil documentos por dia na conta testada. **Respondida: `ACCOUNT`** |
| Formato e lote (P-NSU-04) | `POST /api/v1/baixar-xml/nsu` com `{"Nsu": n}` devolve um ZIP com 1 XML (~3 a 4,5 KB). **O lote existe:** `POST /api/v1/baixar-xml/nsu/lote` com `{"NumerosNsu": [...]}` (até 50) devolve um ZIP com 50 XMLs (~210 KB) em 0,9 s. **Respondida** |
| Limites (P-NSU-06) | Pela documentação, por endpoint e por API key: `nsu` 3.000 req/min; `nsu/lote` 60 req/min (3.000 documentos por minuto). São separados do `contar-xmls` (5/min) e do `baixar-xmls` (2/min). A resposta não traz cabeçalho de limite. **Respondida** |
| Tipos e participantes (P-NSU-02, parcial) | Na amostra apareceram `nfeProc` modelo 55, `nfeProc` modelo 65 (NFC-e), `cteProc` modelo 57 e `procEventoNFe`, e os papéis com documento `emit`, `dest`, `rem` e `toma4`. Como o NSU é da conta e traz NFC-e (que só existe como emitida), o indício é que **os dois lados** vêm. **Confirmar no piloto** |
| Custo e plano (P-NSU-03) | A rota respondeu com a conta atual, sem erro de plano. **Fechada pelo Tony em 2026-11-28: o SIEG não cobra por volume** |
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
| Lote `baixar-xml/nsu/lote` (até 50 NSUs) | Não encontrado na página lida; **confirmado no teste real** (0.1) | teste real |
| Escopo do NSU (conta/API key ou CNPJ) e emitidas x recebidas | Não documentados; **escopo da conta confirmado no teste real**; emitidas x recebidas a confirmar no piloto | teste real, P-NSU-02 |

## 1. Problema e objetivo

**Problema:**
- O modo atual do SIEG ("contar + baixar com `Skip`", 2 req/min) não comporta NFC-e e CT-e. A spec anterior precisava
  de faixa noturna, orçamento, teto por escritório e habilitação empresa a empresa.
- O `Skip` por posição já perdeu nota (B1).
- Os eventos exigem varredura diária própria (A2).

**Objetivo:**
- Uma captura incremental por cursor, NSU após NSU, que traga todos os tipos e os eventos na mesma sequência, sem
  janela de mês nem `Skip`.
- O NSU cobre o mês corrente e o anterior (NSU-RN-13). O modo atual fica para os meses mais antigos ("Sincronizar" e
  recuperação por página).

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
| Modelo do CT-e | **Continua**, adaptado ao modelo novo na seção 5A: papéis (`cte_is_*`, CX-RN-31), tomador (CX-RN-32), valores e ICMS (CX-RN-33) e ligação com a NF-e (CX-RN-34, em `fiscal_document_references`, como previsto na remodelagem). O parser completo do CT-e é a tarefa B-NSU-06; as rotas e telas, B-NSU-09/10 e F-NSU-04/05 |
| P-CX-02 (NFC-e soma na apuração?) | **Fechada pelo Tony (2026-11-28): soma já**, sem esperar mês de corte, com a regra contra dupla contagem (NSU-RN-30). Não bloqueia mais nada |
| P-CX-10 a P-CX-13 (uso fiscal do frete) | **Continuam**, sem mudança. O CT-e é capturado e mostrado e não entra em rotina fiscal (CX-RN-40) |
| Fases 2 e 3 (faixa, orçamento, `company_capture_settings`, `document_capture_status`, `sieg_request_budgets`, tetos, `TAXIFY_SIEG_NFCE_*`, `TAXIFY_SIEG_CTE_*`, P-CX-01, 03, 05 a 07 e 09) | **Substituídas** por esta spec |
| P-CX-04 (SEFAZ DF-e do CT-e) | Fora desta spec. O NSU do SIEG cobre o CT-e; a 3b só volta se o teste real mostrar lacuna |

## 3. Usuários e cenário

| Perfil | O que vê |
|---|---|
| FISCAL e ADMIN | NFC-e, CT-e, NFS-e e eventos chegando sozinhos, sem habilitar nada por empresa. No painel da captura, o canal SIEG mostra o modo (`PERIOD` ou `NSU`) e o atraso. As NFC-e aparecem na lista de notas (filtro por tipo) e somam na apuração; os CT-e têm tela própria (seção 5A) |
| Plataforma | Liga o NSU por escritório (`firm_capture_providers`, fase P) e vê só agregados (quantidades descartadas, CF-e ignorados, chamadas) |
| Escritório bloqueado | Rotinas paradas, como hoje. Ao desbloquear, o cursor continua de onde parou; se ficou para trás do mês-limite, salta para ele (NSU-RN-09 b, NSU-RN-24) |
| Teste grátis | O NSU segue a regra do SIEG no teste (P-NSU-08, recomendação: liberado, porque o custo por chamada é baixo) |

Frequência: um tick a cada `TAXIFY_SIEG_NSU_CRON` (padrão a cada 5 minutos), por fluxo.

## 4. Histórias
- Como FISCAL, quero que as NFC-e e os CT-e das minhas empresas cheguem sozinhos, para não depender de upload.
- Como FISCAL, quero que um cancelamento chegue e seja aplicado à nota em minutos, para não conferir nota cancelada.
- Como ADMIN, quero ver no painel se a captura por NSU está em dia, para confiar no que está no Taxify.
- Como plataforma, quero ligar o NSU escritório a escritório e comparar com o modo atual, para desligar o modo antigo
  com segurança.
- Como FISCAL, quero filtrar a lista de notas por NF-e e NFC-e, para não me afogar nas milhares de NFC-e do varejista.
- Como FISCAL, quero ver os CT-e da empresa, com o papel dela, o tomador, os valores e as NF-e transportadas, para
  conferir o frete sem abrir o XML.
- Como FISCAL, quero que a mesma NFC-e nunca conte duas vezes na apuração, mesmo chegando pelo NSU, pelo modo atual e
  por upload, para confiar no faturamento.

## 5. Regras de negócio

| RN | Regra |
|---|---|
| **NSU-RN-01 Encaixe** | O NSU é o modo `NSU` do provedor `SIEG` (`retrieval = CURSOR`, eventos `INLINE`, tipos `NFE`, `NFCE`, `CTE`, `NFSE`; `CFE` declarado como "recebido e ignorado"). Ele vale para o escritório que tem a linha (`SIEG`, modo `NSU`) em `firm_capture_providers` com `TAXIFY_SIEG_NSU_ENABLED=true`. Nada de "SIEG" no núcleo (FD-RN-41): a estratégia é a `CursorStrategy` da fase P |
| **NSU-RN-02 Escopo do cursor** (P-NSU-01, respondida no teste real) | O NSU é **da API key**, ou seja, do escritório (a API key do SIEG é por escritório, `firm_integrations`). Há **um** `capture_stream` por escritório (`scope_type = FIRM`, `document_type = '*'`, `side = '*'`), e a distribuição segue o NSU-RN-03. O escopo por empresa **não é implementado**. As colunas `scope_type`/`scope_id` do `capture_stream` continuam genéricas (servem a outros provedores), e a variável `TAXIFY_SIEG_NSU_SCOPE` sai |
| **NSU-RN-03 Distribuição por empresa** | Para cada documento recebido, o núcleo extrai os documentos (CNPJ/CPF) dos participantes. NF-e e NFC-e: `emit`, `dest` e `autXML` não contam. CT-e: `emit`, `rem`, `exped`, `receb`, `dest`, `toma3`/`toma4`. NFS-e: prestador, tomador e intermediário. Eventos: a chave do documento e o CNPJ do autor. Depois, casa esses documentos com as empresas **ativas do escritório** (índice por `accounting_firm_id` + CNPJ). Grava uma linha por empresa que casou, como hoje (V16: uma nota entre duas empresas do escritório gera duas linhas). Se **nenhuma empresa casa**, o documento é **descartado sem gravar arquivo nem linha** e só entra na contagem `unmatched` (NSU-RN-12): é dado de quem não é cliente, e não fica no Taxify. Evento sem documento conhecido fica guardado pela chave só se o CNPJ do autor ou a chave casarem com empresa do escritório (FD-RN-07); senão, é descartado e contado. **O descarte é a maior parte do tráfego:** a conta SIEG do escritório traz os documentos de todos os CNPJs que ela monitora (na conta testada, ~7,2 mil por dia), e o Taxify só guarda os das empresas cadastradas. Como o descarte acontece (em memória, sem rastro), está no NSU-RN-36 (P-NSU-09, fechada pelo Tony em 2026-11-28), e o texto da política e dos termos, na seção 5B |
| **NSU-RN-04 Tipos** | NF-e (modelo 55) vira `Nfe`; NFC-e (65) vira `Nfce`; CT-e (57) vira `Cte`; CT-e OS (67) e outros fora do modelo são ignorados e contados `unsupported`. NFS-e (nacional ou ABRASF) vira `Nfse`; a duplicidade ABRASF x nacional segue o E2 da spec irmã. CF-e (SAT/MF-e) é ignorado: não grava e conta `unsupported_cfe`. Evento vira `FiscalDocumentEvent`. Arquivo que não abre ou não é XML fiscal conta `parse_error` e não para o lote |
| **NSU-RN-05 Gravação** | Pela ingestão genérica da remodelagem (`FiscalDocumentIngestionService`): invariantes, chave única por empresa (idempotência, FD-RN-12), duplicidade tratada **por documento** (E1), arquivo gravado **depois** da linha garantida (ou limpo se a linha perder a corrida), origem `SIEG` com `retrieval_mode = NSU` em `fiscal_document_sources` (upsert, FD-RN-31) |
| **NSU-RN-06 Eventos** | Cancelamento registrado (110111, 110112), carta de correção e manifestações chegam na mesma sequência e são aplicados a qualquer tipo pela regra de eventos da remodelagem (FD-RN-07): ligam ao documento da empresa pela chave e aplicam `cancel(eventAt)`. Evento que chega antes do documento fica guardado e é aplicado quando o documento chega. Com isso, a varredura diária de eventos do modo atual (A2) deixa de ser necessária para a empresa com o NSU ligado (NSU-RN-15) |
| **NSU-RN-07 Ciclo do tick** | Por fluxo reservado: (1) `GET consultar-nsu` no máximo a cada `TAXIFY_SIEG_NSU_RANGE_CHECK_MINUTES` (padrão 15). Com `IsSuccess = true`, grava `Data.PrimeiroNsu` em `provider_first_cursor`, `Data.UltimoNsu` em `provider_last_cursor` e `provider_range_checked_at`. Com `IsSuccess = false` ou `IsFailure = true`, registra o código pela lista fechada (NSU-RN-21) a partir de `StatusCode`/`Code`; o `ErrorMessage` **nunca** é gravado nem mostrado. (2) Se `cursor_value ≥ provider_last_cursor`, o fluxo está em dia e sai sem outra chamada. (3) Senão, monta lotes de `cursor_value + 1` até `min(provider_last_cursor, cursor_value + 50)` e chama `POST baixar-xml/nsu/lote` com `{"NumerosNsu": [...]}`. (4) Grava o que veio e avança o cursor (NSU-RN-08). (5) Repete enquanto houver tempo no tick (`TAXIFY_SIEG_NSU_TICK_BUDGET_SECONDS`, padrão 60) e o limite permitir. O endpoint unitário (`POST baixar-xml/nsu`, `{"Nsu": n}`) só é usado no NSU-RN-10. Toda chamada HTTP fica **fora de transação** (C1) |
| **NSU-RN-07a Lote incompleto** | O ZIP do lote traz um XML por NSU encontrado. Se o ZIP trouxer **tantos XMLs quanto NSUs pedidos**, o lote inteiro conta como processado, e o cursor vai ao último NSU do lote. Se trouxer **menos**, o Taxify grava os que vieram (idempotente) e pede os NSUs daquele lote um a um pelo endpoint unitário, para saber quais faltaram e avançar o cursor só até o maior NSU contíguo. Se o nome de cada arquivo dentro do ZIP identificar o NSU (P-NSU-11), esse passo usa o nome e dispensa as chamadas unitárias |
| **NSU-RN-08 Cursor, reserva e duas instâncias** | **Reserva do fluxo:** `UPDATE capture_stream SET lease_owner = :instance, lease_until = :now + lease WHERE id = :id AND (lease_until IS NULL OR lease_until < :now)`; 0 linhas afetadas = outra instância tem o fluxo. **Avanço:** na **mesma transação curta** que grava os documentos do lote, `UPDATE capture_stream SET cursor_value = :new WHERE id = :id AND cursor_value = :expected AND lease_owner = :instance`. Se afetar 0 linhas, a transação é desfeita e o lote é refeito no próximo tick, sem duplicar nada (idempotência por chave). O cursor só avança até o **maior NSU contíguo** processado. Um NSU que falhou deixa o cursor antes dele |
| **NSU-RN-09 Lacuna e cursor para trás** | Dois casos levam ao reposicionamento pela busca do NSU-RN-22: (a) **lacuna**, quando `cursor_value + 1 < provider_first_cursor` (NSUs expirados no SIEG); (b) **cursor para trás**, quando o próximo lote começaria antes do ponto de partida do mês-limite (NSU-RN-22), por exemplo depois de um bloqueio longo. O NSU-RN-24 define quando o (b) é verificado. Nos dois casos o fluxo grava `warning_code = NSU_REPOSITIONED` e a faixa pulada (do cursor até o novo ponto, só números de sequência). O que o salto deixa de fora **dentro do limite** (do dia 1 do mês-limite em diante, só no caso de lacuna) vira tarefa de recuperação pelo modo atual (`recovery_tasks`, `provider_key = SIEG`), por empresa e mês. O que é anterior ao limite **não** é recuperado sozinho (NSU-RN-13) |
| **NSU-RN-10 NSU que não vem** | NSU pedido e não devolvido (vazio, 404 ou o equivalente que o teste mostrar, P-NSU-05) é tentado de novo até `TAXIFY_SIEG_NSU_MAX_RETRIES` (padrão 5) ticks. Depois, é pulado com `warning_code = NSU_SKIPPED`, contado, e o cursor avança. A quantidade de NSUs pulados por dia aparece no painel da plataforma (agregado) |
| **NSU-RN-11 Limite** (P-NSU-06, respondida) | Limites oficiais por endpoint e API key: `nsu/lote` 60 req/min; `nsu` 3.000 req/min; separados do `contar-xmls` e do `baixar-xmls`. No Taxify, `provider_rate_slots` com escopo `CREDENTIAL` (a API key do escritório) e **uma faixa por endpoint** (`lane` = `NSU_BATCH`, `NSU_SINGLE`, `NSU_RANGE`). Tetos prudentes: `TAXIFY_SIEG_NSU_BATCH_PER_MINUTE` = **50** (83% de 60); `TAXIFY_SIEG_NSU_SINGLE_PER_MINUTE` = **300**; `consultar-nsu` sem limite documentado, com teto próprio de 10/min. Contam as duas instâncias juntas. HTTP 429: backoff exponencial (1, 2, 4... até 64 s) com jitter, sem segurar transação, e `warning_code = RATE_LIMITED`. A resposta não traz cabeçalho de limite: o controle é só o nosso contador |
| **NSU-RN-12 Contadores** | Por fluxo e dia de Recife, em `capture_stream_daily_stats`: `requests`, `fetched`, `stored_new`, `duplicated`, `events_stored`, `unmatched`, `outside_pilot` (NSU-RN-35), `unsupported_cfe`, `unsupported_other`, `parse_errors`, `skipped_nsu`, `discarded_too_old` (NSU-RN-23), `old_events_applied` (NSU-RN-23), `search_requests` (NSU-RN-22) e `repositioned_nsus` (NSU-RN-09). Upsert atômico (`INSERT ... ON DUPLICATE KEY UPDATE x = x + :n`), seguro com duas instâncias. Sem CNPJ, chave ou valor |
| **NSU-RN-13 Alcance: mês corrente e mês anterior** (decisão do Tony, 2026-11-27; responde a P-NSU-12) | O NSU só traz documentos emitidos do **dia 1 do mês-limite** em diante. Mês-limite = mês corrente de Recife − `TAXIFY_SIEG_NSU_START_MONTHS_BACK` (padrão 1: corrente + anterior). Esse limite vale para tudo: primeira carga (NSU-RN-22), filtro na gravação (NSU-RN-23), reposicionamento (NSU-RN-09) e empresa nova. **O que fica de fora vai pelo modo atual:** recuperação por página (`baixar-xmls` com período) e "Sincronizar" da empresa, como hoje. Empresa cadastrada depois que o NSU já está ligado não reinicia o cursor da conta: os documentos dela a partir do cadastro chegam pelo NSU; os do mês-limite em diante, de antes do cadastro, vêm por uma tarefa de recuperação pelo modo atual criada no cadastro; os anteriores ao limite, só por "Sincronizar". A primeira carga corre em segundo plano, nos ticks normais, e o painel mostra o progresso (`nsuLag`) |
| **NSU-RN-22 Ponto de partida por busca binária** | Na primeira carga e no reposicionamento (NSU-RN-09), o cursor não começa no `PrimeiroNsu`. O Taxify procura o primeiro NSU cuja **data de referência** (NSU-RN-25) é ≥ 00:00 do dia 1 do mês-limite (Recife). (1) Faixa `[PrimeiroNsu, UltimoNsu]` do `consultar-nsu`. (2) Busca binária com o endpoint **unitário** (`POST baixar-xml/nsu`), lendo só a data do XML e sem gravar nada: NSU do meio com data anterior ao limite leva a busca para a metade de cima, e com data igual ou posterior, para a de baixo. Na faixa de hoje (~651 mil), são ~20 chamadas. (3) NSU sem data utilizável (CF-e, XML ilegível, NSU que não vem) não decide: a busca testa o vizinho seguinte, até `TAXIFY_SIEG_NSU_SEARCH_PROBE_LIMIT` (padrão 10) vizinhos, e falha com `NSU_SEARCH_FAILED` se não achar. (4) **Tolerância à desordem:** a ordem do NSU é a de chegada ao SIEG, não a de emissão. Como a emissão sempre vem antes da chegada, documento emitido depois do limite nunca chega antes dele; a desordem real é de notas antigas chegando tarde e de pequenos atrasos perto do ponto. Por isso o cursor nasce `TAXIFY_SIEG_NSU_START_MARGIN_DAYS` (padrão 1) antes do ponto achado. A margem é convertida em NSUs pela densidade da faixa, `(UltimoNsu − PrimeiroNsu) ÷ 90`, com mínimo de `TAXIFY_SIEG_NSU_START_MARGIN_MIN` (padrão 2.000); na conta testada, ~7,2 mil NSUs. Documentos da margem anteriores ao limite caem no filtro do NSU-RN-23. (5) O resultado (ponto achado, margem e cursor inicial) é gravado em `capture_stream.start_search_*` para auditoria. As chamadas da busca contam na faixa `NSU_SINGLE` |
| **NSU-RN-23 Filtro de idade na gravação (sempre)** | Todo documento do NSU, em qualquer momento e não só na primeira carga, com data de referência **anterior** ao dia 1 do mês-limite é **descartado sem gravar** (nem arquivo) e contado em `discarded_too_old`. **Exceção:** evento (cancelamento, carta de correção, manifestação) de documento **que já está no Taxify** (mesma empresa e chave) é aplicado normalmente (FD-RN-07) e contado em `old_events_applied`, qualquer que seja a data. Evento antigo de documento que não está no Taxify é descartado e contado em `discarded_too_old`, e **não** fica guardado à espera (isso abriria uma fila de eventos de notas que nunca vão chegar pelo NSU). O limite é o mesmo do NSU-RN-13 e é recalculado a cada tick: na virada do mês, o mês-limite anda sozinho |
| **NSU-RN-24 Quando verificar o cursor para trás** | No começo de cada tick, se `provider_last_cursor − cursor_value` passar de `densidade × (dias desde o dia 1 do mês-limite + margem)`, o fluxo roda a busca do NSU-RN-22. Se o ponto achado (menos a margem) for maior que `cursor_value + 1`, o cursor salta para lá (NSU-RN-09 b); senão, segue normal. Sem esse atraso, a busca não roda (zero chamadas extras no dia a dia) |
| **NSU-RN-26 Três gatilhos** (decisão do Tony, 2026-11-27) | (1) **Rotina automática:** o cron (`TAXIFY_SIEG_NSU_CRON`, a cada 5 minutos) usa só o NSU. (2) **"Sincronizar agora"** do painel: roda **na hora** um ciclo do NSU do escritório (segundos) e só cai na fila do modo por período se a empresa continuar com falta no mês (NSU-RN-27). (3) **Pedido manual de período** ("puxar outubro da empresa X"), **primeira carga de empresa nova** (NSU-RN-13) e **recuperação de lacuna** (NSU-RN-09) usam o **modo por período** (`baixar-xmls` com filtro de período e CNPJ), **sempre em fila** e nunca chamando o SIEG dentro da requisição (NSU-RN-28) |
| **NSU-RN-27 "Sincronizar agora"** | Mesmas permissões, recusas, idempotência, cooldown e tetos por escritório da CP-RN-16/17 do painel. Passos: (a) se o escritório tem o NSU ligado, a requisição tenta reservar o fluxo do escritório (lease, NSU-RN-08). Conseguindo, roda ciclos do NSU (NSU-RN-07, sem busca binária) até o fluxo ficar em dia, até `TAXIFY_SIEG_NSU_SYNC_NOW_BUDGET_SECONDS` (padrão 15) ou até acabar a vaga da faixa `NSU_BATCH` no minuto, o que vier antes. Sem a reserva (o cron está rodando naquele momento), o passo é pulado e marcado `BUSY`: o cron já está trazendo. Nenhuma transação fica aberta durante as chamadas (C1). (b) Verifica a falta da empresa no **mês corrente**, para os dois lados: falta = `provider_total − known_gap − imported_any_mode` da linha (`SIEG`, `NFE`, lado, mês) de `capture_period_counts`, em que `imported_any_mode` são as notas da empresa no mês com origem `SIEG` (qualquer modo). A contagem só é usada se `provider_counted_at` tiver até `TAXIFY_SIEG_SYNC_NOW_COUNT_MAX_AGE_MINUTES` (padrão 60) minutos. **A requisição não chama o `contar-xmls`.** (c) Resultado: contagem recente e falta zero nos dois lados = `RESOLVED_BY_NSU` (200), sem pedido na fila. Falta maior que zero, contagem velha ou NSU indisponível = pedido na fila prioritária do modo por período (CP-RN-15: a tentativa prioritária conta de novo e só baixa se houver falta), resposta 202 com o motivo. Escritório sem NSU ligado: comportamento de hoje (202 na fila), com o motivo `NSU_NOT_ENABLED` |
| **NSU-RN-28 Fila do modo por período** | Pedido manual de período, primeira carga de empresa nova e recuperação de lacuna viram tarefas em `recovery_tasks` (existente, V66/V70), com as colunas novas `origin` (`BLOCKED_RECOVERY`, que é o uso de hoje, `MANUAL_PERIOD`, `NEW_COMPANY`, `NSU_GAP`), `provider_key`, `side`, `document_type` (`NFE`, `NFSE`) e `requested_by` (login; nulo para o sistema). Uma tarefa = empresa × mês × lado × tipo; a chave única impede duplicar o mesmo pedido aberto. A fila é atendida pelo agendador do modo por período, com reserva atômica e o mesmo `page_cursor` de hoje. **Limites por escritório:** no máximo `TAXIFY_CAPTURE_PERIOD_MAX_OPEN_PER_FIRM` (padrão 24) tarefas `MANUAL_PERIOD` abertas e `TAXIFY_CAPTURE_PERIOD_MAX_MONTHS_PER_REQUEST` (padrão 12) meses por pedido. As tarefas de sistema (`NEW_COMPANY`, `NSU_GAP`) não contam no teto, mas entram na mesma fila. Ordem: prioridade (Sincronizar agora) > `NSU_GAP` > `NEW_COMPANY` > `MANUAL_PERIOD` > `BLOCKED_RECOVERY`, e dentro de cada uma a mais antiga primeiro. Escritório bloqueado: a fila dele não roda |
| **NSU-RN-29 Limitador do modo por período** (decisão do Tony: o limite é por API key) | `provider_rate_slots` com escopo `CREDENTIAL` (a API key do escritório) e uma faixa por endpoint: `PERIOD_DOWNLOAD` (`baixar-xmls`, 2/min) e `PERIOD_COUNT` (`contar-xmls`, 5/min), contando as duas instâncias. Cada escritório tem a própria vazão, e um escritório com muitos pedidos não atrasa os outros. Isso responde a P-FP-04 da remodelagem (escopo `CREDENTIAL`, não `PLATFORM`). O `SiegRateLimiter` em memória sai na fase P (FD-RN-45) |
| **NSU-RN-25 Data de referência do documento** | É a data de **emissão**, porque é ela que define o mês fiscal, e é a mesma do `issue_date` gravado: NF-e e NFC-e `ide/dhEmi` (leiaute 4.00; `ide/dEmi` nos leiautes antigos); CT-e `ide/dhEmi`; NFS-e nacional `dhEmi` da DPS/NFS-e; NFS-e ABRASF `DataEmissao`. **Evento:** `infEvento/dhEvento`; ele serve à busca binária (aproxima a chegada), e a regra de idade do evento é a da exceção do NSU-RN-23. **Sem data de emissão** (documento sem o campo): usa `protNFe/infProt/dhRecbto` (ou o equivalente do CT-e); sem nenhuma das duas, o documento conta como `parse_error` e não decide a busca. `dhRecbto` **não** é usada como referência principal porque uma nota emitida em outubro e autorizada em novembro (contingência) é de outubro para o Fiscal. Datas com fuso do XML são convertidas para Recife antes da comparação |
| **NSU-RN-14 Paralelo e critério para desligar o modo atual** | Ao ligar o NSU num escritório, o modo atual **continua** por `TAXIFY_SIEG_NSU_PARALLEL_DAYS` (padrão 30) dias. Os dois gravam no mesmo lugar sem duplicar (chave única). Cada um marca a própria origem (`SIEG` + `PERIOD` ou `SIEG` + `NSU`, `fiscal_document_sources.retrieval_mode`), e é isso que permite comparar. **Comparação diária** por empresa, mês, tipo e lado, só nos meses do alcance do NSU (do mês-limite ao corrente, NSU-RN-13): (a) achadas só pelo modo atual há mais de 24 h; (b) achadas só pelo NSU; (c) contagem do `contar-xmls` x entregues pelo NSU. O painel mostra (a), (b) e (c). **Critério para desligar** o modo atual na captura diária do escritório: 30 dias seguidos com (a) = 0 em todas as empresas, nenhuma lacuna sem explicação e (c) fechando. A troca é por escritório, aprovada pelo Tony. Depois dela, o modo atual fica só para o NSU-RN-13 e o NSU-RN-09 |
| **NSU-RN-15 O que o NSU desliga** | Com o NSU ligado e fora do paralelo, o modo atual deixa de fazer, para o escritório: a captura diária de NF-e recebida e emitida, a varredura de eventos (A2) e a busca de NFS-e pelo SIEG (A1). O ADN e a SEFAZ DF-e continuam como fontes oficiais (confronto). Durante o paralelo, nada é desligado |
| **NSU-RN-16 Volume e NFC-e** (P-CX-02 fechada pelo Tony em 2026-11-28) | O NSU traz **todas** as NFC-e das empresas varejistas. O volume e o armazenamento crescem (estimativa na seção 7). NFC-e gravada entra nas consultas de `GoodsInvoice` (apuração, auditoria, relatórios) **desde o primeiro dia**, como hoje entra a NFC-e de upload, sem mês de corte e sem trava por escritório. A regra contra dupla contagem é o NSU-RN-30. A trava antiga (`TAXIFY_SIEG_NSU_NFCE_ALLOWED`, 409 `error.capture.nsu.fiscal.pending`) **não é implementada** |
| **NSU-RN-17 Isolamento** | O fluxo de um escritório só grava em empresas daquele escritório (NSU-RN-03). Documento de empresa de outro escritório que por acaso venha na resposta é tratado como `unmatched`. Rotas e telas seguem a regra de carteira de hoje (404 fora da carteira) |
| **NSU-RN-18 Escritório bloqueado** | O `RoutineGate` para o fluxo. Nada é pedido, e o cursor fica onde está. Ao desbloquear, continua; se o cursor ficou para trás do mês-limite ou expirou, aplica o NSU-RN-09 |
| **NSU-RN-19 Fuso** | Instantes (`lease_until`, `provider_range_checked_at`) em UTC, pela política H2 da spec irmã; o dia dos contadores e o mês das comparações em Recife. O mês do documento é o de `issue_date` como no XML, igual ao modo atual |
| **NSU-RN-20 Credenciais e privacidade** | JWT do integrador (o mesmo `SiegTokenService` de hoje, com `client-id`/`client-secret` da plataforma; se o NSU exigir outro, é a P-NSU-07) + API key do escritório. Nada disso vai para log. Texto de erro do SIEG não é gravado nem mostrado: só os códigos da lista fechada (NSU-RN-21) |
| **NSU-RN-21 Códigos de falha** (`CaptureFailureCode`, lista fechada) | `NSU_GAP`, `NSU_REPOSITIONED`, `NSU_SEARCH_FAILED`, `NSU_SKIPPED`, `RATE_LIMITED`, `PROVIDER_AUTH_FAILED` (401/403), `PROVIDER_UNAVAILABLE` (5xx/timeout), `PROVIDER_BAD_RESPONSE` (ZIP inválido), `CREDENTIAL_MISSING` |
| **NSU-RN-30 Dupla contagem de NFC-e (e de qualquer documento)** (decisão do Tony, 2026-11-28) | **(a) Uma chave, uma linha por empresa.** A mesma chave de acesso nunca gera duas linhas da mesma empresa em `fiscal_documents`: a garantia é a chave única `uk_fiscal_documents_company_key (company_id, access_key)` (V86, já existente no modelo novo) mais a ingestão idempotente (FD-RN-12, NSU-RN-05). A NFC-e que chega pelo NSU, pelo modo por período e por upload vira **uma** linha e até três linhas em `fiscal_document_sources` (uma por origem e modo). **(b) Soma nunca passa pelas origens.** Toda consulta que soma valor (apuração `FiscalDocumentItemRepository.sumByCompanyMonthAndCfop`, relatórios `ReportService`, totais da lista `GoodsInvoiceRepository.totalsByDirection`, auditoria) lê `fiscal_documents`/`fiscal_document_items` e **nunca faz `join` com `fiscal_document_sources`**; filtro por origem é sempre `exists`/`not exists`, como já é em `FiscalDocumentRepository.search`. O reviewer barra `join` com as origens em consulta de soma. **(c) Linhas de empresas diferentes não são duplicidade:** NFC-e de E1 com destinatário CNPJ de E2 (mesmo escritório) vira uma linha `OUTBOUND` em E1 (receita) e uma `INBOUND` em E2 (compra), como a NF-e hoje (V16). **(d) Cancelada não soma** (`status <> CANCELED`, já é assim nas consultas). **(e) NFC-e sem chave:** a invariante I-11 aceita chave nula, e o índice único não protege linha sem chave. A captura (NSU e período) nunca grava NFC-e sem chave; o upload recusa NFC-e sem chave de 44 dígitos (motivo `MISSING_ACCESS_KEY`, NSU-RN-33). **(f) Outra fonte de faturamento:** **não existe hoje no Taxify.** A receita da apuração e dos relatórios vem **só** dos itens das notas de mercadoria (`TaxAssessmentService.revenueByCompany` → `sumByCompanyMonthAndCfop`; o próprio `TaxAssessmentDTO` diz "estimate from the notes in Taxify - not the PGDAS/DCTF"). Não há importação de PGDAS, extrato bancário, Redução Z, SPED ou lançamento manual de receita (procurado no master e na `feature/fiscal-documents` em 2026-11-28); o extrato do e-Fisco é de entradas da Fronteira, não de receita; a Conta Azul é a cobrança do próprio escritório; a NFS-e não entra na soma de itens (I-16); o CT-e não tem itens e não entra (CX-RN-40). **Por isso, nenhum aviso de dupla fonte é criado nesta spec.** Se uma fonte de receita fora das notas for criada no futuro, a spec dela define o aviso. Registro de código: o `RevenueCfops` não classifica CFOP x929 como receita; se o Fiscal apontar outro caso de NF-e que repete uma venda já documentada por NFC-e, vira pergunta (nada é inventado aqui) |
| **NSU-RN-35 Piloto** (decisão do Tony, 2026-11-28) | O piloto é numa **empresa da própria CS Hub com notas emitidas e recebidas**, que o Tony indica quando a implementação estiver pronta. Como o fluxo é por API key (NSU-RN-02), ligar o NSU liga o fluxo do escritório inteiro; para o piloto valer só para a empresa indicada, a distribuição (NSU-RN-03) é restrita pela variável `TAXIFY_SIEG_NSU_PILOT_COMPANY_IDS` (lista de ids; **vazia = todas as empresas do escritório**). Com a lista preenchida, documento que só casa com empresas fora dela é descartado como no NSU-RN-36 e contado em `outside_pilot` (não em `unmatched`); essas empresas seguem 100% pelo modo atual, sem perda. A comparação do paralelo (NSU-RN-14) só considera as empresas da lista. Ao esvaziar a lista (fim do piloto), as demais empresas entram com uma tarefa `NEW_COMPANY` (NSU-RN-13) para os meses do alcance, e o paralelo delas começa nesse dia. Os 30 dias do paralelo e o relatório do NSU-RN-14 respondem a P-NSU-02 |
| **NSU-RN-36 Descarte sem rastro** (P-NSU-09, decisão do Tony, 2026-11-28) | Documento (ou evento) cujo participante não casa com nenhuma empresa ativa do escritório (NSU-RN-03), que fica fora do piloto (NSU-RN-35) ou fora do alcance (NSU-RN-23) é descartado **em memória**: o ZIP do lote é lido como `byte[]`/stream, sem arquivo temporário em disco, sem gravar arquivo no storage, sem linha em nenhuma tabela (nem em `fiscal_documents`, nem em eventos, nem em fila) e sem guardar chave, CNPJ, CPF, nome ou valor em memória além do lote corrente. Só os contadores agregados do NSU-RN-12 (`unmatched`, `outside_pilot`, `discarded_too_old`) registram que ele passou. Exceção de parser (`parse_error`) loga só a classe da exceção e o `streamId`, nunca a mensagem (que pode citar trecho do XML). O mesmo vale para o modo por período: documento que o SIEG devolva sem empresa cadastrada é descartado do mesmo jeito |

## 5A. Telas de NFC-e e CT-e no modelo novo (decisão do Tony, 2026-11-28)

**O que já existe** (branch `feature/fiscal-documents`, conferido em 2026-11-28):
- `Nfce` e `Cte` são subclasses de `FiscalDocument` em `fiscal_documents` (`domain/fiscaldocument/Nfce.java`,
  `Cte.java`), com o bloco T do CT-e (`cte_*`, `sender_*`, `shipper_*`, `receiver_*`, `taker_*`, rota, ICMS e
  `CteParticipation`) já criado pela V86. **`transport_documents`, `transport_document_nfe_refs` e
  `fiscal_xml_events.transport_document_id` não existem e não são criados.**
- A lista de notas (`POST /api/v1/integrations/xmls`, `FiscalDocumentRepository.search`) já devolve `Nfe`, `Nfce` e
  `Cte` juntos (paridade com o modelo antigo); o resumo traz `documentType` (`"NFE"` também para NFC-e,
  `legacyKeyLabel`) e `modelo` (`55`, `65`, `57`), e o front já rotula pelo modelo (`invoice-format.ts`).
- O detalhe, os eventos e o XML (`GET /integrations/xmls/{id}`, `/eventos`, `/xml`) já aceitam CT-e
  (`findXmlDocument` com `type(d) in (Nfe, Nfce, Cte)`).
- O upload (`POST /integrations/xmls/importar`) já grava CT-e como `Cte`, com os campos do parser atual.
- `CteRepository` existe e está vazio; não há rota nem tela de CT-e, nem filtro por tipo na lista, nem
  `fiscal_document_references`.

**Fase 3a da captura ampliada (CX-RN-30 a 37), adaptada:**

| Na spec antiga | Agora |
|---|---|
| CX-RN-30: CT-e em `transport_documents` | `Cte` em `fiscal_documents`. CT-e OS (67) e simplificado: no upload, rejeitados com `UNSUPPORTED_TYPE` (NSU-RN-33); no NSU, `unsupported_other` |
| CX-RN-31: colunas `is_*` | `CteParticipation` (`cte_is_*`), papel principal e direção derivada (FD-RN-08, I-04-T, I-15), já no domínio |
| CX-RN-32 e 33: tomador, valores e ICMS | colunas do bloco T (`cte_taker_role`, `taker_*`, `total_amount` = `vTPrest`, `cte_amount_receivable`, `cte_cargo_value`, `CteIcms`), preenchidas pelo parser completo (B-NSU-06). Caminhos a conferir no MOC do CT-e vigente (D-CX-04) |
| CX-RN-34: `transport_document_nfe_refs` | `fiscal_document_references` (NSU-RN-31) |
| CX-RN-35: evento em `fiscal_xml_events.transport_document_id` | evento em `fiscal_document_events.document_id` (FD-RN-07), já no modelo novo |
| CX-RN-36: faixa SIEG do CT-e | **sai**: o CT-e vem pelo NSU (NSU-RN-04), distribuído por todos os papéis (NSU-RN-03). O tomador apareceu no teste real (`toma4`); a confirmação de que vem sempre é a P-NSU-02, no piloto. Com isso a P-CX-03 deixa de ser necessária para o SIEG |
| CX-RN-37: upload roteando CT-e | NSU-RN-33 |
| CX-RN-38: backfill para `transport_documents` | **sai** (a V87 já converte). Fica só a releitura dos `Cte` copiados sem parser (NSU-RN-34) |
| 8.3: rotas `/api/v1/transport-documents` | mantidas, com os mesmos nomes, lendo `CteRepository` (NSU-RN-32 e seção 6) |

| RN | Regra |
|---|---|
| **NSU-RN-31 NF-e transportadas** | Cada `infDoc/infNFe/chave` do CT-e vira uma linha em `fiscal_document_references` (`reference_kind = CTE_CARRIED_NFE`). **Ligação nos dois sentidos, só dentro da mesma empresa:** ao gravar o CT-e, procura a NF-e/NFC-e da mesma empresa por (`company_id`, `access_key`) e grava `referenced_document_id`; ao gravar uma NF-e, procura as referências da mesma empresa pela chave e preenche o id. Rotina diária idempotente (`TAXIFY_CTE_LINK_CRON`, Recife, chave `TAXIFY_CTE_LINK_ENABLED`) liga o que ficou para trás (NF-e e CT-e gravados ao mesmo tempo em instâncias diferentes), com reserva atômica do job (padrão das rotinas do projeto) e `UPDATE ... WHERE referenced_document_id IS NULL`, seguro com duas instâncias. Documento de outra empresa nunca é ligado, mesmo no mesmo escritório |
| **NSU-RN-32 Tela de CT-e** | Lista por empresa e período (até 366 dias), com filtro por papel (`roles`, qualquer um dos papéis marcados em `CteParticipation`), situação, chave de NF-e transportada e busca por número, nome ou documento de participante. Detalhe com participantes, tomador, rota, valores, ICMS, NF-e transportadas (com link para a nota quando ligada), eventos e download do XML. Documento de pessoa física (CPF) mascarado no detalhe (`***.456.789-**`), como o CX-RN-05. `Cte` copiado sem parser (`metadataVersion = 0`) aparece com os campos que tem e `parsed = false`. O CT-e **sai da lista de notas do front novo** (o backend continua devolvendo, por compatibilidade, NSU-RN-33). **Nenhum total de CT-e entra em total de nota, apuração ou relatório** (CX-RN-40) |
| **NSU-RN-33 Lista de notas com filtro por tipo e upload** | (a) `XmlSearchFilterDTO` ganha `documentTypes` (lista de `NFE`, `NFCE`, `CTE`; opcional). **Ausente ou vazia = `NFE`, `NFCE` e `CTE`, como hoje** (o front antigo segue igual). O front novo manda `["NFE"]` por padrão e oferece "NF-e", "NFC-e" e "NF-e e NFC-e". O mesmo filtro vale em `POST /integrations/xmls/totais`, que soma só os tipos de mercadoria do filtro (`CTE` é ignorado na soma). O resumo ganha `fiscalDocumentType` (`NFE`, `NFCE`, `CTE`), aditivo; `documentType` e `modelo` não mudam. Consulta por `type(d) in :types` (FD-RN-04), que usa `idx_fd_company_type_issue`. (b) **Upload:** o `POST /integrations/xmls/importar` (ZIP ou XML solto) passa o CT-e pelo parser completo (B-NSU-06) e grava `Cte` com papéis e referências. Recusas, sem derrubar o pacote: CT-e em que a empresa não participa de nenhum grupo (`COMPANY_NOT_IN_DOCUMENT`, CX-RN-31; a invariante I-15 continua aceitando o `THIRD_PARTY` antigo copiado), modelo 67 ou outro fora do suportado (`UNSUPPORTED_TYPE`) e NFC-e sem chave de 44 dígitos (`MISSING_ACCESS_KEY`, NSU-RN-30 e). Cada recusa conta em `totalParseErrors` e aparece no campo novo `rejections`. Escritório bloqueado: upload recusado como hoje |
| **NSU-RN-34 Releitura dos CT-e copiados** | Rotina idempotente (na subida e diária, `TAXIFY_CTE_REPARSE_ENABLED`, padrão `true`, porque só relê o que já é do Taxify) relê com o parser completo cada `Cte` com `metadataVersion = 0` cujo XML ainda existe, em lotes de 200 por id (keyset), com reserva atômica do lote. Sem arquivo (expurgado), a linha fica como está (`parsed = false`). Atualização com `version` (otimista); se perder a corrida, pula |

## 5B. Política de privacidade e termos de uso (P-NSU-09, fechada)

**Onde estão:** o texto **não fica no front nem no backend**. A tela de cadastro (`taxify-front`,
`src/app/features/auth/auth.component.html`, linhas ~316 a 344) só mostra links para as URLs de `GET /signup/config`,
que vêm de `TAXIFY_TERMS_URL`, `TAXIFY_PRIVACY_URL` e `TAXIFY_DPA_URL` (`SaasProperties.java`, `application.yaml`,
chaves `terms-url`, `privacy-url`), com a versão em `TAXIFY_TERMS_VERSION` (F1-RN-11 da spec do SaaS). O aceite fica em
`terms_acceptances`. Por isso a mudança é **do documento publicado** (Tony) e da versão, não de código.

**Texto proposto para a política de privacidade** (seção sobre dados de terceiros nos documentos fiscais):

> **Documentos fiscais de terceiros recebidos na captura automática.** Para trazer as notas das empresas que o
> escritório cadastra no Taxify, a captura automática consulta o serviço de documentos fiscais contratado pelo
> escritório (por exemplo, o SIEG) e recebe, em lote, todos os documentos que esse serviço disponibiliza para a conta
> do escritório, inclusive documentos de pessoas e empresas que não estão cadastradas no Taxify. O Taxify lê cada
> documento apenas o suficiente para identificar se ele pertence a uma empresa cadastrada pelo escritório. Os documentos
> que não pertencem a nenhuma empresa cadastrada são descartados imediatamente, na memória do servidor: não são
> gravados, não ficam em arquivo nem em banco de dados, não aparecem em registros de log e não são usados para nenhuma
> outra finalidade. Guardamos apenas a quantidade de documentos descartados, sem nenhum dado que identifique o documento,
> o emitente ou o destinatário. Os documentos das empresas cadastradas seguem as regras de guarda e exclusão descritas
> nesta política.

**Texto proposto para os termos de uso** (seção sobre integrações e responsabilidades do escritório):

> **Captura automática e documentos de terceiros.** Ao conectar ao Taxify a conta de um serviço de documentos fiscais
> (como o SIEG), o escritório declara que tem autorização para consultar os documentos disponíveis nessa conta e
> autoriza o Taxify a recebê-los para identificar os que pertencem às empresas cadastradas. O Taxify guarda somente os
> documentos dessas empresas e descarta os demais no momento do recebimento, sem armazená-los. Cabe ao escritório manter
> cadastradas apenas as empresas que atende e manter atualizadas as autorizações dos seus clientes.

**Acordo de operador (DPA):** recomendo a mesma frase do descarte na descrição do tratamento (o Tony decide; P-NSU-14).

## 6. Contrato da API

As rotas são as da fase P mais estes acréscimos. Todas respeitam a regra de escritório e carteira; fora dela, 404.

| Rota | Quem | Mudança |
|---|---|---|
| `GET /api/v1/capture-panel` e `/companies/{id}` (existentes) | como hoje | **Aditivo.** O canal `SIEG` ganha `mode` (`PERIOD`, `NSU` ou `PARALLEL`) e, com NSU, `nsu: {lagNsu, lastSuccessAt, gap: {from, to} \| null, parallel: {onlyPeriod, onlyNsu, providerCount, nsuDelivered} \| null}`. `lagNsu` = `provider_last_cursor − cursor_value`; com escopo `ACCOUNT`, ele é do escritório e se repete em todas as empresas. Os campos atuais não mudam. Ordenação e paginação, como hoje |
| `GET /api/v1/capture-panel/nsu-comparison` | ADMIN do escritório (escritório inteiro) e FISCAL (carteira) | `period` (`yyyy-MM`, obrigatório), `companyId` (opcional), `documentType`, `side`, `page` (0), `size` (50, máx. 200), `sort` (`onlyPeriod,desc` padrão; desempate `companyId`). 200: página de `{companyId, companyName, documentType, side, period, onlyPeriod, onlyNsu, providerCount, nsuDelivered, status}` com `status` ∈ `MATCHING`, `NSU_MISSING`, `NSU_EXTRA`, `NOT_IN_PARALLEL`. 400 `error.capture.filter.invalid` (`invalidField`) |
| `GET /api/v1/capture-panel/nsu-comparison/keys` | ADMIN e FISCAL (carteira) | `companyId` (obrigatório), `period`, `documentType`, `side`, `kind` (`ONLY_PERIOD` ou `ONLY_NSU`), `page`, `size` (50, máx. 200), ordenado por `issueDate,asc`. 200: página de `{documentId, accessKey, number, issueDate, firstSeenAt}`. A chave aparece porque é da carteira do próprio usuário. 404 fora da carteira |
| `GET /api/v1/platform/capture-panel/nsu` | `@PlatformAdminOnly` | `date` (padrão hoje). 200: **só agregados**, sem escritório nem empresa: `{date, firmsOnNsu, firmsInParallel, requests, fetched, storedNew, unmatched, outsidePilot, unsupportedCfe, skippedNsu, gaps}` (`outsidePilot`, NSU-RN-35) |
| `POST /api/v1/capture-panel/companies/{companyId}/sync-now` (existente, CP-RN-15 a 17) e o equivalente da plataforma | como hoje (ADMIN do escritório; plataforma) | Corpo vazio, como hoje. **Resposta nova** (NSU-RN-27). `200` quando resolveu pelo NSU: `{outcome: "RESOLVED_BY_NSU", nsu: {status: "DONE", fetched, storedNew, eventsStored, cursorFrom, cursorTo, upToDate, durationMs}, month: "yyyy-MM", sides: [{side, providerTotal, knownGap, delivered, missing: 0, countedAt}]}`. `202` quando entrou na fila: `{outcome: "QUEUED", reason: "MISSING" \| "COUNT_STALE" \| "NSU_UNAVAILABLE" \| "NSU_NOT_ENABLED", nsu: {status: "DONE" \| "BUSY" \| "FAILED", ...} \| null, month, sides: [...] \| null, requestId, status: "PENDING", position, estimatedAt}`. Os campos `requestId`, `status`, `position` e `estimatedAt` continuam com o nome e o significado de hoje, então quem só lê o 202 antigo segue funcionando. As recusas e os codes da CP-RN-17 não mudam |
| `POST /api/v1/integrations/xmls/sincronizar` (existente) | `INVOICES_SYNC` + carteira, como hoje | **Passa a enfileirar** (NSU-RN-28): corpo como hoje (`companyId`, datas de emissão e lado opcional). As datas são convertidas em meses inteiros, de no máximo `TAXIFY_CAPTURE_PERIOD_MAX_MONTHS_PER_REQUEST`. Resposta **202** `{requestId, tasks: [{month, side, documentType: "NFE", status}], position, estimatedAt}`. **Quebra o contrato:** a resposta síncrona antiga (`XmlDownloadResultDTO`) sai, e a tela de período passa a mostrar o andamento (F-NSU-03). Erros: 409 `error.capture.period.limit` (`limit`, `inUse`); 400 `error.capture.period.range.invalid` (`maxMonths`); 404 fora da carteira; 403 escritório bloqueado |
| `POST /api/v1/notas-servico/sincronizar` (existente) | como hoje | Mesma fila (`documentType: "NFSE"`). Resposta 202 com o formato acima **e** o campo antigo `iniciado: true`, por compatibilidade, por uma release |
| `GET /api/v1/capture-panel/period-requests` | ADMIN (escritório) e FISCAL (carteira) | `companyId` (opcional), `status` (`PENDING`, `RUNNING`, `DONE`, `FAILED`), `origin`, `page` (0), `size` (50, máx. 200), ordem `createdDate,desc` (desempate `id`). 200: página de `{requestId, companyId, month, side, documentType, origin, status, requestedBy, createdAt, finishedAt, persistedNew, failureCode}` |
| `PUT /api/v1/platform/firms/{firmId}/capture-providers` (fase P) | `@PlatformAdminOnly` | aceita `mode` (`PERIOD`, `NSU`) e `parallelUntil` (data). Sem trava de NFC-e (P-CX-02 fechada) |
### 6.1 Notas, NFC-e e CT-e (seção 5A)

Todas: usuário de escritório, com a carteira de hoje (FISCAL só a carteira, ADMIN o escritório). Empresa fora da
carteira ou de outro escritório: 404 `error.Company.not.found`; documento de outra empresa: 404, sem revelar que
existe. Serviço do plano: `FISCAL_XML_IMPORT` (o mesmo das notas). Teste grátis: igual às notas. Escritório bloqueado:
leitura liberada, upload recusado como hoje.

| Rota | Permissão | Parâmetros | Resposta |
|---|---|---|---|
| `POST /api/v1/integrations/xmls` (existente) | `INVOICES_VIEW` | `XmlSearchFilterDTO` ganha `documentTypes` (lista de `NFE`, `NFCE`, `CTE`; opcional; ausente = os três, como hoje). Ordenação e paginação, como hoje | Página de `FiscalXmlSummaryDTO`, com o campo novo `fiscalDocumentType`. 400 `error.document.filter.invalid` (`invalidField: "documentTypes"`) para valor fora da lista |
| `POST /api/v1/integrations/xmls/totais` (existente) | `INVOICES_VIEW` | mesmo filtro | Como hoje, somando só os tipos de mercadoria do filtro |
| `POST /api/v1/integrations/xmls/importar` (existente) | `INVOICES_SYNC` | como hoje | `XmlDownloadResultDTO` como hoje, mais `rejections: [{fileName, reason}]` com `reason` ∈ `COMPANY_NOT_IN_DOCUMENT`, `UNSUPPORTED_TYPE`, `MISSING_ACCESS_KEY` (aditivo). `fileName` é o nome do arquivo dentro do pacote enviado pelo próprio usuário |
| `GET /api/v1/transport-documents` | `INVOICES_VIEW` | `companyId` (obrigatório), `issuedFrom`, `issuedTo` (datas, obrigatórias, até 366 dias), `roles` (lista de `ISSUER`, `SENDER`, `SHIPPER`, `RECEIVER`, `RECIPIENT`, `TAKER`; qualquer um), `status` (`AUTHORIZED`, `CANCELED`), `nfeAccessKey` (44 dígitos), `search` (número, nome ou dígitos do documento de participante), `page` (0), `size` (50, máx. 200), `sort` (`issueDate`, `serviceTotal`, `number`; padrão `issueDate,desc`, desempate `number`, depois `id`) | 200 página de resumos. 400 `error.transport.filter.invalid` (`invalidField`) |
| `GET /api/v1/transport-documents/{id}` | `INVOICES_VIEW` | — | 200 detalhe; 404 `error.transport.document.not.found` |
| `GET /api/v1/integrations/xmls/{id}/xml` (existente) | `INVOICES_VIEW` | — | Download do XML do CT-e, como hoje para NF-e (a rota já aceita `Cte`). **Nenhuma rota nova de XML** |
| `GET /api/v1/integrations/xmls/{id}/transport-documents` | `INVOICES_VIEW` | — | 200 lista (sem paginação; um NF-e tem poucos CT-e) de resumos dos CT-e da **mesma empresa** que referenciam a nota, ordem `issueDate,desc`; 404 |

**Resumo do CT-e:** `id`, `accessKey`, `number`, `series`, `issueDate`, `status`, `roles` (lista), `primaryRole`,
`issuerName`, `takerRole`, `takerName`, `originUf`, `destinationUf`, `serviceTotal` (`total_amount`), `icmsAmount`,
`nfeRefCount`, `linkedNfeCount`, `parsed`.

**Detalhe do CT-e:** os campos do resumo, mais `cteType`, `serviceType`, `modal`, `cfop`, `operationNature`,
`issuer {document, name, uf}`, `sender`, `shipper`, `receiver`, `recipient`, `taker` (cada um `{document, documentType,
name, uf}`, CPF mascarado), `route {originCityCode, originUf, destinationCityCode, destinationUf}`, `amountReceivable`,
`cargoValue`, `icms {group, cst, base, rate, amount}`, `canceledAt`, `nfeRefs[]` (`referencedAccessKey`,
`referencedDocumentId` ou `null`, `nfeNumber`, `nfeIssuerName`, `fiscalDocumentType`), `events[]` (`type`, `date`),
`xmlAvailable`. Valores em `BigDecimal` com 2 casas como vieram do XML (sem arredondar); alíquota com 4.

| Code | Status | Texto (pt) |
|---|---|---|
| `error.transport.document.not.found` | 404 | "CT-e não encontrado." |
| `error.transport.filter.invalid` | 400 | "Filtro inválido." |
| `error.document.filter.invalid` | 400 | "Filtro inválido." |

### 6.2 Codes da captura

| Code | Status | Texto (pt) |
|---|---|---|
| `error.capture.filter.invalid` | 400 | "Filtro inválido." (existente, se já houver; senão, novo) |
| `error.capture.period.limit` | 409 | "O escritório já tem o máximo de pedidos de período em andamento. Aguarde terminar." |
| `error.capture.period.range.invalid` | 400 | "Escolha um período de no máximo {maxMonths} meses." |

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
| `gap_from`, `gap_to` | bigint | lacuna ou faixa pulada no último reposicionamento (NSU-RN-09) |
| `start_search_point`, `start_search_cursor` | bigint | ponto achado pela busca e cursor inicial depois da margem (NSU-RN-22) |
| `start_search_limit_date` | date | dia 1 do mês-limite usado na busca |
| `start_search_at` | datetime(6) | UTC |
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
| `requests`, `fetched`, `stored_new`, `duplicated`, `events_stored`, `unmatched`, `outside_pilot`, `unsupported_cfe`, `unsupported_other`, `parse_errors`, `skipped_nsu`, `discarded_too_old`, `old_events_applied`, `search_requests`, `repositioned_nsus` | int not null, padrão 0 |
| `created_date`, `last_modified_date` | datetime(6) |

Único `uk_capture_stream_daily_stats (stream_id, stat_date)`. Retenção: 400 dias, apagados pela rotina de limpeza
da fase P.

**Tabela nova: `fiscal_document_references`** (`V9x__FiscalDocumentReferences`, próximo número livre; desenho da
remodelagem, seção 9, linha da captura ampliada)

| Coluna | Tipo | Nota |
|---|---|---|
| `id` | varchar(36) PK | |
| `company_id` | varchar(36) not null | redundante de propósito, para a ligação reversa |
| `document_id` | varchar(36) not null, FK `fiscal_documents` | o CT-e |
| `referenced_access_key` | char(44) not null | `infDoc/infNFe/chave` |
| `referenced_document_id` | varchar(36) null, FK `fiscal_documents` | a NF-e/NFC-e da mesma empresa, quando existe |
| `reference_kind` | varchar(20) not null | `CTE_CARRIED_NFE` (único valor nesta spec) |
| auditoria | | |

Índices: `uk_fdr_document_key (document_id, referenced_access_key, reference_kind)` (idempotência);
`idx_fdr_company_key (company_id, referenced_access_key)` (ligação reversa e "CT-e desta NF-e");
`idx_fdr_unlinked (referenced_document_id, company_id)` (a rotina procura os nulos). Volume: uma linha por NF-e
transportada; uma transportadora com 2.000 CT-e/mês e ~2 NF-e por CT-e gera ~4 mil linhas por mês.

**`fiscal_documents` (tabela grande): nenhuma coluna nem índice novo.** A lista com `documentTypes` e a de CT-e usam
`idx_fd_company_type_issue (company_id, document_type, issue_date)`, que a V86 já cria. Os papéis são filtrados depois
do índice (uma transportadora lê as linhas dela no período).

**Índice de distribuição:** a distribuição (NSU-RN-03) procura empresa por `accounting_firm_id` + CNPJ. Usa o índice
de `companies` por documento se existir (conferir); senão, um cache em memória por tick, com as empresas ativas do
escritório carregadas numa consulta só (sem N+1).

**Volume e custo (hipótese fictícia, para dimensionar):**

Com os números do teste real (conta testada, 2026-11-27):

| Item | Conta | Resultado |
|---|---|---|
| Volume diário da conta | (1.214.885 − 563.888) ÷ 90 dias | ~7,2 mil NSUs por dia (é o total da conta, antes do descarte) |
| Chamadas diárias de lote | 7.200 ÷ 50 | ~144 lotes por dia |
| Tempo diário de captura | 144 ÷ 50 lotes/min | **~3 min por dia**, espalhados pelos ticks |
| `consultar-nsu` | 1 a cada 15 min | 96 por dia por escritório |
| Tráfego | 144 × ~210 KB | ~30 MB por dia baixados e abertos em memória; só os documentos das empresas cadastradas são gravados |
| Primeira carga, dia 1 do mês (só o mês anterior inteiro, ~31 dias, + 1 dia de margem) | 32 dias × 7,2 mil = ~230 mil NSUs ÷ 50 = ~4.600 lotes ÷ 50/min | **~1 h 30 min** |
| Primeira carga, dia 30 do mês (mês anterior + 30 dias do corrente + 1 de margem, ~61 dias) | 61 × 7,2 mil = ~440 mil NSUs ÷ 50 = ~8.800 lotes ÷ 50/min | **~2 h 55 min** |
| Busca do ponto de partida | log₂(651 mil) ≈ 20 chamadas unitárias (+ até 10 vizinhos por passo sem data) | segundos |
| Antes (90 dias inteiros) | 13.030 lotes | ~4 h 20 min |
| Comparação com hoje | o modo atual tem 2 req/min para o escritório inteiro | o NSU tira do `baixar-xmls` a captura diária e os eventos (NSU-RN-15) |
| Armazenamento | depende de quantos documentos são de empresas cadastradas; medir no piloto (`stored_new` ÷ `fetched`) | — |
| Custo no SIEG | o SIEG não cobra por volume (Tony, 2026-11-28) | sem custo por documento; só os limites de chamada |

## 8. Variáveis de ambiente

| Variável | Padrão | Quem liga |
|---|---|---|
| `TAXIFY_SIEG_NSU_ENABLED` | `false` | Tony |
| `TAXIFY_SIEG_NSU_CRON` | `0 */5 * * * *` (Recife) | dev |
| `TAXIFY_SIEG_NSU_BATCH_SIZE` | `50` (máximo do lote) | dev |
| `TAXIFY_SIEG_NSU_BATCH_PER_MINUTE` | `50` (oficial: 60) | Tony |
| `TAXIFY_SIEG_NSU_SINGLE_PER_MINUTE` | `300` (oficial: 3.000) | Tony |
| `TAXIFY_SIEG_NSU_RANGE_PER_MINUTE` | `10` | dev |
| `TAXIFY_SIEG_NSU_RANGE_CHECK_MINUTES` | `15` | dev |
| `TAXIFY_SIEG_NSU_TICK_BUDGET_SECONDS` | `60` | dev |
| `TAXIFY_SIEG_NSU_LEASE_SECONDS` | `300` | dev |
| `TAXIFY_SIEG_NSU_MAX_RETRIES` | `5` | dev |
| `TAXIFY_SIEG_NSU_START_MONTHS_BACK` | `1` (corrente + anterior) | Tony |
| `TAXIFY_SIEG_NSU_SYNC_NOW_BUDGET_SECONDS` | `15` | dev |
| `TAXIFY_SIEG_SYNC_NOW_COUNT_MAX_AGE_MINUTES` | `60` | dev |
| `TAXIFY_CAPTURE_PERIOD_MAX_OPEN_PER_FIRM` | `24` | Tony |
| `TAXIFY_CAPTURE_PERIOD_MAX_MONTHS_PER_REQUEST` | `12` | Tony |
| `TAXIFY_SIEG_PERIOD_DOWNLOAD_PER_MINUTE` | `2` (por API key) | Tony |
| `TAXIFY_SIEG_PERIOD_COUNT_PER_MINUTE` | `5` (por API key) | Tony |
| `TAXIFY_SIEG_NSU_START_MARGIN_DAYS` | `1` | dev |
| `TAXIFY_SIEG_NSU_START_MARGIN_MIN` | `2000` NSUs | dev |
| `TAXIFY_SIEG_NSU_SEARCH_PROBE_LIMIT` | `10` | dev |
| `TAXIFY_SIEG_NSU_PARALLEL_DAYS` | `30` | Tony |
| `TAXIFY_SIEG_NSU_PILOT_COMPANY_IDS` | vazio (= todas as empresas do escritório). No piloto, o id da empresa indicada pelo Tony | Tony |
| `TAXIFY_CTE_LINK_ENABLED` | `true` (só liga documentos que já são do Taxify) | dev |
| `TAXIFY_CTE_LINK_CRON` | `0 40 5 * * *` (Recife) | dev |
| `TAXIFY_CTE_REPARSE_ENABLED` | `true` (só relê XML que já é do Taxify) | dev |

A variável `TAXIFY_SIEG_NSU_NFCE_ALLOWED` **sai** (P-CX-02 fechada).

## 9. Logs
- `capture.nsu.tick`: `streamId`, escopo, NSU inicial e final, quantidades (as do NSU-RN-12) e ms.
- `capture.nsu.gap`: `streamId` e a faixa de NSU (números de sequência, que não são dado de cliente).
- `capture.nsu.lease.lost` e `capture.nsu.cursor.conflict`: `streamId`.
- `cte.link.run`: `linked`, `pending`, `durationMs`. `cte.reparse.run`: `reparsed`, `withoutFile`, `durationMs`.
- `capture.nsu.parse.error`: `streamId` e a classe da exceção, nunca a mensagem (NSU-RN-36).
- Nunca: CNPJ, chave, nome, valor, corpo da resposta do SIEG, trecho do XML ou a API key. Documento descartado não
  gera nenhuma linha de log própria; só soma no contador.

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
| CA-NSU-08 | Dado um cursor 100 e `consultar-nsu` = 250..400, então o fluxo grava `NSU_GAP`, reposiciona pela busca do NSU-RN-22 e cria tarefas de recuperação só para os meses do alcance (do mês-limite em diante) | 09, 22 |
| CA-NSU-19 | Dada a faixa real (563.888 a 1.214.885) num simulador com datas crescentes e ruído, quando a primeira carga roda em 27/11/2026, então a busca acha o primeiro NSU com emissão ≥ 01/10/2026 00:00 (Recife) em até ~20 passos, e o cursor nasce uma margem antes | 22 |
| CA-NSU-20 | Dada uma NF-e emitida em 15/09/2026 que chega pelo NSU em 27/11/2026, então ela não é gravada e `discarded_too_old` soma 1 | 23 |
| CA-NSU-21 | Dado o cancelamento, em 27/11/2026, de uma NF-e de setembro **já gravada** no Taxify, então o cancelamento é aplicado e `old_events_applied` soma 1; se a nota não estiver no Taxify, o evento é descartado e não fica à espera | 23 |
| CA-NSU-22 | Dada a desordem perto do ponto (uma nota emitida em 01/10 com NSU anterior ao ponto achado, dentro da margem), então ela é gravada | 22 |
| CA-NSU-23 | Dada a virada de 30/11 para 01/12/2026, então o mês-limite passa a novembro, e documentos de outubro passam a ser descartados sem nenhuma ação manual | 13, 23 |
| CA-NSU-24 | Dado o fluxo em dia, então a busca binária não roda no tick | 24 |
| CA-NSU-25 | Dado F1 com NSU ligado e a contagem da empresa feita há 20 min, quando o ADMIN clica "Sincronizar agora" e o ciclo traz as notas que faltavam, então a resposta é 200 `RESOLVED_BY_NSU` em até ~15 s e nenhum pedido entra na fila | 27 |
| CA-NSU-26 | Dado que depois do ciclo ainda falta nota no mês (ou a contagem tem mais de 60 min), então a resposta é 202 `QUEUED` com `reason` `MISSING` (ou `COUNT_STALE`), `requestId` e posição, e a requisição não chamou o `contar-xmls` | 27 |
| CA-NSU-27 | Dado o cron segurando o fluxo do escritório, quando o ADMIN clica, então `nsu.status = BUSY`, e a decisão segue pelo passo (b) | 27 |
| CA-NSU-28 | Dado um pedido "outubro da E1", então a resposta é 202 com as tarefas (2026-10, RECEIVED e ISSUED), nenhuma chamada ao SIEG sai na requisição, e a fila atende respeitando 2 `baixar-xmls` e 5 `contar-xmls` por minuto por API key, contando as duas instâncias | 28, 29 |
| CA-NSU-29 | Dado F1 com 24 tarefas manuais abertas, quando pede mais um mês, então recebe 409 `error.capture.period.limit` com `limit` 24 e `inUse` 24 | 28 |
| CA-NSU-30 | Dados F1 e F2 com pedidos de período ao mesmo tempo, então cada escritório usa a própria API key, e a vazão de um não reduz a do outro | 29 |
| CA-NSU-31 | Dada uma empresa nova cadastrada em 27/11/2026 em F1 com NSU, então nascem tarefas `NEW_COMPANY` para 2026-10 e 2026-11 nos dois lados, e nada anterior | 13, 28 |
| CA-NSU-09 | Dado o SIEG respondendo 429, então a instância espera com backoff fora de transação, registra `RATE_LIMITED` e não passa dos tetos por endpoint (`TAXIFY_SIEG_NSU_BATCH_PER_MINUTE` e `TAXIFY_SIEG_NSU_SINGLE_PER_MINUTE`) contando as duas instâncias | 11 |
| CA-NSU-10 | Dado o paralelo, quando o modo atual acha uma chave que o NSU não trouxe em 24 h, então a comparação mostra `NSU_MISSING` com a chave na lista `ONLY_PERIOD` | 14 |
| CA-NSU-11 | Dado o paralelo, então o `Skip` do modo atual conta só as origens `SIEG`/`PERIOD`: notas trazidas pelo NSU não mudam a página pedida | 7 (modelo), 14 |
| CA-NSU-12 | Dado um FISCAL sem a empresa E2 na carteira, quando pede a comparação ou as chaves de E2, então recebe 404 | 17 |
| CA-NSU-13 | Dado F2 bloqueado, então nenhum NSU é pedido, e ao desbloquear o cursor continua | 18 |
| CA-NSU-14 | Dado F1 com NFC-e emitida, quando a plataforma liga o NSU, então liga (200), sem trava, e as NFC-e gravadas pelo NSU entram na apuração do mês | 16 |
| CA-NSU-32 | Dada a NFC-e K de E1 gravada por upload, quando a mesma chave chega pelo NSU e depois pelo modo por período, então E1 tem **uma** linha de K, três linhas de origem, e a receita do mês na apuração e no relatório conta K uma vez | 30 |
| CA-NSU-33 | Dado o NSU e o upload gravando a mesma chave ao mesmo tempo em duas instâncias, então existe uma linha só, nenhuma falha registrada, e a soma conta uma vez | 30 |
| CA-NSU-34 | Dado `TAXIFY_SIEG_NSU_PILOT_COMPANY_IDS` = E1, quando chega um documento só de E2 (cadastrada em F1), então nada é gravado, `outside_pilot` soma 1, e a comparação do paralelo não lista E2 | 35 |
| CA-NSU-35 | Dado um lote com documentos de CNPJs não cadastrados, então nenhum arquivo é criado (storage nem temporário), nenhuma linha em tabela, nenhum log com CNPJ, chave ou trecho de XML, e só `unmatched` muda | 36 |
| CA-NSU-36 | Dada a lista de notas de E1 sem `documentTypes`, então vêm NF-e, NFC-e e CT-e como hoje; com `["NFE"]`, só NF-e; com `["NFCE"]`, só NFC-e; com `["XPTO"]`, 400 `error.document.filter.invalid` | 33 |
| CA-NSU-37 | Dado um CT-e de E2 enviado por upload, então ele aparece em `GET /transport-documents` com papéis, tomador, valores e NF-e transportadas; um CT-e em que E1 não participa, enviado em E1, é recusado com `COMPANY_NOT_IN_DOCUMENT` e o resto do pacote segue | 32, 33 |
| CA-NSU-38 | Dado um CT-e que referencia 2 NF-e, uma já em E3, então `nfeRefCount = 2` e `linkedNfeCount = 1`; quando a segunda chega, `linkedNfeCount = 2` (na gravação ou pela rotina diária) | 31 |
| CA-NSU-39 | Dado um FISCAL sem E2 na carteira, quando pede a lista, o detalhe ou o XML de um CT-e de E2, então recebe 404 | 32, 17 |
| CA-NSU-15 | Dado um lote de 50 NSUs cujo ZIP traz 48 XMLs, então os 48 são gravados, os 50 NSUs são pedidos um a um (ou identificados pelo nome do arquivo, P-NSU-11), e o cursor para antes do primeiro NSU que faltou | 07a |
| CA-NSU-17 | Dado `consultar-nsu` com `IsSuccess = false`, então o fluxo registra o código da lista fechada e o `ErrorMessage` não aparece em log, banco nem tela | 07, 20 |
| CA-NSU-18 | Dadas duas instâncias, quando as duas pedem lotes no mesmo minuto, então a soma não passa de 50 lotes por minuto por API key | 11 |
| CA-NSU-16 | Dado o NSU 120 sem resposta por 5 ticks, então ele é pulado com `NSU_SKIPPED`, contado, e o cursor segue | 10 |

## 11. Casos de teste

Dados fictícios. F1 com E1 (CNPJ 11.222.333/0001-81) e E2 (12.345.678/0001-95). Fornecedor fora da carteira com o
CNPJ fictício 11.444.777/0001-61. Nos casos de CT-e (CT-NSU-37 a 44), E2 é a transportadora e E3
(33.444.555/0001-81, também de F1) a indústria tomadora do frete.

| CT | Cenário | Esperado | CA |
|---|---|---|---|
| CT-NSU-01 | Cursor 100; NSUs 101 a 110: 6 NF-e de E1, 1 NFC-e de E2, 1 CT-e com E1 tomadora, 1 evento de cancelamento de uma NF-e de E1 e 1 CF-e | 6 `Nfe` + 1 `Nfce` + 1 `Cte` (`TAKER`, `INBOUND`) gravados; evento aplicado; CF-e ignorado; cursor 110; estatísticas: `fetched` 10, `stored_new` 8, `events_stored` 1, `unsupported_cfe` 1 | 01, 04, 05 |
| CT-NSU-02 | NF-e do fornecedor fora da carteira para um CNPJ que não é de F1 | `unmatched` 1; nenhum arquivo no storage | 03 |
| CT-NSU-03 | NF-e de E1 para E2 | 2 linhas: E1 `ISSUER`/`OUTBOUND` e E2 `RECIPIENT`/`INBOUND` | 02 |
| CT-NSU-04 | Duas instâncias; A reserva às 21:00:00 UTC com lease de 300 s; B tenta às 21:01 | B não reserva; às 21:05:01, se A não renovou, B reserva, e um avanço de A depois disso afeta 0 linhas | 06 |
| CT-NSU-05 | Queda entre a gravação e o commit | Nada gravado; o próximo tick grava os mesmos 10, sem duplicar | 07 |
| CT-NSU-06 | Hoje 27/11/2026 (mês-limite = outubro); cursor 100, faixa 250..400; a busca acha o ponto 300; margem de 20 NSUs (simulador) | Cursor 279 (próximo lote a partir de 280 = 300 − 20); `NSU_GAP` com faixa pulada 101..279 (101..249 expirados, 250..279 antes da margem); `recovery_tasks` para 2026-10 e 2026-11 por empresa; **nenhuma** para 2026-09 (fora do alcance; só "Sincronizar") | 08 |
| CT-NSU-07 | 429 três vezes seguidas | Esperas de ~1, 2 e 4 s (com jitter); sucesso na 4ª; `RATE_LIMITED` registrado | 09 |
| CT-NSU-08 | Paralelo: o modo atual traz K1 de E1 às 10:00; o NSU não traz até 10:00 do dia seguinte | `NSU_MISSING` em (E1, mês, NFE, RECEIVED) com `onlyPeriod` = 1 e K1 na lista | 10 |
| CT-NSU-09 | Paralelo: E1 com 37 notas recebidas no mês, 30 pelo modo atual e 7 só pelo NSU | `Skip` do modo atual = **30**, não 37 | 11 |
| CT-NSU-10 | Virada de mês: NF-e emitida 31/10/2026 23:50 (Recife) e recebida pelo NSU em 01/11 | Conta em 2026-10 na comparação; o contador diário fica em 01/11 | — |
| CT-NSU-11 | F1 com uma NFC-e emitida no mês (fase 1) | Liga normalmente (sem 409) | 14 |
| CT-NSU-12 | Lote 1.000.001 a 1.000.050; o ZIP traz 48 XMLs; faltam 1.000.020 e 1.000.035 | 48 gravados; 50 chamadas unitárias (ou nenhuma, se o nome do arquivo trouxer o NSU); cursor em 1.000.019; os dois NSUs entram na regra de nova tentativa do NSU-RN-10 | 15 |
| CT-NSU-13 | Em 27/11/2026, `consultar-nsu` = `{IsSuccess: true, Data: {PrimeiroNsu: 563888, UltimoNsu: 1214885}}`, cursor nulo; no simulador, o primeiro NSU emitido ≥ 01/10/2026 é o 787.000 | Densidade = 650.997 ÷ 90 ≈ 7.233 NSUs/dia; margem = 7.233; busca com ≤ 20 passos acha 787.000; cursor nasce em 779.766 (787.000 − 7.233 − 1); primeiro lote 779.767 a 779.816; `nsuLag` = 435.119; ~8.700 lotes, ~2 h 55 min | 19 |
| CT-NSU-16 | Na margem (779.767 a 786.999), 7.000 documentos emitidos em setembro e 233 emitidos em 01/10 | 7.000 em `discarded_too_old`; os 233 de E1/E2 gravados | 20, 22 |
| CT-NSU-17 | NSU do meio da busca é um CF-e (sem data utilizável) | A busca testa o vizinho seguinte; a decisão usa o primeiro com data; `search_requests` inclui as tentativas | 19 |
| CT-NSU-18 | Cancelamento de K1 (NF-e de E1 emitida em 10/09/2026, já gravada) chega em 27/11; cancelamento de K9 (setembro, não está no Taxify) | K1 `CANCELED`, `old_events_applied` 1; K9 descartado, `discarded_too_old` 1, sem linha em eventos | 21 |
| CT-NSU-19 | NF-e emitida 30/09/2026 23:59 (Recife) com `dhEmi` -03:00 e outra 01/10/2026 00:00 | A primeira é descartada; a segunda, gravada (comparação em Recife) | 20 |
| CT-NSU-20 | NF-e emitida em contingência em 30/09 e autorizada (`dhRecbto`) em 02/10 | Descartada: a referência é `dhEmi` (NSU-RN-25) | 20 |
| CT-NSU-21 | 01/12/2026, primeiro tick | Mês-limite = novembro; NF-e de outubro que chegar é descartada; nenhuma configuração mudou | 23 |
| CT-NSU-22 | E1, nov/2026, RECEIVED: `provider_total` 40, `known_gap` 0, contada há 20 min; antes do clique, 37 notas `SIEG`; o ciclo do NSU traz 3 de E1; ISSUED: 12 de 12 | 200 `RESOLVED_BY_NSU`; `sides` = [{RECEIVED, 40, 0, 40, missing 0}, {ISSUED, 12, 0, 12, missing 0}] | 25 |
| CT-NSU-23 | Igual, mas o ciclo traz só 1 (faltam 2) | 202 `QUEUED`, `reason = MISSING`, `sides[0].missing = 2`; pedido prioritário criado; na fila, ele conta de novo e baixa só o lado RECEIVED | 26 |
| CT-NSU-24 | Contagem de E1 feita há 3 h | 202 `QUEUED`, `reason = COUNT_STALE`, depois do ciclo do NSU | 26 |
| CT-NSU-25 | F1 sem NSU ligado | 202 `QUEUED`, `reason = NSU_NOT_ENABLED`, `nsu = null`; o resto como hoje | 26 |
| CT-NSU-26 | Pedido de E1 de 01/01/2026 a 31/12/2026 (12 meses) | 202 com 24 tarefas (12 meses × 2 lados); um pedido de 13 meses recebe 400 `error.capture.period.range.invalid` com `maxMonths` 12 | 28 |
| CT-NSU-27 | Duas instâncias atendendo a fila de F1 no mesmo minuto | No máximo 2 `baixar-xmls` e 5 `contar-xmls` naquele minuto pela API key de F1 | 28 |
| CT-NSU-28 | O mesmo pedido "outubro da E1" feito duas vezes | A segunda devolve as mesmas tarefas abertas (chave única), sem duplicar nem contar no teto | 28 |
| CT-NSU-14 | Duas instâncias com 40 lotes cada, prontos no mesmo minuto | 50 lotes saem naquele minuto e 30 ficam para o seguinte | 18 |
| CT-NSU-15 | Amostra com 5 documentos e 7 CNPJs/CPFs (fictícios), dos quais só E1 é cadastrada em F1 | Só os documentos em que E1 participa são gravados; os demais ficam em `unmatched` | 03 |
| CT-NSU-29 | E1 (varejo) em 11/2026: NFC-e K1 de R$ 100,00 (CFOP 5102, por upload em 03/11), K2 de R$ 50,00 (5102, pelo NSU) e K1 de novo pelo NSU; K3 de R$ 30,00 (5102, NSU) cancelada | Linhas de E1 no mês: K1, K2, K3; receita de revenda na apuração = 100,00 + 50,00 = **R$ 150,00** (K1 uma vez; K3 cancelada fora); `fiscal_document_sources` de K1: `UPLOAD`/`NONE` e `SIEG`/`NSU` | 32 |
| CT-NSU-30 | NFC-e K4 de E1 com destinatário CNPJ de E2 (mesmo escritório), R$ 80,00, CFOP 5102 | E1: linha `OUTBOUND`, receita +80,00; E2: linha `INBOUND`, compra +80,00 (não é dupla contagem) | 32 |
| CT-NSU-31 | Upload de NFC-e com `infNFe/@Id` sem 44 dígitos | Recusada com `MISSING_ACCESS_KEY`; `totalParseErrors` 1; o pacote segue | — (NSU-RN-30 e, 33) |
| CT-NSU-32 | Duas instâncias gravando K5 de E1 (NSU e upload) no mesmo segundo | 1 linha; a perdedora trata a violação de chave única como "já existia"; nenhuma falha no log | 33 |
| CT-NSU-33 | Piloto com E1; documento de E1 com destinatário E2; documento só de E2 | O primeiro grava só em E1 (E2 fora do piloto); o segundo é descartado, `outside_pilot` 1 | 34 |
| CT-NSU-34 | Fim do piloto em 15/12/2026 (lista esvaziada) | E2 ganha tarefas `NEW_COMPANY` de 2026-11 e 2026-12 nos dois lados; o paralelo de E2 começa em 15/12 | 34 |
| CT-NSU-35 | Lote de 50 com 47 documentos de CNPJs não cadastrados e um XML que quebra o parser | Nenhum arquivo em storage nem em pasta temporária; `unmatched` 47; `parse_errors` 1; o log do erro tem só `streamId` e a classe da exceção | 35 |
| CT-NSU-36 | Lista de E1 (10 NF-e, 3.000 NFC-e, 2 CT-e no mês) sem `documentTypes`, com `["NFE"]`, com `["NFE","NFCE"]` e com `["CTE"]` | 3.012; 10; 3.010; 2. `/totais` com `["CTE"]` = zeros | 36 |
| CT-NSU-37 | CT-e de E2 (emitente), `toma3/toma = 3`, destinatário E3, `vTPrest` R$ 1.250,00, `ICMS00` base 1.250,00, alíquota 12,0000, ICMS 150,00 (fictícios), duas chaves de NF-e | Em E2: `roles = [ISSUER]`, `takerRole = RECIPIENT`, `serviceTotal = 1250.00`, `icms.group = ICMS00`, `icms.amount = 150.00`, `nfeRefCount = 2` | 37 |
| CT-NSU-38 | O mesmo CT-e por upload em E3 | Linha separada em E3 com `roles = [RECIPIENT, TAKER]` | 37 |
| CT-NSU-39 | CT-e modelo 67 no upload | `UNSUPPORTED_TYPE`; pacote segue | 37 |
| CT-NSU-40 | Detalhe de CT-e com remetente pessoa física (CPF fictício 123.456.789-09) | `sender.document = "***.456.789-**"` | 37 |
| CT-NSU-41 | `issuedFrom=2026-01-01&issuedTo=2027-01-02` | 400 `error.transport.filter.invalid`, `invalidField = issuedTo` (367 dias) | — (seção 6.1) |
| CT-NSU-42 | NF-e chega depois do CT-e, em outra instância, no mesmo segundo | Ligada na gravação ou, no pior caso, pela rotina diária; rotina rodando nas duas instâncias liga uma vez | 38 |
| CT-NSU-43 | `Cte` copiado pela V87 (`metadataVersion = 0`), um com XML e um expurgado; rotina de releitura roda duas vezes | O com XML ganha papéis e valores e `parsed = true`; o expurgado fica `parsed = false`; a segunda rodada não muda nada | — (NSU-RN-34) |
| CT-NSU-44 | `GET /integrations/xmls/{id}/transport-documents` de NF-e de E3, com um CT-e de E3 e um CT-e de E2 que referenciam a mesma chave | Só o CT-e de E3 | 38 |
| CT-NSU-45 | Escritório bloqueado: lista de CT-e e upload | Lista 200; upload recusado como hoje | — |

## 12. Tarefas

| Tarefa | Agente | Entrega | Depende de |
|---|---|---|---|
| D-NSU-01 | Tony + dev | **Feito em 2026-11-27** (seção 0.1). Falta: abrir um ZIP de lote para ver se o nome do arquivo traz o NSU (P-NSU-11), sem guardar XML de cliente fora do Taxify | — |
| B-NSU-01 | backend | Acréscimo `retrieval_mode` em `fiscal_document_sources` (na remodelagem, antes da V86 ir para produção) | remodelagem |
| B-NSU-02 | backend | Modo `NSU` do `SiegCaptureProvider`: `GET consultar-nsu` (resposta da 0.1), `POST baixar-xml/nsu/lote` (`NumerosNsu`, até 50) como caminho principal e `POST baixar-xml/nsu` para o lote incompleto (NSU-RN-07a), leitura do ZIP com vários XMLs (documentos e eventos), 429 com backoff, faixas de limite por endpoint (NSU-RN-11) | fase P |
| B-NSU-03 | backend | Distribuição por empresa (NSU-RN-03), escopo `ACCOUNT`, com o cache de empresas ativas por escritório | B-NSU-02 |
| B-NSU-04 | backend | `CursorStrategy` com reserva, avanço condicional, lacuna, NSU pulado, contadores (`capture_stream_daily_stats`) | fase P |
| B-NSU-07 | backend | Busca binária do ponto de partida com margem e vizinhos (NSU-RN-22, 24), leitura só da data (NSU-RN-25), filtro de idade com a exceção de eventos (NSU-RN-23), reposicionamento e recuperação limitada ao alcance (NSU-RN-09, 13), simulador de faixa para os testes | B-NSU-02, B-NSU-04 |
| B-NSU-05 | backend | Paralelo e comparação (NSU-RN-14), `Skip` por `retrieval_mode = PERIOD`, desligar o modo atual por escritório (NSU-RN-15), rotas da seção 6 | B-NSU-04 |
| B-NSU-06 | backend | Parser completo do CT-e (modelo da spec anterior, CX-RN-31 a 34) e `fiscal_document_references` | remodelagem |
| B-NSU-08 | backend | "Sincronizar agora" com ciclo do NSU na requisição e decisão pela falta (NSU-RN-27), com a resposta nova; fila do modo por período em `recovery_tasks` (colunas novas, origens, prioridade, tetos, NSU-RN-28); `POST /integrations/xmls/sincronizar` e `/notas-servico/sincronizar` enfileirando; `GET /capture-panel/period-requests`; faixas `PERIOD_DOWNLOAD`/`PERIOD_COUNT` por API key (NSU-RN-29); codes novos em `errors_pt`/`errors_en` | B-NSU-04, fase P |
| F-NSU-03 | frontend | "Sincronizar agora": mostrar "Resolvido pelo NSU" (com quantas notas chegaram) ou "Na fila" (motivo e posição). Tela de período: pedido vira acompanhamento em lista (`period-requests`), em vez de esperar a resposta síncrona | B-NSU-08 |
| F-NSU-01 | frontend | Painel: modo, `nsuLag`, lacuna e o resumo do paralelo; tela de comparação com a lista de chaves | B-NSU-05 |
| F-NSU-02 | frontend | Console da plataforma: modo `NSU`, `parallelUntil` e o painel agregado (com `outsidePilot`) | B-NSU-05 |
| Q-NSU-1 | QA | CT-NSU-01 a 28 com um SIEG falso (WireMock) que reproduz a forma confirmada no D-NSU-01 (JSON do `consultar-nsu`, ZIP unitário e ZIP de lote com 50 XMLs), com duas instâncias reais no local | B-NSU-05 |
| Q-NSU-2 | QA | Paralelo real na empresa piloto da CS Hub (NSU-RN-35) por 30 dias, com o relatório do critério do NSU-RN-14 para o Tony; responde a P-NSU-02 | go-live |

**Acréscimos de 2026-11-28:**

| Tarefa | Agente | Entrega | Depende de |
|---|---|---|---|
| B-NSU-09 | backend | Dupla contagem e descarte: teste de regressão das somas (apuração, relatório, totais) com a mesma chave em três origens e sem `join` com `fiscal_document_sources` (NSU-RN-30); recusa de NFC-e sem chave no upload; descarte sem rastro e log só com a classe da exceção (NSU-RN-36); restrição do piloto `TAXIFY_SIEG_NSU_PILOT_COMPANY_IDS` com o contador `outside_pilot` e as tarefas `NEW_COMPANY` ao esvaziar (NSU-RN-35); remoção da trava de NFC-e | B-NSU-03, B-NSU-04 |
| B-NSU-10 | backend | Telas de NFC-e e CT-e (seção 5A e 6.1): `documentTypes` em `XmlSearchFilterDTO` (lista e totais) e `fiscalDocumentType` no resumo; `GET /transport-documents` e `/{id}` lendo `CteRepository`; `GET /integrations/xmls/{id}/transport-documents`; `rejections` no upload; releitura dos `Cte` copiados (NSU-RN-34); rotina de ligação (NSU-RN-31); codes em `errors_pt`/`errors_en` | B-NSU-06 |
| F-NSU-04 | frontend | Lista de notas (`/notas-fiscais`): filtro "NF-e / NFC-e / NF-e e NFC-e" com NF-e por padrão, mandando `documentTypes` na lista e nos totais; CT-e sai da lista; modal de importação passa a dizer "NF-e, NFC-e ou CT-e" e mostra as `rejections` com o motivo em pt | B-NSU-10 (contrato fechado na 6.1; pode começar em paralelo) |
| F-NSU-05 | frontend | Tela "CT-e" da empresa (aba ou rota `/notas-fiscais/ct-e`, a critério do front): filtros por período, papel, situação, chave de NF-e e busca; detalhe com participantes, tomador, rota, valores, ICMS, NF-e transportadas (link para a nota quando ligada), eventos e download do XML (rota existente); aviso "dados resumidos" quando `parsed = false`; botão de importar (mesmo modal). Na NF-e, bloco "CT-e desta nota" | B-NSU-10 (contrato fechado; pode começar em paralelo) |
| Q-NSU-3 | QA | CT-NSU-29 a 45 por HTTP real no MySQL local, com duas instâncias nos casos de concorrência; isolamento por carteira e escritório; telas F-NSU-04/05 com Chrome headless | B-NSU-09, B-NSU-10, F-NSU-04, F-NSU-05 |
| D-NSU-02 | Tony (PO apoia) | Publicar nas URLs de `TAXIFY_PRIVACY_URL` e `TAXIFY_TERMS_URL` (e, se decidir, `TAXIFY_DPA_URL`) os parágrafos da seção 5B e subir `TAXIFY_TERMS_VERSION`. Sem código | antes de ligar fora do piloto |
| F-NSU-06 | frontend | **Só se o Tony escolher a opção (b) da P-NSU-14:** aviso aos escritórios já cadastrados de que os termos mudaram, com link para a versão nova | P-NSU-14 |

**Ordem de execução** (depois do deploy da remodelagem):
1. B-NSU-01 (já previsto na remodelagem) e, em paralelo, B-NSU-06 (parser do CT-e e `fiscal_document_references`).
2. Fase P pronta → B-NSU-02, depois B-NSU-04 e B-NSU-03.
3. B-NSU-10 (depende só do B-NSU-06) em paralelo com B-NSU-07; F-NSU-04 e F-NSU-05 começam assim que o contrato da
   6.1 é aceito.
4. B-NSU-09, B-NSU-05 e B-NSU-08.
5. F-NSU-01, F-NSU-02 e F-NSU-03.
6. Q-NSU-1 e Q-NSU-3.
7. Deploy (aprovação do Tony) → o Tony indica a empresa do piloto → Q-NSU-2 (30 dias). D-NSU-02 antes de ligar em
   qualquer escritório além do piloto.

## 13. Riscos
- **O NSU da conta traz muito mais do que o Taxify guarda.** São ~7,2 mil documentos por dia na conta testada,
  abertos em memória, e a maior parte é descartada. Mitigação: o descarte é feito sem gravar nada (NSU-RN-03,
  NSU-RN-36), os contadores ficam por fluxo e o texto da política e dos termos (seção 5B) é publicado antes de ligar fora
  do piloto. Também é preciso medir CPU e memória da EC2 na primeira carga.
- **RBT12 com meses sem NFC-e.** A NFC-e soma já (P-CX-02), mas o NSU só traz do mês-limite em diante, e o modo por
  período só busca NF-e e NFS-e. Num varejista que nunca teve NFC-e no Taxify, os 12 meses anteriores ficam sem a
  receita de NFC-e, e o RBT12 (e a faixa do Simples) sai subavaliado até completar 12 meses. Ver P-NSU-13.
- **Piloto restrito por variável.** Esquecer a lista preenchida deixa as outras empresas fora do NSU (sem perda, porque
  o modo atual continua). Mitigação: o painel da plataforma mostra `outsidePilot`.
- **Lote sem identificação do NSU por arquivo** (P-NSU-11). O caminho do NSU-RN-07a custa até 50 chamadas unitárias
  por lote incompleto, dentro do limite de 3.000/min.
- **NSU sem emitidas ou sem recebidas** (P-NSU-02). Nesse caso, o modo atual continua para o lado que faltar. O
  NSU-RN-15 só desliga o que o paralelo comprovar.
- **Alcance curto (corrente + anterior).** Nota de mês mais antigo que chega tarde ao SIEG é descartada pelo NSU e só
  entra pelo modo atual ou por "Sincronizar". Mitigação: contador `discarded_too_old` por fluxo no painel da plataforma
  (agregado) e no do escritório. Se ele for alto, aumentar `TAXIFY_SIEG_NSU_START_MONTHS_BACK` (decisão do Tony).
- **Busca binária sobre uma ordem que não é de emissão.** Mitigação: a margem de 1 dia, o filtro de idade e o
  CA-NSU-22. O paralelo mostra se escapou alguma nota perto do ponto.
- **Escritório parado ou bloqueado.** Ao voltar, o cursor salta para o mês-limite (NSU-RN-09 b e NSU-RN-24); os meses
  do alcance pulados são recuperados pelo modo atual, ao custo de 2 req/min.
- **Volume de NFC-e no armazenamento e na apuração.** A apuração de um varejista muda de número quando as NFC-e passam
  a chegar sozinhas. Mitigação: NSU-RN-30 (nada conta duas vezes), a memória de cálculo da apuração por CFOP e o
  paralelo do piloto com a conferência do Fiscal. Telas: filtro por tipo com NF-e por padrão (NSU-RN-33).
- **Documento de não cliente.** É descartado sem gravar (NSU-RN-36). Mesmo assim, passa em memória pelo Taxify; o
  texto da seção 5B informa isso.

## 14. Fora do escopo
- CF-e (só contado).
- CT-e OS (modelo 67) e CT-e simplificado.
- Uso fiscal do CT-e (P-CX-10 a 13): o CT-e é capturado e mostrado, sem entrar em total, apuração ou relatório.
- SEFAZ DF-e do CT-e (P-CX-04).
- Mudança no ADN e na SEFAZ DF-e de NF-e.
- Aviso de dupla fonte de faturamento: não há outra fonte hoje (NSU-RN-30 f).
- Busca de NFC-e de meses anteriores ao mês-limite pelo SIEG (P-NSU-13).

## 15. Perguntas em aberto

### Respondidas no teste real (2026-11-27, seção 0.1)
| P | Resposta | Efeito na spec |
|---|---|---|
| P-NSU-01 | NSU da conta (API key do escritório). **Fechada pelo Tony (2026-11-27): cada escritório do SaaS terá a própria conta e API key no SIEG**, ou seja, um fluxo de NSU por escritório | Só o escopo `ACCOUNT` é implementado (NSU-RN-02). Escritório sem API key própria fica sem NSU (`CREDENTIAL_MISSING`, FD-RN-44) |
| P-NSU-04 | Unitário: 1 XML por ZIP. Lote `nsu/lote` com `NumerosNsu` (até 50): ZIP com 50 XMLs em ~0,9 s | O lote é o caminho principal; existe o tratamento de lote incompleto (NSU-RN-07, 07a) |
| P-NSU-05 | `GET consultar-nsu` → `Data.PrimeiroNsu`/`Data.UltimoNsu` mais o envelope `IsSuccess`/`IsFailure`/`Code`/`StatusCode`/`ErrorMessage` | NSU-RN-07; o `ErrorMessage` nunca é gravado |
| P-NSU-06 | Limites por endpoint e API key, separados dos atuais: lote 60/min, unitário 3.000/min | Tetos de 50 e 300 por minuto, uma faixa por endpoint (NSU-RN-11) |
| P-NSU-07 | O JWT atual funciona | Nenhuma credencial nova |
### Fechadas pelo Tony em 2026-11-28
| P | Decisão | Efeito na spec |
|---|---|---|
| P-NSU-03 | O SIEG não cobra por volume | Sai dos bloqueios e dos riscos |
| P-NSU-09 | Documento sem empresa cadastrada é descartado em memória, sem arquivo, sem linha e sem log de conteúdo (só contadores); política e termos ganham um parágrafo | NSU-RN-36; texto na seção 5B; D-NSU-02 |
| P-CX-02 | NFC-e capturada soma já na apuração, na auditoria e nos relatórios, sem dupla contagem | NSU-RN-16 e NSU-RN-30; a trava de NFC-e sai |
| Piloto (P-NSU-02, Q-NSU-2) | Numa empresa da própria CS Hub com emitidas e recebidas, indicada pelo Tony quando a implementação estiver pronta | NSU-RN-35; único item que trava ligar |

### Ainda trava ligar
| P | Pergunta | Recomendação | Impacto |
|---|---|---|---|
| **Empresa do piloto** | Qual empresa da CS Hub? | O Tony indica quando a implementação estiver pronta | Sem ela, o NSU não é ligado |
| P-NSU-02 (verificada no piloto) | A sequência traz as emitidas **e** as recebidas de uma mesma empresa? E o CT-e como tomador? | Confirmar no paralelo do piloto: a comparação por lado (NSU-RN-14) mostra se falta um lado | Não trava ligar; trava só desligar o modo atual. Lado ausente continua no modo atual |

### Têm recomendação aceitável (não bloqueiam)
| P | Pergunta | Recomendação | Se for diferente |
|---|---|---|---|
| P-NSU-11 | O nome de cada arquivo no ZIP do lote identifica o NSU? | Abrir um ZIP no D-NSU-01 e verificar. Enquanto isso, vale o caminho das chamadas unitárias do NSU-RN-07a | Se identificar, o lote incompleto dispensa as chamadas unitárias |
| ~~P-NSU-12~~ | ~~Primeira carga com os 90 dias inteiros?~~ | **Decidida pelo Tony (2026-11-27):** só o mês corrente e o anterior (`TAXIFY_SIEG_NSU_START_MONTHS_BACK = 1`), com busca binária do ponto de partida e filtro de idade sempre ativo (NSU-RN-13, 22 a 25) | — |

### Têm recomendação aceitável
| P | Pergunta | Recomendação | Se for diferente |
|---|---|---|---|
| P-NSU-08 | NSU no teste grátis? | Liberado: o custo por chamada é baixo, e a captura é a vitrine do teste | Bloqueado: o teste mostra menos |
| P-NSU-10 | Prazo do paralelo | 30 dias (um fechamento) | Menos tempo, menos evidência |
| P-NSU-13 (nova, 2026-11-28) | RBT12 de varejista sem NFC-e antiga no Taxify: o que fazer? Opções: (a) nada, só a memória de cálculo; (b) alerta na apuração quando o mês tem NFC-e e algum dos 12 meses anteriores não tem nenhuma ("RBT12 pode estar subavaliado: faltam NFC-e de meses anteriores; importe os XMLs"), no padrão dos alertas atuais (`AssessmentAlertDTO`, como o `RBT12_PROPORTIONAL`); (c) buscar NFC-e antigas pelo modo por período | **(b)**: custo baixo, não muda número nenhum, e avisa o Fiscal exatamente onde a conta pode estar errada. (c) custa 2 req/min por API key e exige saber o `TipoXml` da NFC-e (a antiga P-CX-01) | (a): o Fiscal pode aceitar um DAS na faixa errada sem perceber. (c): spec própria |
| P-NSU-14 (nova, 2026-11-28) | Mudança nos termos (seção 5B) para escritórios já cadastrados: (a) só nova versão (vale para cadastros novos); (b) nova versão + aviso no sistema aos já cadastrados (F-NSU-06); (c) exigir novo aceite no próximo login. E o acordo de operador (DPA) também ganha a frase? | **(b)**, e sim no DPA: a mudança informa um tratamento que já acontece, sem nova finalidade; o aviso dá transparência sem travar o uso. Decisão jurídica do Tony | (c) exige tela e regra de bloqueio novas (spec própria). (a) deixa os escritórios atuais sem saber |
| P-NSU-15 (nova, 2026-11-28) | O piloto restrito a uma empresa (NSU-RN-35) está certo, ou o Tony prefere ligar o escritório CS Hub inteiro em paralelo e só **analisar** a empresa indicada? | **Restringir** (NSU-RN-35): é a leitura literal da decisão, e a NFC-e de outros varejistas da CS Hub só passa a somar depois do piloto aprovado | Escritório inteiro: tira a variável e o `outside_pilot`, mas a apuração de todos os varejistas da CS Hub muda já no piloto |
