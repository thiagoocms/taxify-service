# Captura de XML de NF-e: SIEG e SEFAZ (NFeDistribuicaoDFe)

> Spec retroativa (as-built). Descreve o que o código faz hoje, sem propor mudanças. As divergências e dúvidas encontradas estão em "Perguntas em aberto".

## Status

**Implementado.**

- Migrations: `V16__FiscalXmlDocuments` (notas, chave única por empresa + chave de acesso), `V18__FiscalXmlEvents`, `V19__FiscalXmlSyncControl`, `V29__SefazDistControl`, `V33__FiscalXmlDocumentSources` (de qual fonte a nota já veio), `V34__SefazNoteSummaries` (resumos `resNFe`), `V36__CaptureRuns` (histórico das execuções).
- Commit de referência: `62f0b33` (timeouts do Feign da SIEG sob `spring.cloud.openfeign.client.config`: leitura de 180 s em vez de 60 s). Para o histórico completo, rode `git log -- src/main/java/br/com/cshub/taxify/integration/sieg src/main/java/br/com/cshub/taxify/integration/sefaz`.

## Problema e objetivo

Sem o Taxify, o analista entra no SIEG ou na SEFAZ empresa por empresa, baixa as notas de entrada e de saída e confere se nenhuma ficou de fora. A captura faz isso sozinha e em paralelo por duas fontes:

1. **SIEG**: hub de documentos do escritório. A chave de API é do escritório e o limite é de 2 requisições por minuto para a conta inteira.
2. **SEFAZ, pela NFeDistribuicaoDFe do Ambiente Nacional**, com o certificado A1 de cada empresa. Não usa captcha nem navegador.

A nota é gravada uma única vez por empresa, venha de onde vier. O confronto SIEG × SEFAZ aponta as notas que estão em uma fonte e não na outra.

Como medir hoje:
- Por empresa, `fiscal_xml_sync_control` guarda quantas notas o SIEG informa no mês e quantas já foram importadas.
- `capture_runs` guarda o histórico das execuções da SEFAZ (status OK, SEM_MOVIMENTO ou ERRO).
- O confronto SIEG × SEFAZ conta as notas que só aparecem em uma fonte.

## Usuários e cenário

- **Analista do time Fiscal**: consulta as notas capturadas em "Notas fiscais", filtra pela origem e pelo confronto SIEG × SEFAZ, acompanha as entradas que só têm resumo e faz a manifestação do destinatário.
- **Administrador do escritório**: envia o certificado A1, dispara a captura SEFAZ manual e reenvia a ciência que a SEFAZ recusou (tela da empresa, aba de certificado).
- **Rotinas automáticas**:
  - SIEG: a cada minuto, em lotes de empresas.
  - SEFAZ: de hora em hora, aos 15 minutos.
  - Varredura de eventos: uma vez por dia, por empresa e por lado.

## Histórias de usuário

- Como analista, quero que as notas de entrada e de saída do mês apareçam sozinhas no Taxify, para não baixar XML à mão.
- Como analista, quero saber quantas notas o SIEG tem e quantas já foram importadas, para ter certeza de que nada ficou de fora.
- Como analista, quero que um cancelamento feito depois da importação marque a nota como cancelada, para não conferir uma nota que não vale.
- Como analista, quero ver as notas que estão só no SIEG ou só na SEFAZ há mais de 48 h, para investigar quando uma fonte falha.
- Como analista, quero ver as entradas que a SEFAZ entregou só como resumo e o andamento da Ciência da Operação, para saber quando o XML completo vai chegar.
- Como analista, quero manifestar várias entradas de uma vez (confirmação, desconhecimento, operação não realizada), vendo o prazo de cada uma, para não perder o prazo.
- Como administrador, quero disparar a captura SEFAZ de uma empresa na hora, para não esperar a próxima rodada.

## Regras de negócio

### Captura pelo SIEG

**RN-01: Quem entra na captura SIEG.** Entra a empresa com assinatura vigente (`startDate <= hoje <= endDate`) cujo plano tem `FISCAL_XML_IMPORT` **e** `SIEG_INTEGRATION`. Toda empresa ganha uma linha zerada em `fiscal_xml_sync_control`, mesmo sem direito; a restrição pelo plano vale só para a chamada ao provedor.
`FiscalXmlSyncScheduler.java:143-150`, `:158-176`; `PlanServiceEnum.java:25-27`.

**RN-02: Lote e rodízio.** Cada execução (cron padrão `0 * * * * *`) atende `batch-size` empresas (padrão 2), escolhidas a partir de um cursor que gira e volta ao começo da lista. O cursor fica só em memória e volta ao início quando a aplicação reinicia. A falha de uma empresa vai para o log e não interrompe o lote.
`FiscalXmlSyncScheduler.java:98-133`, `:185-196`; `FiscalXmlSyncProperties.java:28-33`.

**RN-03: Janela.** A sincronização automática pede sempre o **mês corrente, do dia 1 até hoje**. Se `fiscal-xml.sync.reference-date` estiver definido, essa data substitui "hoje".
`FiscalXmlSyncScheduler.java:113`, `:277-278`.

**RN-04: Dois lados, contados à parte.** Primeiro RECEIVED, as notas em que a empresa é destinatária (`CNPJdest`). Depois ISSUED, as notas que ela emitiu (`CNPJemit`). Cada lado tem os próprios contadores: `total_sieg`/`total_importado`/`ultima_verificacao_em` para o recebido e `issued_total_sieg`/`issued_total_imported`/`issued_checked_at` para o emitido.
`FiscalXmlSyncScheduler.java:199-205`; `FiscalXmlIntegrationService.java:145-156`; `FiscalXmlSyncControl.java:43-64`.

