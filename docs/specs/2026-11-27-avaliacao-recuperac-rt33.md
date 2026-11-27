# Avaliação do "RECUPERAC RT33 · Plataforma de Simulações Tributárias": o que trazer para o Taxify

## Status e decisões

**Avaliação de produto, revisão 2** (2026-11-27, po-taxify), **com as decisões do Tony de 2026-11-27** (abaixo). Não é
spec de implementação: as specs da Onda 1 foram escritas a partir dela e são a referência para dev, reviewer e QA.

| O quê | Situação |
|---|---|
| F5a Levantamento do crédito de estoque | **Spec escrita:** [2026-11-27-credito-estoque-cbs-levantamento.md](2026-11-27-credito-estoque-cbs-levantamento.md). P-01 e P-02 respondidas. Prazo: no ar até ~15/12/2026 |
| F1a-0 Alíquotas de IBS/CBS por ano com status | **Spec escrita:** [2026-11-27-aliquotas-ibs-cbs-por-ano.md](2026-11-27-aliquotas-ibs-cbs-por-ano.md). P-05 respondida. No ar antes de 01/01/2027 |
| F1a Tabela oficial cClassTrib e prontidão da carteira | **Spec escrita:** [2026-11-27-validador-ibs-cbs-tabela-oficial.md](2026-11-27-validador-ibs-cbs-tabela-oficial.md) (inclui a importação de tabelas oficiais pelo console, compartilhada com o F2). P-06 e P-07 respondidas. Depende do D-01 só para o primeiro arquivo real |
| F2 NCM inexistente ou fora de vigência | **Spec escrita:** [2026-11-27-ncm-inexistente-ou-fora-de-vigencia.md](2026-11-27-ncm-inexistente-ou-fora-de-vigencia.md). P-09 com a recomendação. Depende do D-03 só para o primeiro arquivo real |
| F4, F5b, F6, F1b, F7 | Próxima onda; dependem de decisões e bases listadas |
| F3, F8, H1 e o resto | Depois, ou não recomendado |

### Decisões do Tony (2026-11-27)

| P | Decisão | Efeito nas specs |
|---|---|---|
| **P-01** | **Confirmado.** O art. 381 da LC 214/2025 e o Decreto 12.955/2026 estão confirmados. Enquadramento: Lucro Presumido = hipótese I; Lucro Real = hipóteses II/III; Simples Nacional e MEI fora | CE-RN-03 do F5a vira regra (sem "a validar" no enquadramento) |
| **P-02** | **Confirmado.** Inventário em 31/12/2026; crédito apropriado até 30/06/2027. O levantamento fica no ar até 30/06/2027 | Data de encerramento do F5a (CE-RN-12) |
| **P-05** | Cada alíquota tem status (`LEI`, `PENDENTE`, `ESTIMATIVA`). **Só a que tem ato oficial (`LEI`) gera achado**; as outras aparecem como referência. O Fiscal cadastra 2027 quando sair o ato | AL-RN-01 a AL-RN-04 do F1a-0 |
| **P-06** | Achados de IBS/CBS em 2026 são **alerta até o fim do paralelo**: aparecem no painel de prontidão e na nota, **sem virar pendência ALTA nem entrar no resumo por e-mail** | VT-RN-08 do F1a: `WARNING`, sem regra de auditoria nesta fase |
| **P-07** | As tabelas oficiais são importadas pelo **admin da plataforma** no console, valem para todos os escritórios e geram aviso quando passam de **60 dias** sem atualização | TO-RN-01 a TO-RN-10 (no F1a, reaproveitadas pelo F2) |
| Demais (P-03, P-04, P-08 a P-21) | Seguem a recomendação do PO (seção 8) | P-03: nenhum serviço novo no `PlanServiceEnum`; auditoria segue `INVOICE_AUDIT`; escrita do F5a com `TAX_ASSESSMENT_CLOSE`. P-09: NCM inválido só avisa na regra por produto. P-21: a plataforma não vê contagens por escritório |

**O que continua "a validar"** (não foi confirmado pelo Tony): tudo do material que não está na tabela acima. Em
especial, para o F5b: a porcentagem de 9,25%, as 12 parcelas, o art. 383, as leis do inciso II, a vedação parcial, o
critério de custo, os artigos 605 a 611 do decreto um a um e a leitura do monofásico no Simples. Para F1a e F2: as
versões das tabelas (IT 2025.002 v1.71, Res. Gecex 926/2026) até o download oficial (D-01, D-03). Para F1a-0: as
alíquotas de 2027 até o ato oficial.

**Fonte analisada:** texto das 11 ferramentas e lógica extraída do HTML offline do RT33 (scripts de correção de
25/11/2026, bases e motor comum). É material de terceiros: serve de referência de **funcionalidade**. Nada de código,
base, texto ou layout dele entra no Taxify. As tabelas oficiais vêm direto das fontes oficiais (Portal Nacional da
NF-e, gov.br/nfse, Portal Único Siscomex, Planalto), versionadas e com vigência.

**Regra fiscal:** toda alíquota, redução, crédito, prazo, artigo ou cronograma que aparece aqui é **o que o material
cita**, com a referência que ele dá. Esta avaliação não conferiu nenhuma delas. Tudo fica "a validar pelo Fiscal e pela
fonte oficial" antes de virar regra de negócio, **exceto o que o Tony confirmou em 2026-11-27** (tabela "Decisões do
Tony" acima).

### O que mudou em relação à revisão 1
1. **Cobertura da captura corrigida.** A revisão 1 tratava NFC-e e CT-e como capturados. Não são: a rotina do SIEG pede
   só NF-e (`TipoXml 1`, `integration/sieg/SiegFiscalXmlProvider.java:21`); NFC-e e CT-e só entram por upload
   (spec de captura, linha 366; spec do painel, linha 795). Isso muda F4 (o B2C do varejo fica subcontado), F5b (saldo
   por XML é impossível no varejo) e F6. Entrou o habilitador **H1** (captura de NFC-e) com a conta de custo do SIEG.
2. **Expurgo dos arquivos XML considerado.** O compose do repositório expurga o arquivo depois de 3 meses
   (`FISCAL_XML_PURGE_RETENTION_MONTHS: 3`, `docker-compose.yml:74`); os metadados ficam. Toda funcionalidade que precisa
   de campo **ainda não gravado** (NBS, grupos extras do IBSCBS, NFS-e) só consegue reprocessar ~3 meses para trás.
3. **Afirmações erradas sobre o motor removidas.** O motor **não** trata devolução (motor, T-12 e P-04) nem transferência
   entre estabelecimentos da mesma raiz (não há tratamento no código). O motor roda sob demanda e não grava; quem grava
   pendência é a auditoria (`AUDIT_FINDING`).
4. **Regras por produto reposicionadas.** Elas valem para notas **recebidas** (`ProductRuleCheck` só roda em nota não
   própria, motor RN-07) e o Taxify não tem cadastro de produtos vendidos (`service/CompanyProductService.java:19-23`).
   O F3 da revisão 1 ("aceitar a sugestão de cClassTrib na regra do produto") misturava os dois lados e foi refeito.
5. **F5 corrigido conforme o próprio material:** monofásicos de PIS/COFINS ficam **dentro** do crédito (inciso II), a ST
   que importa é a de PIS/COFINS das leis listadas, e não a de ICMS; o Lucro Real não cumulativo fica fora, salvo inciso
   II e parcela com vedação (inciso III). O F5a agora usa o histórico de regime por mês, que já existe
   (`domain/company/CompanyTaxRegime.java`), e a classificação de CFOP de compra, que também já existe
   (`service/assessment/RevenueCfops.java`).
6. **Serviço do plano, teste grátis, bloqueio e custo SIEG** declarados por funcionalidade. Achado novo: o
   `PlanServiceEnum` é controlado pelo próprio escritório e `REPORTS` não é conferido em lugar nenhum, então não serve de
   alavanca comercial da CS Hub. A alavanca é o módulo do Plano Taxify (fase 3, P3-07), que ainda não existe.
7. **Urgência nova:** a checagem `IBSCBS_RATE` para de rodar em silêncio em 01/01/2027, porque só 2026 tem alíquota
   cadastrada (`V24__IbsCbsRates`). Virou o item F1a-0.
8. **Ganhos estimados** (com a conta e as hipóteses explícitas), roadmap por atividade (B, F, D, V, Q) e perguntas
   separadas entre as que bloqueiam e as que têm recomendação aceitável.

---

## 1. Resumo executivo

O RT33 é um kit de 11 ferramentas que roda no navegador, offline e sem salvar nada, ligado a um evento de capacitação
(RECUPERAC RT33, Recife, 3ª edição; duas ferramentas pedem a senha do evento). O foco é **consultoria sobre a Reforma
Tributária**: validar o grupo IBS/CBS dos XML, classificar NCM e NBS nos anexos da LC 214, simular a transição do
Simples, o preço e o split payment, calcular o crédito presumido de CBS sobre o estoque e gerar "oportunidades" e
apresentações comerciais. Toda análise começa com o usuário subindo XML, PGDAS em PDF, SPED, planilha ou cartão CNPJ
(com OCR), uma empresa por vez, e o resultado some quando a aba fecha. O próprio material registra, nos scripts de
correção, que as versões originais de várias ferramentas tinham erros graves (procurar o grupo IBSCBS no documento
inteiro, casar NCM por prefixo com "confiança alta", recomendar oportunidade sem base nas respostas). Ou seja: ele não
serve como fonte de verdade nem como gabarito para testar o Taxify.

**Conclusão:** o Taxify não deve virar um simulador. A vantagem dele é ter as NF-e de toda a carteira capturadas todo
dia, com histórico de regime por mês, regras por produto, apuração e trilha. Vale trazer, nesta ordem:

1. **F5a, levantamento do crédito de estoque (dezembro/2026).** Lista, na carteira inteira, quem pode ter direito ao
   crédito presumido de CBS sobre o estoque de 31/12/2026 e precisa fechar um inventário confiável. A janela é curta (o
   material fala em inventário em 31/12/2026 e apropriação até 30/06/2027, a validar), o esforço é P e o serviço é
   vendável.
2. **F1a-0 e F1a, validador IBS/CBS pelas tabelas oficiais (dezembro/2026 a janeiro/2027).** Alíquotas por ano com
   status, para a checagem não parar em 2027, e conferência de CST × cClassTrib pela tabela oficial versionada, com um
   painel de prontidão da carteira (emitidas e fornecedores).
3. **F2, NCM inexistente ou fora de vigência.** Esforço P, erro objetivo, base para F3 e para a Fronteira PE.
4. **Próxima onda:** F4 (perfil B2B × B2C, antes da janela de opção do Simples que o material cita para março/2027),
   F5b (cálculo do crédito com o inventário oficial), F6 (radar do Simples: segregação de monofásico e ST), F1b (regras
   da NT) e F7 (NFS-e nacional).

Simulação de preço, split payment, gerador de PPT, questionário comercial e OCR ficam de fora ou para depois.

