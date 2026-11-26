# Gestão de documentos fiscais: importação manual, busca, eventos, conferência, exportação, DANFE, armazenamento e expurgo

> Spec retroativa (as-built). Descreve o que o código faz hoje, sem propor mudança. Dúvidas e inconsistências estão em "Perguntas em aberto". A captura automática (SIEG/SEFAZ) e a NFS-e têm specs próprias:
> - `2026-11-26-captura-xml-sieg-e-sefaz.md`
> - `2026-11-26-nfse-notas-de-servico.md`

## Status

**Implementado.**

- Migrations:
  - `V16__FiscalXmlDocuments`: notas; chave única `(company_id, access_key)`; colunas de conferência `review_status`, `reviewed_at`, `reviewed_by`, `review_note`.
  - `V17__FiscalXmlDocumentItems`: itens.
  - `V18__FiscalXmlEvents`: eventos.
  - `V27__FiscalXmlHeaderExtras`: campos de cabeçalho usados pelo detalhe sem abrir o arquivo.
  - `V33__FiscalXmlDocumentSources`: fontes.
  - `V55__FiscalXmlItemsCoveringIndex`: índice de itens.
- Commit de referência: `6eadd7a`, índices de pendências e de itens (nota, CFOP, valor), que servem à busca.
- Histórico completo: `git log -- src/main/java/br/com/cshub/taxify/integration/storage src/main/java/br/com/cshub/taxify/service/FiscalXml* src/main/java/br/com/cshub/taxify/service/DanfePdfService.java`.

## Problema e objetivo

A conferência fiscal precisa de um único lugar com todas as notas de cada empresa. O analista deve conseguir:
- trazer à mão o XML que nenhuma fonte automática trouxe;
- achar a nota certa por número, participante, CFOP, origem ou situação;
- ver se ela foi cancelada;
- marcar o que já conferiu;
- baixar o XML, o ZIP do período ou o DANFE.

O módulo também guarda os arquivos XML (disco ou S3) e apaga os antigos (expurgo).

Como medir hoje:
- contadores do upload (novas, duplicadas, com erro);
- status de conferência por nota (`PENDING`/`REVIEWED`/`DIVERGENT`) com quem conferiu e quando;
- pendências de conferência do mês no painel (`countPendingReviewByCompany`).

## Usuários e cenário

- **Analista Fiscal**: trabalha diariamente em `/notas-fiscais` e `/notas-fiscais/:id`. Filtra o período, abre a nota, confere os itens e os eventos, marca como conferida ou com divergência, baixa o XML ou o PDF e exporta o ZIP do mês.
- **Analista com permissão de sincronizar**: importa XML ou ZIP de notas que não vieram por SIEG nem SEFAZ.
- **Rotinas**:
  - expurgo mensal dos arquivos antigos;
  - migração disco → S3, uma vez, na subida da aplicação, quando configurada.

## Histórias de usuário

- Como analista, quero importar um XML ou um ZIP com várias notas de uma empresa, para completar o que as fontes automáticas não trouxeram.
- Como analista, quero filtrar as notas por período, número, entrada/saída, participante, CFOP, origem e situação de conferência, para achar rápido o que preciso conferir.
- Como analista, quero ver os totais de entradas e saídas do período (valor, ICMS, ICMS-ST, IPI, PIS, COFINS) com as canceladas à parte, para ter a visão do mês.
- Como analista, quero ver os eventos da nota (cancelamento, carta de correção, manifestação), para saber se ela ainda vale.
- Como analista, quero marcar uma ou várias notas como conferidas ou com divergência (com descrição), para registrar o andamento e deixar trilha.
- Como analista, quero baixar o XML (original ou retificado), o DANFE em PDF e o ZIP do período, para entregar ao cliente ou a outro sistema.
- Como responsável pela infraestrutura, quero guardar os XMLs no S3 com criptografia e expurgar os arquivos antigos, para não lotar o disco da EC2.

## Regras de negócio

### Importação manual

**RN-01: Entrada.** `POST /integrations/xmls/importar` (multipart: `file` e `companyId`) aceita um `.xml` ou um `.zip`. O tipo é decidido pelos 2 primeiros bytes: `PK` indica zip.

| Situação | Resultado |
|---|---|
| Arquivo vazio | 400 `error.fiscalXml.import.empty.file` |
| Zip sem nenhum `.xml` ou ilegível | 400 `error.fiscalXml.import.no.xml.found` |
| Entrada de diretório ou que não termina em `.xml` | ignorada |
| Zip dentro do zip | não é aberto |
| Arquivo com mais de 20 MB | ignorado, com aviso no log |
| Requisição acima de 50 MB | barrada pelo limite do Spring |