**RN-05: Lado em dia não gasta requisição.** Se `totalSieg > 0`, `importado + diferença conhecida do mês >= totalSieg` (RN-08) e a última verificação foi hoje, o lado é pulado. Um lado com `totalSieg = 0` verificado de verdade hoje (janela já gravada) também é pulado, mas só por `TAXIFY_SIEG_ZERO_TOTAL_RECOUNT_HOURS` horas depois da verificação (padrão 6; 0 = reconta a cada tentativa, como antes). Decisão do Tony de 2026-11-27.
`FiscalXmlSyncScheduler.java:263-267`, `:295-297`.

**RN-06: Retomada da paginação.** Quando a última verificação foi no mesmo mês:
- `skip` = notas do lado na janela que **o próprio SIEG já entregou** (`found_in_sieg_at` preenchido), lidas do banco na hora - nunca as que só a SEFAZ ou um upload trouxeram, que não ocupam as primeiras posições da ordem do SIEG (correção B1 de 2026-11-27; antes contava qualquer origem e o SIEG pulava notas);
- se `totalSieg > 0` e `importado + diferença conhecida < totalSieg`, a requisição vai como "pendência conhecida" e o `contar-xmls` é pulado.

Se o mês virou, ou se o lado nunca foi verificado, a pendência não é presumida. O `skip` sempre vem do banco (o da janela nova costuma ser 0).

**Mês anterior nos primeiros dias (decisão do Tony de 2026-11-27).** A janela regular vai do dia 1 até hoje, então uma NF-e de um mês que só chega ao SIEG no mês seguinte não seria pedida. Até o dia `TAXIFY_SIEG_PREVIOUS_MONTH_DAYS` (padrão 10; 0 = nunca), a primeira tentativa do dia de cada lado confere também o mês anterior inteiro pela contagem, com as mesmas regras da janela regular: contagem com `Skip = 0`, download só se a contagem passar do que o SIEG já entregou daquele mês mais a diferença conhecida dele, página a partir do que o SIEG entregou e reconciliação (fora da transação) quando a paginação termina abaixo do total. Não mexe nos contadores do mês corrente: guarda só a diferença do mês anterior (segunda vaga, `*_earlier_gap*`, V85) e atualiza "como o mês anterior fechou" (CP-RN-22) quando é esse o mês. As chamadas vão com a finalidade `previous` no contador `sieg.requests`. Custo: 1 contagem por lado por dia nos dias 1 a 10, mais as páginas do que faltar.
`FiscalXmlSyncScheduler.java:269-278`; `FiscalXmlIntegrationService.java:160-169`.

**RN-07: Conta antes de baixar.**
- Sem pendência conhecida, chama `contar-xmls`, sem `BaixarEventos` e sempre com `Skip = 0` (a janela inteira), para contar só as notas.
- Se a contagem dá 0, não baixa e grava o total 0.
- Se a contagem não passa do que o SIEG já entregou mais a diferença conhecida do mês (RN-08), não baixa (a página seguinte viria vazia).
- "O que o SIEG já entregou" é contado **na mesma fronteira da janela pedida ao SIEG** (dias UTC): no horário gravado, que é o local da nota, de 21:00 do dia anterior a 20:59:59 do último dia (Recife, -03:00). Uma nota de 30/11 22h de Recife (01:00Z de 1/12) conta na janela de dezembro, como no SIEG - sem falso "faltam" em novembro nem em dezembro. Notas de outro fuso (-04:00, -05:00) podem ficar uma hora fora.
- A resposta traz a contagem de todos os tipos: a de NFS-e do mesmo filtro segue para a etapa de NFS-e (RN-10 da spec de NFS-e), sem chamada a mais.
- Se a contagem falha, devolve -1: baixa mesmo assim e **não** grava o total, que continua refletindo só respostas reais do SIEG.

`FiscalXmlIntegrationService.java:171-186`, `:199-209`.

**RN-08: Fim da paginação abaixo do total e reconciliação (B1, 2026-11-27).** Quando uma página volta com menos de 50 itens e o SIEG entregou menos notas da janela do que conta:
- se a página pedida era a primeira (`skip = 0`), a passada já foi completa: a diferença (`total − entregues`) fica guardada para o mês (`*_known_gap`, `*_known_gap_month`, V85);
- senão, e se a diferença for **maior** do que a guardada para o mês, roda uma reconciliação a partir da primeira página, no máximo ceil(total/50) páginas, parando quando completa o total ou numa página curta. Ela roda **fora** da transação do lado: cada página grava na sua própria transação, uma falha para a passada mantendo o que veio e não grava nada (a próxima tentativa pede de novo); só a passada que chega ao fim grava a diferença que sobrou. Uma passada interrompida por falha não é tentada de novo no mesmo dia (por empresa, lado e mês, em memória: um reinício ou a segunda instância podem tentar mais uma vez), para que um erro preso numa página não releia as anteriores a cada vez da empresa na fila. O `POST /fiscal-xml/sincronizar` manual nunca reconcilia;
- a diferença guardada substitui o antigo "total do SIEG = importado": o total continua o que o SIEG disse (o painel mostra a falta) e o agendador trata o lado como em dia enquanto nada passar dessa diferença. Nota que o SIEG conta e nunca entrega, emitente igual ao destinatário contado nos dois lados e a virada do mês em UTC não releem o mês todo dia.
`FiscalXmlIntegrationService.java:164-167`, `:240-242`.

**RN-09: O que conta como "importado".** `count` das notas da empresa com `own_issue` igual ao do lado, `issue_date` dentro da janela e `found_in_sieg_at` preenchido: só as que o SIEG entregou (B1, 2026-11-27). As notas da janela de **qualquer** origem ficam ao lado, em `*_stored_any_source` (V85), para o painel.
`FiscalXmlIntegrationService.java:236-237`; `FiscalXmlDocumentRepository.java:37-39`.

**RN-10: Requisição ao SIEG.** `TipoXml = 1` (NF-e), `Take = 50`, `Skip` conforme a RN-06 (nunca negativo), `BaixarEventos = true` no download. As datas vão em UTC: início às 00:00:00Z e fim às 23:59:59.999Z do dia informado.
`SiegFiscalXmlProvider.java:21`, `:36-45`; `FiscalXmlIntegrationService.java:145-156`, `:764-774`.