O padrão que mais vale trazer não é uma ferramenta, e sim a disciplina: **cada alíquota carrega o status (lei,
pendente, estimativa, premissa) e o fundamento, e cada achado cita a regra oficial e a versão da tabela**. O Taxify já
tem o mecanismo para o paralelo com o Fiscal: a regra da auditoria nasce "a conferir" e só vira "indício" quando o time
fiscal marca como conferida (auditoria, RN-03).

---

## 2. O que o Taxify já tem (e os limites que importam)

| Ativo | Onde | Uso nesta avaliação |
|---|---|---|
| Item da NF-e com GTIN, NCM, CFOP, quantidades, valores, CST e valor de ICMS, IPI, PIS e COFINS | `domain/xml/FiscalXmlDocumentItem.java` | F5, F6, F2 |
| Detalhe tributário do item (CEST, origem, CSOSN, ST, alíquotas de PIS/COFINS, crédito do Simples) | `domain/xml/FiscalItemTaxDetail.java` (V22) | F5, F6 |
| Grupo IBSCBS do item (CST, cClassTrib, base, alíquotas, reduções, valores, indicador de diferimento ou devolução) e totais IBSCBSTot | `domain/xml/FiscalItemIbsCbs.java`, `FiscalIbsCbsTotals.java` (V22, V23, V25) | F1 |
| Cabeçalho com `ide_consumidor_final`, `dest_indicador_ie`, `dest_cpf`, `emit_regime_tributario` (CRT), finalidade, status e cancelamento | `domain/xml/FiscalXmlDocument.java` | F4, F5 |
| Checagens IBS/CBS: formato do cClassTrib e prefixo do CST, alíquota do ano, conta, vIBS, grupo ausente (CRT 3), totais | `service/fiscalcheck/IbsCbsItemCheck.java`, `IbsCbsDocumentCheck.java` | F1 |
| Alíquotas de referência por ano (só 2026 semeado, sem tela) | `domain/reform/IbsCbsRate.java`, `V24__IbsCbsRates.java` | F1a-0 |
| Auditoria da carteira (malha) sobre notas **emitidas**, com catálogo global de regras, vigência, liga/desliga e "a conferir × conferida" | `service/audit/AuditEngine.java:115`, `AuditRuleDefinition.java` (14 regras, inclusive `IBSCBS_GRUPO`), spec de auditoria RN-01 a RN-04 | F1, F2, F6 |
| Histórico de regime por mês (regime, atividade, anexo de serviço) | `domain/company/CompanyTaxRegime.java` (`company_tax_regimes`) | F5a, F4, F6 |
| Classificação de CFOP: venda de produção, revenda, serviço, devolução de venda e **compra para comercialização/industrialização** | `service/assessment/RevenueCfops.java` | F5a, F4, F6 |
| Apuração do Simples por anexo, **sem** fator R e **sem** segregação de ST/monofásico | `service/assessment/TaxAssessmentCalculator.java:20-25`; spec da apuração, limitações | F6, F8 |
| Bloco IBS/CBS informativo da apuração (débito, crédito, saldo pelas notas) | `dto/assessment/IbsCbsSummaryDTO.java` | F1, F8 |
| Relatórios: mensal, curva ABC de compras (por produto ou fornecedor) e simulação Simples × Presumido × Real em 12 meses | `rest/ReportResource.java`, spec de relatórios RN-07 a RN-14 | F4, F8 |
| Regras por produto (notas recebidas): CFOP de entrada, NCM, CSTs, cClassTrib, por fornecedor | spec de regras por produto, `ProductRuleCheck` | F5b (destinação do item), F3 |
| Pendências e tipos | `enumerated/PendencyType.java` | F5a, F6 |
| Acesso à carteira | `common/TenantAccessService.java:84` (`accessibleCompanyIds`) | todas |
| Reprocessamento do XML guardado quando o parser muda (`metadata_version`) | `config/FiscalXmlTaxDetailBackfill.java` | F1b, F7 |

**Limites que a revisão 1 não considerou:**
- **NFC-e e CT-e não são capturados automaticamente.** O SIEG baixa só NF-e (`SiegFiscalXmlProvider.java:21`). A
  NFeDistribuicaoDFe traz NF-e de entrada. NFC-e e CT-e só entram por upload. O parser de CT-e existe
  (`FiscalXmlMetadataParser.java:77`).
- **NFS-e:** o parser lê o padrão nacional e o ABRASF, mas não lê NBS nem o grupo IBSCBS (`integration/nfse/NfseXml.java`;
  `domain/xml/ServiceInvoice.java` não tem essas colunas).
- **Arquivo XML expurgado** depois de `FISCAL_XML_PURGE_RETENTION_MONTHS` (3 no compose do repositório; o valor da EC2
  precisa ser confirmado). Os metadados continuam no banco. O backfill marca como feito o que não tem mais arquivo.
- **Sem grupo de pagamento** (`detPag`/`tPag`) e sem dados de DI gravados por item.
- **Motor:** devolução sem tratamento (motor, P-04) e transferência entre estabelecimentos da mesma raiz sem tratamento.
- **Planos:** o `PlanServiceEnum` pertence ao "Plano de honorário", escrito pelo próprio ADMIN do escritório (spec SaaS,
  seção (a)); `REPORTS` e `MULTI_USER_ACCESS` não são conferidos em lugar nenhum (planos, RN-07 e P-01). O módulo
  vendável pela CS Hub (`TaxifyModule`, `@RequiresModule`) é da fase 3 e não existe (spec SaaS, F3-RN-10 e P3-07).
- **Lock entre instâncias:** os agendadores atuais usam `AtomicBoolean` (por instância). Rotina nova precisa ser
  idempotente por chave única ou usar reserva atômica no banco.

---

## 3. Ferramenta por ferramenta

| Ferramenta | O que faz (entradas → saídas) | O que o material cita (a validar) | Já existe no Taxify? | Faz sentido? | Por quê |
|---|---|---|---|---|---|
| **Validador IBS/CBS** | XML de NF-e, NFC-e, CT-e e NFS-e (upload/ZIP) → por item: grupo IBSCBS no próprio item, CST × cClassTrib pela tabela oficial (existência, compatibilidade, vigência, modelo), grupos exigidos/vedados pelos indicadores da tabela, alíquotas do ano, redução, diferimento, devolução, totais com as tolerâncias da NT; achado classificado (estrutural, tributária, matemática, alerta, não verificável) com regra, código de rejeição e situação (ativa/futura); CSV | NT 2025.002 v1.53; IT 2025.002 v1.71 de 30/10/2026 com 173 cClassTrib; "em 2026 várias regras UB estão flexibilizadas". A tela ainda diz "v1.40" | **Parcial.** `IbsCbsItemCheck` (formato de 6 dígitos e prefixo do CST, alíquota do ano, conta), `IbsCbsDocumentCheck` (grupo ausente no CRT 3, totais), regra `IBSCBS_GRUPO` na auditoria, agrupador IBS/CBS no front (`taxify-front/.../company-fiscal-conference/finding-groups.ts:10`). **Não** confere contra a tabela oficial, **não** tem regras da NT com código de rejeição, **não** grava os grupos extras do IBSCBS, **não** lê o IBSCBS da NFS-e, e a alíquota só tem 2026 | **Agora** (F1a-0, F1a) / **Próximo** (F1b, F7) | Roda sobre 100% das NF-e capturadas, emitidas e recebidas, sem upload. 2026 é ano-teste: o erro de emissão aparece antes de virar rejeição ou crédito glosado |
| **Classificador NCM** | Planilha → NCM válido/vigente, enquadramento nos anexos da LC 214 com status (confirmado, comprovação, candidato, sem benefício...), cClassTrib sugerido, confronto de carga "hoje × ano" com carga atual estimada; botão "Validar com IA" que monta prompt para o Claude | Res. Gecex 926/2026 (NCM com 15.157 linhas, vigente em 25/11/2026); anexos I a XV da LC 214 consolidados com a LC 227/2026; Anexo XIV revogado | **Não.** O NCM está em cada item e em cada regra de produto. A Fronteira usa NCM para a tabela de PE (`service/fronteira/FronteiraCalculator.java:252`). Não há tabela NCM | **Agora** (F2) / **Depois** (F3) | NCM inexistente é erro objetivo. O enquadramento nos anexos é parecer e depende do Fiscal |
| **Classificador NBS** | Busca de NBS por texto, planilha, auditoria de NFS-e (padrão nacional; ABRASF "não verificável"), reduções da LC 214 por NBS, correlação CNAE × NBS | NBS 2.0 (Anexo I da Portaria Conjunta RFB/SCS, alterações 6, 12 e 18), 1.210 códigos; lista nacional de serviços (580 linhas); indicador de operação (26 linhas); leiaute IBSCBS v1.01 | **Não.** O parser lê `cTribNac`, não lê NBS nem IBSCBS | **Próximo** (F7, auditoria das NFS-e) / **Não** (busca) | A busca já existe de graça no portal oficial. O valor está em conferir as NFS-e que o Taxify já captura |
| **Estoque e crédito de CBS** | XML de entradas e saídas do ano + inventário CSV opcional → saldo por item em 31/12 (GTIN ou descrição), lotes por PEPS (padrão) ou composição ponderada, hipóteses I, II e III, exclusões com motivo, importados pelo PIS/COFINS-Importação, produtos acabados pelo custo de MP e embalagem, estornos por devolução em 2027, 12 parcelas e memória | LC 214, art. 381, caput, I, II, III, §§ 1º, 3º, 4º e 5º; art. 383 (5 anos para usar); "Decreto 12.955/2026, arts. 605 a 611"; 9,25% sobre nacionais; Lei 10.865, art. 8º, §§ 21 e 21-A; leis do inciso II (10.147, 10.485, MP 2.158-35, 9.532, 12.402); vedação parcial (Leis 10.637 e 10.833, art. 3º, §§ 7º a 9º); "ST de ICMS não interfere"; Simples fora, com leitura literal para monofásico "a validar"; apropriação até 30/06/2027 | **Não.** Mas os dados de entrada existem: item com GTIN, NCM, CFOP, quantidade, valor, CST e valor de PIS/COFINS, emitente, CPF/CNPJ, finalidade e cancelamento; regime por mês; CFOP de compra classificado; CFOP de entrada por produto nas regras | **Agora** (F5a) / **Próximo** (F5b) | Dinheiro recuperável com prazo. O histórico de entradas já está no Taxify, o que nenhuma ferramenta offline tem |
| **Simulador da Transição** | PGDAS-D (PDF), XML → DAS ano a ano 2026–2033 pela partilha, por dentro × por fora, opção pelo regime regular por semestre, crédito por tipo de fornecedor | Anexos XVIII a XXII da LC 214 com a LC 227/2026; CBS 2027 de 11,3468% "calculada pelo TCU em 28/10/2026, pendente de resolução do Senado até 15/12/2026"; redução de 0,1 p.p. em 2027–2028 (art. 347); IBS 18,7% "estimativa do CGIBS (Res. 14/2026)"; teste 2026 sem efeito para o Simples (art. 348, III, "c"); janela de opção em setembro (1º semestre) e março (2º semestre) (LC 123, art. 13, § 10); efeitos a partir de 1º/1/2027 (art. 544, III) | **Parcial.** Apuração do DAS por anexo (LC 155) e simulação de 12 meses (Relatórios). Não há projeção 2027+ nem opção pelo regime regular | **Depois** (F8) | Depende de alíquotas que o próprio material marca como pendentes ou estimadas e do perfil de F4 |
| **Formação de Preço** | XML da carteira, planilha → preço e margem por produto em cada ano 2026–2033; módulo de serviços | Base do IVA sem ICMS/ISS (art. 12, § 2º); fim do monofásico de PIS/COFINS em 2027 salvo combustíveis; crédito presumido de produtor rural e ZFM | **Não** | **Não** (agora) / **Depois** só se houver demanda | É consultoria de precificação. Exige comissão, despesas e margem, que o Taxify não tem |
| **Split Payment** | Faturamento, setor, prazos e mix de meios de pagamento digitados → capital de giro perdido e custo financeiro | LC 214, arts. 31 a 36; "Decreto 12.955/2026, art. 31, III" (antecipação de recebíveis); "operação efetiva: 2027"; alíquota padrão de 30% | **Não.** O grupo de pagamento das notas não é gravado | **Não** (agora) | Tudo é digitado: o Taxify não acrescenta dado próprio |
| **Análise B2B × B2C** | XML + cartão CNPJ → mix de vendas para quem credita × quem não credita, fornecedores por CRT, crédito por categoria, estratégias, PDF | B2B = quem toma crédito (art. 41, § 3º; art. 47, § 9º, I); "a nota não informa o regime do cliente: CNPJ entra como B2B a confirmar"; arts. 47 a 57, 108–109, 127–128, 251 | **Não**, mas os campos existem (`ide_consumidor_final`, `dest_indicador_ie`, `dest_cpf`, `emit_regime_tributario`) | **Próximo** (F4) | O mix e o perfil dos fornecedores são **fatos** das notas, sem regra fiscal nova |
| **Mapeamento 360°** | Cartão CNPJ (OCR), PGDAS, SPED Contribuições, XML, CSV → cadastro, comparativo de regimes, "Simples Híbrido", CBS na migração, projeção 2026–2033, radar com honorário estimado, relatório | "Motor reproduzido da planilha oficial"; CBS 11,3% e IBS 18,7% (mesmas ressalvas) | **Parcial**: cadastro, apuração e simulação de regimes | **Não** (como ferramenta) | É a junção das outras com entrada manual. Cada pedaço útil vira funcionalidade sobre dado capturado (F4, F6, F8) |
| **Mapa da Mina** | Questionário de 11 perguntas → score, oportunidades, portfólio com ticket e cobrança | O script de correção registra que o original "recomendava sem base nas respostas" | **Não** | **Não** | Recomenda a partir de declaração. O F6 faz o mesmo com evidência nas notas |
| **Gerador de Oportunidades** | Segmento → quadro com base legal, PPT com a marca do escritório, roteiro de WhatsApp | "Base até 24/11/2026" | **Não** | **Não** (conteúdo) / **Depois** (PDF do F6) | Conteúdo editorial de terceiros, com curadoria jurídica contínua; direito autoral |

