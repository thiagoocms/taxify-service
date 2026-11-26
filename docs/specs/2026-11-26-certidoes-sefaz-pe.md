# Certidões estaduais SEFAZ-PE (as-built)

## Status
**Implementado** (roadmap M5). Spec retroativa: descreve o código como está em 2026-11-26, sem propor mudança.

- Commit de referência: `77b7680` (feat: certidão SEFAZ-PE (M5) e rascunho do pedido de revisão da Fronteira (M8)).
- Migration: `V56__CertidoesEstaduais` (tabela `certidoes_estaduais`). Usa também `capture_runs` (`V36__CaptureRuns`) e `pendencies` (`V35`).
- Backend: `rest/CertidaoResource`, `service/certidao/*`, `integration/efisco/EfiscoCertidaoClient`, `domain/certidao/CertidaoEstadual`, `enumerated/CertidaoTipo`, `enumerated/CertidaoSituacao`.
- Frontend: coluna "Certidão PE" na lista de empresas (`features/companies/company-list`) e seção "Certidão SEFAZ-PE" na ficha da empresa (`features/companies/company-certidao`, incluída em `company-form.component.html:134`).

## Problema e objetivo
O time Fiscal precisa manter a certidão estadual (CRF/CND) de cada empresa de PE em dia e saber quando ela está positiva. Hoje isso é feito entrando no e-Fisco empresa por empresa e controlando a validade em planilha.

O que o módulo faz hoje:
- guarda a certidão de cada empresa de PE (PDF e dados lidos: tipo, número, situação, emissão e validade);
- mostra na carteira um selo por empresa (sem certidão, válida, a vencer, vencida, positiva);
- gera pendências quando a certidão está positiva, vencida ou a 15 dias ou menos do vencimento;
- tenta emitir a certidão sozinho toda segunda-feira. **Na prática a emissão automática nunca conclui**: a página pública do e-Fisco exige captcha (Cloudflare Turnstile) e o Taxify não resolve captcha. A emissão real é feita por uma pessoa no e-Fisco e o PDF é enviado ao Taxify.

Como medir (não há métrica implementada): número de empresas de PE com selo "Vencida" ou "Sem certidão" na carteira; pendências `CERTIDAO_PE_*` abertas.

## Usuários e cenário
- **Analista Fiscal** (permissão `COMPANIES_VIEW`): olha a coluna da carteira e a seção da ficha; abre o PDF guardado.
- **Analista com `INVOICES_SYNC`**: clica "Emitir agora" (que hoje devolve o aviso de captcha), emite no e-Fisco pelo link "Abrir a emissão no e-Fisco" e envia o PDF na ficha.
- **Sistema**: job semanal (segunda, 6h) e geração de pendências de hora em hora.
- Frequência esperada: semanal (o job) e sob demanda quando aparece pendência "vencendo"/"vencida".

## Histórias de usuário
- Como analista Fiscal, quero ver na lista de empresas a situação da certidão de PE de cada uma, para saber quais estão vencidas, a vencer ou positivas sem abrir o e-Fisco.
- Como analista Fiscal, quero enviar o PDF da certidão que emiti no e-Fisco, para que o Taxify leia número, validade e situação e guarde o arquivo.
- Como analista Fiscal, quero que o Taxify recuse um PDF de outro CNPJ, para não guardar certidão na empresa errada.
- Como analista Fiscal, quero receber pendência quando a certidão estiver positiva, vencida ou perto de vencer, para agir antes de a empresa precisar dela.
- Como analista Fiscal, quero ver o histórico de certidões anteriores e a última tentativa de emissão, para auditar o que aconteceu.

## Regras de negócio

**RN-01 Aplicabilidade.** A certidão só se aplica a empresa com UF = `PE` (comparação sem caixa e sem espaços) e com CNPJ de 14 dígitos no cadastro. Empresa de outra UF ou com CPF/sem documento recebe `aplicavel=false` com o motivo, e upload/emissão respondem 400 com esse motivo.
Ref.: `CertidaoService.java:80-102`, `:337-343`.