`FiscalXmlResource.java:162-184`; `FiscalXmlIntegrationService.java:266-306`; `FiscalXmlPackageExtractor.java:30-32`, `:54-106`; `application.yaml:50-53`.

**RN-02: Leitura em lotes.** O upload é lido como fluxo, um XML por vez, e gravado em lotes de 100. Entre os lotes, o contexto de persistência é descarregado (`flush` + `clear`). Tudo roda em uma única transação. A resposta traz as contagens e as chaves gravadas, sem o conteúdo dos XMLs.
`FiscalXmlIntegrationService.java:50-51`, `:275-332`.

**RN-03: Classificação de cada XML.**
1. Evento (`procEvento*` ou `evento`): vai para os eventos (RN-09) e não conta como nota.
2. NFS-e: vai para `service_invoices` (spec de NFS-e).
3. O resto é lido como NF-e (`infNFe`) ou CT-e (`infCte`).

`FiscalXmlIntegrationService.java:365-386`.

**RN-04: Chave de acesso.** Os dígitos do atributo `Id` de `infNFe`/`infCte` viram a chave só se forem exatamente 44. Caso contrário, a chave fica nula.
`FiscalXmlMetadataParser.java:426-432`.

**RN-05: Conteúdo não reconhecido.** Um XML sem `infNFe`/`infCte`, ou um conteúdo que nem é XML, vira `UNKNOWN`. Ele conta como erro de leitura **e também é gravado como nota nova**, com arquivo de nome aleatório (UUID). O teste `SiegXmlIntegrationServiceImportTest:207-213` confirma o comportamento.
`FiscalXmlIntegrationService.java:385-390`, `:403`; `FiscalXmlFileStorage.java:127-129`.

**RN-06: Duplicidade.** Uma chave que a empresa já tem conta como duplicada e só ganha a marca da fonte (o upload não marca nenhuma fonte). Nota sem chave nunca é considerada duplicada.
`FiscalXmlIntegrationService.java:392-401`, `:432-444`.

**RN-07: O que é gravado numa nota nova.**
- Arquivo em `{companyId}/{chave}.xml`.
- Cabeçalho completo: identificação, emitente, destinatário, totais, campos extras e IBS/CBS.
- Itens com impostos.
- Retenções federais (`NfeRetentions`).
- `source_system = UPLOAD`.
- Versão do parser (`metadata_version` = 6).
- Direção:
  - `own_issue` = o CNPJ ou CPF do emitente é igual ao documento da empresa;
  - nota que não é própria → `INBOUND`;
  - nota própria com `tpNF = 0` → `INBOUND`;
  - outros casos → `OUTBOUND`.

Depois de gravar, completa o resumo SEFAZ da mesma chave, se houver, e vincula os eventos que chegaram antes da nota.
`FiscalXmlIntegrationService.java:403-417`, `:575-603`; `FiscalDirections.java:17-30`; `FiscalXmlMetadataParser.java:57-61`.

### Eventos

**RN-08: Leitura do evento.**
- Raiz: `procEvento*` ou `evento`.
- Chave: `chNFe` ou `chCTe`.
- Tipo: `tpEvento`; sequência: `nSeqEvento`; data: `dhEvento`.
- Texto: `xJust` ou `xCorrecao`.
- Protocolo: `nProt` do `retEvento`.
- **Registrado** só quando o `retEvento` traz `cStat` 135, 136 ou 155. Um evento sem `retEvento` conta como não registrado.

`FiscalEventParser.java:28-68`; `FiscalEventData.java:6-31`.

**RN-09: Gravação do evento.** O evento é único por `(empresa, chave, tipo, sequência)`; uma repetição é ignorada. A descrição é cortada em 120 caracteres. Se a nota já existe, o evento é vinculado a ela na hora. Se não existe, fica sem vínculo e é aplicado quando a nota chegar.
`FiscalXmlEventService.java:26`, `:39-79`; `V18__FiscalXmlEvents.java:27`.

**RN-10: Cancelamento.** Um evento **registrado** de tipo 110111 (cancelamento) ou 110112 (cancelamento por substituição) produz três efeitos:
- a nota vai para `status = CANCELED`;
- `canceled_at` recebe o `dhEvento`;
- a trilha de auditoria ganha `CANCELED` com o texto "Cancelada na SEFAZ: <justificativa>".