---

## 4. Funcionalidades recomendadas

**Como ler as estimativas.** Os ganhos em horas são **hipóteses a medir no paralelo**, não compromisso. A conta usa a
carteira da CS Hub de **306 empresas** (número usado na spec SaaS, decisão 20) e proporções de regime que a CS Hub
precisa confirmar (P-20). Hipótese de trabalho: 50% Simples, 35% Presumido, 10% Real e 5% outros, ou seja, ~153,
~107, ~31 e ~15 empresas. Os valores em reais são **exemplos fictícios** para mostrar a ordem de grandeza.

**Serviço do plano (vale para todas).** O `PlanServiceEnum` é o Plano de honorário que o próprio escritório escreve.
Ele decide quais empresas a rotina processa, mas não protege receita da CS Hub. A recomendação é: as rotinas novas
seguem o serviço que já governa a rotina vizinha (`INVOICE_AUDIT` para a auditoria) e nenhum serviço novo entra no
`PlanServiceEnum` agora. O controle comercial fica com o módulo **"Reforma Tributária"** na lista de módulos do Plano
Taxify (P3-07), quando a fase 3 existir. Decisão do Tony: P-03.

---

### F5a. Levantamento do crédito presumido de CBS sobre o estoque (agora)

**Problema.** O material cita um crédito presumido de CBS sobre o estoque existente na virada para 2027 (LC 214,
art. 381), com inventário fixo em 31/12/2026 (o material cita "Dec. 12.955/2026, art. 608, § 1º") e apropriação até
30/06/2027 (art. 381, § 4º, I). Tudo a validar. Se a regra se confirmar, quem não fechar um inventário confiável em
31/12 perde ou enfraquece o crédito. Hoje o Fiscal teria de abrir empresa por empresa para saber quem avisar.

**O que entrega.** Uma lista, na carteira inteira, de quem **pode** ter direito (pelo regime e pelo volume de compras
de 2026), com a situação do contato ("avisado", "inventário recebido", "descartado"). **Não calcula crédito** (o
cálculo é o F5b). É uma lista de trabalho para a campanha de dezembro.

**Dados que já existem.**
- Regime em dez/2026 e regime cadastrado para jan/2027: `company_tax_regimes` (`valid_from` mensal).
- Compras de 2026: itens de notas de entrada (`direction = INBOUND`), não canceladas e não excluídas, com CFOP
  classificado como `PURCHASE` por `RevenueCfops.classify` (o mesmo critério da apuração).
- Quebra das compras por CST de PIS (`cst_pis` do item): é **fato**, sem classificar o que entra ou sai.
- Cobertura: meses de 2026 com pelo menos uma nota de entrada. A empresa que entrou na carteira no meio do ano aparece
  com cobertura baixa.

**Ganho.**
- *Escritório cliente:* avisa a tempo cada cliente que precisa fechar inventário. Exemplo fictício: uma empresa do
  Presumido com R$ 400.000,00 de estoque nacional elegível teria, pela porcentagem que o material cita (9,25%, art.
  381, § 3º, I, a validar), R$ 37.000,00 de crédito, em 12 parcelas de R$ 3.083,33.
- *CS Hub:* lista pronta para uma campanha de serviço com prazo (levantamento e cálculo do crédito, F5b), cobrável à
  parte (P-04). Na venda do SaaS: "no primeiro dia, o Taxify mostra quais clientes da sua carteira precisam de
  inventário em 31/12".
- *Time Fiscal:* hipótese de 10 min por empresa para conferir regime e compras à mão: 306 × 10 min ≈ **51 h uma vez**,
  contra ~3 h para revisar a lista pronta. Risco evitado: cliente com direito que não foi avisado.

**Esforço.** P (uma consulta agregada, um snapshot diário, uma tela, CSV e situação por empresa).

**Serviço do plano.** Nenhum serviço novo (P-03). O snapshot roda para toda empresa ativa de escritório que permite
rotinas automáticas. Módulo do Plano Taxify (futuro): "Reforma Tributária".

**Teste grátis.** Funciona com as até 5 empresas do teste, mas a cobertura de 2026 é baixa: a captura nova traz o mês
corrente pelo SIEG e cerca de 90 dias pela SEFAZ. A tela mostra a cobertura. Não há busca retroativa no teste.

**Escritório bloqueado.** Lista e CSV continuam liberados (GET), mostrando o último snapshot com a data. Mudar a
situação responde 403 `error.firm.access.blocked` (F1-RN-08). O snapshot não roda (`allowsAutomaticRoutines`).

**Custo SIEG.** Zero: usa só o que está no banco. Completar 2026 de quem tem cobertura baixa é decisão do F5b.

**Riscos.**
- Regra recente e ainda não confirmada (art. 381 e decreto citados). Mitigação: a tela diz "levantamento de quem pode
  ter direito, pela leitura a validar" e mostra a fonte da classificação.
- O CFOP da nota de compra é o do fornecedor (5102 serve para revenda e para uso e consumo do comprador). O volume é
  ordem de grandeza, não base de crédito.
- LGPD: dado do próprio escritório; nada vai para fora; o log só leva ids e contagens.

#### Rascunho para a spec (F5a)

**Regras de negócio**

| RN | Regra |
|---|---|
| CE-RN-01 Escopo | Empresas não excluídas e ativas acessíveis ao usuário (`TenantAccessService`): o ADMIN vê o escritório; o colaborador vê a carteira (`users_companies`). Ano de referência = 2026 (`ano` aceita só 2026 nesta fase; outro valor: 400 `error.reform.stock.year.invalid`) |
| CE-RN-02 Regime de referência | O período de `company_tax_regimes` vigente em 01/12/2026 (maior `valid_from` ≤ 2026-12-01, ou `valid_from` nulo). Sem período: o campo `taxRegimeType` da empresa. "Regime do ano seguinte" = o período vigente em 01/01/2027, se já cadastrado; senão, nulo |
| CE-RN-03 Classificação indicativa | Tabela abaixo. **Mapeamento de regra fiscal: precisa do aceite do Tony (P-01)** |
| CE-RN-04 Compras do ano | Soma de `valor_total` dos itens de notas com `direction = INBOUND`, `status` não cancelada, `deleted = false`, `issue_date` entre 2026-01-01 00:00 e 2026-12-31 23:59:59.999 (data local do `dhEmi`, como gravada) e `RevenueCfops.classify(INBOUND, cfop) = PURCHASE`. Arredondamento: soma exata, exibida com 2 casas HALF_UP |
| CE-RN-05 Quebra por CST de PIS | Mesma base da CE-RN-04, agrupada por `cst_pis` (nulo vira "sem CST"). Mostra valor e número de itens. Não classifica |
| CE-RN-06 Cobertura | `mesesComEntrada` = número de meses de 2026 com ≥ 1 nota de entrada não cancelada; `primeiraEntrada` e `ultimaEntrada` = menor e maior `issue_date` |
| CE-RN-07 Snapshot | Rotina diária `TAXIFY_STOCK_SURVEY_CRON` (padrão `0 15 6 * * *`, America/Recife), desligada por padrão (`TAXIFY_STOCK_SURVEY_ENABLED=false`). Grava uma linha por (empresa, ano) com upsert pela chave única. Duas instâncias produzem o mesmo resultado (idempotente pela chave; a última escrita vence com os mesmos números). Pula escritório sem `allowsAutomaticRoutines`. Para quando `ano` passar de 2027-06-30 (o levantamento perde sentido; confirmar em P-02) |
| CE-RN-08 Botão "Atualizar agora" | Por empresa, recalcula na hora (POST anotado como escrita: bloqueado responde 403). Limite: 1 por empresa a cada 10 min (409 `error.reform.stock.refresh.cooldown`, com `retryAt` no topo do corpo) |
| CE-RN-09 Situação | `NAO_AVALIADA` (padrão), `AVISADA`, `INVENTARIO_RECEBIDO`, `DESCARTADA`. Guarda quem, quando e observação (até 500 caracteres, sem validação de conteúdo). Concorrência otimista por `version` (409 `error.reform.stock.status.conflict`). Toda mudança vai para a trilha de auditoria existente (`AbstractAuditingEntity`) |
| CE-RN-10 Ordem | Padrão: classificação (I, II/III, sem regime, fora), depois `comprasAno` decrescente, depois razão social |
| CE-RN-11 Texto fixo | Toda tela e CSV: "Levantamento de quem pode ter direito ao crédito presumido de CBS sobre o estoque, pela leitura de LC 214, art. 381, a validar. Não é cálculo de crédito." |