**RN-02 Leitura do PDF.** O arquivo tem que começar com `%P` (cabeçalho de PDF) e ter texto extraível (OpenPDF `PdfTextExtractor`, todas as páginas). O texto é normalizado: minúsculas, sem acento, espaços simples. Se não contiver "certidao", é recusado ("o PDF não parece uma certidão da SEFAZ").
Ref.: `CertidaoPdfParser.java:60-79`, `:82-86`, `:158-161`.

**RN-03 Situação.** Ordem de busca no texto normalizado:
1. "positiva com efeito(s) de negativa" (com ou sem vírgula) → `POSITIVA_EFEITO_NEGATIVA`;
2. "certidao [de regularidade fiscal] positiva" ou "situacao: positiva" → `POSITIVA`;
3. "certidao [de regularidade fiscal] negativa" ou "situacao: negativa" → `NEGATIVA`;
4. "nao constam/existem/ha/consta ... debitos" (até 4 palavras no meio) → `NEGATIVA`;
5. "constam/existem/consta ... debitos" → `POSITIVA`.
Sem nenhum desses, o PDF é recusado.
Ref.: `CertidaoPdfParser.java:126-143`.

**RN-04 Tipo.** Texto com "regularidade fiscal" → `CRF`; senão, com "negativa de debito" → `CND`; qualquer outro caso → `CRF`.
Ref.: `CertidaoPdfParser.java:115-120`.

**RN-05 Datas.** Emissão: primeira data `dd/MM/aaaa` após "data de emissão", "emitida em" ou "emissão". Validade: primeira data após "válida até", "validade até", "validade", "válido até" ou "data de validade". Sem validade explícita e com emissão: validade = emissão + N dias, onde N vem de "validade de NN dias" / "válida por NN dias" ou, se não houver, **90 dias** (constante `VALIDITY_DAYS`, comentada no código como "a validade do e-Fisco para a certidão", sem norma citada). Sem validade e sem emissão, o PDF é recusado. Data malformada é ignorada e tenta-se a próxima ocorrência.
Ref.: `CertidaoPdfParser.java:27-38`, `:91-99`, `:145-155`.

**RN-06 Número.** Número após "certidão nº/número/nr", "controle nº" ou "número da certidão", com no mínimo 6 caracteres de dígitos, ponto, barra ou hífen. Opcional: sem número a certidão é aceita com `numero = null`.
Ref.: `CertidaoPdfParser.java:32-33`, `:100-101`.

**RN-07 CNPJ da certidão.** Primeiro CNPJ depois do rótulo "CNPJ" (até 30 caracteres não numéricos no meio); sem rótulo, o primeiro CNPJ formatado (`00.000.000/0000-00`) ou 14 dígitos isolados. Se o CNPJ lido for diferente do CNPJ da empresa, o envio é recusado (400 "A certidão é do CNPJ X, que não é o desta empresa"). **Se nenhum CNPJ for encontrado no PDF, a certidão é aceita sem essa checagem.**
Ref.: `CertidaoPdfParser.java:39-42`, `:105-112`; `CertidaoService.java:246-249`.

**RN-08 Gravação e duplicidade.** Cada certidão lida vira uma linha de `certidoes_estaduais` com `uf=PE`, `documento` = CNPJ da empresa, `origem` (`UPLOAD` ou `EFISCO`), `imported_at` e `imported_by` (usuário logado ou "sistema"). Se já existir certidão da empresa com o **mesmo número**, ela é substituída (dados e PDF; o PDF anterior é apagado do storage). Sem número, sempre cria linha nova. O PDF vai para o storage dos XMLs (`FiscalXmlFileStorage.storeCertidaoPdf`); se falhar ao gravar o PDF, a certidão é salva mesmo assim, sem PDF (a tela não oferece "Ver PDF").
Ref.: `CertidaoService.java:245-283`; `CertidaoEstadual.java:24-27`.

**RN-09 Certidão vigente.** A certidão que vale é a mais recente por `valida_ate` desc e, no empate, `imported_at` desc. As demais são histórico. Consequência: enviar uma certidão mais antiga não substitui a vigente.
Ref.: `CertidaoService.java:236-237`, `:317-326`; `CertidaoPendencies.java:39-41`.

