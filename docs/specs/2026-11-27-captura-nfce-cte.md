# Captura ampliada no SIEG: contagem de NFC-e, CT-e e CF-e, busca de NFC-e e captura de CT-e

> Feature nova, pedida pelo Tony em 2026-11-27. Escrita pelo po-taxify no mesmo dia. Três fases, cada uma entregável
> sozinha e na ordem pedida: (1) guardar e mostrar as contagens que o `contar-xmls` já devolve; (2) buscar NFC-e emitidas;
> (3) capturar CT-e com tabela e parser próprios.
>
> **Convenção desta spec (exigência do Tony):** tabelas, colunas, índices, enums, rotas, parâmetros e campos JSON em
> **inglês**; o texto, em português. As rotas e campos antigos continuam como estão.
>
> Caminhos relativos a `C:\workspace\taxify` (backend) e `C:\workspace\taxify-front` (frontend). Pacote base
> `br.com.cshub.taxify` (abreviado `…`).
>
> Depende de:
> - [Captura de XML: SIEG e SEFAZ](2026-11-26-captura-xml-sieg-e-sefaz.md): fila, janela do mês, RN-05 ("lado em dia
>   não gasta chamada"), limitador de 2 req/min;
> - [Painel de controle da captura](2026-11-27-painel-captura-xml.md): rotas `/capture-panel`, reserva atômica, fila de
>   prioridade (CP-RN-15), exceção da visão da plataforma (P-CP-01);
> - [NFS-e](2026-11-26-nfse-notas-de-servico.md): exemplo de documento com tabela própria (`service_invoices`);
> - [Gestão de documentos fiscais](2026-11-26-gestao-de-documentos-fiscais.md): upload, lista e expurgo;
> - [Taxify SaaS, fase 1](2026-11-26-taxify-saas-assinatura-do-escritorio.md): bloqueio, rotinas paradas, teste grátis.

> **SUBSTITUÍDA EM PARTE (2026-11-27, decisão do Tony).** As **fases 2 (NFC-e) e 3 (CT-e) foram substituídas** pela
> [Captura SIEG por NSU](2026-11-27-captura-sieg-nsu.md). Deixam de existir: a faixa noturna, os orçamentos, a
> habilitação por empresa, os tetos, `company_capture_settings`, `document_capture_status`, `sieg_request_budgets` e as
> variáveis `TAXIFY_SIEG_NFCE_*`/`TAXIFY_SIEG_CTE_*`. **Continuam valendo:** a fase 1 (contagens, em
> `capture_period_counts`), o modelo do CT-e (CX-RN-31 a 34) e as perguntas P-CX-02 e P-CX-10 a P-CX-13.

## Ajuste de 2026-11-27: remodelagem dos documentos e tabelas de controle

> Este quadro **prevalece** sobre o texto das seções abaixo onde houver conflito. Ele vem da
> [remodelagem dos documentos fiscais](2026-11-27-remodelagem-documentos-fiscais.md) (decisão do Tony: fazer agora)
> e da spec irmã [Capturas e controles](2026-11-27-capturas-e-controles.md). O texto das fases é reescrito quando
> cada fase for retomada.

| Onde | Antes (nesta spec) | Agora |
|---|---|---|
| Fase 1, modelo | `V89__SiegDocumentCounts` (`sieg_document_counts`) | **Não cria tabela.** Contagens em `capture_period_counts` (T2, criada pela remodelagem): uma linha por canal `SIEG`, tipo (`NFE`, `NFCE`, `CTE`, `NFSE`; CF-e fica de fora até ter tipo), lado e mês. Leitores e contrato da fase 1 iguais, lendo de lá |
| Fase 1, CX-RN-13 | `document_type = 'NFE'` e modelo 55 | consulta em `Nfe` |
| Fase 2, documentos | NFC-e em `fiscal_xml_documents` com `ide_modelo = '65'` | subclasse `Nfce` em `fiscal_documents`; filtro `models` (CX-RN-28) vira filtro por subtipo; `imported_total` = `Nfce` com `company_role = ISSUER` |
| Fase 2, fila e situação | `V91__DocumentCaptureStatus` (`document_capture_status`) | **`capture_stream` (T1)** para fila e resultado por etapa; pendente = `capture_period_counts`. A fase 2 espera o T1 |
| Fase 2, orçamento | `V92__SiegRequestBudgets` (`sieg_request_budgets`) | `provider_request_budget` (T5), com o mesmo consumo atômico |
| Fase 3, documento | `V93__TransportDocuments` (`transport_documents`) | **Sai.** CT-e é `Cte` em `fiscal_documents` (bloco T, já criado pela remodelagem). **A fase 3 fica parada até a remodelagem estar em produção** |
| Fase 3, referências | `V94__TransportDocumentNfeRefs` | `fiscal_document_references` (`reference_kind = CTE_CARRIED_NFE`), criada na fase 3 |
| Fase 3, eventos | `V95__FiscalXmlEventsTransportDocument` | **Sai.** Evento do CT-e em `fiscal_document_events.document_id` |
| Fase 3, CX-RN-38 | cópia dos CT-e antigos | **Sai.** A migration da remodelagem já converte `CTE` em `Cte` |
| Numeração | V89 a V95 | V85 é da `fix/sieg-call-savings` e a remodelagem usa V86 a V89; as migrations desta spec são renumeradas depois da V89 |

## Status e decisões

| Fase | Situação |
|---|---|
| **Fase 1, contagens** | **Pronta para implementação.** Sem chamada nova ao SIEG, sem regra fiscal, sem flag de risco |
| **Fase 2, NFC-e** | **Pronta para implementação, desligada.** O go-live depende de **P-CX-01** (código `TipoXml` da NFC-e no SIEG, confirmação técnica) e de **P-CX-02** (o Fiscal confirma que apuração, auditoria e relatórios devem passar a somar as NFC-e capturadas, sem dupla contagem). Ambas bloqueiam **ligar**, não implementar |
| **Fase 3, CT-e** | **3a (tabela, parser, upload, ligação com a NF-e, SIEG emitente e destinatário) pronta para implementação.** A busca do SIEG como **tomador** depende de **P-CX-03**. **3b (SEFAZ, distribuição de DF-e do CT-e) bloqueada** por **P-CX-04** (serviço, versão e quem recebe o documento) |
| Uso fiscal do CT-e | **Fora desta spec.** Crédito de ICMS ou PIS/COFINS sobre frete, frete na base da Fronteira PE e receita de transporte na apuração são perguntas ao Tony e ao Fiscal (P-CX-10 a P-CX-13). Esta spec captura e mostra; não aplica regra |

**Regra fiscal.** A spec não cria nenhuma. Os campos do CT-e citados (grupos `ide`, `toma3`/`toma4`, `rem`, `exped`,
`receb`, `dest`, `vPrest`, `imp/ICMS*`, `infCarga`, `infDoc/infNFe/chave`) são do leiaute do CT-e 4.00 e precisam ser
conferidos no Manual de Orientação do CT-e vigente antes do parser (D-CX-04). O valor `00` e outros detalhes de
preenchimento não são tratados aqui.

## 1. Problema e objetivo

**Problema.**
1. O `contar-xmls` do SIEG já devolve, de graça, quantas NFC-e, CT-e, CF-e e NFS-e existem para o mesmo filtro da
   contagem de NF-e (`SiegContarXmlsDataDTO`, comentário "verified live: ... always breaks down every type"), mas o
   Taxify só lê o campo `NFe` (`SiegClient.contarXmlsNfe`) e joga o resto fora. Hoje ninguém sabe quantas NFC-e a
   carteira emite, então não dá para decidir a captura com número.
2. Sem NFC-e, o varejo fica sem as saídas: apuração do Simples subcontada, auditoria `NFCE_CANCELAMENTO` sem dado,
   perfil B2C (F4) e radar (F6) cegos. A NFC-e só entra por upload.
3. CT-e só entra por upload e cai em `fiscal_xml_documents` com `document_type = 'CTE'` e campos de NF-e
   (`FiscalXmlMetadataParser.java:77`): sem remetente, tomador, valor da prestação, ICMS do frete nem as chaves das
   NF-e transportadas.
4. **Bug existente:** o "importado" do lado emitido conta **todo** documento da empresa no mês
   (`FiscalXmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetween`, sem filtro de modelo nem de tipo), e
   compara com o total **só de NF-e** do SIEG. Uma NFC-e ou um CT-e enviados por upload já hoje inflam o "importado" e
   podem esconder NF-e faltando. Com a captura de NFC-e, isso piora muito.

**Objetivo.**

| Fase | Entrega | Como medir |
|---|---|---|
| 1 | Contagem por tipo, empresa, mês e lado, gravada a cada contagem que já acontece; tela de volume com a estimativa de custo no SIEG | Tony decide a fase 2 com o volume real da carteira (P-10 da avaliação do RT33) |
| 2 | NFC-e emitidas das empresas escolhidas, numa faixa própria da fila, com teto atômico de chamadas e janela de horário, sem atrasar a NF-e | Atraso da NF-e (tempo de volta da fila) não piora mais que 10% com a faixa ligada; NFC-e do mês completas até o dia 3 do mês seguinte em ≥ 95% das empresas habilitadas |
| 3 | CT-e com tabela própria, ligação CT-e → NF-e pelas chaves, tela e detalhe | Upload manual de CT-e pelo Fiscal cai a zero nas empresas cobertas |

## 2. O que já existe (confirmado no código em 2026-11-27)

| Item | Onde | Uso |
|---|---|---|
| Contagem de todos os tipos numa chamada | `integration/sieg/SiegFeignClient.contarXmls`, `SiegContarXmlsDataDTO` (`NFe`, `NFCe`, `CTe`, `CFe`, `NFSe`) | Fase 1 |
| Contagem só de NF-e por lado, sem `BaixarEventos` | `SiegClient.contarXmlsNfe`; `FiscalXmlIntegrationService.countPendingNfe` (linha 233) | Fase 1: ler o resto do mesmo retorno |
| Lado: `ISSUED` = `CNPJemit`, `RECEIVED` = `CNPJdest` | `FiscalXmlIntegrationService.syncSideOutcome` (linha 179) | Fases 1 a 3 |
| `TipoXml` 1 (NF-e) e 3 (NFS-e) | `SiegFiscalXmlProvider.TIPO_XML_NFE`, `ServiceInvoiceService.TIPO_XML_NFSE` | Fases 2 e 3 |
| Página de 50, `Skip` = já importados da janela | `SiegFiscalXmlProvider.sync`, `FiscalXmlIntegrationService.pageSize()` | Fase 2 |
| Fila persistida com reserva atômica por `last_attempt_at`, lote por tick, prioridade | `service/FiscalXmlSyncScheduler` (`reserve`, `nextBatch`, `queueOrder`) | Fase 2 |
| Limitador de 2 req/min **em memória, por instância** | `integration/sieg/SiegRateLimiter`; `application.yaml` `max-requests-per-minute: 2` | Fase 2 (ver riscos) |
| Persistência idempotente por (`company_id`, `access_key`) | `uk_fiscal_xml_document_company_key` (V16); `persistAll` | Fase 2 |
| NFC-e já reconhecida no parser e nas telas (modelo 65) | `FiscalXmlMetadataParser`, `DanfePdfService.java:375`, `AuditEngine.java:297` | Fase 2 |
| CT-e lido só com campos básicos, gravado como `document_type = 'CTE'` | `FiscalXmlMetadataParser.java:77` | Fase 3 (migração) |
| Evento de CT-e (`procEventoCTe`, `chCTe`) já reconhecido | `integration/fiscalxml/FiscalEventParser.java:50` | Fase 3 |
| Documento com tabela própria, origens (`found_in_sieg_at`, `found_in_adn_at`) e chave única por empresa | `service/ServiceInvoiceService`, `V37__ServiceInvoices`, `V42__ServiceInvoiceSources` | Fase 3 (modelo) |
| Armazenamento local ou S3 e expurgo mensal dos arquivos (metadados ficam) | `integration/sieg/FiscalXmlFileStorage`, `service/FiscalXmlPurgeScheduler` (`FISCAL_XML_PURGE_RETENTION_MONTHS`) | Fases 2 e 3 |
| `JPA_DDL_AUTO=validate` | `application.yaml:40` | Compatibilidade: coluna a mais não quebra a versão antiga |
| Gate de rotinas, plano e bloqueio | `RoutineGate`, `PlanServiceEnum.importsFrom(..., SIEG_INTEGRATION)` | Todas |

## 3. Usuários e cenário

| Papel | Fase | O que faz |
|---|---|---|
| ADMIN do escritório | 1, 2, 3 | Vê o volume por tipo; habilita a captura de NFC-e e de CT-e por empresa |
| FISCAL (carteira) | 1, 2, 3 | Vê o volume das empresas da carteira; filtra NF-e × NFC-e nas telas; consulta CT-e e a ligação com a NF-e |
| Admin da plataforma | 1, 2 | Vê o volume agregado da plataforma e o consumo da faixa de NFC-e (sem dado de empresa, P-CX-08) |
| Agendador SIEG | 1, 2, 3 | Grava as contagens; atende a faixa de NFC-e e de CT-e dentro do teto |

**Teste grátis.** Fase 1: disponível. Fases 2 e 3: **não disponível** (P-CX-06): a faixa usa o limitador compartilhado
por todos os escritórios. O `PUT` de habilitação responde 403 `error.capture.trial.unavailable`.
**Escritório bloqueado.** Leitura liberada; habilitar ou desabilitar responde 403 `error.firm.access.blocked`
(interceptor existente); nenhuma faixa roda (`RoutineGate("sieg")`, já aplicado ao tick inteiro).
**Serviço do plano.** Nenhum serviço novo no `PlanServiceEnum` (mesma linha da P-03 da avaliação do RT33). A captura
continua exigindo `FISCAL_XML_IMPORT` + `SIEG_INTEGRATION`; NFC-e e CT-e exigem, além disso, a habilitação por empresa
(`company_capture_settings`). Quando existir o módulo do Plano Taxify (P3-07), a habilitação pode passar a depender dele.

## 4. Histórias

- H-CX-1. Como ADMIN, quero ver quantas NFC-e, CT-e, CF-e e NFS-e cada empresa tem no SIEG por mês, para saber quem
  precisa de captura e quanto ela custa, sem gastar chamada.
- H-CX-2. Como admin da plataforma, quero o volume total e a estimativa de chamadas da plataforma, para dimensionar a
  faixa do SIEG antes de ligar.
- H-CX-3. Como ADMIN, quero habilitar a captura de NFC-e só nas empresas de varejo, para não gastar o SIEG com quem não
  emite.
- H-CX-4. Como FISCAL, quero filtrar as notas por modelo, para as milhares de NFC-e não esconderem as NF-e.
- H-CX-5. Como FISCAL, quero que o "importado" de NF-e conte só NF-e, para o painel não dizer "em dia" com nota
  faltando.
- H-CX-6. Como FISCAL, quero ver os CT-e da empresa (como emitente, remetente, destinatário ou tomador), com o valor da
  prestação, o ICMS do frete e as NF-e transportadas, para conferir o frete sem abrir XML.
- H-CX-7. Como FISCAL, quero abrir a NF-e e ver os CT-e que a transportaram (e vice-versa).

---

## 5. Regras gerais (valem para as três fases)

| RN | Regra |
|---|---|
| **CX-RN-01 Compatibilidade com a versão antiga no deploy** | (a) Migrations **só aditivas**: tabela nova, coluna nova **nula** ou com padrão, índice novo. Nada de renomear, apagar, mudar tipo ou tornar `NOT NULL` coluna usada pela versão antiga. Com `ddl-auto=validate`, a versão antiga ignora o que é novo. (b) Toda captura nova nasce com flag `false`; **só se liga depois que todas as instâncias estão na versão nova** (item do checklist de deploy). (c) Mudança de comportamento que a versão antiga faria diferente (contagem do "importado", destino do upload de CT-e) é tolerada durante a janela de deploy e corrigida sozinha pela versão nova na próxima rodada (idempotente) |
| **CX-RN-02 Idempotência por chave de acesso** | NF-e e NFC-e: `uk_fiscal_xml_document_company_key (company_id, access_key)`, já existente. CT-e: `uk_transport_documents_company_key (company_id, access_key)`. Gravação concorrente da mesma chave: a perdedora pega a violação de chave única (`DuplicateKeys.isDuplicateKey`) e trata como "já existia", sem erro e sem log de falha |
| **CX-RN-03 Duas instâncias** | Contagens: `INSERT ... ON DUPLICATE KEY UPDATE` na chave única. Faixas de NFC-e e CT-e: reserva da empresa por `UPDATE` condicional em `document_capture_status.last_attempt_at` (mesmo padrão do `reserve` atual) e teto de chamadas consumido de forma atômica em `sieg_request_budgets` (CX-RN-22). Nada depende de `AtomicBoolean` |
| **CX-RN-04 Horário** | Toda data e hora gravada por estas regras em `America/Recife` (`ClockConfig`); "mês" = mês civil de Recife; janela de horário da faixa em Recife |
| **CX-RN-05 LGPD e logs** | Logs só com ids, tipos e contagens. Nunca CNPJ, CPF, nome, chave de acesso, valor nem corpo de resposta do SIEG ou da SEFAZ (regra P-CP-09 do painel). CPF de remetente ou destinatário pessoa física no CT-e é gravado como dado do documento (como `dest_cpf` da NF-e) e mascarado nas listas (`***.456.789-**`) |
| **CX-RN-06 Expurgo e S3** | Os arquivos de NFC-e e CT-e seguem `FiscalXmlFileStorage` (local ou S3) e o expurgo de `FISCAL_XML_PURGE_RETENTION_MONTHS`. Os metadados ficam. Arquivo expurgado: download responde 410 `error.document.xml.purged` (CT-e) ou o comportamento atual (NF-e/NFC-e) |

---

## Fase 1. Contagens por tipo (sem chamada nova)

### 6.1 Regras

| RN | Regra |
|---|---|
| **CX-RN-10 Captura da contagem** | Toda vez que o Taxify chama o `contar-xmls` (hoje: `countPendingNfe`, por lado, com a janela do mês corrente, sem `BaixarEventos`), grava **todos** os campos do retorno em `sieg_document_counts` para (`company_id`, `period_month`, `side`), com `counted_at` (Recife) e a janela usada (`window_start`, `window_end`). `SiegClient` ganha `countXmls(...)` que devolve o DTO inteiro; `contarXmlsNfe` passa a delegar a ele (mesmo comportamento). **Nenhuma chamada nova**: quando o lado é pulado por estar em dia (RN-05 da captura) ou por `knownPending`, a contagem não é atualizada e `counted_at` mostra a idade |
| **CX-RN-11 Falha da contagem** | Se a chamada falha, nada é gravado (a linha anterior fica). Se a gravação da contagem falha, a captura de NF-e segue (log `sieg.counts.save.failed` com `companyId`); a contagem nunca derruba a captura |
| **CX-RN-12 Campo nulo** | Campo ausente no retorno grava `NULL` (não zero): "o SIEG não informou" é diferente de "zero documentos" |
| **CX-RN-13 Correção do "importado" de NF-e** (bug do item 1.4) | O "importado" de cada lado passa a contar só NF-e: `document_type = 'NFE'` e (`ide_modelo = '55'` ou `ide_modelo` nulo). Repositório: método novo `countNfeByCompanyAndOwnIssueAndPeriod`. Durante o deploy a versão antiga ainda conta tudo; a primeira rodada da versão nova corrige o número (CX-RN-01 c) |
| **CX-RN-14 Estimativa de custo** | Por empresa e mês: `estimatedRequests.nfce = ceil(nfce / 50) + 1` (páginas de download + 1 contagem de virada), `estimatedRequests.cte = ceil(cteIssued / 50) + ceil(cteReceived / 50) + 2`. É estimativa de produto, para dimensionar a faixa; não é regra de cobrança. Tempo equivalente: `requests / 2` minutos de SIEG exclusivo (limite de 2 req/min) |
| **CX-RN-15 Retenção** | Uma linha por empresa, mês e lado; nada é apagado nesta fase (5.000 empresas × 2 lados × 12 meses = 120 mil linhas por ano) |

### 6.2 Modelo de dados

**V89__SiegDocumentCounts** (número reservado depois das specs da Onda 1, que vão até V88; conferir e renumerar na hora)

| Column | Type | Note |
|---|---|---|
| `id` | varchar(36) PK | |
| `company_id` | varchar(36) not null, FK `companies` | |
| `period_month` | char(7) not null | `YYYY-MM` |
| `side` | varchar(10) not null | `ISSUED`, `RECEIVED` (`FiscalXmlSide`) |
| `nfe_count`, `nfce_count`, `cte_count`, `cfe_count`, `nfse_count` | int null | CX-RN-12 |
| `window_start`, `window_end` | date not null | janela da chamada |
| `counted_at` | datetime(6) not null | Recife |
| + auditoria e `deleted` do projeto | | |

Único `uk_sieg_document_counts_company_month_side (company_id, period_month, side)`: idempotência e busca por empresa.
Índice `idx_sieg_document_counts_month (period_month, company_id)`: a tela de volume lista um mês do escritório inteiro
(junção com `companies` por `company_id`, filtro de escritório em `companies`).

### 6.3 Contrato da API

| Method and route | Who | Params | Response |
|---|---|---|---|
| `GET /api/v1/capture-panel/document-volume` | `INVOICES_VIEW`; ADMIN vê o escritório, os demais a carteira | `month` (`YYYY-MM`, obrigatório, de 12 meses atrás até o mês corrente), `search` (razão social ou CNPJ, mín. 3), `onlyWithNfce` (bool), `page` (0), `size` (50, máx. 200), `sort` (`nfceIssued`, `cteTotal`, `companyName`, `countedAt`; padrão `nfceIssued,desc`, desempate `companyName`) | 200 corpo abaixo; 400 `error.capture.volume.month.invalid`, `error.capture.volume.filter.invalid` (`invalidField`) |
| `GET /api/v1/capture-panel/companies/{companyId}` (existente) | como hoje | — | ganha o bloco `documentCounts` (mês corrente e anterior, os dois lados); empresa fora da carteira ou de outro escritório: 404 `error.Company.not.found` (como hoje) |
| `GET /api/v1/platform/capture-panel/document-volume` | `@PlatformAdminOnly` | `month` | 200 **só agregados**: `{month, companiesCounted, totals{nfe, nfce, cte, cfe, nfse}, estimatedRequests{nfce, cte}, estimatedSiegMinutes{nfce, cte}, companiesWithNfce, companiesWithCte}`; sem nome, CNPJ nem escritório (P-CX-08) |

**Corpo de `document-volume`.**

```json
{
  "month": "2026-11",
  "summary": {"companies": 3, "issued": {"nfe": 410, "nfce": 6100, "cte": 0, "cfe": 0, "nfse": 12},
              "received": {"nfe": 880, "nfce": 0, "cte": 95, "cfe": 0, "nfse": 40},
              "estimatedRequests": {"nfce": 125, "cte": 4}, "estimatedSiegMinutes": {"nfce": 62.5, "cte": 2.0}},
  "page": {"content": [{
      "companyId": "…", "companyName": "Mercadinho Exemplo Ltda", "cnpj": "11222333000181",
      "issued": {"nfe": 10, "nfce": 3000, "cte": 0, "cfe": 0, "nfse": 0, "countedAt": "2026-11-27T08:10:00-03:00"},
      "received": {"nfe": 300, "nfce": 0, "cte": 20, "cfe": 0, "nfse": 0, "countedAt": "2026-11-27T08:09:30-03:00"},
      "estimatedRequests": {"nfce": 61, "cte": 3},
      "nfceCaptureEnabled": false, "cteCaptureEnabled": false
  }], "totalElements": 3, "totalPages": 1, "number": 0, "size": 50}
}
```

`issued`/`received` nulos quando o lado nunca foi contado no mês. `summary` soma todas as empresas que passam nos
filtros. Os nomes são fictícios.

| Code | Status | Texto (pt) |
|---|---|---|
| `error.capture.volume.month.invalid` | 400 | "Mês inválido. Use um dos últimos 12 meses." |
| `error.capture.volume.filter.invalid` | 400 | "Filtro inválido." |

---

## Fase 2. Busca de NFC-e emitidas

### 7.1 Regras

| RN | Regra |
|---|---|
| **CX-RN-20 Quem entra** | Empresa com captura SIEG (plano atual), escritório liberado pelo `RoutineGate`, `company_capture_settings.nfce_enabled = true`, fora do teste grátis, e `TAXIFY_SIEG_NFCE_ENABLED=true`. Só o lado **emitido** (`CNPJemit`). NFC-e não tem destinatário com CNPJ na regra geral e não é distribuída pela NFeDistribuicaoDFe, então não há lado recebido nem fonte SEFAZ |
| **CX-RN-21 Habilitação** | `PUT /companies/{companyId}/capture-settings` (só ADMIN do escritório). Teto: `TAXIFY_SIEG_NFCE_MAX_COMPANIES_PER_FIRM` (padrão 20) empresas com NFC-e por escritório: ao passar, 409 `error.capture.nfce.limit` com `limit` e `inUse`. Concorrência otimista por `version`. Desabilitar para a faixa na próxima rodada; o que já foi importado fica |
| **CX-RN-22 Teto de chamadas** (orçamento) | Cada chamada da faixa (contagem ou página) consome 1 unidade de dois orçamentos do dia de Recife, na mesma transação, antes da chamada: global (`scope_key = 'GLOBAL'`, teto `TAXIFY_SIEG_NFCE_MAX_REQUESTS_PER_DAY`, padrão 600) e do escritório (`scope_key = <firmId>`, teto `TAXIFY_SIEG_NFCE_MAX_REQUESTS_PER_FIRM_PER_DAY`, padrão 200). Consumo: `INSERT IGNORE` da linha do dia e `UPDATE sieg_request_budgets SET used = used + 1 WHERE budget_date = ? AND lane = 'NFCE' AND scope_key = ? AND used < :cap`; 0 linhas afetadas = orçamento esgotado, a chamada não sai. Atômico com duas instâncias |
| **CX-RN-23 Janela e ritmo** | A faixa roda no mesmo tick do agendador SIEG, **depois** do lote normal de NF-e, só entre `TAXIFY_SIEG_NFCE_WINDOW_START` e `TAXIFY_SIEG_NFCE_WINDOW_END` (padrão 22:00 e 06:00 de Recife; janela que cruza a meia-noite é aceita) e com no máximo `TAXIFY_SIEG_NFCE_REQUESTS_PER_TICK` (padrão 2) chamadas por tick e instância. As chamadas passam pelo mesmo `SiegRateLimiter`. Pedido de "Sincronizar agora" **não** inclui NFC-e nesta fase |
| **CX-RN-24 Pendência e ordem** | Pendente = `provider_total > imported_total` em `document_capture_status` (`document_kind = 'NFCE'`, `side = 'ISSUED'`). `provider_total` vem da contagem da fase 1 (`sieg_document_counts.nfce_count` do lado emitido do mês), **sem chamada extra**, se `counted_at` tem menos de `TAXIFY_SIEG_NFCE_COUNT_MAX_AGE_HOURS` (padrão 24); senão, a faixa faz a própria contagem (1 unidade do orçamento). `imported_total` = NFC-e da empresa no mês (`ide_modelo = '65'`, `own_issue = true`). Ordem: `last_attempt_at` mais antigo primeiro (nulo primeiro), depois maior pendência. Empresa sem pendência não gasta chamada |
| **CX-RN-25 Download** | `baixar-xmls` com `TipoXml = TAXIFY_SIEG_NFCE_XML_TYPE` (P-CX-01), `CNPJemit` = CNPJ da empresa, janela do mês, `Take = 50`, `Skip = imported_total`, `BaixarEventos = true` (cancelamentos vêm junto). Persistência pelo `persistAll` atual (`source_system = 'SIEG'`). Página vazia com pendência: `provider_total` vira `imported_total` (mesma regra do NF-e, linha 201) e a empresa sai da faixa até a próxima contagem |
| **CX-RN-26 Virada de mês** | Nos primeiros `TAXIFY_SIEG_NFCE_PREVIOUS_MONTH_DAYS` (padrão 3) dias do mês, a faixa também trata o mês anterior (linha própria em `document_capture_status` com `period_month` do mês anterior), para pegar NFC-e transmitidas tarde. Depois disso, o mês anterior é encerrado. Mês anterior à habilitação nunca é buscado (P-CX-07) |
| **CX-RN-27 Resultado por etapa** | Cada tentativa grava em `document_capture_status`: `last_attempt_at`, `last_success_at`, `last_failure_at`, `last_failure_code` (lista fechada `CaptureFailureCode`, sem texto do provedor) e `budget_exhausted_at`. O painel de captura mostra a NFC-e como canal próprio |
| **CX-RN-28 Filtro por modelo nas telas** | `XmlSearchFilterDTO` e a lista de notas ganham o filtro `models` (lista de `"55"`, `"65"`). **Sem o filtro, o backend devolve como hoje** (compatível com o front antigo). O front novo manda `models=["55"]` por padrão e oferece "NF-e", "NFC-e" e "Todas". Mesma regra na conferência da empresa, na manifestação (só NF-e, já é assim) e nos relatórios que listam notas |
| **CX-RN-29 Impacto nas outras rotinas** | NFC-e capturada é nota como qualquer outra: entra na apuração (receita), na auditoria (`NFCE_CANCELAMENTO`, numeração por série), no motor de conferência e nos relatórios. **O go-live depende da P-CX-02.** A conferência sob demanda de empresa com milhares de NFC-e precisa do filtro por modelo (CX-RN-28) para não carregar tudo |

### 7.2 Custo no SIEG (conta)

| Hipótese (fictícia) | Conta | Resultado |
|---|---|---|
| Varejista com 3.000 NFC-e/mês | 3.000 ÷ 50 = 60 páginas + ~1 contagem de virada | ~61 chamadas/mês ≈ 30,5 min de SIEG exclusivo |
| 40 varejistas habilitados | 40 × 61 | ~2.440 chamadas/mês ≈ 81 por dia |
| Capacidade da faixa | janela de 8 h (22h–06h) = 96 ticks de 5 min × 2 chamadas | 192 chamadas por noite por instância, sob o teto global de 600/dia |
| Atraso da NF-e na janela | cada chamada da faixa ocupa 30 s do limitador | até 60 s a mais por tick só à noite; de dia, zero |

Contagem: **zero chamada extra** quando a contagem da fase 1 tem menos de 24 h. A contagem do lado emitido já traz a
NFC-e.

### 7.3 Modelo de dados

**V90__CompanyCaptureSettings**

| Column | Type | Note |
|---|---|---|
| `id` | varchar(36) PK | |
| `company_id` | varchar(36) not null, FK | único |
| `nfce_enabled` | bit not null, padrão 0 | |
| `cte_enabled` | bit not null, padrão 0 | fase 3 |
| `updated_by` | varchar(50) | login |
| `updated_at` | datetime(6) | Recife |
| `version` | int not null | |
| + auditoria e `deleted` | | |

Único `uk_company_capture_settings_company (company_id)`. Empresa sem linha = tudo desabilitado.

**V91__DocumentCaptureStatus**

| Column | Type | Note |
|---|---|---|
| `id` | varchar(36) PK | |
| `company_id` | varchar(36) not null, FK | |
| `document_kind` | varchar(10) not null | `NFCE`, `CTE` (`CapturedDocumentKind`) |
| `side` | varchar(10) not null | `ISSUED`, `RECEIVED`, `TAKER` (só CT-e) |
| `period_month` | char(7) not null | |
| `provider_total`, `imported_total` | int | |
| `counted_at`, `last_attempt_at`, `last_success_at`, `last_failure_at`, `budget_exhausted_at` | datetime(6) | Recife |
| `last_failure_code` | varchar(40) | `CaptureFailureCode` |
| + auditoria e `deleted` | | |

Único `uk_document_capture_status (company_id, document_kind, side, period_month)`: idempotência da linha e reserva.
Índice `idx_document_capture_status_queue (document_kind, period_month, last_attempt_at)`: a fila da faixa lê só as
linhas do tipo e do mês, já na ordem.

**V92__SiegRequestBudgets**

| Column | Type | Note |
|---|---|---|
| `id` | varchar(36) PK | |
| `budget_date` | date not null | Recife |
| `lane` | varchar(10) not null | `NFCE`, `CTE` |
| `scope_key` | varchar(36) not null | `GLOBAL` ou id do escritório |
| `used` | int not null, padrão 0 | |
| + auditoria | | |

Único `uk_sieg_request_budgets (budget_date, lane, scope_key)`. Limpeza: linhas com mais de 90 dias apagadas pela
rotina de limpeza do painel (`TAXIFY_CAPTURE_PRIORITY_PURGE_CRON` reaproveitado) ou por uma nova, a critério do dev.

**Tabelas grandes (`fiscal_xml_documents`, `fiscal_xml_document_items`).** Nenhuma coluna nova. Volume: 40 varejistas ×
3.000 NFC-e × ~5 itens ≈ 600 mil itens novos por mês (≈ 7 milhões por ano). **Nenhum índice novo** nesta fase, porque
toda consulta nova é por empresa e mês e já usa `idx_fxd_company_issue` / `idx_fxd_company_direction_issue`: o filtro
por `ide_modelo` lê as linhas da empresa no mês (≈ 3.100 num varejista), o que é barato. Se o Q-CX-4 mostrar lista ou
contagem lentas, o índice candidato é `(company_id, ide_modelo, issue_date)`, criado com `ALGORITHM=INPLACE, LOCK=NONE`
e aprovação do Tony (é a segunda maior tabela do banco).

### 7.4 Contrato da API

| Method and route | Who | Body / params | Response |
|---|---|---|---|
| `GET /api/v1/companies/{companyId}/capture-settings` | `INVOICES_VIEW` + carteira | — | 200 `{companyId, nfceEnabled, cteEnabled, available: {nfce, cte}, updatedBy, updatedAt, version}`; `available` = flag global ligada e escritório fora do teste; 404 `error.Company.not.found` |
| `PUT /api/v1/companies/{companyId}/capture-settings` | ADMIN do escritório | `{nfceEnabled, cteEnabled, version}` | 200 como o GET; 403 `error.firm.access.blocked`; 403 `error.capture.trial.unavailable`; 409 `error.capture.nfce.limit` (`limit`, `inUse`); 409 `error.capture.cte.limit` (idem); 409 `error.capture.settings.conflict` (`currentVersion`); 409 `error.capture.lane.disabled` (`lane`) quando a flag global está desligada e o pedido habilita; 404 |
| `POST /api/v1/integrations/xmls/search` (existente, `buscarXmls`) | como hoje | `XmlSearchFilterDTO` ganha `models` (lista de `"55"`/`"65"`, opcional) | como hoje |
| `GET /api/v1/capture-panel` e `/companies/{id}` (existentes) | como hoje | `canal` aceita `SIEG_NFCE` (além dos atuais) | o canal NFC-e aparece com a situação de `document_capture_status` (`em dia`, `pendente`, `teto atingido`, `falha`) |
| `GET /api/v1/platform/capture-panel/lanes` | `@PlatformAdminOnly` | `date` (padrão hoje) | 200 `{date, lanes: [{lane, used, cap, exhaustedAt}]}` só com o orçamento **global** (sem escritório) |

| Code | Status | Texto (pt) |
|---|---|---|
| `error.capture.trial.unavailable` | 403 | "A captura de NFC-e e CT-e não está disponível no teste grátis." |
| `error.capture.nfce.limit` | 409 | "O escritório já tem o máximo de empresas com captura de NFC-e." |
| `error.capture.cte.limit` | 409 | "O escritório já tem o máximo de empresas com captura de CT-e." |
| `error.capture.settings.conflict` | 409 | "Outra pessoa alterou esta configuração. Recarregue e tente de novo." |
| `error.capture.lane.disabled` | 409 | "Esta captura ainda não está liberada." |

---

## Fase 3. CT-e com tabela e parser próprios

### 8.1 Regras

| RN | Regra |
|---|---|
| **CX-RN-30 Documento** | CT-e modelo 57 (`procCTe`/`CTe`, leiaute 4.00, D-CX-04) gravado em `transport_documents`, nunca mais em `fiscal_xml_documents`. CT-e OS (modelo 67) e CT-e simplificado ficam fora desta fase: no upload, respondem como "tipo não suportado" na contagem de erros do pacote, sem derrubar o pacote |
| **CX-RN-31 Papéis da empresa** | A empresa pode estar no CT-e como emitente (`emit`), remetente (`rem`), expedidor (`exped`), recebedor (`receb`), destinatário (`dest`) e tomador (`toma3`/`toma4`). Cada papel é uma coluna booleana (`is_issuer`, `is_sender`, `is_shipper`, `is_receiver`, `is_recipient`, `is_taker`), calculada comparando o CNPJ da empresa (14 dígitos) com o de cada grupo. Um mesmo CT-e pode ter vários papéis. Se nenhum papel casa (upload na empresa errada), o upload recusa o arquivo com o motivo "empresa não participa do CT-e" |
| **CX-RN-32 Tomador** | `taker_role` ∈ {`SENDER`, `SHIPPER`, `RECEIVER`, `RECIPIENT`, `OTHER`} pelo código `toma` (0 a 3 em `toma3`; 4 em `toma4`). `taker_document`/`taker_name`/`taker_uf` = os do participante indicado (ou do grupo `toma4`). Mapeamento a conferir no MOC do CT-e (D-CX-04) |
| **CX-RN-33 Valores e ICMS** | `service_total` = `vPrest/vTPrest`; `amount_receivable` = `vPrest/vRec`; `cargo_value` = `infCTeNorm/infCarga/vCarga`. ICMS: o grupo presente dentro de `imp/ICMS` (`ICMS00`, `ICMS20`, `ICMS45`, `ICMS60`, `ICMS90`, `ICMSOutraUF`, `ICMSSN`) vai para `icms_group`, e `CST`, `vBC`, `pICMS` e `vICMS` (ou os equivalentes do grupo) para `icms_cst`, `icms_base`, `icms_rate`, `icms_amount`. Grupo sem um dos campos grava nulo. Valores em `decimal(19,2)`, alíquota `decimal(7,4)`, gravados como vieram (sem arredondar) |
| **CX-RN-34 NF-e transportadas** | Cada `infDoc/infNFe/chave` vira uma linha em `transport_document_nfe_refs`. Ligação: na gravação do CT-e, procura a NF-e da **mesma empresa** por (`company_id`, `access_key`) e grava `fiscal_xml_document_id`; na gravação de uma NF-e, procura as referências da mesma empresa pela chave (índice `idx_tdnr_company_key`) e preenche o id. Uma rotina diária idempotente (`TAXIFY_CTE_LINK_CRON`) liga o que ficou para trás (NF-e e CT-e chegando em instâncias diferentes ao mesmo tempo). Documentos de outra empresa não são ligados, mesmo no mesmo escritório |
| **CX-RN-35 Cancelamento** | Evento de cancelamento do CT-e (`procEventoCTe`, já reconhecido pelo `FiscalEventParser`) grava em `fiscal_xml_events` com a coluna nova `transport_document_id` e muda `status` para `CANCELED` e `canceled_at`. Evento que chega antes do CT-e fica guardado pela chave e é aplicado quando o CT-e chega (mesma regra dos eventos de NF-e) |
| **CX-RN-36 SIEG** | Com `TAXIFY_SIEG_CTE_ENABLED=true` e `cte_enabled` na empresa: faixa `CTE` igual à da NFC-e (CX-RN-22 a 27, com tetos próprios `TAXIFY_SIEG_CTE_*`), `TipoXml = 2`, lado `ISSUED` (`CNPJemit`, transportadora) e `RECEIVED` (`CNPJdest`). Lado `TAKER` **só depois da P-CX-03**. Contagem pendente: `sieg_document_counts.cte_count` do lado |
| **CX-RN-37 Upload** | O upload atual de XML (`FiscalXmlResource`, pacote ZIP ou XML solto) passa a mandar CT-e para `transport_documents`. A resposta continua com os mesmos campos; o CT-e conta em `totalPersistedNew` |
| **CX-RN-38 CT-e antigos em `fiscal_xml_documents`** | Rotina idempotente (`TransportDocumentBackfill`, na subida e diária) copia para `transport_documents` cada linha com `document_type = 'CTE'` que ainda não tem cópia (pela chave): relê o XML se o arquivo ainda existe; se foi expurgado, copia só os campos que a linha tem e marca `metadata_version = 0` ("sem arquivo"). A linha antiga **não é apagada** (a versão antiga do deploy ainda a lê, CX-RN-01). A versão nova esconde `document_type = 'CTE'` das telas de NF-e. Uma migration posterior, depois da fase 3 estável, faz o soft delete (fora desta spec) |
| **CX-RN-39 SEFAZ (fase 3b)** | Distribuição de DF-e do CT-e com o certificado A1 da empresa: **bloqueada pela P-CX-04**. Quando liberada, grava `found_in_sefaz_at` e usa a mesma persistência |
| **CX-RN-40 Uso fiscal** | Nenhuma rotina fiscal (apuração, Fronteira, motor, auditoria) lê `transport_documents` nesta spec. As perguntas P-CX-10 a P-CX-13 decidem isso numa spec própria |

### 8.2 Modelo de dados

**V93__TransportDocuments**

| Column | Type | Note |
|---|---|---|
| `id` | varchar(36) PK | |
| `company_id` | varchar(36) not null, FK | |
| `access_key` | char(44) not null | |
| `model` | varchar(2) not null | `57` |
| `series`, `number` | varchar(5), varchar(20) | |
| `issue_date` | datetime(6) not null | hora local do `dhEmi`, como na NF-e |
| `cte_type`, `service_type`, `modal` | varchar(2) | `tpCTe`, `tpServ`, `modal` como vieram |
| `cfop` | varchar(4) | |
| `operation_nature` | varchar(60) | `natOp` |
| `issuer_cnpj`, `issuer_name`, `issuer_uf` | varchar(14), varchar(200), char(2) | |
| `sender_document`, `sender_name`, `sender_uf` | varchar(14), varchar(200), char(2) | CNPJ ou CPF |
| `shipper_document`, `shipper_name` | varchar(14), varchar(200) | |
| `receiver_document`, `receiver_name` | varchar(14), varchar(200) | |
| `recipient_document`, `recipient_name`, `recipient_uf` | varchar(14), varchar(200), char(2) | |
| `taker_role` | varchar(10) | CX-RN-32 |
| `taker_document`, `taker_name`, `taker_uf` | varchar(14), varchar(200), char(2) | |
| `origin_city_code`, `origin_uf`, `destination_city_code`, `destination_uf` | varchar(7), char(2), varchar(7), char(2) | `cMunIni`, `UFIni`, `cMunFim`, `UFFim` |
| `service_total`, `amount_receivable`, `cargo_value` | decimal(19,2) | |
| `icms_group` | varchar(12) | |
| `icms_cst` | varchar(3) | |
| `icms_base`, `icms_amount` | decimal(19,2) | |
| `icms_rate` | decimal(7,4) | |
| `is_issuer`, `is_sender`, `is_shipper`, `is_receiver`, `is_recipient`, `is_taker` | bit not null | CX-RN-31 |
| `status` | varchar(12) not null | `AUTHORIZED`, `CANCELED` (`TransportDocumentStatus`) |
| `canceled_at` | datetime(6) | |
| `source_system` | varchar(20) not null | `SIEG`, `SEFAZ_DIST`, `UPLOAD`, `BACKFILL` (primeira origem) |
| `found_in_sieg_at`, `found_in_sefaz_at` | datetime(6) | |
| `xml_file_path` | varchar(1000) | nulo quando veio do backfill sem arquivo |
| `xml_purged_at` | datetime(6) | gravado pelo expurgo |
| `metadata_version` | int not null | versão do parser (reprocessamento futuro) |
| + auditoria e `deleted` | | |

Índices:
- `uk_transport_documents_company_key (company_id, access_key)`: idempotência (CX-RN-02).
- `idx_transport_documents_company_issue (company_id, issue_date)`: toda lista é por empresa e período.
- `idx_transport_documents_issue_date (issue_date)`: o expurgo mensal procura por data em todas as empresas (mesmo
  padrão do `findByIssueDateBeforeAndDeletedIsFalse` das notas).

Os papéis são filtrados depois do índice por empresa e período (uma transportadora com 2.000 CT-e/mês lê 2.000 linhas):
índice por papel não compensa.

**V94__TransportDocumentNfeRefs**

| Column | Type | Note |
|---|---|---|
| `id` | varchar(36) PK | |
| `transport_document_id` | varchar(36) not null, FK | |
| `company_id` | varchar(36) not null | redundante de propósito, para o índice de ligação reversa |
| `nfe_access_key` | char(44) not null | |
| `fiscal_xml_document_id` | varchar(36) null, FK | ligado quando a NF-e existe |
| + auditoria | | |

Únicos e índices: `uk_tdnr_document_key (transport_document_id, nfe_access_key)` (idempotência);
`idx_tdnr_company_key (company_id, nfe_access_key)` (ligação reversa e "CT-e desta NF-e");
`idx_tdnr_unlinked (fiscal_xml_document_id, company_id)` (a rotina de ligação procura os nulos).

**V95__FiscalXmlEventsTransportDocument.** `fiscal_xml_events.transport_document_id` varchar(36) **nulo**, com índice.
A versão antiga ignora a coluna.

### 8.3 Contrato da API

Todas: usuário de escritório; empresa fora da carteira ou de outro escritório: 404 `error.Company.not.found`;
documento de outra empresa: 404 `error.transport.document.not.found`.

| Method and route | Permission | Params | Response |
|---|---|---|---|
| `GET /api/v1/transport-documents` | `INVOICES_VIEW` | `companyId` (obrigatório), `issuedFrom`, `issuedTo` (datas, obrigatórias, até 366 dias), `role` (lista de `ISSUER`, `SENDER`, `SHIPPER`, `RECEIVER`, `RECIPIENT`, `TAKER`), `status`, `nfeAccessKey` (44 dígitos), `search` (número, nome ou documento de participante), `page` (0), `size` (50, máx. 200), `sort` (`issueDate`, `serviceTotal`, `number`; padrão `issueDate,desc`, desempate `number`) | 200 página de resumos; 400 `error.transport.filter.invalid` (`invalidField`) |
| `GET /api/v1/transport-documents/{id}` | `INVOICES_VIEW` | — | 200 detalhe; 404 |
| `GET /api/v1/transport-documents/{id}/xml` | `INVOICES_VIEW` | — | 200 `application/xml`; 410 `error.document.xml.purged`; 404 |
| `GET /api/v1/integrations/xmls/{id}/transport-documents` | `INVOICES_VIEW` | — | 200 lista de resumos dos CT-e ligados à NF-e (pelas referências da mesma empresa); 404 |

**Resumo.** `id`, `accessKey`, `number`, `series`, `issueDate`, `status`, `roles` (lista), `issuerName`,
`takerRole`, `takerName`, `originUf`, `destinationUf`, `serviceTotal`, `icmsAmount`, `linkedNfeCount`, `nfeRefCount`.
**Detalhe.** Os campos do resumo e as colunas da V93 (documentos de pessoa física mascarados, CX-RN-05), `nfeRefs[]`
(`nfeAccessKey`, `fiscalXmlDocumentId`, `nfeNumber`, `nfeIssuerName`), `events[]` (`type`, `date`), `xmlAvailable`.

| Code | Status | Texto (pt) |
|---|---|---|
| `error.transport.document.not.found` | 404 | "CT-e não encontrado." |
| `error.transport.filter.invalid` | 400 | "Filtro inválido." |
| `error.document.xml.purged` | 410 | "O arquivo XML deste documento já foi expurgado; os dados continuam disponíveis." |

---

## 9. Variáveis de ambiente

Todas no `application.yaml` (`taxify.capture.*`) e no `docker-compose.aws.yml` com o padrão seguro. Ligar na EC2:
Tony, e só com todas as instâncias na versão nova (CX-RN-01 b).

| Variável | Padrão | Fase |
|---|---|---|
| (nenhuma) | — | 1 (sem chamada nova) |
| `TAXIFY_SIEG_NFCE_ENABLED` | `false` | 2 |
| `TAXIFY_SIEG_NFCE_XML_TYPE` | `4` (**a confirmar, P-CX-01**; a faixa não roda se o valor não for confirmado no go-live) | 2 |
| `TAXIFY_SIEG_NFCE_WINDOW_START` / `_END` | `22:00` / `06:00` | 2 |
| `TAXIFY_SIEG_NFCE_REQUESTS_PER_TICK` | `2` | 2 |
| `TAXIFY_SIEG_NFCE_MAX_REQUESTS_PER_DAY` | `600` | 2 |
| `TAXIFY_SIEG_NFCE_MAX_REQUESTS_PER_FIRM_PER_DAY` | `200` | 2 |
| `TAXIFY_SIEG_NFCE_MAX_COMPANIES_PER_FIRM` | `20` | 2 |
| `TAXIFY_SIEG_NFCE_COUNT_MAX_AGE_HOURS` | `24` | 2 |
| `TAXIFY_SIEG_NFCE_PREVIOUS_MONTH_DAYS` | `3` | 2 |
| `TAXIFY_SIEG_CTE_ENABLED` | `false` | 3 |
| `TAXIFY_SIEG_CTE_*` (mesmas 8 do NFC-e, com `XML_TYPE=2`, tetos 300/100/50) | — | 3 |
| `TAXIFY_CTE_LINK_CRON` | `0 40 5 * * *` | 3 |
| `TAXIFY_CTE_BACKFILL_ENABLED` | `true` (só copia; idempotente) | 3 |

## 10. Logs

| Evento | Nível | Campos |
|---|---|---|
| `sieg.counts.saved` | DEBUG | `companyId`, `side`, `month` |
| `sieg.counts.save.failed` | WARN | `companyId`, classe da exceção |
| `sieg.lane.tick` | INFO | `lane`, `requests`, `companies`, `budgetLeftGlobal`, `durationMs` |
| `sieg.lane.budget.exhausted` | INFO | `lane`, `scope` (`GLOBAL` ou `firmId`), `date` |
| `sieg.lane.company.failed` | WARN | `lane`, `companyId`, `failureCode` |
| `capture.settings.changed` | INFO | `companyId`, `userId`, `nfceEnabled`, `cteEnabled` |
| `cte.persisted` | DEBUG | `companyId`, `count`, `duplicates` |
| `cte.link.run` | INFO | `linked`, `pending`, `durationMs` |
| `cte.backfill.run` | INFO | `copied`, `withoutFile`, `durationMs` |

## 11. Critérios de aceite

- **CA-CX-01 (CX-RN-10)** Dado uma contagem do lado emitido que devolve `NFe=10, NFCe=3000, CTe=0, CFe=0, NFSe=2`,
  então `sieg_document_counts` do mês e lado tem esses números e o NF-e segue como antes; o número de chamadas ao SIEG
  da rodada é o mesmo de antes da mudança.
- **CA-CX-02 (CX-RN-13)** Dado 10 NF-e e 5 NFC-e emitidas no mês no Taxify e o SIEG com 10 NF-e, então o painel mostra
  o lado emitido em dia (10/10), e não 15/10.
- **CA-CX-03 (CX-RN-11)** Dado uma falha ao gravar a contagem, então a NF-e é baixada e gravada normalmente.
- **CA-CX-04 (6.3)** Dado um FISCAL sem a empresa X na carteira, então X não aparece em `document-volume` nem no
  `summary`. A visão da plataforma mostra só totais, sem nome, CNPJ ou escritório.
- **CA-CX-05 (CX-RN-22)** Dado o orçamento global de 600 com 599 usados e duas instâncias pedindo uma chamada no mesmo
  instante, então só uma chamada sai e `used = 600`.
- **CA-CX-06 (CX-RN-23)** Dado 14:00 de Recife, então a faixa de NFC-e não faz nenhuma chamada.
- **CA-CX-07 (CX-RN-24, 25)** Dado `nfce_count = 120` contado há 2 h e 50 NFC-e importadas, então a faixa baixa a página
  com `Skip = 50` sem chamar o `contar-xmls`.
- **CA-CX-08 (CX-RN-28)** Dado a lista de notas sem o filtro `models`, então o resultado é o de hoje (NF-e e NFC-e);
  com `models=["55"]`, só NF-e.
- **CA-CX-09 (CX-RN-21, teste)** Dado um escritório em teste grátis, quando o ADMIN habilita NFC-e, então 403
  `error.capture.trial.unavailable`.
- **CA-CX-10 (CX-RN-02, 03)** Dado a mesma NFC-e baixada por duas instâncias, então existe uma linha só, e nenhum erro
  é registrado como falha.
- **CA-CX-11 (CX-RN-31, 34)** Dado um CT-e em que a empresa é tomadora e que referencia 2 NF-e, uma delas já no Taxify,
  então o CT-e aparece com `roles = [TAKER]`, `nfeRefCount = 2`, `linkedNfeCount = 1`; quando a segunda NF-e chega,
  `linkedNfeCount = 2`.
- **CA-CX-12 (CX-RN-38)** Dado um CT-e antigo em `fiscal_xml_documents` com o arquivo expurgado, então a rotina cria a
  cópia em `transport_documents` com `metadata_version = 0`, a linha antiga continua e a tela de NF-e não a mostra.
- **CA-CX-13 (CX-RN-01)** Dado o banco migrado até a V95 e a versão anterior da aplicação rodando contra ele, então ela
  sobe (`validate`), captura NF-e e lista notas normalmente.
- **CA-CX-14 (bloqueio)** Dado um escritório bloqueado, então nenhuma faixa roda para ele e o `PUT capture-settings`
  responde 403 `error.firm.access.blocked`.

## 12. Casos de teste

Empresas fictícias: E1 = 11.222.333/0001-81 (varejo, NFC-e), E2 = 12.345.678/0001-95 (transportadora), E3 =
33.444.555/0001-81 (indústria, tomadora de frete), E4 = 44.555.666/0001-81 (outro escritório). Usuários
`admin@exemplo.test`, `fiscal1@exemplo.test` (carteira: E1), `csadmin@exemplo.test` (plataforma).

| CT | Dado | Esperado | RN |
|---|---|---|---|
| CT-CX-01 | Contagem de E1 emitidas: `NFe 10, NFCe 3000, CTe 0, CFe 0, NFSe 2` | Linha (E1, 2026-11, ISSUED) com esses valores e `counted_at` de Recife | 10 |
| CT-CX-02 | Retorno sem o campo `CFe` | `cfe_count = NULL` | 12 |
| CT-CX-03 | Lado emitido de E1 em dia hoje (sem chamada) | Linha inalterada; `counted_at` antigo | 10 |
| CT-CX-04 | Duas instâncias gravam a contagem de E1 ao mesmo tempo | Uma linha; últimos números | 03, 10 |
| CT-CX-05 | E1 com 10 NF-e e 5 NFC-e (upload) no mês; SIEG 10 NF-e | `issuedTotalImported = 10`; lado em dia | 13 |
| CT-CX-06 | E1 com um CT-e antigo (`document_type = 'CTE'`) emitido no mês | Não conta no importado de NF-e | 13 |
| CT-CX-07 | `document-volume?month=2026-11`, E1 com 3.000 NFC-e | `estimatedRequests.nfce = 61` (3.000 ÷ 50 = 60, + 1); `estimatedSiegMinutes` do resumo = soma ÷ 2 | 14 |
| CT-CX-08 | E1 com 3.001 NFC-e | `estimatedRequests.nfce = 62` (teto de 60,02 = 61, + 1) | 14 |
| CT-CX-09 | `month=2025-10` com o Clock em 2026-11-27 | 400 `error.capture.volume.month.invalid` | 6.3 |
| CT-CX-10 | `fiscal1@exemplo.test` lista o volume | Só E1 | 6.3 |
| CT-CX-11 | `csadmin@exemplo.test` em `/platform/capture-panel/document-volume` | Só totais; o corpo não tem `companyId`, `cnpj` nem `firmId` | 6.3 |
| CT-CX-12 | ADMIN habilita NFC-e em E1 (flag ligada, escritório pago) | 200, `nfceEnabled = true`, `version` +1 | 21 |
| CT-CX-13 | 21ª empresa do escritório com NFC-e | 409 `error.capture.nfce.limit`, `limit = 20`, `inUse = 20` | 21 |
| CT-CX-14 | Habilitar com a flag global desligada | 409 `error.capture.lane.disabled`, `lane = NFCE` | 21 |
| CT-CX-15 | `fiscal1@exemplo.test` tenta o `PUT` | 403 | 21 |
| CT-CX-16 | Tick às 21:59 e às 22:00 de Recife | Nenhuma chamada da faixa às 21:59; até 2 às 22:00 | 23 |
| CT-CX-17 | Janela 22:00–06:00, tick às 05:59 e às 06:00 | 05:59 roda; 06:00 não | 23 |
| CT-CX-18 | E1: `nfce_count = 120` contado há 2 h, 50 importadas | 1 chamada `baixar-xmls` com `Skip = 50`, `Take = 50`, `CNPJemit` de E1, `TipoXml` da variável | 24, 25 |
| CT-CX-19 | E1: contagem com 30 h | A faixa conta antes (1 unidade) e depois baixa (1 unidade) | 24 |
| CT-CX-20 | Página vazia com pendência | `provider_total = imported_total`; E1 sai da faixa | 25 |
| CT-CX-21 | Orçamento global com 599 usados; duas instâncias | Só uma chamada; `sieg.lane.budget.exhausted` uma vez | 22 |
| CT-CX-22 | Orçamento do escritório esgotado (200) e o global livre | Faixa pula as empresas do escritório e atende as de outro | 22 |
| CT-CX-23 | Dia 2026-12-02: E1 com pendência de novembro | Faixa trata novembro e dezembro | 26 |
| CT-CX-24 | Dia 2026-12-04 | Novembro encerrado; só dezembro | 26 |
| CT-CX-25 | Mesma NFC-e na página e já importada por upload | `totalDuplicatedSkipped = 1`; sem erro | 02 |
| CT-CX-26 | NFC-e cancelada na página (evento junto) | Nota gravada com `status = CANCELED` | 25 |
| CT-CX-27 | Falha HTTP 500 do SIEG na faixa | `last_failure_code = SIEG_UNAVAILABLE` (lista fechada); log sem corpo | 27 |
| CT-CX-28 | Lista de notas de E1 sem `models` e com `models=["55"]` | 3.010 e 10 | 28 |
| CT-CX-29 | Escritório bloqueado com NFC-e habilitada | Nenhuma chamada da faixa; `PUT` 403 | bloqueio |
| CT-CX-30 | CT-e de E2 (emitente) com `toma3/toma = 3` e destinatário E3, valor da prestação R$ 1.250,00, `ICMS00` com base 1.250,00, alíquota 12,0000 e ICMS 150,00 (fictícios), duas chaves de NF-e | Na empresa E2: `roles = [ISSUER]`, `takerRole = RECIPIENT`, `serviceTotal = 1250.00`, `icmsGroup = ICMS00`, `icmsAmount = 150.00`, `nfeRefCount = 2` | 31 a 34 |
| CT-CX-31 | O mesmo CT-e enviado por upload em E3 | Linha separada em E3 com `roles = [RECIPIENT, TAKER]` | 31 |
| CT-CX-32 | CT-e enviado em E1, que não participa | Recusado no pacote com "empresa não participa do CT-e"; os outros arquivos do pacote seguem | 31 |
| CT-CX-33 | `toma4` com CNPJ de E3 | `takerRole = OTHER`, `taker_document` de E3, `is_taker = true` em E3 | 32 |
| CT-CX-34 | NF-e referenciada chega depois do CT-e, em outra instância, no mesmo segundo | Ligada na gravação ou, no pior caso, pela rotina diária | 34 |
| CT-CX-35 | Cancelamento do CT-e antes do próprio CT-e | Ao chegar o CT-e, `status = CANCELED` | 35 |
| CT-CX-36 | CT-e modelo 67 no upload | Erro "tipo não suportado" contado; pacote segue | 30 |
| CT-CX-37 | Backfill com 3 CT-e antigos (2 com arquivo, 1 expurgado), rodando duas vezes | 3 cópias na primeira rodada; nenhuma nova na segunda; o expurgado com `metadata_version = 0` | 38 |
| CT-CX-38 | `GET /transport-documents/{id}` de CT-e de E4 por usuário do escritório de E1 | 404 | 8.3 |
| CT-CX-39 | `GET /transport-documents/{id}/xml` com arquivo expurgado | 410 `error.document.xml.purged` | 06 |
| CT-CX-40 | `issuedFrom=2026-01-01&issuedTo=2027-01-02` | 400 `error.transport.filter.invalid`, `invalidField = issuedTo` (367 dias) | 8.3 |
| CT-CX-41 | Detalhe de CT-e com remetente pessoa física (CPF fictício 123.456.789-09) | `senderDocument = "***.456.789-**"` | 05 |
| CT-CX-42 | Aplicação anterior (commit de hoje) subindo contra o banco na V95 | Sobe e captura NF-e | 01 |
| CT-CX-43 | Upload de CT-e na versão antiga durante o deploy | Linha em `fiscal_xml_documents`; o backfill da versão nova copia | 01, 38 |

## 13. Tarefas por agente

### Fase 1 (backend e frontend em paralelo; contrato fechado)

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| B-CX-1 | backend | V89; `SiegClient.countXmls` (DTO inteiro) com `contarXmlsNfe` delegando; gravação da contagem em `countPendingNfe` sem derrubar a captura | — |
| B-CX-2 | backend | Correção do importado de NF-e (CX-RN-13), com teste da regressão do painel | — |
| B-CX-3 | backend | `GET /capture-panel/document-volume`, bloco `documentCounts` no detalhe e a rota agregada da plataforma; mensagens pt | B-CX-1 |
| F-CX-1 | frontend | Aba "Volume por tipo" no painel de captura (`/captura`): mês, cartões do resumo, tabela paginada, estimativa de chamadas e minutos; bloco no detalhe da empresa; total agregado no `/plataforma/captura` | contrato |
| Q-CX-1 | QA | CT-CX-01 a 11 por HTTP real, no MySQL local; contar as chamadas ao SIEG (mock) antes e depois | B-CX-3, F-CX-1 |

### Fase 2

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| D-CX-01 | admin da plataforma + dev | Confirmar o `TipoXml` da NFC-e no SIEG e que `CNPJemit` filtra NFC-e (P-CX-01), com uma chamada de teste numa empresa fictícia de homologação ou numa empresa da CS Hub autorizada pelo Tony | — |
| B-CX-4 | backend | V90 a V92; `capture-settings` (GET/PUT, tetos, teste, `version`) | Fase 1 |
| B-CX-5 | backend | Faixa `NFCE` no tick (janela, orçamento atômico, reserva, pendência, virada de mês, resultado por etapa); canal `SIEG_NFCE` no painel; rota `/platform/capture-panel/lanes` | B-CX-4 |
| B-CX-6 | backend | Filtro `models` na busca de notas, na conferência e nos relatórios que listam notas, sem mudar o padrão do backend | — |
| F-CX-2 | frontend | Configuração de captura na ficha da empresa (só ADMIN; escondida sem `available`); filtro "NF-e / NFC-e / Todas" com NF-e por padrão nas telas de notas e na conferência; canal NFC-e no painel | contrato |
| Q-CX-2 | QA | CT-CX-12 a 29; Clock fixo para janela e virada; duas instâncias no orçamento | B-CX-6, F-CX-2 |
| Q-CX-4 | QA | Massa sintética: 40 empresas × 3.000 NFC-e × 5 itens; medir lista, contagem do importado e conferência com filtro; decidir se o índice candidato é preciso | B-CX-5 |
| V-CX-1 | Fiscal | Paralelo de 1 mês com 3 varejistas: apuração com NFC-e capturadas × conferência manual (P-CX-02) | go-live |

### Fase 3

| Id | Agente | Tarefa | Depende de |
|---|---|---|---|
| D-CX-04 | PO + Fiscal | Conferir no MOC do CT-e vigente os caminhos dos campos da CX-RN-31 a 34 e os grupos de ICMS | — |
| B-CX-7 | backend | V93 a V95; parser de CT-e (`CteXmlParser`, separado do de NF-e); persistência idempotente; papéis; referências e ligação nos dois sentidos; eventos; upload roteando CT-e | D-CX-04 |
| B-CX-8 | backend | Rotina de ligação e backfill (CX-RN-34, 38); NF-e esconde `document_type = 'CTE'`; expurgo estendido a `transport_documents` | B-CX-7 |
| B-CX-9 | backend | Rotas de CT-e (8.3) e `integrations/xmls/{id}/transport-documents` | B-CX-7 |
| B-CX-10 | backend | Faixa `CTE` (emitente e destinatário) reaproveitando a da fase 2; lado `TAKER` só depois da P-CX-03 | B-CX-5, B-CX-7 |
| F-CX-3 | frontend | Tela "CT-e" da empresa (`/notas-fiscais` ganha aba ou rota `/ct-e`, a critério do front, com filtros por papel e período), detalhe com NF-e ligadas e eventos, download do XML; na NF-e, bloco "CT-e desta nota" | contrato |
| Q-CX-3 | QA | CT-CX-30 a 43; teste de compatibilidade com a versão anterior (CT-CX-42, 43) | B-CX-10, F-CX-3 |
| B-CX-11 | backend | Fase 3b: distribuição de DF-e do CT-e | P-CX-04 |

**Ordem sugerida:** Fase 1 inteira (B-CX-1 e B-CX-2 em paralelo; F-CX-1 em paralelo com B-CX-3). Na fase 2, B-CX-6 e
F-CX-2 podem começar junto com a fase 1, porque só dependem do contrato. Fase 3 depois do D-CX-04; B-CX-9 e F-CX-3 em
paralelo.

## 14. Riscos

- **Limitador do SIEG é por instância.** Com duas instâncias, o Taxify pode fazer até 4 req/min contra o limite de
  2/min (risco que já existe hoje). A faixa limita o **volume diário** de forma atômica, mas não o ritmo por minuto entre
  instâncias. Recomendação: limitador distribuído numa spec própria antes de ligar a fase 2 com duas instâncias (P-CX-05).
- **Apuração muda de número** quando a NFC-e passa a entrar sozinha: risco de dupla contagem se o escritório já lança o
  faturamento do varejo de outra forma. Mitigação: P-CX-02 antes do go-live e paralelo V-CX-1.
- **`Skip` pelo número importado** supõe ordem estável no SIEG e que o importado veio do SIEG (mesma premissa do NF-e).
  Upload misturado pode pular página: duplicados são ignorados e a página vazia zera a pendência, mas pode faltar nota.
  O painel mostra a diferença entre o SIEG e o importado.
- **Volume:** ~7 milhões de itens por ano com 40 varejistas. Expurgo dos arquivos segue em 3 meses; metadados crescem.
  Medição no Q-CX-4.
- **Telas afogadas:** filtro por modelo com NF-e por padrão no front novo.
- **CT-e antigos duplicados** durante a transição (linha antiga e cópia): a tela de NF-e esconde os antigos; a limpeza
  vem numa migration posterior.

## 15. Pré-requisitos de go-live

1. Fase 1: Q-CX-1 aprovado; deploy com aprovação do Tony.
2. Fase 2: P-CX-01 confirmada (D-CX-01), P-CX-02 respondida pelo Fiscal, P-CX-05 decidida, Q-CX-2 e Q-CX-4 aprovados,
   todas as instâncias na versão nova, Tony liga `TAXIFY_SIEG_NFCE_ENABLED`.
3. Fase 3a: D-CX-04, Q-CX-3, Tony liga `TAXIFY_SIEG_CTE_ENABLED`. Fase 3b: P-CX-04.
4. Jurídico: nada novo para a fase 1. Na fase 3, a política de privacidade já cobre dado de documento fiscal; o CPF de
   pessoa física do CT-e segue a mesma regra do CPF da NF-e (conferir com o jurídico se a política cita CT-e).

## 16. Fora do escopo

- CF-e SAT e NFS-e nesta captura (só contagem; NFS-e já tem a sua).
- NFC-e recebida e NFC-e pela SEFAZ.
- CT-e OS (67), CT-e simplificado, MDF-e.
- Busca retroativa de meses anteriores à habilitação (P-CX-07).
- Qualquer uso fiscal do CT-e (CX-RN-40).
- "Sincronizar agora" para NFC-e e CT-e.

## 17. Perguntas em aberto

### Bloqueiam (o quê, exatamente)

| P | Bloqueia | Pergunta | Opções | Recomendação | Impacto se for diferente |
|---|---|---|---|---|---|
| **P-CX-01** | Ligar a fase 2 | Qual o `TipoXml` da NFC-e no SIEG, e o `CNPJemit` filtra NFC-e? | confirmar na documentação do SIEG e numa chamada de teste | Variável `TAXIFY_SIEG_NFCE_XML_TYPE` (padrão 4, a confirmar) e teste no D-CX-01 | Só o valor da variável |
| **P-CX-02** | Ligar a fase 2 | O Fiscal confirma que apuração, auditoria e relatórios devem somar as NFC-e capturadas? Algum escritório lança o faturamento do varejo de outra forma (dupla contagem)? | somar; somar só a partir de um mês; não somar | Somar a partir do mês em que a captura foi habilitada, com o paralelo V-CX-1 | "Não somar" exige filtro por modelo na apuração (regra fiscal nova, spec própria) |
| **P-CX-03** | Lado tomador do CT-e no SIEG | O `baixar-xmls` filtra CT-e pelo tomador (ou remetente)? Com que campo? | campo próprio do SIEG; não há | Confirmar na documentação; sem filtro, o tomador vem da SEFAZ (fase 3b) e do upload | Sem o lado tomador, a indústria que paga frete fica só com upload até a 3b |
| **P-CX-04** | Fase 3b | Serviço, versão e regras da distribuição de DF-e do CT-e (CTeDistribuicaoDFe): endpoint, autorizador, quais participantes recebem (inclusive o tomador) | estudar a NT do CT-e | D-CX-03 antes de especificar a 3b | Define se a 3b existe |

### Têm recomendação aceitável

| P | Pergunta | Recomendação | Impacto se for diferente |
|---|---|---|---|
| P-CX-05 | Limitador distribuído do SIEG antes da fase 2? | Sim, spec curta própria (fila global no banco); enquanto não existir, ligar a faixa só com uma instância processando o SIEG | Com duas instâncias e sem isso, risco de bloqueio da chave no SIEG |
| P-CX-06 | NFC-e e CT-e no teste grátis? | Não (CX-RN-21) | Liberar exige orçamento próprio para teste |
| P-CX-07 | Buscar meses anteriores à habilitação? | Não nesta fase; avaliar com a recuperação por página que já existe | Um mês de varejista custa ~60 chamadas |
| P-CX-08 | A plataforma vê o volume por empresa ou por escritório? | Só agregados (F1-RN-15 e P-21); a exceção da P-CP-01 não é ampliada | Ampliar exige decisão de privacidade do Tony |
| P-CX-09 | Quem habilita a captura por empresa? | Só o ADMIN do escritório (mexe no custo) | Dar ao FISCAL exige permissão nova |
| P-CX-10 | (Fiscal) O CT-e de entrada gera crédito de ICMS do frete a conferir? | Pergunta para o Fiscal; spec própria | — |
| P-CX-11 | (Fiscal) O frete do CT-e entra na base da Fronteira PE? | Pergunta para o Fiscal, com fonte no RICMS-PE; spec própria | — |
| P-CX-12 | (Fiscal) PIS/COFINS sobre frete no Lucro Real usa o CT-e? | Pergunta para o Fiscal; spec própria | — |
| P-CX-13 | (Fiscal) A receita das transportadoras na apuração passa a vir do CT-e emitido? | Pergunta para o Fiscal; hoje o CT-e não entra na apuração | Mudaria o faturamento das transportadoras |