**RN-11: Limite de requisições.** Um limitador de janela deslizante de 60 s permite `sieg.max-requests-per-minute` chamadas (padrão 2) e **bloqueia a thread** até haver vaga, em vez de deixar a SIEG devolver HTTP 429. Ele é aplicado no interceptor de todas as chamadas do `SiegFeignClient` (`baixar-xmls` e `contar-xmls`).
`SiegRateLimiter.java:23-37`; `SiegFeignClientConfig.java:21-31`; `SiegProperties.java:14`.

**RN-12: Autenticação no SIEG.**
- Header `X-API-Key` com a chave do **escritório** (FirmIntegration do tipo SIEG). Sem chave, a chamada falha com `error.sieg.api.key.missing`.
- Se `client-id`, `client-secret` e `base-url` estiverem preenchidos, vai também `Authorization: Bearer <jwt>`. O JWT vem de `POST /api/v1/create-jwt` e fica em cache por 55 minutos.

`SiegClient.java:43-46`, `:63-66`; `SiegTokenService.java:25`, `:39-69`.

**RN-13: Leitura da resposta.**
- HTTP 404 com "Nenhum arquivo XML localizado" no corpo é página vazia, não erro.
- Qualquer outro status >= 400 é erro, e os primeiros 500 caracteres do corpo vão para o log.
- O corpo de sucesso é um zip, aberto pelo `FiscalXmlPackageExtractor`.

`SiegClient.java:68-83`.

**RN-14: Varredura de cancelamentos.** Uma vez por dia, por empresa e por lado, são relidas as notas emitidas de hoje − 2 dias até hoje, com os eventos, para pegar cancelamentos feitos depois da importação. A leitura vai de 50 em 50 a partir do início, até 20 páginas, e para quando uma página volta com menos de 50 notas. Os contadores do mês não mudam. O dia só é marcado se a varredura der certo.
Desde 2026-11-27 (A2), o SIEG só é chamado se o banco tem nota do lado emitida na janela - consulta local, sem requisição; a consulta começa um dia antes (hoje − 3), porque os dias do SIEG são UTC e a data gravada é a local. Sem nota, o dia é marcado sem chamada (o painel segue em dia). Desligável por `TAXIFY_SIEG_EVENTS_ONLY_RECENT_NOTES=false`.
`FiscalXmlSyncScheduler.java:75-76`, `:237-257`; `FiscalXmlIntegrationService.java:474-493`.

**RN-15: Sincronização manual pelo SIEG.** `POST /integrations/xmls/sincronizar` roda a mesma lógica para uma empresa e uma janela informada. Sem `side`, roda os dois lados (recebido primeiro) e soma os resultados. Atualiza o `fiscal_xml_sync_control` como a rotina automática.
`FiscalXmlResource.java:150-160`; `FiscalXmlIntegrationService.java:129-139`, `:189-197`.

### Persistência comum (SIEG, SEFAZ e upload)

**RN-16: Gravação única por empresa.** Todo conteúdo passa por `persistAll`:
- evento vai para o `FiscalXmlEventService`;
- NFS-e vai para `service_invoices`;
- NF-e com chave já existente na empresa conta como duplicada e não é regravada.

A mesma chave pode existir em duas empresas do mesmo escritório: é a saída de uma e a entrada da outra. Restrição única: `(company_id, access_key)`.
`FiscalXmlIntegrationService.java:345-430`; `V16__FiscalXmlDocuments.java:99`, `:103-115`.

**RN-17: De qual fonte a nota veio.** `source_system` guarda a primeira origem (`SIEG`, `SEFAZ_DIST` ou `UPLOAD`). Os campos `found_in_sieg_at` e `found_in_sefaz_at` recebem a primeira vez que cada fonte trouxe a nota, seja nova ou já conhecida. Upload não marca nenhum dos dois.
`FiscalXmlIntegrationService.java:406-407`, `:432-444`; `FiscalXmlSource.java:3-10`.

**RN-18: A nota completa o resumo.** Ao gravar uma nota nova, se existe um resumo SEFAZ (`sefaz_note_summaries`) ainda aberto com a mesma chave, ele recebe `completed_at` e a nota passa a contar como vista pela SEFAZ desde a criação do resumo. Vale para nota vinda de qualquer fonte.
`FiscalXmlIntegrationService.java:446-463`.

### Captura pela SEFAZ (NFeDistribuicaoDFe)

**RN-19: Quem entra na captura SEFAZ automática.** Entra a empresa que atende às três condições:
- o escritório tem um certificado A1 de uma empresa com a mesma raiz de CNPJ (8 primeiros dígitos). Filiais usam o certificado da matriz;
- a UF da empresa é válida e está em `sefaz.dist.ufs`. Lista vazia significa todas as UFs;
- o plano vigente tem `FISCAL_XML_IMPORT` **e** `SEFAZ_INTEGRATION`.

`SefazDistScheduler.java:90-116`; `SefazProperties.java:37-52`; `CompanyCertificateService.java:152-166`.

**RN-20: Execução.** Roda pelo cron `sefaz.dist.cron` (padrão `0 15 * * * *`) em uma thread virtual. Se a execução anterior ainda não terminou, a nova é pulada. Cada empresa decide sozinha, pelo `next_query_at`, se a espera da SEFAZ já acabou.
`SefazDistScheduler.java:55-88`; `SefazDistService.java:74-77`.

**RN-21: Pré-condições por empresa.** Cada falha abaixo gera status ERRO com a mensagem gravada em `last_detail`, e a SEFAZ não é chamada:

| Situação | Mensagem |
|---|---|
| Empresa sem CNPJ | "A empresa não tem CNPJ." |
| Sem UF | "UF da empresa não informada." |
| Sem certificado | "Sem certificado A1." |
| Certificado vencido | "Certificado vencido em dd/MM/aaaa." |
| Arquivo do certificado não encontrado | pede para reenviar o certificado |
| Certificado não abre | motivo da falha |

`SefazDistService.java:136-170`.