Uma nota já cancelada não é processada de novo. Carta de correção (110110) e manifestação ficam só registradas.
`FiscalEventData.java:24-31`; `FiscalXmlEventService.java:89-99`.

### Conferência (revisão)

**RN-11: Conferir notas.**
- Status: `REVIEWED`, `DIVERGENT` ou `PENDING`.
- `DIVERGENT` exige uma nota explicativa (`error.review.note.required`).
- A lista de ids não pode estar vazia (`error.review.documents.required`) e aceita até 500 ids.
- A nota explicativa tem até 500 caracteres.
- Operação tudo ou nada: se algum id não existe ou está excluído, a resposta é 404 e nada muda.
- O acesso é conferido por empresa.
- `reviewed_by` recebe o login do usuário, ou `system`.
- `PENDING` limpa `reviewed_at` e `reviewed_by`.
- Toda mudança grava na trilha de auditoria: `REVIEWED` → `OK`, `DIVERGENT` → `DIVERGENT`, `PENDING` → `REVIEW_REOPENED`. A chave da trilha é a chave de acesso, ou o id da nota quando não há chave.

`FiscalXmlReviewService.java:40-78`; `FiscalXmlDocument.java:974-980`; `ReviewRequestDTO.java:10-15`.

### Busca, detalhe e totais

**RN-12: Busca (lista).** Lê só as colunas do banco, sem abrir arquivo. `companyId` é obrigatório. Os filtros são opcionais:

| Filtro | Regra |
|---|---|
| Período | por `issue_date`, em dias inteiros; data vazia deixa o limite aberto |
| Número | exato, sem zeros à esquerda (`000123` = `123`) |
| Direção | `INBOUND`/`OUTBOUND` |
| Participante | parte do nome do emitente ou do destinatário, sem diferenciar maiúsculas, ou os dígitos do CNPJ/CPF quando o texto só tem dígitos e `./-` |
| Conferência | nulo no banco conta como `PENDING` |
| Origem | `SIEG`, `SEFAZ_DIST`, `UPLOAD` |
| Fonte | `SO_SIEG`/`SO_SEFAZ`, com corte de 48 h |
| CFOP | algum item **emitido** com aquele CFOP |
| Divergência | só notas com achado das checagens fiscais ou marcadas `DIVERGENT` |

Notas excluídas ficam fora. A ordem padrão é `issue_date` decrescente. O filtro de divergência roda **em memória**: todas as notas dos outros filtros são carregadas, checadas e paginadas no servidor.
`FiscalXmlQueryService.java:134-209`; `FiscalXmlDocumentRepository.java:48-79`; `XmlSearchFilterDTO.java:16-57`; `IssuePeriod.java:16-24`.

**RN-13: CFOPs mostrados na lista.** Por nota, os CFOPs distintos dos itens. Se a nota tem versão retificada gerada pela regra atual do produto, mostra o CFOP da regra, ajustado à operação. Senão, mostra o CFOP emitido.
`FiscalXmlQueryService.java:187-228`.

**RN-14: Totais do período.** Usam só a empresa e o período; os outros filtros são ignorados. O resultado sai por direção (`INBOUND`/`OUTBOUND`; direção nula conta como entrada).
- Somas: quantidade, Σ total da nota, Σ ICMS, Σ ICMS-ST, Σ IPI, Σ PIS, Σ COFINS.
- Notas **canceladas ficam fora das somas** e são contadas à parte.

`FiscalXmlDocumentRepository.java:150-168`; `FiscalXmlQueryService.java:98-126`.

**RN-15: Detalhe.** Uma NF-e com `metadata_version >= 4` é montada só pelas colunas. Os outros casos reabrem o arquivo. Se o arquivo não existe mais (expurgado), o detalhe volta às colunas, sem os poucos campos que as versões antigas não guardavam. O detalhe traz direção, situação, cancelamento, conferência e fontes.
`FiscalXmlQueryService.java:230-280`, `:338-382`.

**RN-16: Itens.** A aba de produtos mostra os itens **como a nota está agora**: o CFOP retificado quando há versão retificada com a regra atual, e o CFOP emitido guardado em `cfopEmitido`. O DANFE usa os itens **como foram emitidos**. Os dois são lidos do banco, sem abrir o arquivo.
`FiscalXmlQueryService.java:282-307`.