| Regime de referência (dez/2026) | `classificacao` | Leitura que o material faz (a validar) |
|---|---|---|
| `LUCRO_PRESUMIDO` | `HIPOTESE_I` | regime cumulativo de PIS/COFINS em 31/12/2026 (art. 381, I) |
| `LUCRO_REAL` | `HIPOTESE_II_III` | só bens com monofásico ou ST de PIS/COFINS das leis do inciso II, ou a parcela com vedação parcial (inciso III) |
| `SIMPLES_NACIONAL`, `MEI` | `FORA` | fora dos incisos I e III; inciso II para monofásico "leitura literal a validar" |
| nulo ou `OTHER` | `SEM_REGIME` | — (a pendência `REGIME_MISSING` já existe) |
| Regime de jan/2027 cadastrado como `SIMPLES_NACIONAL` ou `MEI` | `FORA` com motivo "não estará no regime regular da CBS em 2027" | art. 381, caput (o material cita também art. 41) |

**Contrato da API** (rotas em `/api/v1/reforma/credito-estoque`, permissão `TAX_ASSESSMENT_VIEW`, como os relatórios;
escrita com `TAX_ASSESSMENT_CLOSE`, a confirmar em P-03).

| Método e rota | Quem | Parâmetros | Resposta |
|---|---|---|---|
| `GET /` | ADMIN (escritório) e colaborador (carteira) | `ano` (obrigatório, 2026), `classificacao` (lista), `situacao` (lista), `busca` (razão social ou CNPJ), `page` (0), `size` (50, máx. 200), `sort` (`comprasAno`, `razaoSocial`, `classificacao`; padrão CE-RN-10) | Página no formato padrão do projeto com `content[]` e `resumo` |
| `GET /{companyId}` | idem; empresa fora da carteira ou de outro escritório: **404** `error.Company.not.found` | `ano` | Detalhe |
| `POST /{companyId}/atualizar` | idem | `ano` | 200 com o detalhe recalculado; 409 cooldown; 403 bloqueado |
| `PUT /{companyId}/situacao` | idem | corpo `{ano, situacao, observacao, version}` | 200 com a linha; 400 `error.reform.stock.status.invalid`; 409 conflito; 403 bloqueado; 404 |
| `GET /export.csv` | idem | mesmos filtros do `GET /` | CSV `;`, UTF-8 com BOM, fórmulas neutralizadas (prefixo `'` em célula que começa com `=`, `+`, `-`, `@`) |

Linha de `content[]`: `companyId`, `razaoSocial`, `cnpj`, `regimeReferencia`, `regimeAnoSeguinte`, `classificacao`,
`motivoClassificacao`, `comprasAno`, `itensCompra`, `notasEntrada`, `mesesComEntrada`, `primeiraEntrada`,
`ultimaEntrada`, `situacao`, `situacaoAlteradaPor`, `situacaoAlteradaEm`, `version`, `calculadoEm`.
`resumo`: `empresas`, `porClassificacao{HIPOTESE_I, HIPOTESE_II_III, FORA, SEM_REGIME}`, `porSituacao{...}`,
`comprasAnoTotal`, `calculadoEm` (o mais antigo dos snapshots da página).
Detalhe: os campos da linha mais `comprasPorMes[12]{mes, valor, notas}`, `comprasPorCstPis[]{cst, valor, itens}`,
`topNcm[10]{ncm, valor, itens}` (fato, por valor de compra) e `memoria` (texto com o critério da CE-RN-04 e a lista de
CFOPs de compra do `RevenueCfops`).

**Modelo de dados.** Tabela `stock_credit_survey` (migration nova): `id`, `company_id`, `ano`, `regime_referencia`,
`regime_ano_seguinte`, `classificacao`, `compras_ano` decimal(19,2), `itens_compra`, `notas_entrada`,
`meses_com_entrada`, `primeira_entrada`, `ultima_entrada`, `compras_por_cst_json` (TEXT), `calculado_em`, `situacao`,
`situacao_alterada_por`, `situacao_alterada_em`, `observacao`, `version` + colunas de auditoria. Único
(`company_id`, `ano`); índice (`ano`, `classificacao`). A consulta agregada usa `idx_fxd_company_direction_issue`
(já existe) e é feita **por lote de 50 empresas** (`company_id IN (...)`), nunca uma por empresa. Hipótese de volume
(5.000 empresas × ~2.000 itens de entrada/ano = 10 milhões de linhas): cabe numa rodada de madrugada; medir em QA com
massa sintética.

**Logs.** Início e fim do snapshot com `firmCount`, `companyCount`, `durationMs`; erro por empresa com `companyId` e a
classe da exceção. Nunca CNPJ, razão social nem valor.

**Critérios de aceite (exemplos).**
- CA-CE-01 (CE-RN-02, 03) Dado a empresa A com Presumido desde 2025-01 e sem período de 2027, quando o snapshot roda,
  então `classificacao = HIPOTESE_I` e `regimeAnoSeguinte = null`.
- CA-CE-02 (CE-RN-03) Dado a empresa B com Presumido até 2026-12 e Simples a partir de 2027-01, então
  `classificacao = FORA` com o motivo de 2027.
- CA-CE-03 (CE-RN-04) Dado compras canceladas e itens com CFOP de remessa, então ficam fora da soma.
- CA-CE-04 (CE-RN-01) Dado um colaborador sem a empresa C na carteira, quando pede `GET /{C}`, então 404; e C não
  aparece na lista nem no CSV. Empresa de outro escritório: 404.
- CA-CE-05 (CE-RN-07) Dado um escritório bloqueado, quando o snapshot roda, então as empresas dele não são
  recalculadas, a lista mostra o `calculadoEm` anterior e o `PUT /situacao` responde 403.
- CA-CE-06 (CE-RN-09) Dado dois usuários editando a situação com o mesmo `version`, então o segundo recebe 409.

**Casos de teste (números fictícios).**

| CT | Dado | Esperado |
|---|---|---|
| CT-CE-01 | Empresa 11.222.333/0001-81, Presumido. Entradas 2026: item CFOP 5102 de R$ 120.000,00 (CST PIS 01), item CFOP 5405 de R$ 30.000,00 (CST 04), item CFOP 6102 de R$ 10.000,00 (CST 06), nota cancelada de R$ 5.000,00, item CFOP 5949 de R$ 2.000,00 | `comprasAno = 160000.00`; quebra 01: 120.000,00, 04: 30.000,00, 06: 10.000,00; a cancelada e a remessa fora |
| CT-CE-02 | Nota emitida em 2026-12-31T23:30:00-04:00 (gravada como 2026-12-31 23:30) | Entra em 2026 |
| CT-CE-03 | Nota em 2027-01-01T00:10:00-03:00 | Fica fora |
| CT-CE-04 | Empresa 12.345.678/0001-95 sem regime | `SEM_REGIME`, compras calculadas mesmo assim |
| CT-CE-05 | Empresa com entradas só de out, nov e dez | `mesesComEntrada = 3` |
| CT-CE-06 | Duas instâncias rodam o snapshot no mesmo minuto | Uma linha por (empresa, 2026), mesmos números |
| CT-CE-07 | `POST /atualizar` duas vezes em 5 min | Segunda: 409 com `retryAt` |
| CT-CE-08 | CSV com razão social "=SOMA(A1)" (fictícia) | Célula exportada como `'=SOMA(A1)` |
| CT-CE-09 | `ano=2025` | 400 `error.reform.stock.year.invalid` |

**Variáveis de ambiente.** `TAXIFY_STOCK_SURVEY_ENABLED=false`, `TAXIFY_STOCK_SURVEY_CRON=0 15 6 * * *`,
`TAXIFY_STOCK_SURVEY_REFRESH_COOLDOWN_MINUTES=10`. Ligar na EC2: Tony.

**Textos de erro (pt).** `error.reform.stock.year.invalid` = "Ano de referência inválido. Use 2026.";
`error.reform.stock.status.invalid` = "Situação inválida."; `error.reform.stock.status.conflict` = "Outra pessoa
alterou esta empresa. Recarregue e tente de novo."; `error.reform.stock.refresh.cooldown` = "Aguarde alguns minutos para
atualizar de novo."

---

### F5b. Cálculo do crédito presumido sobre o estoque (próximo)

**O que entrega.** Por empresa, o cálculo do crédito com memória por item, a partir do **inventário oficial importado**
(Bloco H do SPED Fiscal ou planilha no layout do Taxify). O Taxify casa cada item do inventário com as aquisições
capturadas (GTIN, código do fornecedor, NCM, descrição), mostra a origem e o custo documentado, aplica as hipóteses e
exclusões validadas pelo Tony, calcula as parcelas e lista o que ficou "sem documento de aquisição".

**Por que o inventário é obrigatório.** O material reconhece que o caminho mais seguro é o inventário do sistema. No
Taxify isso vale ainda mais: sem NFC-e capturada, o saldo por XML de um varejista está sempre errado (as vendas no
balcão não aparecem).

**Dados que já existem.** Item da nota de entrada (GTIN, NCM, quantidade, valor, CST e valor de PIS/COFINS, emitente
CNPJ ou CPF, finalidade, cancelamento); o CFOP **de entrada por produto** das regras por produto, que diz a destinação
(revenda, uso e consumo, ativo) melhor que o CFOP do fornecedor; o regime por mês.

**Ganho.**
- *Cliente:* crédito recuperável com memória defensável. Exemplo fictício do F5a: R$ 37.000,00 em 12 parcelas.
- *CS Hub:* serviço com prazo, que pode ter honorário de êxito (P-04). Diferencial forte: a ferramenta offline pede
  todos os XML do ano; o Taxify já tem.
- *Fiscal:* o casamento inventário × aquisição é o trabalho mais braçal. Hipótese: 2 dias por empresa viram 3 h. Com
  ~40 empresas elegíveis (hipótese), ~520 h viram ~120 h.

**Esforço.** G (importador de Bloco H e planilha, casamento, cálculo, cronograma, telas, exportação).

**Serviço do plano.** Nenhum novo (P-03); módulo "Reforma Tributária". **Teste grátis:** disponível, mas sem histórico
de compras do ano o resultado sai quase todo "sem documento". **Bloqueado:** leitura e exportação liberadas; importar
inventário e recalcular, 403.