**RN-22: Leitura por NSU.**
- `distNSU` a partir do `ult_nsu` salvo. O valor inicial do campo é `"000000000000001"`.
- Até `max-batches-per-company` lotes (padrão 40, cerca de 50 documentos cada), com pausa de 2 s entre eles.
- O NSU e o `max_nsu` são salvos **depois de gravar cada lote**. Se a aplicação cair, perde-se no máximo a releitura de um lote, e as notas já conhecidas são puladas.
- A chamada usa SOAP 1.2 com mTLS e HTTP/1.1 obrigatório (a SEFAZ recusa HTTP/2), timeout de 90 s. Os documentos chegam em `docZip`, em gzip e base64.

`SefazDistService.java:172-217`; `SefazDistControl.java:35`; `SefazDistClient.java:54-113`, `:115-135`; `SefazProperties.java:22-25`.

**RN-23: Fim da leitura.** Só `cStat` 137 e 138 são processados. Com 137, ou com `ultNSU == maxNSU`, a empresa está em dia e o `next_query_at` fica em agora + 65 min. O código usa 65 em vez de 60 para não consultar antes da hora pedida pela SEFAZ.
`SefazDistResponse.java:29-31`; `SefazDistService.java:42-43`, `:201-214`.

**RN-24: cStat 656 (consumo indevido).**
- A leitura para e o `next_query_at` fica em agora + 65 min.
- Se a resposta traz um `ultNSU` **maior** que o salvo, o NSU avança até ele e o detalhe da execução avisa que os documentos anteriores não virão por aqui (use o SIEG ou o upload).
- O NSU nunca volta para trás.

`SefazDistService.java:180-199`, `:233-240`.

**RN-25: Outros cStat.** Status ERRO com `cStat + xMotivo` no detalhe; o NSU não muda. Falha de conexão (`IOException`) e erro inesperado também dão ERRO só para aquela execução.
`SefazDistService.java:96-102`, `:201-204`.

**RN-26: Tipos de documento.**

| Schema | Tratamento |
|---|---|
| `procNFe*` (nota completa) | persistência comum (RN-16), origem `SEFAZ_DIST` |
| `procEvento*` (evento completo) | persistência comum (RN-16), origem `SEFAZ_DIST` |
| `resNFe*` (resumo) | vira resumo |
| `resEvento` | ignorado |

`SefazDistResponse.java:14-26`; `SefazDistService.java:220-231`.

**RN-27: Resumo (`resNFe`).** Se a nota completa já existe na empresa, ela só ganha `found_in_sefaz_at`. Se não existe, o resumo é criado ou atualizado em `sefaz_note_summaries` com NSU, emitente, data, tipo, valor, situação, autorização e protocolo. Um resumo conhecido recebe a situação mais nova, por exemplo um cancelamento.
`SefazManifestService.java:81-127`.

**RN-28: Ciência da Operação automática.** Só é enviada se `sefaz.dist.auto-ciencia = true` e o plano vigente tem `FISCAL_XML_IMPORT` + `SEFAZ_INTEGRATION` + `SEFAZ_MANIFESTATION`.
- Candidatos: resumos abertos, com situação `'1'` ou nula, em `PENDENTE` ou em `ERRO` com menos de `ciencia-max-attempts` tentativas (padrão 3), do mais antigo para o mais novo.
- Até `ciencia-per-run` por execução (padrão 100), em lotes de 20.
- O evento é o 210210, `cOrgao 91`, assinado com o A1.

`SefazManifestService.java:129-190`; `SefazNoteSummaryRepository.java:24-32`; `SefazProperties.java:29-34`; `PlanServiceEnum.java:16-20`, `:29-32`; `SefazEventClient.java:50-62`, `:93-94`.

**RN-29: Resultado da ciência.**
- Registrada ou já existente: o resumo vai para `ENVIADA` com data e protocolo. Se foi registrada agora, o evento 210210 também é gravado nas notas da empresa.
- Recusada: `ERRO`, tentativas + 1 e o motivo truncado em 255 caracteres.
- Lote inteiro recusado: todos os resumos do lote vão para `ERRO`.

`SefazManifestService.java:192-238`.

**RN-30: XML completo pela chave.** Depois da ciência, cada resumo `ENVIADA` é consultado com `consChNFe`, desde que:
- a ciência tenha sido enviada há mais de 30 min;
- a última consulta tenha sido há mais de 6 h, ou nunca tenha ocorrido;
- não passe de `fetch-per-run` consultas por execução (padrão 20).

O que acontece com cada resposta:
- `procNFe`: a nota é importada e o resumo é completado (RN-18).
- 656: a busca para.
- `cStat` 632, 640, 641 ou 653: o código desiste da busca. O resumo fica em `ERRO` com as tentativas no máximo e passa a aguardar o SIEG ou o upload.

A busca pela chave não roda se a distribuição já levou 656 na mesma execução.
`SefazManifestService.java:46-51`, `:154-168`, `:240-269`; `SefazDistService.java:83-95`.

**RN-31: Histórico de execuções.** Cada captura SEFAZ grava em `capture_runs` uma linha com serviço `SEFAZ_DIST`, início, fim, status (OK se houve nota, evento, resumo ou ciência; SEM_MOVIMENTO; ERRO), quantidade de notas novas e detalhe de até 500 caracteres. Falha ao gravar o histórico não derruba a captura. O painel de pendências apaga as linhas com mais de 90 dias.
`SefazDistService.java:104-133`, `:254-305`; `V36__CaptureRuns.java`; `PendencyScheduler.java:25`, `:79`.

**RN-32: Captura SEFAZ manual.** `POST /companies/{id}/sefaz/capturar` exige SEFAZ no plano (`error.sefaz.not.in.plan`). Vale para qualquer UF, porque não passa pelo filtro `sefaz.dist.ufs`, e continua respeitando o `next_query_at`. Se a espera ainda não acabou, devolve o estado atual sem chamar a SEFAZ.
`CompanyResource.java:97-106`; `CompanyCertificateService.java:73-81`; `SefazDistService.java:66-77`.