**RN-17: XML bruto.** `GET /{id}/xml?retificado=false|true` devolve o original. Com `retificado=true`, devolve a versão retificada quando ela existe; se não existe, devolve o original. O arquivo se chama `{chave}.xml`, ou `{id}.xml` sem chave. Arquivo ausente responde 404 `error.fiscalXml.file.not.found`.
`FiscalXmlResource.java:126-138`; `FiscalXmlQueryService.java:309-336`.

**RN-18: Isolamento.** Toda leitura e escrita resolve a empresa por `CompanyValidation.checkExist`, que chama `TenantAccessService.assertCompanyAccess`. A nota é lida por `findFirstByIdAndDeletedIsFalse` com a mesma checagem.
`CompanyValidation.java:38-49`; `FiscalXmlQueryService.java:322-327`.

### Exportação

**RN-19: Exportar lista (JSON) e ZIP.** As duas exportações filtram por empresa e período (`issue_date`). Para cada nota, usam a versão **retificada** quando ela existe; senão, o original.
- **JSON** (`/exportar`): cabeçalho, itens e o XML de cada nota.
- **ZIP** (`/exportar/zip`, arquivo `notas-fiscais.zip`): um arquivo por nota, `{chave}.xml` ou `{id}.xml`. Nomes repetidos ganham `-2`, `-3`... O ZIP é montado inteiro em memória.
- Arquivo ilegível ou expurgado é pulado e registrado no log, sem derrubar a exportação.
- A consulta **não filtra notas excluídas nem canceladas**.

`FiscalXmlResource.java:197-219`; `FiscalXmlIntegrationService.java:495-573`; `FiscalXmlDocumentRepository.java:32-33`.

### DANFE

**RN-20: PDF da nota.** `GET /{id}/pdf` gera, em tempo real, um PDF A4 a partir do detalhe (RN-15) e dos itens emitidos (RN-16), com a mesma checagem de acesso.
- Título: `DACTE` para CT-e, `DANFE NFC-e` para o modelo 65, `DANFE` nos demais casos.
- Marca d'água "SEM VALOR FISCAL - HOMOLOGAÇÃO" quando `tpAmb = 2`.
- Código de barras CODE128 da chave.
- Nos dados adicionais, avisa quando existe uma versão retificada no Taxify; o PDF reflete o XML original.
- O protocolo de autorização não é guardado e fica fora do PDF.
- **Não há marca de nota cancelada.**

`DanfePdfService.java:43-105`, `:150-163`, `:283-291`, `:345-376`.

### Armazenamento

**RN-21: Onde os arquivos ficam.** `fiscal-xml.storage.type` define o destino:
- `local`: arquivos em `fiscal-xml.sync.storage-path`, o volume Docker;
- `s3`: arquivos no bucket. Sem bucket configurado, a aplicação não sobe.

A leitura usa o prefixo da localização gravada: `s3://` vai para o S3, qualquer outra coisa vai para o disco. Assim, as notas gravadas em disco antes da mudança continuam abrindo. O S3 grava com SSE-S3 (`AES256`). As credenciais vêm da cadeia padrão da AWS (role da EC2 ou `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`). `endpoint` opcional, com path-style, para S3 compatível.
`FiscalXmlFileStorage.java:21-54`, `:98-149`; `S3XmlStorage.java:20-69`, `:104-107`; `LocalXmlStorage.java:10-42`; `StorageConfig.java:7-15`.

**RN-22: Organização das chaves.** Os caminhos são relativos à pasta `storage-path` no disco, ou ao `prefix` do bucket no S3 (padrão `fiscal-xml`). A localização gravada no banco é o caminho do disco ou `s3://{bucket}/{prefix}/{chave}`.

| Arquivo | Caminho |
|---|---|
| Original | `{companyId}/{chave}.xml` |
| Retificado | `{companyId}/{chave}-retificado-{epochMillis}.xml` (nunca sobrescreve o original nem a versão anterior) |
| NFS-e | `{companyId}/NFSE-{chave}.xml` |
| Certificado A1 | `certificados/{companyId}-{ms}.pfx[.enc]` |
| Extrato da Fronteira | `fronteira/{companyId}-{período}-{ms}.pdf` |
| Certidão | `certidoes/{companyId}-{UF}-{ms}.pdf` |

`FiscalXmlFileStorage.java:64-96`, `:127-137`; `S3XmlStorage.java:55-69`.

**RN-23: Migração do disco para o S3.** Roda com `type=s3` e `migrate-local=true`, em segundo plano numa thread virtual, quando a aplicação termina de subir.
- Copia o original e o retificado de cada nota que ainda não está no S3, em lotes de 200 ordenados por id, mantendo a chave `{companyId}/{arquivo}`.
- Atualiza o caminho gravado na nota.
- **Não apaga o disco.**
- Arquivos ausentes contam como "missing" (já expurgados); falhas vão para o log e são refeitas no próximo restart.