**RN-10 Status de validade.** Com `diasRestantes = valida_ate − hoje` (dias corridos, `ChronoUnit.DAYS`):
- sem certidão ou sem validade → `SEM_CERTIDAO`;
- `diasRestantes < 0` → `VENCIDA`;
- `0 ≤ diasRestantes ≤ 15` → `A_VENCER` (constante `RENEW_DAYS = 15`);
- `diasRestantes > 15` → `VALIDA`.
"Precisa emitir" (`due`) = qualquer status diferente de `VALIDA`. A mesma regra alimenta o job, a pendência e o selo.
Ref.: `CertidaoRenewalRule.java:12-50`.

**RN-11 Selo na tela.** Prioridade do selo (front): `SEM_CERTIDAO` → "Sem certidão"; `VENCIDA` → "Vencida" (vermelho); situação `POSITIVA` → "Positiva" (vermelho); `A_VENCER` → "A vencer" (amarelo); `POSITIVA_EFEITO_NEGATIVA` → "Positiva c/ efeito de negativa" (azul); senão "Negativa" (verde).
Ref.: `taxify-front/src/app/core/models/certidao.model.ts:54-71`.

**RN-12 Pendências da certidão.** Só para empresa de PE, sobre a certidão vigente (RN-09). Empresa **sem nenhuma certidão não gera pendência** (decisão documentada no código: não inundar a tela ao ligar o módulo).
- situação `POSITIVA` → `CERTIDAO_PE_POSITIVA`, severidade ALTA;
- situação `POSITIVA_EFEITO_NEGATIVA` → `CERTIDAO_PE_POSITIVA`, severidade BAIXA;
- status `VENCIDA` → `CERTIDAO_PE_VENCIDA`, ALTA;
- status `A_VENCER` → `CERTIDAO_PE_VENCENDO`, MÉDIA (título com os dias restantes).
Positiva e vencida podem coexistir. Chave natural: `<TIPO>:<id da certidão>` — uma certidão nova gera pendência nova e a antiga fecha sozinha. Objeto apontado: a empresa.
Ref.: `CertidaoPendencies.java:45-82`; `PendencyType.java:20-23`.

**RN-13 Atualização de pendências após gravar.** Depois de upload ou emissão bem-sucedida, as pendências da empresa são recalculadas na hora (`PendencyService.refresh`). Falha nesse passo é só logada; não desfaz a certidão.
Ref.: `CertidaoService.java:173`, `:202`, `:285-291`.

**RN-14 Emissão no e-Fisco.** O cliente faz um GET na página pública `https://efisco.sefaz.pe.gov.br/sfi_fin_gpc/PREmitirCertidaoRegularidadeWeb` (timeout 60s, conexão 30s). Resultados:
- página com captcha (`cf-turnstile`, `g-recaptcha`, `hcaptcha`, "captcha"...) → erro `CAPTCHA` com a mensagem "O Taxify não resolve captcha: emita no e-Fisco e envie o PDF na ficha da empresa";
- HTTP ≥ 400 → `ERRO_HTTP`;
- sem o campo `NuDocumentoPessoa` → `PAGINA_INESPERADA`;
- formulário sem captcha → também `PAGINA_INESPERADA` ("fluxo ainda não mapeado").
Ou seja, **hoje a emissão automática sempre falha**. O captcha nunca é contornado.
Ref.: `EfiscoCertidaoClient.java:11-69`.

**RN-15 Registro de cada tentativa.** Toda tentativa (manual ou do job) grava um `capture_runs` com `service=CERTIDAO_PE`, status `OK`/`ERRO`, detalhe (até 500 caracteres) e `new_documents` 1/0. Falha ao gravar é só logada. Esses registros aparecem na "saúde das integrações" da tela de Pendências e como "Última tentativa de emissão" na ficha.
Ref.: `CertidaoService.java:294-309`, `:321-324`; `PendencyService.java:223-228`.

**RN-16 Job semanal.** `CertidaoScheduler.tick` roda no cron `efisco.certidao.cron` (padrão `0 0 6 * * MON`), em thread virtual, sem rodar duas vezes em paralelo. Seleciona empresas de **todas as firmas** com UF PE, ativas, com CNPJ e `due=true` (RN-10). Tenta uma por vez, com pausa de 3 s. Se uma tentativa falhar por `CAPTCHA`, `BLOQUEADO`, `PAGINA_INESPERADA` ou interrupção, as demais empresas não consultam o e-Fisco: só recebem um `capture_runs` de ERRO com o mesmo motivo.
Ref.: `CertidaoScheduler.java:13-84`; `CertidaoService.java:205-207`, `:222-243`.