**RN-33: Confronto SIEG × SEFAZ.** Só é calculado quando o plano tem as duas fontes (`FISCAL_XML_IMPORT` + `SIEG_INTEGRATION` + `SEFAZ_INTEGRATION`).
- **Só no SIEG**: nota de terceiro (`own_issue = false`, porque a SEFAZ não distribui as notas da própria empresa), trazida pelo SIEG há mais de 48 h, nunca vista pela SEFAZ e emitida nos últimos 90 dias.
- **Só na SEFAZ**: trazida pela SEFAZ há mais de 48 h e nunca vista pelo SIEG, sem limite de data de emissão.

Na lista de notas, o filtro `fonte = SO_SIEG | SO_SEFAZ` usa o mesmo corte de 48 h, mas sem o limite de 90 dias.
`SefazNotesService.java:28-30`, `:50-69`, `:86-96`; `FiscalXmlDocumentRepository.java:99-104`, `:145-148`; `FiscalXmlQueryService.java:144-145`, `:158-165`.

**RN-34: Reenviar ciência.** `POST /companies/{id}/sefaz/resumos/{chave}/reenviar-ciencia` funciona só para um resumo ainda aberto. Ele volta a `PENDENTE`, com tentativas, detalhe, última busca e detalhe da busca zerados, e sai na próxima captura. Chave inexistente ou resumo já completo devolve 400.
`SefazNotesService.java:71-84`.

### Manifestação do destinatário (tela)

**RN-35: Entradas que devem manifestação.** A lista traz:
- notas completas: NF-e (`document_type = 'NFE'`, modelo 55 ou nulo) endereçadas ao CNPJ da empresa, não emitidas por ela, não canceladas, com chave, emitidas nos últimos 180 dias e **sem** evento conclusivo registrado (210200 confirmação, 210220 desconhecimento, 210240 operação não realizada);
- resumos abertos, autorizados, emitidos nos últimos 180 dias e sem evento conclusivo.

O prazo é a data de autorização + 180 dias. Nas notas completas, usa-se a data de emissão como data de autorização. A lista vem ordenada pelo prazo mais próximo, com no máximo 500 notas e 500 resumos. Também informa se a empresa já teve ciência e se o XML está completo.
`SefazManifestationScreenService.java:59-64`, `:93-140`, `:249-266`; `FiscalXmlDocumentRepository.java:81-97`; `SefazNoteSummaryRepository.java:52-60`.
Fonte legal: o código cita só "180 days from the authorization", sem a norma.

**RN-36: Envio da manifestação.**
- Tipos: `CIENCIA`, `CONFIRMACAO`, `DESCONHECIMENTO`, `NAO_REALIZADA`.
- De 1 a 100 chaves por requisição. Valem só os dígitos, sem repetição, e cada chave precisa ter 44 dígitos.
- `NAO_REALIZADA` exige justificativa de 15 a 255 caracteres, com os espaços normalizados.
- Exige o serviço de manifestação no plano (RN-28) e um certificado A1 válido.
- O envio vai em lotes de 20. A resposta traz o resultado por nota.
- O evento registrado é gravado nas notas. Um resumo que ainda não estava `ENVIADA` passa a `ENVIADA` e é buscado pela chave na próxima captura.
- Falha de conexão marca todas as notas daquele lote como não enviadas, com a mensagem.

`SefazManifestationScreenService.java:142-229`; `SefazEventClient.java:58-80`, `:110-115`.

## Critérios de aceite