`FiscalXmlStorageMigration.java:17-106`; `FiscalXmlDocumentRepository.java:199-207`.

### Expurgo

**RN-24: Expurgo dos arquivos.**
- Cron `fiscal-xml.purge.cron`, padrão `0 0 4 1 * *`: dia 1 de cada mês, às 04:00.
- Pega as notas não excluídas com `issue_date` < agora − `retention-months` (padrão 3). Vale a data fiscal da nota, não a data de importação.
- Apaga o arquivo original e o retificado referenciado na nota.
- As colunas da nota e os itens **ficam** no banco, e o caminho do arquivo também fica gravado.
- É idempotente: apagar o que já foi apagado não faz nada. Uma nota que falha não para as outras.

`FiscalXmlPurgeScheduler.java:16-81`; `FiscalXmlPurgeProperties.java:6-20`.

**RN-25: Efeito do expurgo nas telas.**
- O detalhe e o DANFE continuam saindo pelas colunas do banco (RN-15, RN-16, RN-20). Nas notas gravadas por parser antigo (versão < 4), faltam alguns campos de cabeçalho.
- A exportação pula a nota (RN-19).
- O XML bruto (original e retificado) responde 404 (RN-17).

`FiscalXmlQueryService.java:247-253`, `:329-336`.

### Infraestrutura de jobs (referência)

**RN-26: IntegrationJobRunner.** Executor de jobs em segundo plano com estado na linha `FirmIntegration` do escritório:
- trava atômica no banco (`claimSync`);
- heartbeat a cada 60 s; job sem heartbeat por 5 min conta como abandonado e pode ser reiniciado;
- progresso gravado no máximo a cada 2 s;
- resultado em JSON e erro com até 1.000 caracteres;
- o contexto de segurança de quem iniciou acompanha o job.

Hoje ele é usado **só** pela importação da Acessórias e pela sincronização de cobrança. A captura de XML, a NFS-e e o upload **não** usam o runner. `SyncProgress` é a interface de progresso que o runner usa.
`IntegrationJobRunner.java:29-191`; `SyncProgress.java:3-22`.

## Critérios de aceite