**Custo SIEG.** Para completar 2026 de quem tem cobertura baixa, seria preciso buscar o passado no SIEG. Conta: empresa
com 400 notas de entrada por mês × 11 meses = 4.400 notas ÷ 50 por página = **88 requisições ≈ 44 min de SIEG
exclusivo**. 30 empresas assim ≈ 22 h, competindo com a captura diária (cada empresa gasta 2 a 8 chamadas por rodada).
Recomendação: busca retroativa só sob pedido, uma empresa por vez, pela mesma fila de prioridade do painel de captura
(1 vaga por rodada, spec do painel CP-RN-15), com teto por escritório. Alternativa sem custo: o certificado A1 na
SEFAZ não serve, porque a DistDF-e só guarda cerca de 90 dias.

**Riscos.** Crédito apropriado a maior gera autuação: o resultado é "levantamento para conferência" e a apropriação é
decisão do responsável técnico. Critério de custo (o material usa PEPS por padrão no script e "custo médio" no texto da
tela; cita "Dec. 609": custo pelos critérios do IRPJ) a validar. Bem importado depende do PIS/COFINS-Importação pago
(DI), que o Taxify não grava por item.

**Perguntas.** P-01, P-02, P-04, P-11, P-12, P-13.

---

### F1a-0. Alíquotas de referência de IBS/CBS por ano, com status (agora, antes de 01/01/2027)

**Problema.** `IbsCbsItemCheck` só compara a alíquota quando o ano tem linha em `ibs_cbs_rates`; sem linha, não confere
(`IbsCbsItemCheck.java:47`). Só 2026 existe (V24) e não há tela (motor, P-07). Em 01/01/2027 a checagem `IBSCBS_RATE`
para de rodar sem ninguém perceber.

**O que entrega.** Colunas novas em `ibs_cbs_rates`: `status` (`LEI`, `PENDENTE`, `ESTIMATIVA`), `fundamento` (texto,
até 500), `fonte_url`, `cadastrado_por`, `cadastrado_em`. Tela no console da plataforma para cadastrar e editar
(`@PlatformAdminOnly`), com histórico pela trilha de auditoria. Regra: **só alíquota com status `LEI` gera achado
`IBSCBS_RATE`**; `PENDENTE` e `ESTIMATIVA` aparecem como informação e não geram achado. Aviso no console quando o ano
corrente ou o seguinte não tem linha com status `LEI`.

**Exemplo do que o Tony vai decidir cadastrar:** o material cita, para 2027, CBS de referência de 11,3468% "calculada
pelo TCU em 28/10/2026, pendente de resolução do Senado até 15/12/2026", com redução de 0,1 p.p. em 2027–2028 (art.
347), e IBS de 0,1%. Nada disso entra sem a fonte oficial (P-05).

**Ganho.** Evita perda silenciosa de conferência; é pré-requisito de F8. Esforço **P**. Serviço do plano: nenhum (é
dado de referência global). Teste e bloqueio: não se aplicam (console da plataforma). Custo SIEG: zero.

**Rascunho para a spec.**
- Rotas: `GET /api/v1/platform/ibs-cbs-rates`, `PUT /api/v1/platform/ibs-cbs-rates/{ano}` (corpo `{aliquotaCbs,
  aliquotaIbsUf, aliquotaIbsMun, status, fundamento, fonteUrl}`), só admin da plataforma; 400
  `error.reform.rate.invalid` (alíquota negativa, acima de 100 ou com mais de 4 casas; `fundamento` vazio com status
  `LEI`), 400 `error.reform.rate.year.invalid` (ano < 2026 ou > 2040).
- Migration: colunas novas; a linha de 2026 recebe `status = LEI`, `fundamento` a definir pelo Tony (P-05).
- CT: ano 2027 com status `ESTIMATIVA` e nota de 2027 com pCBS diferente → nenhum `IBSCBS_RATE`; o mesmo com status `LEI`
  → `ERROR` com a mensagem atual; nota de 2027 sem linha → nenhum achado e o aviso no console.
- Logs: ano alterado e `adminLogin`; nunca o fundamento inteiro.

---

### F1a. CST × cClassTrib pela tabela oficial e prontidão da carteira (agora)

**O que entrega.**
1. **Tabela oficial versionada** de CST e cClassTrib do IBS/CBS, importada pelo admin da plataforma a partir do
   arquivo oficial do Portal Nacional da NF-e: versão (ex.: "IT 2025.002 v1.71", o material cita essa versão de
   30/10/2026; confirmar a vigente), data de publicação, hash SHA-256 do arquivo, quem importou e quando. Linhas: CST,
   cClassTrib, descrição, início e fim de vigência, documentos permitidos (NF-e, NFC-e, CT-e, NFS-e...) e os indicadores
   de grupos que a tabela trouxer (o material lê, por exemplo, indicadores de redução, diferimento, crédito presumido e
   por modelo). Uma versão fica "ativa"; as anteriores ficam guardadas.
2. **Checagens novas no motor** (bean novo, tabela carregada uma vez no `FiscalCheckContext`, motor RN-01):

   | Código | Quando | Severidade (proposta, P-06) | Mensagem |
   |---|---|---|---|
   | `IBSCBS_CST_UNKNOWN` | CST do grupo não existe na tabela ativa | WARNING em 2026 | "CST {cst} do IBS/CBS não existe na tabela {versão}" |
   | `IBSCBS_CLASS_TRIB_UNKNOWN` | cClassTrib com formato válido que não existe na tabela ativa | WARNING em 2026 | "cClassTrib {c} não existe na tabela {versão}" |
   | `IBSCBS_CLASS_TRIB_NOT_IN_FORCE` | existe, mas a data local do `dhEmi` está fora da vigência | WARNING em 2026 | "cClassTrib {c} fora de vigência em {data} (vigente de {início} a {fim}, tabela {versão})" |
   | `IBSCBS_CLASS_TRIB_MODEL` | o documento (55 ou 65) não é permitido para o cClassTrib | WARNING em 2026 | "cClassTrib {c} não é permitido em {NF-e/NFC-e} (tabela {versão})" |

   Todas trazem `expected` com a descrição oficial e rodam em nota emitida e recebida (a recebida mostra o erro do
   fornecedor, que afeta o crédito a partir de 2027). A checagem atual de formato e prefixo continua.