1. **(RN-01)** Dado uma empresa com assinatura vigente cujo plano tem `FISCAL_XML_IMPORT` mas não `SIEG_INTEGRATION`, quando o agendador SIEG roda, então o provedor não é chamado para ela e a linha de controle continua como está.
2. **(RN-01)** Dado uma empresa nova, sem linha em `fiscal_xml_sync_control`, quando o agendador roda, então uma linha zerada é criada para ela.
3. **(RN-02)** Dado 5 empresas com direito e `batch-size = 2`, quando o agendador roda 3 vezes, então as empresas atendidas são [1,2], [3,4], [5,1].
4. **(RN-03/RN-04)** Dado o dia 2026-11-26, quando a empresa é sincronizada, então o SIEG recebe a janela de 2026-11-01 a 2026-11-26, primeiro com `CnpjDest` = CNPJ da empresa e depois com `CnpjEmit` = CNPJ da empresa.
5. **(RN-05)** Dado um lado com `total_sieg = 40`, `total_importado = 40` e verificação de hoje, quando o agendador roda, então nenhuma requisição é feita para esse lado.
6. **(RN-06)** Dado um lado verificado neste mês com `total_sieg = 120` e `total_importado = 50`, quando o agendador roda, então o download vai com `Skip = 50` e sem chamar `contar-xmls`.
7. **(RN-06)** Dado um lado verificado no mês anterior com pendência, quando o mês vira, então `Skip = 0` e o `contar-xmls` é chamado.
8. **(RN-07)** Dado que o `contar-xmls` responde 0 NF-e, quando o lado é sincronizado, então o `baixar-xmls` não é chamado e `total_sieg = 0`.
9. **(RN-07)** Dado que o `contar-xmls` falha, quando o lado é sincronizado, então o `baixar-xmls` é chamado e `total_sieg` não muda.
10. **(RN-08)** Dado uma passada completa que termina com 60 entregues de 63, então `total_sieg` continua 63, a diferença 3 fica guardada para o mês e as tentativas seguintes (no mesmo dia e no seguinte) não releem o mês; só uma diferença maior volta a reconciliar.
11. **(RN-11)** Dado `max-requests-per-minute = 2`, quando três chamadas ao SIEG saem no mesmo minuto, então a terceira espera até a primeira sair da janela de 60 s.
12. **(RN-13)** Dado que o SIEG responde 404 com "Nenhum arquivo XML localizado", quando a página é pedida, então o resultado é uma lista vazia, sem erro.
13. **(RN-14)** Dado uma nota importada ontem e cancelada hoje, quando a varredura diária do lado roda, então o evento 110111 é gravado e a nota fica `CANCELED`.
14. **(RN-16)** Dado uma nota já gravada para a empresa A, quando a mesma chave chega de novo para A, então ela conta como duplicada e não ganha um novo registro. Quando a mesma chave chega para a empresa B do mesmo escritório, então é gravada para B.
15. **(RN-17)** Dado uma nota trazida pelo SIEG, quando a SEFAZ entrega a mesma chave, então `source_system` continua `SIEG` e `found_in_sefaz_at` é preenchido.
16. **(RN-19)** Dado uma filial sem certificado próprio cuja matriz (mesma raiz de CNPJ, mesmo escritório) tem A1, com UF coberta e SEFAZ no plano, quando o agendador SEFAZ roda, então a filial é capturada com o certificado da matriz.
17. **(RN-21)** Dado um certificado vencido, quando a captura roda, então o status é ERRO com "Certificado vencido em dd/MM/aaaa." e a SEFAZ não é chamada.
18. **(RN-22/RN-23)** Dado `ult_nsu = 000000000000100` e uma SEFAZ que responde 138 com `ultNSU = 150` e `maxNSU = 150`, quando a captura roda, então as notas são gravadas, `ult_nsu = 150` e `next_query_at` = agora + 65 min.
19. **(RN-24)** Dado `ult_nsu = 100` e uma resposta 656 com `ultNSU = 300`, quando a captura roda, então `ult_nsu = 300`, `next_query_at` = agora + 65 min e o detalhe explica o ajuste. Dada uma resposta 656 com `ultNSU = 50`, então `ult_nsu` continua 100.
20. **(RN-25)** Dado uma resposta com cStat diferente de 137, 138 e 656, quando a captura roda, então o status é ERRO, o detalhe traz `cStat xMotivo` e o NSU não muda.
21. **(RN-27)** Dado um `resNFe` de uma chave já gravada pelo SIEG, quando a captura roda, então nenhum resumo é criado e a nota ganha `found_in_sefaz_at`.
22. **(RN-28/RN-29)** Dado um plano com `SEFAZ_MANIFESTATION` e 25 resumos `PENDENTE`, quando a captura roda, então a ciência sai em dois lotes (20 + 5) e os resumos aceitos vão para `ENVIADA` com protocolo.
23. **(RN-30)** Dado um resumo `ENVIADA` há 40 min e nunca consultado, quando a captura roda, então a nota é pedida pela chave e, se voltar `procNFe`, é importada e o resumo fica completo. Se voltar 632, então o resumo fica `ERRO` com as tentativas no máximo.
24. **(RN-31)** Dado qualquer captura SEFAZ terminada, então existe uma linha em `capture_runs` com serviço `SEFAZ_DIST`.
25. **(RN-32)** Dado uma empresa sem SEFAZ no plano, quando o administrador chama "Capturar agora", então a resposta é 400 `error.sefaz.not.in.plan`.
26. **(RN-33)** Dado um plano só com SIEG, quando a tela de resumos é aberta, então as contagens "só no SIEG" e "só na SEFAZ" vêm nulas.
27. **(RN-35)** Dado uma entrada autorizada em 2026-10-01 sem evento conclusivo, quando a tela de manifestação abre em 2026-11-26, então a nota aparece com prazo 2027-03-30 e 124 dias restantes.
28. **(RN-36)** Dado o tipo `NAO_REALIZADA` com justificativa de 10 caracteres, quando o envio é feito, então a resposta é 400 `error.sefaz.manifestation.justification`.

## Endpoints da API

Base: `/taxify-service/api/v1`. Regra de permissão: anotação no método; se não houver, a da classe (`PermissionAuthorizationManager.java:72-94`). Em `CompanyResource` vale `@AdminOnly(writeOnly = true)`: POST/PUT/DELETE exigem ADMIN e GET exige `COMPANIES_VIEW`.

| Método | Caminho | Permissão | O que faz | Front |
|---|---|---|---|---|
| POST | `/integrations/xmls/sincronizar` | `INVOICES_SYNC` | Sincronização manual SIEG por janela (RN-15) | não usado |
| GET | `/fiscal-xml-sync-control` | `INVOICES_VIEW` | Total SIEG × importado por empresa, **só o lado recebido** | não usado |
| GET | `/companies/{id}/certificado` | `COMPANIES_VIEW` | Certificado em uso e situação da última captura SEFAZ | aba de certificado da empresa |
| POST | `/companies/{id}/sefaz/capturar` | ADMIN | Captura SEFAZ agora (RN-32) | `company-certificate.component.ts:328` |
| GET | `/companies/{id}/sefaz/resumos` | `COMPANIES_VIEW` | Resumos abertos (até 500), contagem por situação da ciência, confronto (RN-33) | `company-certificate.component.ts:267` |
| POST | `/companies/{id}/sefaz/resumos/{chave}/reenviar-ciencia` | ADMIN | RN-34 | `company-certificate.component.ts:275` |
| GET | `/integrations/xmls/manifestacao?companyId=` | `INVOICES_VIEW` | Pendentes de manifestação (RN-35) | `/manifestacao` (`features/invoices/manifestation`) |
| POST | `/integrations/xmls/manifestacao` | `INVOICES_AUDIT` | Envia a manifestação (RN-36) | `/manifestacao` |

Telas relacionadas:
- `/notas-fiscais` (`features/invoices/invoice-xml-list`): filtro de origem `SIEG`, `SEFAZ_DIST`, `UPLOAD`, `SO_SIEG`, `SO_SEFAZ` (`invoice-xml-list.component.ts:140-145`).
- `/pendencias`: saúde das integrações, lida de `capture_runs`, últimos 7 dias (`PendencyService.java:51`, `:203-218`).

## Dados e integrações