**RN-17 Acesso.** Leitura (carteira, ficha, PDF) exige `COMPANIES_VIEW`; emitir e enviar PDF exigem `INVOICES_SYNC`. A ficha e o upload validam acesso à empresa (`CompanyValidation.checkExist` → `assertCompanyAccess`); o PDF valida acesso à empresa dona da certidão; a carteira lista só empresas do escritório do usuário que ele pode ver (`accessibleCompanyIds`). Usuário sem escritório na carteira → 400 `error.pendency.firm.required`.
Ref.: `CertidaoResource.java:20`, `:52`, `:59`; `CertidaoService.java:108-115`, `:145-147`, `:353-363`.

## Critérios de aceite (comportamento atual)

- **CA-01 (RN-01)** Dado uma empresa com UF `SP`, quando abro a ficha, então a seção Certidão não aparece (`aplicavel=false`) e um POST de upload retorna 400 "A certidão SEFAZ-PE é só para empresas de Pernambuco".
- **CA-02 (RN-01)** Dado uma empresa de PE cadastrada com CPF, quando envio um PDF, então recebo 400 "A empresa não tem CNPJ no cadastro".
- **CA-03 (RN-02)** Dado um arquivo .txt renomeado para .pdf, quando envio, então recebo 400 "Não foi possível ler a certidão: o arquivo não é um PDF."
- **CA-04 (RN-03)** Dado um PDF com "Certidão Positiva com Efeitos de Negativa", quando envio, então a situação gravada é `POSITIVA_EFEITO_NEGATIVA` (não `POSITIVA` nem `NEGATIVA`).
- **CA-05 (RN-05)** Dado um PDF com emissão 01/10/2026 e sem validade escrita, quando envio, então `valida_ate = 30/12/2026`.
- **CA-06 (RN-07)** Dado uma empresa com CNPJ 11.111.111/0001-11 e um PDF da certidão do CNPJ 22.222.222/0001-22, quando envio, então recebo 400 "A certidão é do CNPJ 22.222.222/0001-22, que não é o desta empresa." e nada é gravado.
- **CA-07 (RN-08)** Dado uma certidão nº 2026.000123 já guardada, quando envio de novo o mesmo PDF, então continua existindo uma linha só para esse número, com o PDF trocado.
- **CA-08 (RN-10, RN-12)** Dado hoje = 26/11/2026 e a vigente negativa válida até 11/12/2026, quando as pendências são geradas, então existe `CERTIDAO_PE_VENCENDO` MÉDIA "vence em 15 dia(s)" e o selo é "A vencer".
- **CA-09 (RN-10)** Dado hoje = 26/11/2026 e validade 12/12/2026, então o status é `VALIDA`, sem pendência de vencimento.
- **CA-10 (RN-12)** Dado uma empresa de PE sem certidão, quando as pendências são geradas, então nenhuma pendência `CERTIDAO_PE_*` é criada e o selo é "Sem certidão".
- **CA-11 (RN-12)** Dado a vigente `POSITIVA` e vencida, então existem duas pendências abertas: `CERTIDAO_PE_POSITIVA` ALTA e `CERTIDAO_PE_VENCIDA` ALTA.
- **CA-12 (RN-12, RN-13)** Dado uma pendência `CERTIDAO_PE_VENCIDA` aberta, quando envio uma certidão negativa nova válida por 90 dias, então a pendência antiga é resolvida automaticamente ("Resolvida automaticamente: a causa não existe mais.").
- **CA-13 (RN-14, RN-15)** Dado o e-Fisco com captcha, quando clico "Emitir agora", então recebo `status=ERRO` com a mensagem de captcha e o link da página pública, e um `capture_runs` ERRO é gravado.
- **CA-14 (RN-16)** Dado três empresas de PE vencidas e o e-Fisco com captcha, quando o job roda, então só a primeira consulta o e-Fisco e as outras duas recebem `capture_runs` ERRO com o mesmo motivo.
- **CA-15 (RN-17)** Dado um usuário sem `INVOICES_SYNC`, quando abre a ficha, então vê a certidão e o histórico, mas não vê "Emitir agora" nem o envio de PDF, e o POST retorna 403.
- **CA-16 (RN-17)** Dado um usuário de outro escritório, quando pede `GET /certidoes/{id}/pdf` de uma certidão que não é da carteira dele, então o acesso é negado.