1. **(RN-01)** Dado um upload vazio, quando ele é enviado, então a resposta é 400 `error.fiscalXml.import.empty.file`.
2. **(RN-01)** Dado um ZIP só com um PDF dentro, quando ele é enviado, então a resposta é 400 `error.fiscalXml.import.no.xml.found`.
3. **(RN-02/RN-07)** Dado um ZIP com 250 NF-e inéditas da empresa, quando ele é enviado, então as 250 são gravadas (em três lotes: 100 + 100 + 50) com `source_system = UPLOAD`, e a resposta traz `totalPersistedNew = 250`.
4. **(RN-03)** Dado um ZIP com 3 notas e 2 `procEventoNFe`, então `totalXmlsCounted = 3`, `totalEventsRecorded = 2` e os eventos não contam como erro.
5. **(RN-05)** Dado o conteúdo "não é xml", quando ele é enviado, então `totalParseErrors = 1` **e** `totalPersistedNew = 1`. Esse é o comportamento atual, coberto por teste.
6. **(RN-06)** Dado uma chave que a empresa já tem, quando o mesmo XML é reenviado, então `totalDuplicatedSkipped = 1` e nada novo é gravado.
7. **(RN-07)** Dado uma NF-e emitida pelo CNPJ da empresa com `tpNF = 0`, quando ela é gravada, então `own_issue = true` e `direction = INBOUND`. Com `tpNF = 1`, então `direction = OUTBOUND`.
8. **(RN-09/RN-10)** Dado um cancelamento registrado (`cStat` 135) que chega antes da nota, quando a nota é importada depois, então ela já entra `CANCELED`, com `canceled_at` igual ao `dhEvento`, e a trilha ganha "Cancelada na SEFAZ".
9. **(RN-08)** Dado um `evento` sem `retEvento`, quando ele é gravado, então `registered = false` e a nota não é cancelada.
10. **(RN-11)** Dado o status `DIVERGENT` sem descrição, quando o usuário confere, então a resposta é 400 `error.review.note.required`.
11. **(RN-11)** Dado 3 ids, um deles inexistente, quando é feita a conferência em lote, então a resposta é 404 e nenhuma das três muda.
12. **(RN-11)** Dado uma nota `REVIEWED`, quando ela volta para `PENDING`, então `reviewed_at` e `reviewed_by` ficam nulos e a trilha ganha `REVIEW_REOPENED`.
13. **(RN-12)** Dado o número "000123" no filtro, então a nota de `nNF = 123` aparece.
14. **(RN-12)** Dado o participante "12.345.678/0001-90" (fictício), então a busca é feita pelos dígitos `12345678000190` nos CNPJs do emitente e do destinatário.
15. **(RN-14)** Dado, no período, 2 entradas válidas de R$ 1.000,00 e R$ 500,00 e 1 entrada cancelada de R$ 300,00, quando os totais são pedidos, então as entradas mostram quantidade 2, total R$ 1.500,00 e 1 cancelada.
16. **(RN-15/RN-25)** Dado uma NF-e versão 4+ com o arquivo expurgado, quando o detalhe e o PDF são pedidos, então os dois saem completos pelas colunas. Quando o XML bruto é pedido, então a resposta é 404 `error.fiscalXml.file.not.found`.
17. **(RN-17)** Dado uma nota sem versão retificada, quando o XML é pedido com `retificado=true`, então volta o original.
18. **(RN-18)** Dado o id de uma nota de outro escritório, quando qualquer endpoint `/integrations/xmls/{id}` é chamado, então o acesso é negado como recurso não encontrado.
19. **(RN-19)** Dado duas notas sem chave e uma nota retificada, quando o ZIP é exportado, então os nomes não se repetem (`{id}.xml`) e o conteúdo da retificada é o arquivo retificado.
20. **(RN-19)** Dado uma nota com o arquivo expurgado no período, quando o ZIP é exportado, então ela é pulada e as outras saem.
21. **(RN-20)** Dado uma NF-e de homologação (`tpAmb = 2`), quando o PDF é gerado, então todas as páginas têm a marca d'água "SEM VALOR FISCAL - HOMOLOGAÇÃO".
22. **(RN-21)** Dado `type=s3` sem bucket, quando a aplicação sobe, então ela falha com "fiscal-xml.storage.s3.bucket is required".
23. **(RN-21)** Dado `type=s3` e uma nota com caminho de disco, quando o XML é lido, então ele é lido do disco.
24. **(RN-23)** Dado `migrate-local=true`, quando a aplicação sobe, então os arquivos de disco são copiados para o S3, o caminho da nota passa a `s3://...` e o arquivo de disco continua existindo.
25. **(RN-24)** Dado `retention-months = 3`, a data 2026-12-01 e uma nota emitida em 2026-08-15, quando o expurgo roda, então os arquivos original e retificado dela são apagados e a linha da nota continua no banco.

## Endpoints da API

Base: `/taxify-service/api/v1/integrations/xmls`. A classe tem `@RequiresPermission(INVOICES_VIEW)`; quando um método tem anotação própria, ela vale no lugar da da classe.

| Método | Caminho | Permissão | O que faz | Tela |
|---|---|---|---|---|
| POST | `` (raiz) | `INVOICES_VIEW` | Busca paginada (RN-12) | `/notas-fiscais` |
| POST | `/totais` | `INVOICES_VIEW` | Totais do período (RN-14) | `/notas-fiscais` (cards) |
| GET | `/{id}` | `INVOICES_VIEW` | Detalhe (RN-15) | `/notas-fiscais/:id` |
| GET | `/{id}/produtos` | `INVOICES_VIEW` | Itens como estão (RN-16) | `/notas-fiscais/:id` |
| GET | `/{id}/eventos` | `INVOICES_VIEW` | Eventos (RN-08 a RN-10) | `/notas-fiscais/:id` |
| GET | `/{id}/xml?retificado=` | `INVOICES_VIEW` | XML bruto (RN-17) | `/notas-fiscais/:id` |
| GET | `/{id}/pdf` | `INVOICES_VIEW` | DANFE (RN-20) | `/notas-fiscais/:id` ("Baixar PDF") |
| PUT | `/{id}/conferencia` | `INVOICES_AUDIT` | Conferir uma nota (RN-11) | `/notas-fiscais/:id` |
| POST | `/conferencia` | `INVOICES_AUDIT` | Conferir várias (RN-11) | `/notas-fiscais` (seleção → "Conferidas") |
| POST | `/importar` (multipart) | `INVOICES_SYNC` | Upload manual (RN-01 a RN-07) | `/notas-fiscais` ("Importar XML", aceita `.xml,.zip`) |
| POST | `/exportar` | `INVOICES_VIEW` | Exportação em JSON (RN-19) | não usado pelo front |
| POST | `/exportar/zip` | `INVOICES_VIEW` | ZIP do período (RN-19) | `/notas-fiscais` ("Exportar ZIP") |
| POST | `/{id}/retificar` | `INVOICES_RECTIFY` | Retificação (fora desta spec) | `/notas-fiscais/:id` |