**Tabelas**
- `fiscal_xml_documents`: notas. Campos de captura: `source_system`, `found_in_sieg_at`, `found_in_sefaz_at` (V16, V33).
- `fiscal_xml_events`: eventos, chave única `(company_id, access_key, event_type, sequence_number)` (V18).
- `fiscal_xml_sync_control`: uma linha por empresa. Guarda os contadores de cada lado e a data das varreduras diárias (V19; V38 acrescenta `service_invoices_checked_on`, usado pela NFS-e).
- `sefaz_dist_control`: `ult_nsu`, `max_nsu`, `next_query_at`, última execução (V29).
- `sefaz_note_summaries`: resumos `resNFe` e a situação da ciência, única por `(company_id, access_key)` (V34).
- `capture_runs`: histórico por empresa e por execução (V36).

**Integrações externas**

| Integração | Endpoints | Autenticação |
|---|---|---|
| SIEG | `POST {sieg.base-url}/api/v1/baixar-xmls` (zip); `POST /api/v1/contar-xmls` (JSON); `POST /api/v1/create-jwt` | `X-API-Key` do escritório + Bearer opcional |
| SEFAZ NFeDistribuicaoDFe | produção `https://www1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx`; homologação `hom1` | mTLS com o A1 |
| SEFAZ NFeRecepcaoEvento4 do AN | produção `https://www.nfe.fazenda.gov.br/NFeRecepcaoEvento4/NFeRecepcaoEvento4.asmx` (ciência e manifestação) | evento assinado com o A1 |

Exemplo fictício de fluxo:
- Empresa "Indústria Exemplo Ltda", CNPJ 12.345.678/0001-90 (fictício), UF PE.
- Chave de uma entrada: `26251112345678000190550010000012341000123456` (fictícia).
- 1ª captura: chega como `resNFe` e a ciência é enviada.
- Captura seguinte (mais de 30 min depois): `consChNFe` traz o `procNFe`. A nota é gravada com `source_system = SEFAZ_DIST` e o resumo fica completo.

## Configuração

| Variável de ambiente | Propriedade | Padrão no código |
|---|---|---|
| `FISCAL_XML_SYNC_ENABLED` | `fiscal-xml.sync.enabled` | obrigatória no `application.yaml` (true na classe) |
| `FISCAL_XML_SYNC_CRON` | `fiscal-xml.sync.cron` | `0 * * * * *` |
| `FISCAL_XML_SYNC_BATCH_SIZE` | `fiscal-xml.sync.batch-size` | 2 |
| `FISCAL_XML_SYNC_PROVIDER` | `fiscal-xml.sync.provider` | `sieg` (nome do bean `FiscalXmlProvider`) |
| (sem variável) | `fiscal-xml.sync.reference-date` | nula (usa hoje) |
| `SIEG_CLIENT_ID`, `SIEG_CLIENT_SECRET` | `sieg.client-id`, `sieg.client-secret` | obrigatórias no yaml |
| (sem variável) | `sieg.max-requests-per-minute` | 2 |
| (sem variável) | `spring.cloud.openfeign.client.config.siegClient` | conexão 15 s, leitura 180 s |
| `SEFAZ_DIST_ENABLED` | `sefaz.dist.enabled` | true |
| `SEFAZ_DIST_CRON` | `sefaz.dist.cron` | `0 15 * * * *` |
| `SEFAZ_DIST_PRODUCTION` | `sefaz.dist.production` | true (false = homologação, tpAmb 2) |
| `SEFAZ_DIST_MAX_BATCHES` | `sefaz.dist.max-batches-per-company` | 40 |
| `SEFAZ_DIST_UFS` | `sefaz.dist.ufs` | vazio = todas as UFs |
| `SEFAZ_AUTO_CIENCIA` | `sefaz.dist.auto-ciencia` | true |
| (sem variável) | `sefaz.dist.ciencia-per-run` / `ciencia-max-attempts` / `fetch-per-run` / `pause-between-batches-millis` / `timeout-seconds` | 100 / 3 / 20 / 2000 / 90 |
| `CERT_ENCRYPTION_KEY` | `taxify.secrets.key` | vazio (certificado A1 sem cifra própria) |

Pool de agendamento: `spring.task.scheduling.pool.size = 3`. As capturas SEFAZ e ADN rodam em threads virtuais próprias. A sincronização SIEG roda na thread do agendador.

## Testes existentes

- `integration/sieg/SiegRateLimiterTest`, `SiegTokenServiceTest`, `SiegClientTest`
- `service/FiscalXmlSyncSchedulerTest`: elegibilidade, lote, rodízio, lado em dia, pendência conhecida, virada de mês, varredura de eventos, NFS-e diária
- `service/FiscalXmlIntegrationServiceSyncTest`: lados, `contar-xmls` zero ou com falha, página vazia, contagem limitada à janela
- `service/FiscalXmlSyncControlServiceTest`
- `integration/sefaz/SefazDistClientTest`, `SefazEventClientTest`, `SefazPropertiesTest`
- `service/SefazDistServiceTest`: lotes, 656 com e sem ajuste de NSU, rejeição, conexão, certificado ausente ou vencido
- `service/SefazDistSchedulerTest`, `SefazManifestServiceTest`, `SefazManifestationScreenServiceTest`
- `service/SiegXmlIntegrationServiceImportTest`: marcação de fonte e conclusão do resumo

**Lacunas**
- `SefazNotesService` (confronto e reenvio de ciência) não tem teste.
- Os controllers `FiscalXmlResource` e `CompanyResource` (permissões por endpoint, 400 sem plano) não têm teste. Só existe o teste genérico `PermissionAuthorizationManagerTest`.
- Não há teste da janela em UTC (RN-10) nem de notas emitidas perto da meia-noite do último dia do mês.
- O `skip` com notas do mês vindas da SEFAZ é coberto por `SiegCallSavingsIntegrationTest` (B1).
- `SiegFiscalXmlProvider` não tem teste.

## Fora do escopo / limitações conhecidas