3. **Regra nova na auditoria** `IBSCBS_CLASS_TRIB` (grupo COERENCIA, severidade MEDIA, fundamento "Tabela cClassTrib do
   Portal Nacional da NF-e, versão {x}"), sobre notas emitidas, nascendo "a conferir" (auditoria RN-03). É o paralelo
   com o Fiscal: só vira "indício" quando o time marca a regra como conferida.
4. **Painel de prontidão** por competência, na carteira: por empresa, notas emitidas (NF-e), % com grupo IBSCBS, itens
   com grupo, % de itens com cClassTrib válido e vigente pela tabela ativa, os 3 códigos de achado mais frequentes; e o
   mesmo para as **entradas** (prontidão dos fornecedores), com os 5 fornecedores com mais itens sem grupo ou com código
   inválido. Calculado por SQL agregado (junção com a tabela ativa), sem rodar o motor nota a nota.

**Dados que já existem.** `ibscbs_cst` e `ibscbs_class_trib` de cada item; `ide_modelo`; `issue_date`;
`emit_regime_tributario`. Não precisa reprocessar XML, então o expurgo não atrapalha.

**Ganho.**
- *Escritório cliente:* vê a empresa emitindo errado antes de a regra virar rejeição e vê os fornecedores que destacam
  errado.
- *CS Hub:* argumento de venda mensurável ("x% da sua carteira já emite o grupo certo") e serviço de adequação do
  cadastro dos clientes. No teste grátis, o painel aparece cheio assim que as primeiras notas chegam: bom gancho de
  conversão (medir: % de testes que abrem o painel e taxa de conversão desses).
- *Fiscal:* troca a conferência por amostragem por uma lista de exceções. Hipótese: 20 min por empresa do regime normal
  por mês (~45 empresas = 15 h/mês) caem para ~4 h/mês de tratamento de exceções.

**Esforço.** M.

**Serviço do plano.** A checagem no motor segue a conferência atual (sem trava por plano). A regra da auditoria entra
no job, que já exige `INVOICE_AUDIT` (`AuditScheduler.java:84`). O painel lista toda a carteira e mostra se a empresa
tem `INVOICE_AUDIT`. Módulo futuro: "Reforma Tributária" (P-03).

**Teste grátis.** Disponível para as 5 empresas. **Bloqueado:** painel e conferência em leitura; a auditoria automática
para (decisão 15); a importação da tabela é da plataforma e não é afetada.

**Custo SIEG.** Zero.

**Riscos.**
- Tabela desatualizada gera falso positivo. Mitigação: a versão e a data aparecem no achado e no laudo; o console avisa
  quando a tabela ativa tem mais de 60 dias (P-07 decide o processo).
- O material diz que em 2026 várias regras estão flexibilizadas: achado não pode virar alarme. Severidade de 2026 é
  decisão do Tony (P-06).
- Responsabilidade técnica: o laudo continua dizendo "indício para conferir".
- O formato do arquivo oficial pode mudar entre versões. O importador valida as colunas e recusa o arquivo com erro
  claro, sem importar pela metade (transação única).

#### Rascunho para a spec (F1a)

**Contrato da API.**

| Método e rota | Quem | Resposta e erros |
|---|---|---|
| `POST /api/v1/platform/ibscbs-tables` (multipart `arquivo`, `versao`, `publicadaEm`) | admin da plataforma | 201 `{id, versao, publicadaEm, hash, linhas, importadaEm}`; 400 `error.reform.table.file.invalid` (com `details` listando linha e coluna, no máximo 20); 409 `error.reform.table.version.duplicate` (mesma versão ou mesmo hash) |
| `POST /api/v1/platform/ibscbs-tables/{id}/ativar` | admin da plataforma | 200; 404 `error.reform.table.not.found`. Troca a ativa de forma atômica |
| `GET /api/v1/platform/ibscbs-tables` | admin da plataforma | lista de versões, ativa marcada |
| `GET /api/v1/reforma/tabela-ibscbs` | qualquer usuário autenticado | a versão ativa e as linhas (para a tela de consulta e para o front explicar o achado) |
| `GET /api/v1/reforma/prontidao?competencia=AAAA-MM&lado=EMITIDAS\|RECEBIDAS&page&size&sort` | ADMIN (escritório) e colaborador (carteira), permissão `INVOICES_VIEW` | página com `companyId`, `razaoSocial`, `cnpj`, `regime`, `notas`, `notasComGrupo`, `pctNotasComGrupo`, `itens`, `itensClassTribValido`, `pctItensValidos`, `principaisAchados[3]{codigo, quantidade}`, `temInvoiceAudit`; `resumo` da carteira; `tabelaVersao`. Ordem padrão: `pctItensValidos` crescente, depois `notas` decrescente |
| `GET /api/v1/reforma/prontidao/{companyId}/fornecedores?competencia` | idem; fora da carteira ou de outro escritório: 404 `error.Company.not.found` | até 50 fornecedores: `documento`, `nome`, `crt`, `notas`, `itensSemGrupo`, `itensInvalidos` |

**Modelo de dados.** `ibscbs_class_trib_tables` (`id`, `versao`, `publicada_em`, `hash` único, `ativa`,
`importada_por`, `importada_em`) e `ibscbs_class_trib_rows` (`table_id`, `cst`, `class_trib`, `descricao`,
`vigencia_inicio`, `vigencia_fim`, `documentos` (lista curta), `indicadores_json`), índice único (`table_id`,
`class_trib`). Algumas centenas de linhas por versão. Para o painel: índice novo em `fiscal_xml_document_items`
(`document_id`, `ibscbs_class_trib`) só se o plano de execução pedir (medir antes; a tabela de itens é a maior do banco).

**Variáveis.** `TAXIFY_IBSCBS_OFFICIAL_TABLE_CHECK_ENABLED=false` (liga as checagens novas no motor e na auditoria);
`TAXIFY_IBSCBS_TABLE_STALE_DAYS=60`. Ligar na EC2: Tony, depois do paralelo.

**Casos de teste (tabela de teste montada no fixture, com códigos e vigências fictícios).**

| CT | Dado | Esperado |
|---|---|---|
| CT-F1-01 | Tabela com `000001` (CST 000) vigente de 2026-01-01 sem fim, permitido em 55 e 65; nota 55 de 2026-11-10, item CST 000 cClassTrib 000001 | Nenhum achado novo |
| CT-F1-02 | Item com cClassTrib `000999` (fora da tabela) | `IBSCBS_CLASS_TRIB_UNKNOWN` WARNING com a versão no texto |
| CT-F1-03 | `200010` com vigência até 2026-06-30; nota de 2026-06-30T23:50-03:00 | Sem achado |
| CT-F1-04 | Mesmo código; nota de 2026-07-01T00:05-03:00 | `IBSCBS_CLASS_TRIB_NOT_IN_FORCE` |
| CT-F1-05 | Código permitido só em 55, usado em NFC-e (65) enviada por upload | `IBSCBS_CLASS_TRIB_MODEL` |
| CT-F1-06 | Nota cancelada com código inválido | Sem achado (motor RN-02) |
| CT-F1-07 | Importar a mesma versão duas vezes | 409 `error.reform.table.version.duplicate` |
| CT-F1-08 | Arquivo com coluna faltando | 400 com `details`; nenhuma linha gravada |
| CT-F1-09 | Duas instâncias ativam versões diferentes ao mesmo tempo | Fica exatamente uma ativa (update condicional) |
| CT-F1-10 | Prontidão: 10 notas emitidas, 8 com grupo, 40 itens com grupo e 30 válidos | `pctNotasComGrupo = 80.00`, `pctItensValidos = 75.00` (2 casas, HALF_UP) |
| CT-F1-11 | Colaborador pede fornecedores de empresa fora da carteira | 404 |
| CT-F1-12 | Flag desligada | Nenhum achado novo; painel mostra os dados de grupo, sem a coluna de validade |

---

### F1b. Regras da NT 2025.002 com código de rejeição (próximo)

**O que entrega.** As regras estruturais da NT (grupos exigidos e vedados pelos indicadores da tabela, gRed, gDif,
gDevTrib, crédito presumido, composição da base) como checagens, cada uma com o número da regra e o código de
rejeição, separando "regra ativa" de "implementação futura".

**Pré-requisito técnico.** Gravar a presença dos grupos que hoje não são gravados (o parser só guarda o indicador
conjunto de diferimento ou devolução, `FiscalXmlMetadataParser.java:344`). Isso exige subir a `metadata_version` e o
backfill, que **só alcança os arquivos ainda não expurgados** (~3 meses). As notas mais antigas ficam "não
verificáveis" para essas regras. Buscar de novo no SIEG não compensa (custo da conta do F5b).

**Esforço.** M. **Custo SIEG:** zero. **Plano, teste e bloqueio:** iguais ao F1a. **Perguntas:** P-08.

---

### F2. NCM inexistente ou fora de vigência (agora)

**O que entrega.** Tabela NCM oficial versionada (Portal Único Siscomex), importada pelo admin da plataforma, com
vigência e o ato legal (o material cita a Res. Gecex 926/2026, vigente em 25/11/2026; confirmar a vigente). Usos:
- checagem no motor `NCM_UNKNOWN` e `NCM_NOT_IN_FORCE` (WARNING), em nota emitida e recebida;
- regra nova na auditoria `NCM_INVALIDO` (COERENCIA, MEDIA), nascendo "a conferir";
- aviso no formulário da regra por produto quando o `ncmCorreto` não existe (não bloqueia, P-09).

**Dados que já existem.** `ncm` de cada item e de cada regra. A Fronteira já trata NCM sem tabela de PE como "sem
regra" (`FronteiraCalculator.java:252`), e o achado novo explica o porquê.

**Ganho.**
- *Cliente:* evita rejeição e erro de ST/CEST e de enquadramento futuro.
- *CS Hub:* base para F3 e para a Fronteira PE; argumento de qualidade de cadastro.
- *Fiscal:* hoje esse erro passa batido. Hipótese: poucos casos por mês, mas cada um custa uma retificação ou uma
  conversa com o fornecedor.

**Esforço.** P. **Serviço do plano:** como F1a (`INVOICE_AUDIT` para a auditoria). **Teste:** disponível.
**Bloqueado:** leitura; auditoria parada. **Custo SIEG:** zero.

**Riscos.** Baixos. NCM perde o zero à esquerda em planilha: guardar e comparar sempre como texto de 8 dígitos. NCM
com 2, 4 ou 6 dígitos (posição) não é código de item: `NCM_UNKNOWN` com mensagem própria ("NCM incompleto").

**Rascunho para a spec.** Tabelas `ncm_tables` (versão, ato, publicada_em, hash, ativa) e `ncm_codes` (`table_id`,
`codigo` char(8), `descricao`, `vigencia_inicio`, `vigencia_fim`), único (`table_id`, `codigo`); ~10 a 15 mil linhas
por versão. Rotas espelhadas do F1a em `/api/v1/platform/ncm-tables` e consulta `GET /api/v1/reforma/ncm/{codigo}`.
Codes: `error.reform.ncm.file.invalid`, `error.reform.ncm.version.duplicate`, `error.reform.ncm.not.found`. Variável
`TAXIFY_NCM_CHECK_ENABLED=false`. CT: NCM `01012100` vigente (sem achado); NCM com fim de vigência em 2026-03-31 em nota
de 2026-04-02 (`NCM_NOT_IN_FORCE`); `99999999` (`NCM_UNKNOWN`); `1012100` com 7 dígitos (`NCM_UNKNOWN`, "NCM
incompleto"); nota cancelada (sem achado).

---

### F4. Perfil da empresa: mix B2B × B2C e regime dos fornecedores (próximo, antes de março/2027)

**O que entrega.** Indicadores por empresa nos últimos 12 meses, sem cálculo de tributo:
- **Saídas** (NF-e emitidas, venda pelo `RevenueCfops`): destinatário CNPJ com IE (`indIEDest = 1`), CNPJ isento ou não
  contribuinte (2 e 9), CPF, exterior, e o indicador de consumidor final. Quando o destinatário é empresa **do mesmo
  escritório**, o regime dele é conhecido pelo cadastro e aparece ("cliente do Simples", "cliente do regime normal").
- **Entradas:** compras por CRT do fornecedor (1, 2, 3, 4 e "sem CRT/pessoa física").
- **Concentração:** 5 maiores clientes e fornecedores por valor (pessoa física sempre agrupada numa linha, sem CPF).
- **Cobertura:** aviso "a empresa emite NFC-e e o Taxify não captura NFC-e; o B2C está subcontado" quando houver sinal
  (NFC-e por upload, ou atividade de varejo no cadastro).

**Ganho.**
- *Cliente do Simples que vende B2B:* recebe o dado para decidir se opta pelo regime regular do IBS/CBS. O material
  cita janelas em setembro (1º semestre) e março (2º semestre), LC 123, art. 13, § 10, na redação da LC 227/2026, a
  validar. A de março/2027 seria a próxima.
- *CS Hub:* lista de "quem precisa dessa conversa" em cada carteira: gatilho de consultoria recorrente (semestral, pela
  leitura do material).
- *Fiscal:* tira o levantamento em planilha. Hipótese: 1 h por empresa do Simples com venda B2B relevante (~50
  empresas = 50 h por janela) cai para ~5 h de revisão.

**Esforço.** P a M. **Serviço do plano:** nenhum novo; módulo "Reforma Tributária". **Teste:** disponível (12 meses
curtos). **Bloqueado:** leitura liberada (é GET agregado, sem rotina). **Custo SIEG:** zero.

**Limite conhecido.** A nota de venda não diz se o comprador é do Simples. Fora da carteira do escritório, saber exige
consulta externa pelo CNPJ (fonte e custo a definir; dado de terceiro saindo do Taxify, LGPD). P-14.

**Riscos.** Baixos. Os nomes de clientes e fornecedores seguem o mesmo acesso das notas. Sem CPF na tela nem no CSV.

---

### F6. Radar de oportunidades com evidência nas notas, começando pelo Simples (próximo)

**O que entrega.** Lista **interna do escritório** com indícios de pagamento a maior, cada um com a evidência (notas e
itens), o valor de ordem de grandeza, o fundamento citado e o que conferir. Primeira regra sugerida: **empresa do
Simples que revende produto com PIS/COFINS monofásico ou com ICMS-ST e não segrega essa receita no DAS** (o material
cita LC 123, art. 18, § 4º-A, I). A apuração do Taxify hoje não segrega (`TaxAssessmentCalculator.java:20-25`).

**Como usa os dados.** Saídas de revenda (`RevenueCfops.RESALE`) com CSOSN de ST (o material trata 500 como ST) e NCM
da lista de monofásicos; entradas do mesmo produto com indício de monofásico (o material usa o CST de PIS 04, 05 e 06
como indício, a validar). O casamento "produto vendido ↔ produto comprado" usa GTIN e código; sem casamento, o indício
fica "a confirmar". O achado entra pela auditoria (`AUDIT_FINDING`, regra nova "a conferir").

**Janela.** Pela leitura do material, a monofasia de PIS/COFINS acaba em 2027 (salvo combustíveis) e a ST de ICMS vai
até 2032. O ganho do monofásico fica no **passado** (restituição, prazo a validar) e o da ST continua.

**Ganho.**
- *Cliente:* restituição. Exemplo fictício: revenda monofásica de R$ 30.000,00/mês com uma parcela de PIS+COFINS de
  2,00% no DAS dá R$ 600,00/mês, R$ 7.200,00/ano (porcentagem ilustrativa: a partilha real depende da faixa e é
  regra a validar).
- *CS Hub:* o "Mapa da Mina" feito com dado, com receita de serviço auditável.
- *Fiscal:* cruzamento que hoje quase nunca é feito.

**Esforço.** M (primeira regra); cada regra nova, P a M. **Serviço do plano:** `INVOICE_AUDIT` (entra na auditoria).
**Teste:** disponível. **Bloqueado:** leitura; auditoria parada. **Custo SIEG:** zero, mas **varejo sem NFC-e não tem
as saídas** (H1).

**Riscos.** Achado mostrado ao cliente gera expectativa: a visão é interna, cada item diz "indício, conferir". A
restituição é pedida pelo escritório, nunca pelo sistema.

**Perguntas.** P-15, P-16, P-17.

---

### F7. NFS-e nacional: NBS e grupo IBS/CBS (próximo)

**O que entrega.** Ler NBS, indicador de operação e o grupo IBSCBS das NFS-e do padrão nacional (as que chegam pelo ADN
e pelo SIEG), conferir com as tabelas oficiais de gov.br/nfse (o material cita leiaute v1.01, NBS 2.0) e aplicar as
checagens de F1a. ABRASF fica "não verificável".

**Pré-requisito.** Colunas novas em `service_invoices` e versão do parser; o backfill só alcança os arquivos não
expurgados. **Esforço** M. **Plano:** `NFSE_NATIONAL_INTEGRATION` já governa a captura; as checagens seguem a regra de
F1a. **Custo SIEG:** zero (re-leitura local). **Ganho:** o mesmo de F1a para a carteira de serviços. **Pergunta:** P-18.

---

### F3. Enquadramento dos NCM vendidos nos anexos da LC 214 (depois)

**O que muda em relação à revisão 1.** O Taxify não tem cadastro de produtos vendidos; as regras por produto são das
**entradas**. Então o F3 vira um **relatório**: para os NCM das saídas da empresa (itens das NF-e emitidas), os anexos
em que o código aparece, com status (listado sem condição, candidato por posição ou capítulo, item com condição escrita,
sem benefício) e o cClassTrib que a tabela oficial associa. Nada é aplicado sozinho. Nas entradas, o mesmo relatório
ajuda a conferir o cClassTrib do fornecedor.

**Riscos.** Responsabilidade técnica alta: enquadrar benefício é parecer. O material registra que a versão original
casava prefixo e dava "confiança alta" errada; é exatamente o erro a evitar. **Esforço** M. **Dependências:** F1a, F2,
texto oficial consolidado dos anexos (o material diz que a LC 227/2026 alterou o texto e revogou o Anexo XIV;
confirmar no Planalto). **Perguntas:** P-19.

---

### F8. Projeção do Simples com opção pelo regime regular (depois)

Acrescentar à simulação de regimes (Relatórios, RN-10 a RN-14) o cenário 2027+ do Simples: DAS pela partilha do ano e
opção pelo regime regular com crédito das compras pelo CRT do fornecedor (vem de F4). Cada alíquota com o status do
F1a-0; enquanto houver alíquota não `LEI`, toda saída leva a marca "estimativa". O material cita a partilha dos Anexos
XVIII a XXII da LC 214 com a LC 227/2026 e o crédito do fornecedor do Simples (art. 47, § 9º, II), a validar.
**Esforço** G. **Dependências:** F1a-0 e F4.

---

### H1. Habilitador: captura de NFC-e (avaliar)

Sem NFC-e, o varejo fica sem saídas no Taxify: F4 subconta o B2C, F6 não vê a revenda e F5b não confere saldo. Conta do
SIEG: varejista com 3.000 NFC-e/mês ÷ 50 por página = **60 requisições ≈ 30 min de SIEG por empresa por mês**; 40
varejistas ≈ 20 h/mês só de NFC-e, num limitador global de 2 req/min (2.880 por dia, já disputadas pela captura de
NF-e). Recomendação: **não ligar agora**. Avaliar depois a contagem (`contar-xmls` já devolve todos os tipos,
`SiegContarXmlsDataDTO.java:7`) para medir o volume real antes de decidir (P-10).

---

## 5. Roadmap em ondas

Legenda: **D** = dados e bases oficiais; **V** = validação do Fiscal/Tony; **B** = backend; **F** = frontend; **Q** = QA.
Cada funcionalidade passa pela esteira completa (spec → B → F → reviewer → QA). As atividades estão na ordem sugerida;
"→" indica dependência.

### Onda 1: agora (dezembro/2026)

**1. F5a Levantamento do crédito de estoque** (prazo: no ar até ~15/12/2026)

| Id | Área | Atividade | Depende de |
|---|---|---|---|
| V-5a-1 | V | Tony responde P-01 (mapeamento regime → classificação) e P-02 (texto e período) | — |
| B-5a-1 | B | Migration `stock_credit_survey`; consulta agregada por lote de 50 empresas (CE-RN-04 a 06) | V-5a-1 |
| B-5a-2 | B | Snapshot diário com flag e cron (CE-RN-07), `POST /atualizar` com cooldown | B-5a-1 |
| B-5a-3 | B | Rotas de lista, detalhe, situação e CSV, com carteira, 404 e bloqueio | B-5a-1 |
| F-5a-1 | F | Tela "Reforma > Crédito de estoque": lista, filtros, resumo, cobertura, situação, CSV, texto fixo | B-5a-3 (contrato já na spec: pode rodar em paralelo) |
| Q-5a-1 | Q | CT-CE-01 a 09; isolamento entre escritórios; bloqueado; duas instâncias | B-5a-3, F-5a-1 |
| V-5a-2 | V | Paralelo: o Fiscal confere a lista contra 3 empresas conhecidas | Q-5a-1 |

**2. F1a-0 Alíquotas por ano com status** (no ar até 31/12/2026)

| Id | Área | Atividade | Depende de |
|---|---|---|---|
| V-1a0-1 | V | Tony responde P-05 (status, fundamento de 2026, o que cadastrar para 2027) | — |
| B-1a0-1 | B | Migration com as colunas novas; regra "só `LEI` gera achado"; rotas do console | V-1a0-1 |
| F-1a0-1 | F | Tela no console da plataforma, com aviso de ano sem `LEI` | B-1a0-1 |
| Q-1a0-1 | Q | CTs do F1a-0, virada de ano (nota de 31/12/2026 23:59 e de 01/01/2027 00:00) | F-1a0-1 |

**3. F1a Tabela cClassTrib oficial e prontidão** (dezembro/2026 a janeiro/2027)

| Id | Área | Atividade | Depende de |
|---|---|---|---|
| D-01 | D | Baixar do Portal Nacional da NF-e a tabela de CST/cClassTrib vigente; registrar versão, data, URL e hash; definir o layout do importador a partir do arquivo oficial | — |
| V-1a-1 | V | Tony responde P-06 (severidade 2026) e P-07 (quem atualiza a tabela e com que frequência) | — |
| B-1a-1 | B | Tabelas versionadas, importador transacional, ativação atômica | D-01 |
| B-1a-2 | B | Checagens novas no motor (contexto carregado uma vez), flag | B-1a-1, V-1a-1 |
| B-1a-3 | B | Regra `IBSCBS_CLASS_TRIB` na auditoria ("a conferir") | B-1a-2 |
| B-1a-4 | B | Rotas de prontidão (emitidas, recebidas, fornecedores) por SQL agregado | B-1a-1 |
| F-1a-1 | F | Console: importar e ativar versão | B-1a-1 |
| F-1a-2 | F | Conferência: códigos novos no grupo IBS/CBS (`finding-groups.ts`) com a versão da tabela | B-1a-2 |
| F-1a-3 | F | Painel de prontidão da carteira e detalhe de fornecedores | B-1a-4 |
| Q-1a-1 | Q | CT-F1-01 a 12 | F-1a-3 |
| V-1a-2 | V | Paralelo de 1 mês: Fiscal confere os achados de 5 empresas do regime normal e marca a regra como conferida | Q-1a-1 |

**4. F2 NCM**

| Id | Área | Atividade | Depende de |
|---|---|---|---|
| D-03 | D | Baixar a NCM vigente do Portal Único Siscomex com o ato e a data | — |
| B-2-1 | B | Tabelas versionadas e importador (reaproveita o desenho do B-1a-1) | D-03, B-1a-1 |
| B-2-2 | B | Checagens `NCM_UNKNOWN`/`NCM_NOT_IN_FORCE`, regra `NCM_INVALIDO`, aviso na regra por produto | B-2-1 |
| F-2-1 | F | Console de importação; aviso no formulário da regra; achados na conferência | B-2-2 |
| Q-2-1 | Q | CTs do F2 | F-2-1 |

### Onda 2: próximo (janeiro a abril/2027)

| Ordem | Funcionalidade | D | V | B | F | Q | Depende de |
|---|---|---|---|---|---|---|---|
| 5 | **F4 Perfil B2B × B2C** (antes de março) | — | P-14 (consulta do regime do comprador) e critério comercial "avaliar opção" | agregação por empresa e 12 meses; regime do destinatário quando é empresa do escritório; sinal de NFC-e sem cobertura | bloco na ficha da empresa; filtro "avaliar opção" na carteira | totais contra a curva ABC e soma manual de uma empresa fictícia; PF sem CPF | — |
| 6 | **F5b Cálculo do crédito de estoque** (no ar até ~abril, antes de 30/06) | layout do Bloco H e da planilha do Taxify | todas as RN de inclusão, exclusão, custo, porcentagem, importados e parcelas (P-11 a P-13) **antes** do dev | importador de inventário; casamento item × aquisição; cálculo com memória; cronograma; busca retroativa sob pedido pela fila de prioridade | tela por empresa: itens, motivo de exclusão, "sem documento", exportação | casos com números: nacional, importado, alíquota zero, uso e consumo, devolução posterior, item sem nota, transferência entre filiais | F5a, P-01 |
| 7 | **F1b Regras da NT** | regras da NT 2025.002 vigente, uma a uma com referência | P-08 (quais regras entram) | parser com os grupos novos, `metadata_version`, backfill; checagens | regra citada no achado | XML fictícios por cenário; nota já expurgada vira "não verificável" | F1a |
| 8 | **F6 Radar: monofásico e ST no Simples** | lista oficial de NCM monofásicos (P-16) | P-15, P-17 (regra de segregação, prazo de restituição) | regra na auditoria com evidência; casamento de produto | lista interna por carteira; detalhe com as notas | revenda monofásica, produção própria (fora), ST, cancelada | F2 |
| 9 | **F7 NFS-e nacional** | anexos da NFS-e nacional (gov.br/nfse) | P-18 | parser NBS/IBSCBS; checagens de F1a adaptadas | grupo na tela da NFS-e e na conferência | XML nacional e ABRASF fictícios | F1a |

### Onda 3: depois (quando alíquotas e regulamento estiverem fixados)

| Ordem | Funcionalidade | Observação |
|---|---|---|
| 10 | **F3 Enquadramento dos NCM vendidos** | depende de F1a, F2 e do texto oficial dos anexos |
| 11 | **F8 Projeção do Simples com opção pelo regime regular** | depende de F1a-0 com status `LEI` e de F4 |
| 12 | PDF do radar (F6) e do perfil (F4) para reunião com o cliente | substitui o gerador de PPT, com dado próprio e ressalvas |
| 13 | **H1 Captura de NFC-e** | só depois de medir o volume pela contagem do SIEG (P-10) |
| 14 | Conciliação PGDAS-D × notas | só por integração oficial (procuração), nunca OCR de PDF |
| 15 | Split payment | só com regulamento publicado e o grupo de pagamento gravado |

---

## 6. O que não recomendamos

- **Copiar bases, textos ou lógica do RT33.** Material de terceiros, ligado a um evento, com partes protegidas por
  senha. As bases vêm das fontes oficiais, com versão e hash gravados pelo Taxify.
- **Usar o RT33 como gabarito de teste.** O próprio material registra erros graves nas versões originais; os casos de
  teste do Taxify saem das fontes oficiais e da validação do Fiscal.
- **Mapa da Mina e questionário com score, ticket e cobrança.** Recomenda a partir de declaração; o F6 faz o papel com
  evidência.
- **Gerador de PPT por segmento e roteiro de WhatsApp.** Conteúdo editorial que exige curadoria jurídica contínua e cria
  responsabilidade sobre afirmações de terceiros.
- **"Validar com IA" enviando os dados do item a serviço externo.** Fere o sigilo profissional e a LGPD. Qualquer IA
  exige decisão do Tony, contrato e registro.
- **OCR de cartão CNPJ e de PGDAS.** O Taxify já tem cadastro e notas; OCR traz erro de leitura e manutenção.
- **Busca avulsa de NCM e NBS.** O portal oficial já faz de graça.
- **"Carga atual estimada por setor" e confronto com 2033.** Média sem fonte vira número que o cliente leva a sério.
- **Saldo de estoque só por XML.** Sem NFC-e, sem ajustes e sem inventário, erra sempre no varejo. O F5b parte do
  inventário oficial.
- **Simulador de split payment e formação de preço agora.** Entradas digitadas e regra não fechada; o Taxify não
  acrescenta nada além de uma planilha.
- **Processar só no navegador, sem salvar.** O Taxify precisa do contrário: histórico, trilha e conferência repetível
  no paralelo.

---

## 7. Requisitos de acesso e padrões (valem para todas)

- Tudo dentro do escritório (`accounting_firm`). O colaborador vê só a carteira (`users_companies`, via
  `TenantAccessService`); o ADMIN vê o escritório. Empresa de outro escritório ou fora da carteira: **404**
  `error.Company.not.found`, nunca 403.
- **Plataforma (CS Hub):** só importa e ativa tabelas oficiais e cadastra alíquotas (`@PlatformAdminOnly`). Nenhuma
  visão da plataforma mostra CNPJ, nome ou valor de empresa cliente (F1-RN-15). Contagens agregadas por escritório na
  plataforma também ficam de fora até o Tony decidir (P-21).
- **Escritório bloqueado:** telas e exportações em leitura; toda escrita nova (situação, atualizar, importar
  inventário) responde 403 `error.firm.access.blocked`; rotinas novas consultam `allowsAutomaticRoutines` e param.
- **Teste grátis:** tudo disponível (proposta da P3-07), limitado às 5 empresas; a tela mostra a cobertura do
  histórico.
- **Feature arriscada nasce desligada:** `TAXIFY_STOCK_SURVEY_ENABLED`, `TAXIFY_IBSCBS_OFFICIAL_TABLE_CHECK_ENABLED`,
  `TAXIFY_NCM_CHECK_ENABLED`, todas `false`. Ligar na EC2: Tony.
- **Logs:** só ids e contagens. Nada de CNPJ, chave de acesso, nome ou valor. Nada de texto cru de arquivo importado.
- **CSV:** fórmulas neutralizadas; pessoa física nunca com CPF.
- **Visões internas:** F5a e F6 são do escritório; não vão ao cliente sem ação explícita do analista.
- **Toda estimativa** traz o status de cada alíquota e a frase "estimativa para conferência, não substitui a análise do
  responsável técnico".
- **Dinheiro em `BigDecimal`**, arredondamento explícito (2 casas, HALF_UP, como o motor, RN-04).
- Sem dado real em fixture, log ou commit.

---

## 8. Perguntas consolidadas para o Tony

> **Respondidas em 2026-11-27.** P-01, P-02, P-05, P-06 e P-07: decisões na tabela "Decisões do Tony" do topo. As
> demais seguem a recomendação abaixo. As tabelas ficam como registro do que foi perguntado.

### Bloqueavam (respondidas em 2026-11-27)

| P | Funcionalidade | Pergunta | Opções | Recomendação | Impacto se a resposta for outra |
|---|---|---|---|---|---|
| **P-01** | F5a, F5b | O art. 381 da LC 214 e o "Decreto 12.955/2026, arts. 605 a 611" citados no material estão confirmados? O mapeamento Presumido → hipótese I, Real → II/III, Simples/MEI → fora (CE-RN-03) está certo? | (a) confirma; (b) ajusta o mapeamento; (c) não confirma e o F5a não sai | (a), com a frase "a validar" mantida até o F5b | (b) muda só a tabela da CE-RN-03; (c) cancela F5a e F5b |
| **P-02** | F5a | O prazo citado (inventário em 31/12/2026, apropriação até 30/06/2027) está certo? Até quando o levantamento fica no ar? | até 30/06/2027; até o fim do F5b | até 30/06/2027 | muda só a data de corte da CE-RN-07 |
| **P-05** | F1a-0 | Status das alíquotas (`LEI`, `PENDENTE`, `ESTIMATIVA`), fundamento de 2026 e o que cadastrar para 2027 | cadastrar 2027 só quando houver ato oficial; cadastrar já com `PENDENTE` | cadastrar só com ato oficial (`LEI`); antes disso, linha `PENDENTE` sem achado | se aceitar estimativa gerando achado, aparece ERROR em nota correta |
| **P-06** | F1a | Severidade dos achados novos em 2026 e quando endurece | WARNING em 2026 e ERROR a partir de 2027; WARNING sempre até o paralelo | WARNING até o fim do paralelo; ERROR depois, por decisão sua | ERROR já em 2026 enche a conferência de alarme em ano-teste |
| **P-07** | F1a, F2 | Quem baixa e importa as tabelas oficiais e com que frequência | admin da plataforma, conferindo o portal toda semana; job automático com aprovação | admin da plataforma (como as regras da auditoria), checagem mensal e aviso de 60 dias | job automático exige chamada externa e parser frágil |

### Têm recomendação aceitável (aceitas pelo Tony em 2026-11-27: valem as recomendações)

| P | Funcionalidade | Pergunta | Recomendação | Impacto se for diferente |
|---|---|---|---|---|
| P-03 | todas | Serviço do plano: criar serviço novo no `PlanServiceEnum`, usar `REPORTS` (hoje sem efeito) ou nenhum até o módulo da fase 3? | Nenhum serviço novo; auditoria segue `INVOICE_AUDIT`; módulo "Reforma Tributária" entra na lista da P3-07; escrita do F5a com `TAX_ASSESSMENT_CLOSE` | Serviço novo exige migration e conferência em cada rota, sem efeito comercial (o escritório se dá o serviço) |
| P-04 | F5a, F5b | A CS Hub vende o levantamento e o cálculo como serviço? Com que cobrança (fixo, êxito)? | Oferecer à carteira da CS Hub primeiro; decidir o preço depois do paralelo | Não muda o produto, muda a campanha |
| P-08 | F1b | Quais regras da NT entram primeiro? | Só as estruturais (grupos exigidos e vedados) | Mais regras = mais esforço e mais falso positivo em 2026 |
| P-09 | F2 | NCM inválido bloqueia o salvamento da regra por produto? | Não: só avisa | Bloquear trava o cadastro quando a tabela estiver desatualizada |
| P-10 | H1 | Medir o volume de NFC-e da carteira pela contagem do SIEG antes de decidir a captura? | Sim, só a contagem | Ligar direto pode atrasar a captura de NF-e |
| P-11 | F5b | Critério de custo: PEPS ou custo médio? ICMS e IPI recuperáveis saem do custo? | O que o IRPJ da empresa usa (o material cita "Dec. 609"), informado por empresa | Muda a fórmula do F5b |
| P-12 | F5b | Inventário: Bloco H, planilha ou os dois? | Bloco H primeiro; planilha no layout do Taxify como alternativa | Só planilha aumenta erro de digitação |
| P-13 | F5b | Busca retroativa de 2026 no SIEG para quem tem cobertura baixa? | Sob pedido, uma empresa por vez, na fila de prioridade, com teto por escritório | Busca em massa ocupa o SIEG por dias |
| P-14 | F4 | A primeira versão aceita "contribuinte com IE × consumidor final" sem consultar o regime do comprador? Qual % B2B marca "avaliar opção"? | Sim; regime só quando o comprador é empresa do escritório; critério comercial a definir (sugestão: B2B ≥ 30% da receita) | Consulta externa exige fonte, custo e análise LGPD |
| P-15 | F6 | A primeira regra do radar é monofásico/ST no Simples? | Sim, começando pela ST (continua até 2032, pela leitura do material) | Outra regra muda os dados usados |
| P-16 | F6 | Fonte oficial da lista de NCM monofásicos e quem mantém | Leis federais por NCM, mantidas pelo admin da plataforma como tabela versionada | Sem fonte, o F6 não sai |
| P-17 | F6 | Prazo de restituição e forma do pedido | Validar com o Fiscal; o sistema só mostra o indício | — |
| P-18 | F7 | Que parte da carteira emite NFS-e no padrão nacional? | Medir pelo `layout` gravado em `service_invoices` antes de priorizar | Se for pouca, F7 desce de onda |
| P-19 | F3 | Quem pode usar o relatório de enquadramento com o cliente? | Só uso interno até o paralelo | — |
| P-20 | estimativas | Quantas empresas da carteira há em cada regime? | Tirar do cadastro (`company_tax_regimes`) e refazer as contas desta avaliação | Muda os ganhos estimados, não o desenho |
| P-21 | todas | A plataforma pode ver contagens agregadas por escritório (ex.: "x empresas com indício")? | Não, por enquanto (F1-RN-15) | Exigiria exceção na política de privacidade |

---

## 9. O que esta avaliação não conseguiu fazer

- Não conferiu nenhuma regra fiscal citada pelo material (art. 381, "Decreto 12.955/2026", alíquotas, prazos, NT, IT,
  Res. Gecex, LC 227/2026). Tudo segue "o material cita".
- Não baixou as tabelas oficiais (cClassTrib, NCM, NBS) nem confirmou o layout dos arquivos: D-01 e D-03 ficam para a
  spec.
- Não mediu a carteira real (distribuição por regime, volume de itens, emissores de NFC-e, NFS-e nacional × ABRASF):
  as estimativas usam a hipótese da seção 4 e pedem P-10, P-18 e P-20.
- Não confirmou o valor de `FISCAL_XML_PURGE_RETENTION_MONTHS` na EC2 (o repositório usa 3).
- Não mediu o plano de execução da consulta agregada do F5a nem do painel de prontidão na base real; a spec pede a
  medição em QA com massa sintética.