Arquivos do front:
- `features/invoices/invoice-xml-list/invoice-xml-list.component.ts`:
  - filtros: período "Este mês", "Mês anterior", "Últimos 90 dias", "Todo o período", "Personalizado"; conferência; origem e fonte (linhas 56-60 e 134-145);
  - botão "Importar XML", visível com `INVOICES_SYNC` (linhas 213-215);
  - conferência em lote com `INVOICES_AUDIT` (linhas 150 e 408).
- `features/invoices/invoice-detail/invoice-detail.component.ts`: conferência (linha 478) e PDF (linhas 520-542).
- `core/services/fiscal-xml.service.ts`: cliente HTTP.
- Rotas em `app.routes.ts:47-54` e `:95-102`, com o guard `INVOICES_VIEW`.

## Dados e integrações

**Tabelas**
- `fiscal_xml_documents` (V16 e posteriores):
  - cabeçalho completo;
  - totais `decimal(19,2)`;
  - `xml_file_path` e `rectified_file_path` (até 1.000 caracteres);
  - `source_system`, `direction`, `own_issue`, `status`, `canceled_at`;
  - colunas de conferência;
  - `metadata_version`;
  - `found_in_*`.

  Chave única `(company_id, access_key)`; índices `(company_id, issue_date)` e `(company_id, direction, issue_date)`.
- `fiscal_xml_document_items` (V17 e posteriores).
- `fiscal_xml_events` (V18): chave única `(company_id, access_key, event_type, sequence_number)`; `document_id` nulo enquanto a nota não chegou.
- `invoice_audit_log` (V15): trilha da conferência e dos cancelamentos.

**Integrações externas**
- **AWS S3**: bucket, região (padrão `us-east-2`), prefixo (padrão `fiscal-xml`) e endpoint opcional.

## Configuração

| Variável | Propriedade | Padrão |
|---|---|---|
| `FISCAL_XML_STORAGE_PATH` | `fiscal-xml.sync.storage-path` | `./data/fiscal-xml` |
| `FISCAL_XML_STORAGE_TYPE` | `fiscal-xml.storage.type` | `local` |
| `FISCAL_XML_STORAGE_MIGRATE_LOCAL` | `fiscal-xml.storage.migrate-local` | false |
| `FISCAL_XML_S3_BUCKET` / `FISCAL_XML_S3_REGION` / `FISCAL_XML_S3_PREFIX` / `FISCAL_XML_S3_ENDPOINT` | `fiscal-xml.storage.s3.*` | vazio / `us-east-2` / `fiscal-xml` / vazio |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | cadeia padrão da AWS | role da EC2, recomendada |
| `FISCAL_XML_PURGE_ENABLED` | `fiscal-xml.purge.enabled` | obrigatória no yaml (true na classe) |
| `FISCAL_XML_PURGE_CRON` | `fiscal-xml.purge.cron` | `0 0 4 1 * *` |
| `FISCAL_XML_PURGE_RETENTION_MONTHS` | `fiscal-xml.purge.retention-months` | 3 |
| (sem variável) | `spring.servlet.multipart.max-file-size` / `max-request-size` | 50MB / 50MB |

Constantes no código:
- 100 XMLs por lote de upload (`FiscalXmlIntegrationService.java:51`);
- 20 MB por XML (`FiscalXmlPackageExtractor.java:32`);
- 500 ids por conferência (`ReviewRequestDTO.java:12`).

## Testes existentes

- `service/SiegXmlIntegrationServiceImportTest`: XML único, ZIP, eventos, duplicada, origem do upload, conclusão do resumo, conteúdo inválido, ZIP grande em lotes.
- `integration/fiscalxml/FiscalXmlPackageExtractorTest`, `FiscalEventParserTest`.
- `service/FiscalXmlEventServiceTest`, `FiscalXmlReviewServiceTest`.
- `service/FiscalXmlQueryServiceSearchTest`, `FiscalXmlQueryServiceDetailTest`.
- `service/FiscalXmlIntegrationServiceExportTest`.
- `service/DanfePdfServiceTest`.
- `integration/storage/S3XmlStorageTest`, `FiscalXmlStorageMigrationTest`.
- `service/FiscalXmlPurgeSchedulerTest`: desligado, apaga original e retificado, corte por meses, nota sem arquivo, falha isolada.
- `integration/sieg/FiscalXmlMetadataParserTest`; `service/FiscalDirectionsTest`.
- `service/job/IntegrationJobRunnerTest`.