- O SIEG baixa só NF-e (`TipoXml 1`) na rotina mensal. CT-e e NFC-e só entram se chegarem por upload ou pela SEFAZ. A NFS-e tem rotina própria (ver a spec de NFS-e).
- A sincronização automática cobre só o mês corrente. Para meses anteriores, use `/sincronizar`, que não tem tela, ou o upload.
- A SEFAZ guarda os documentos por 90 dias, segundo o comentário em `SefazNotesService.java:29` e o README. O que for mais antigo só chega por SIEG ou upload.
- As execuções do SIEG não vão para `capture_runs`, então a saúde mostrada em `/pendencias` cobre só SEFAZ, ADN e certidão.
- O cursor do rodízio SIEG fica em memória e volta ao início quando a aplicação reinicia.
- O limitador bloqueia a thread do agendador. Cada empresa pode gastar várias requisições na mesma execução: contar e baixar nos 2 lados, mais a varredura de eventos, mais a NFS-e.
- `syncFromSieg` e `refreshRecentEvents` são `@Transactional` e envolvem chamadas HTTP de até 180 s.
- O protocolo de autorização da nota não é guardado.

## Perguntas em aberto

1. **(Resolvida em 2026-11-27, B1: o skip conta só o que o SIEG entregou.) Skip do SIEG e notas de outras fontes.** O `skip` enviado ao SIEG é a contagem de notas do lado no mês vindas de **qualquer** origem (RN-06 com RN-09). Exemplo: a SEFAZ já trouxe 30 entradas do mês e o SIEG tem 100. O SIEG recebe `Skip = 30` e pode nunca devolver as 30 primeiras da ordem dele, que não são necessariamente as mesmas 30. É intencional?
2. **Janela em UTC × contagem em horário local.** A janela vai ao SIEG em UTC (RN-10), mas `issue_date` é gravado no horário local da nota (-03:00) e a contagem do "importado" usa esse horário. Notas emitidas depois das 21h do último dia do mês caem no mês seguinte na consulta ao SIEG. Qual fuso deve valer?
3. **Padrão de `SEFAZ_DIST_UFS`.** O README diz que o padrão é `PE`. O código (`SefazProperties.java:37-38`) e o `application.yaml` usam vazio, que significa todas as UFs. Qual é o valor desejado em produção?
4. **NSU inicial.** O `ult_nsu` inicial é `"000000000000001"` (`SefazDistControl.java:35`). Se a SEFAZ devolve os documentos *depois* do `ultNSU` informado, o documento de NSU 1 nunca vem. Confirmar o valor correto com quem conhece a NT da distribuição.
5. **Cancelamento de nota que só tem resumo.** `resEvento` é ignorado (RN-26). O cancelamento de uma nota que só existe como resumo só aparece se a SEFAZ reentregar o `resNFe` com a nova situação. Isso basta?
6. **Painel de controle de importação.** `GET /fiscal-xml-sync-control` expõe só o lado recebido (`FiscalXmlSyncControlService.java:39-49`) e nenhuma tela do front o consome. `POST /integrations/xmls/sincronizar` também não tem tela. O README cita o painel ("a SIEG tem 200 notas, já importamos 180"). Manter, expor ou remover?
7. **Prazo e códigos de desistência.** A fonte do prazo de 180 dias da manifestação (RN-35) e dos `cStat` 632, 640, 641 e 653 usados para desistir da busca pela chave (`SefazManifestService.java:51`) não está citada no código. O Tony valida?
8. **Assimetria do confronto.** "Só no SIEG" fica limitado às notas emitidas nos últimos 90 dias. "Só na SEFAZ" não tem limite, e o filtro da lista não aplica os 90 dias em nenhum dos dois (RN-33). É intencional?
9. **Permissões diferentes para ações parecidas.** A captura SEFAZ manual e o reenvio de ciência exigem ADMIN. A sincronização SIEG manual exige só `INVOICES_SYNC` e a manifestação exige `INVOICES_AUDIT`. É intencional?
10. **Sigilo.** O `src/main/resources/application-default.yaml`, versionado e modificado no working tree, contém credenciais reais: client id/secret da SIEG, da Conta Azul e usuário, senha e host do banco na EC2. Isso fere a regra "nada de dado real em commit" e precisa de decisão: remover do repositório e rotacionar as credenciais?
11. **Javadoc desatualizado.** O Javadoc cita classes que não existem (`SiegXmlSyncScheduler` em `FiscalXmlIntegrationService.java:103` e `SiegXmlIntegrationService#importManual` em `FiscalXmlResource.java:166`). Corrigir?

## Economia de chamadas ao SIEG (2026-11-27)

O SIEG aceita 2 requisições por minuto para a conta inteira (2.880 por dia). A rotina gastava pelo menos 8 por empresa por dia antes de trazer nota nova. Mudanças: RN-05 (lado com total 0), RN-06/RN-09 (skip só do SIEG), RN-07 (não baixa o que já veio; contagem de NFS-e aproveitada), RN-08 (diferença guardada e reconciliação fora da transação), RN-14 (eventos só com nota recente) e a NFS-e (spec própria). Cada chamada é medida pelo contador `sieg.requests` com a tag `purpose` (`count`, `page`, `reconcile`, `events`, `nfse`, `recovery`, `priority`, `manual`) e por uma linha diária no log, só com contagens (`TAXIFY_SIEG_REQUEST_LOG_ENABLED`, `TAXIFY_SIEG_REQUEST_LOG_CRON`).

A janela continua em UTC (00:00Z a 23:59Z do dia): não foi possível confirmar se o SIEG aceita offset. A janela de hoje termina às 20:59 de Recife; as notas da noite entram no dia seguinte. Desde 2026-11-27 as contagens locais comparadas com as do SIEG (o "entregue", o `skip`, a diferença, o "qualquer origem" do painel e a NFS-e) usam a mesma fronteira UTC convertida para Recife, e o mês anterior é conferido nos primeiros dias do mês. **Pergunta de fuso resolvida:** a comparação é sempre na fronteira do SIEG; continua em aberto só se o SIEG aceita offset no filtro (se aceitar, a janela poderia ir no dia de Recife).