## Casos de teste (exemplos fictícios; hoje = 26/11/2026)

| # | Entrada | Cálculo | Resultado esperado |
|---|---|---|---|
| 1 | Texto "Certidão de Regularidade Fiscal ... Negativa ... Nº 2026.000123 ... Emitida em 01/10/2026 ... Válida até 30/12/2026 ... CNPJ 11.111.111/0001-11" | 30/12 − 26/11 = 34 dias | CRF, NEGATIVA, VALIDA, sem pendência |
| 2 | Igual, válida até 11/12/2026 | 15 dias | A_VENCER, pendência VENCENDO MÉDIA |
| 3 | Válida até 26/11/2026 | 0 dias | A_VENCER (vence hoje) |
| 4 | Válida até 25/11/2026 | −1 dia | VENCIDA, pendência VENCIDA ALTA |
| 5 | Emitida em 01/10/2026, sem validade, sem "NN dias" | 01/10 + 90 | validade 30/12/2026 |
| 6 | Emitida em 01/10/2026, "validade de 60 dias" | 01/10 + 60 | validade 30/11/2026 |
| 7 | "Certidão Negativa de Débitos" sem "regularidade fiscal" | — | tipo CND |
| 8 | Sem palavra "positiva/negativa", mas "não constam débitos" | — | NEGATIVA |
| 9 | Texto sem situação reconhecível | — | 400 "a situação ... não foi encontrada" |
| 10 | Sem emissão e sem validade | — | 400 "a data de validade não foi encontrada" |
| 11 | PDF com CNPJ diferente do cadastro | — | 400, nada gravado |
| 12 | PDF sem nenhum CNPJ | — | aceito (lacuna, ver Perguntas) |
| 13 | Upload sem arquivo | — | 400 "Selecione o PDF da certidão emitida no e-Fisco." |
| 14 | Positiva com efeito de negativa, válida | — | pendência POSITIVA com severidade BAIXA |

Os testes unitários usam CNPJ e números fictícios; nada de PDF real de cliente em fixture.

## Endpoints da API
Base: `/api/v1/certidoes` (classe com `@RequiresPermission(COMPANIES_VIEW)`).

| Método | Caminho | Permissão | O que faz |
|---|---|---|---|
| GET | `/carteira` | COMPANIES_VIEW | Lista `Summary` (companyId, status, situação, validade, certidaoId, hasPdf) das empresas de PE com CNPJ da carteira |
| GET | `/empresa/{companyId}` | COMPANIES_VIEW | `CompanyCertidao`: aplicável/motivo, atual, histórico, última tentativa, página de emissão |
| GET | `/{id}/pdf` | COMPANIES_VIEW | PDF inline (`certidao-pe.pdf`); 404 se não houver PDF |
| POST | `/empresa/{companyId}/emitir` | INVOICES_SYNC | Tenta emitir; `IssueResult` (status OK/ERRO, detalhe, página, certidão) |
| POST | `/empresa/{companyId}/pdf` (multipart `file`) | INVOICES_SYNC | Lê, valida e guarda o PDF enviado |

## Telas
- **Lista de empresas** (`company-list`): coluna "Certidão PE" (190px) com o selo (RN-11), validade e link "PDF" quando há arquivo. Empresas fora de PE ou sem CNPJ não recebem dado.
- **Ficha da empresa** (`company-certidao`): texto de ajuda ("válida por 90 dias", "toda segunda-feira", "positiva vira pendência alta"); cartão da vigente (tipo, selo, nº, emissão, validade com dias restantes, origem "Emitida pelo Taxify" ou "PDF enviado por <usuário>", "Ver PDF"); última tentativa de emissão; com `INVOICES_SYNC`: "Emitir agora", link "Abrir a emissão no e-Fisco", seletor de arquivo e "Enviar PDF da certidão"; lista "Anteriores".