**Lacunas**
- Não há teste de controller do `FiscalXmlResource`: permissões por endpoint, `Content-Disposition`, multipart vazio.
- `LocalXmlStorage` e o roteamento de leitura do `FiscalXmlFileStorage` (disco × `s3://`) não têm teste.
- `FiscalXmlQueryService.totals` (canceladas fora das somas) e `findXml` com `retificado=true` sem versão retificada não têm teste.
- Não há teste de exportação com nota excluída ou cancelada, nem de DANFE de nota cancelada.
- O comportamento do filtro "somente divergentes" em memória com volume grande não tem teste.

## Fora do escopo / limitações conhecidas

- A retificação de CFOP/NCM (`FiscalXmlRectificationService`) e as checagens de divergência aparecem aqui só pelo efeito na lista, nos itens e na exportação. Elas são outro módulo.
- O `FiscalXmlResource` não tem endpoint para excluir nota. O soft delete (`deleted`) existe no modelo e é respeitado na busca e no detalhe.
- O ZIP de exportação é montado inteiro em memória. Com "Todo o período", pode ficar grande para a EC2 de 2 GB.
- O filtro "somente divergentes" carrega todas as notas do filtro na memória.
- O expurgo carrega todas as notas antigas a cada execução e tenta apagar de novo as que já foram expurgadas. No S3, isso custa uma chamada `HEAD` por arquivo.
- O S3 grava todo objeto com `contentType application/xml`, inclusive PDFs e certificados.
- A importação inteira roda em uma única transação.

## Perguntas em aberto

1. **Lixo gravado como nota.** Um conteúdo que não é NF-e nem CT-e (até texto qualquer) é gravado como nota `UNKNOWN`, além de contar como erro (RN-05). Essa linha aparece na busca e na exportação. É intencional, ou deveria ser descartada?
2. **Nota de terceiro no upload.** O upload aceita uma NF-e em que a empresa não é emitente nem destinatária, e ela entra como `INBOUND`. Deveria ser recusada ou avisada?
3. **Nota excluída na deduplicação e na exportação.** A verificação de duplicada (`existsByCompany_IdAndAccessKey`) e a exportação (`findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc`) não filtram `deleted`. Uma nota excluída impede a reimportação e sai no ZIP. A exportação também inclui as canceladas. O que deve sair na exportação?
4. **Retenção de 3 meses.** O expurgo apaga os arquivos após 3 meses pela data de emissão. Com isso, o XML bruto, a exportação, a retificação, os backfills de parser e qualquer recálculo que dependa do XML (Fronteira, auditoria) param de funcionar para essas notas. Qual é o prazo de guarda que o escritório quer? Há exigência de guarda dos XMLs que o Tony precise considerar? (O código não cita norma.)
5. **NFS-e fora do expurgo e da migração.** O expurgo e a migração para o S3 ignoram os arquivos de NFS-e (`service_invoices`). Eles devem seguir a mesma política?
6. **DANFE de nota cancelada.** O DANFE de uma nota cancelada sai sem nenhuma indicação de cancelamento (RN-20). Deve ter marca d'água "CANCELADA"?
7. **Permissão para exportar.** A exportação em ZIP e em JSON exige só `INVOICES_VIEW`. Quem só visualiza pode baixar todos os XMLs da empresa de uma vez. Isso atende ao sigilo profissional e à LGPD, ou a exportação em lote deve ter permissão própria e registro em log de auditoria?
8. **Caminho do arquivo no disco.** No storage local, o caminho do arquivo é montado com `Path.resolve(chave)`. A chave da NF-e é só dígitos, mas a chave sintética da NFS-e ABRASF vem do XML sem sanitização (ver a spec de NFS-e). Vale validar que o caminho final fica dentro de `storage-path`?
9. **Endpoints sem tela.** `POST /exportar` (JSON) e o painel `fiscal-xml-sync-control` citado no README não têm tela no front. Manter?
10. **Conferência de nota cancelada.** O filtro de conferência trata o `review_status` nulo como `PENDING`. O painel de pendências do mês conta só entradas não canceladas. Uma nota cancelada continua "A conferir" na lista. Ela deveria sair automaticamente da fila de conferência?