## Dados e integrações
- **`certidoes_estaduais`** (V56): `id`, `company_id` (FK companies), `uf`, `tipo` (CRF/CND), `numero`, `situacao`, `emitida_em`, `valida_ate`, `documento` (CNPJ da empresa), `pdf_path`, `file_name`, `origem` (UPLOAD/EFISCO), `imported_at`, `imported_by`, `deleted` + colunas de auditoria. Índice `(company_id, valida_ate)`.
- **`capture_runs`** (V36): uma linha por tentativa, `service=CERTIDAO_PE`. Apagadas após 90 dias pelo job de pendências.
- **`pendencies`** (V35): tipos `CERTIDAO_PE_POSITIVA`, `CERTIDAO_PE_VENCENDO`, `CERTIDAO_PE_VENCIDA`.
- **Storage de arquivos**: mesmo storage dos XMLs (`FiscalXmlFileStorage`).
- **e-Fisco**: página pública de emissão (sem login, sem e-CPF). Não usa a sessão do contador.

## Configuração
| Chave | Padrão | Efeito |
|---|---|---|
| `efisco.certidao.enabled` | `true` | Liga/desliga o job semanal |
| `efisco.certidao.cron` | `0 0 6 * * MON` | Horário do job |
| `spring.servlet.multipart.max-file-size` | `50MB` | Limite do upload (global) |

As duas chaves `efisco.certidao.*` não estão em `application.yaml` nem no compose: valem só os padrões do código.

## Testes existentes
- `CertidaoPdfParserTest`: CRF negativa; positiva com efeito de negativa; positiva; situação pela frase "não constam"; CND pelo título; validade = emissão + 90; CNPJ não é lido como número; recusa de não-certidão; leitura de um PDF gerado no teste.
- `CertidaoRenewalRuleTest`: sem certidão; 15 dias ou menos; vencida.
- `CertidaoPendenciesTest`: positiva ALTA; efeito de negativa BAIXA; negativa válida sem pendência; vencendo; vencida + positiva; só PE.
- `CertidaoSchedulerTest`: captcha interrompe as demais; job desligado.
- `CertidaoEfiscoClientTest`: detecção do Turnstile; formulário sem captcha.

**Lacunas:** não há teste de `CertidaoService` (upload, CNPJ divergente, substituição pelo mesmo número, falha ao gravar PDF, controle de acesso), nem do `CertidaoResource`, nem de componente no front.

## Fora do escopo / limitações conhecidas
- Emissão automática não funciona enquanto o e-Fisco exigir captcha (RN-14); o job hoje só registra a falha.
- Só a certidão estadual de PE. Não há certidão federal, municipal, FGTS nem trabalhista.
- PDF escaneado (imagem, sem texto) não é lido: não há OCR.
- A lista da carteira não filtra empresa inativa, mas o job ignora as inativas.
- Não há exclusão de certidão pela tela nem pela API.
- Não há alerta por e-mail; o aviso é só a pendência.
- A leitura depende do texto do PDF do e-Fisco: uma mudança de layout pode quebrar a leitura da situação ou das datas.

## Perguntas em aberto (para o Tony)
1. A validade padrão de **90 dias** para a certidão estadual de PE está certa para CRF e CND, inclusive a positiva com efeito de negativa? Qual é a norma?
2. A janela de **15 dias** para emitir a próxima certidão é a que o time quer?
3. Quando o PDF não traz CNPJ legível, a certidão deve ser aceita (como hoje) ou recusada?
4. A certidão "positiva com efeito de negativa" deve continuar como pendência BAIXA, ou o time quer outra severidade ou nenhuma pendência?
5. Empresa de PE sem nenhuma certidão não gera pendência. O time quer uma pendência "sem certidão" (talvez INFO) depois de um período de implantação?
6. O tipo padrão (CRF quando o texto não diz "negativa de débito") está correto para os PDFs que o e-Fisco emite hoje?
7. Filiais de PE com CNPJ próprio precisam de certidão própria ou basta a da matriz? Hoje cada CNPJ é tratado sozinho.
8. Deve existir um caminho autenticado (e-CPF do contador) para emitir sem captcha? Isso depende de mapear o e-Fisco e não está no código.
