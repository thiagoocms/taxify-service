# Fronteira PE (e-Fisco): extrato do ICMS antecipado × cálculo próprio, nota a nota

> Spec retroativa (as-built). Descreve o que está no código em 2026-11-26. Não propõe nada novo. Onde o código e o contexto de negócio não batem, o ponto vai para "Perguntas em aberto" e fica sem solução aqui.

## Status

**Implementado** (backend e frontend), em produção no fluxo de conferência da Fronteira.

Marcos (commits em `master`, do mais antigo ao mais novo):

| Commit | O que entrou |
|---|---|
| `e5fdec6` | Importação do PDF do extrato do e-Fisco, cálculo item a item pela tabela de ICMS (ST com MVA ajustada, Simples art. 363-A, sem encerramento) e fórmulas próprias |
| `b723423` | Carga líquida do credenciamento para qualquer regime, `aplicaEm` da fórmula e crédito do fornecedor do Simples (pCredSN/vCredICMSSN) |
| `2ec266d` | Regime normal pelo diferencial; teste de fórmula com os valores do item |
| `4b77484` | Cálculo "como o e-Fisco": base × interna − crédito, crédito outorgado do Simples, frete na base, mercadoria com agregação por NCM, ativo e uso e consumo pela regra fiscal do produto |
| `54a99b3` | Base dupla (arts. 333/336) só informativa na memória |
| `e8800ed` | Regra de carga líquida criada a partir do extrato; bonificação (x910) e outras (x949) cobradas como compra |
| `cdc624c` | Certificado do contador (e-CPF A1) do escritório para o e-Fisco |
| `ebadb77`, `615d230` | Rotina automática do e-Fisco (hoje uma vez por dia, 7h); extrato igual fica como está; mês anterior só até o vencimento ou o pagamento |
| `af0efa4` | PDF do extrato guardado ("Ver PDF") |
| `2e5dc28`, `ead9710`, `c4d5291`, `c795e07` | Memória de cálculo do e-Fisco por nota (busca, guarda, leitura e comparação) |
| `39ddabe` | Valor da guia ajustada e relatório do mês em PDF e CSV |
| `a682e36` | Regras gerais (tabela de ICMS) só alteradas pelo admin da plataforma (CS Hub) |
| `77b7680` | **M8**: rascunho do pedido de revisão (PDF e texto) |

Migrations: `V45__EfiscoExtracts`, `V46__EfiscoExtractNotes`, `V47__FronteiraRules`, `V49__FronteiraRuleAplicaEm`, `V50__FronteiraRuleAgregacao`, `V52__EfiscoExtractPdf`, `V53__EfiscoMemories` (em `src/main/java/db/migration`).

## Problema e objetivo

Hoje o analista confere a Fronteira à mão em duas etapas: (1) ver se toda nota de entrada interestadual está no extrato da SEFAZ-PE e (2) recalcular nota a nota o ICMS antecipado cobrado. O Taxify faz as duas coisas:

1. Traz o extrato do e-Fisco (sozinho, com o e-CPF do contador, ou por upload do PDF) e cruza cada linha com o XML da nota já capturado.
2. Calcula o ICMS antecipado de cada item pela tabela de ICMS e pelas fórmulas próprias, mostra a memória de cálculo e classifica cada nota: confere, cobrou a mais, cobrou a menos, não calculou, sem regra ou pendente.
3. Lista as entradas interestaduais do mês que o extrato não cobra, com a prévia do imposto.
4. Gera o relatório do mês (PDF/CSV), o valor da guia ajustada e o rascunho do pedido de revisão das notas cobradas a mais.

Como medir (o código não mede nada disso; são os indicadores que o resumo já expõe): quantidade de notas `CONFERE` sobre o total, quantidade de `SEM_REGRA` e `PENDENTE` (o que ainda exige trabalho manual) e o valor de `cobradoAMais`. No paralelo de 1 a 3 meses, o objetivo é o resultado do Taxify bater com a conferência manual.

## Usuários e cenário

- **Analista do time Fiscal**: abre a tela Fronteira por empresa de PE e mês. Faz isso pelo menos uma vez por mês por empresa, antes do vencimento do extrato. A rotina automática roda todo dia às 7h.
- **Responsável pela tabela de ICMS** (permissão `FISCAL_RULES_MANAGE`): confere as regras ("a conferir" → conferida) e liga ou desliga cada uma.
- **Admin da plataforma (CS Hub)**: único que cria, altera, exclui, confere ou liga as regras **gerais** (as que valem para todos os escritórios).
- Empresa piloto: indústria credenciada na sistemática de tecidos (carga líquida de 5,5%). O perfil de tecidos está no código como regra `SIMPLES_PERCENTUAL` cuja descrição é o nome do credenciamento lido do extrato.

## Histórias de usuário

- HU-01. Como analista, quero que o extrato do e-Fisco de cada empresa de PE seja buscado sozinho com o certificado do contador, para não ter de baixar PDF por PDF.
- HU-02. Como analista, quero importar à mão o PDF do extrato, para o caso de a rotina ser bloqueada.
- HU-03. Como analista, quero ver cada nota do extrato ao lado do cálculo do Taxify, com a memória de cálculo, para auditar o resultado durante o paralelo.
- HU-04. Como analista, quero ver as notas cobradas sem XML, só com resumo, com valor diferente, canceladas ou fora da taxa usual, para investigar cada uma.
- HU-05. Como analista, quero ver as entradas interestaduais do mês que o extrato não cobra, com a prévia, para antecipar o que vem.
- HU-06. Como analista, quero buscar a memória de cálculo do próprio e-Fisco de uma nota e comparar etapa por etapa com a do Taxify, para decidir quem está certo.
- HU-07. Como analista, quero registrar uma fórmula própria (por produto, fornecedor, NCM ou empresa) que passa a valer nas próximas notas, para tratar exceções sem esperar o desenvolvimento.
- HU-08. Como responsável fiscal, quero que toda regra nova ou alterada fique "a conferir" até eu marcar, para que nenhum resultado use regra não validada sem aviso.
- HU-09. Como analista, quero o relatório do mês em PDF ou CSV e o valor da guia ajustada, para arquivar e mandar ao cliente.
- HU-10. Como analista, quero o rascunho do pedido de revisão das notas cobradas a mais, em PDF e em texto, para protocolar no e-Fisco.
- HU-11. Como CS Hub, quero que só o admin da plataforma altere as regras gerais, para que um escritório não mude o cálculo dos outros.

## Regras de negócio

Referências no formato `arquivo:linha`. Os caminhos são relativos a `src/main/java/br/com/cshub/taxify/`.

### Extrato e importação

**RN-01. Leitura do PDF do extrato.** O PDF "Extrato de notas fiscais relativas a operações interestaduais sujeitas ao ICMS antecipado" é lido com PDFBox, com o texto ordenado por posição. O arquivo é recusado se o texto não tiver "ICMS ANTECIPADO" nem "OPERACOES INTERESTADUAIS", se não tiver nenhuma nota ou se não tiver período fiscal no formato mm/aaaa. O parser lê:
- cabeçalho: período fiscal, IE, razão social, ARE, código da receita, credenciamento de antecipação, credenciamento interno, data de vencimento, nº do extrato e "emitido em";
- uma linha por nota: registro, data de passagem (opcional), nº da nota sem zeros à esquerda, UF, valor da NF, ICMS devido e CNPJ/CPF do emitente (só dígitos);
- rodapé: ICMS a recolher, pago, em processo, autuado, sob mandado de segurança, anistiado e reg. débito.

Os rótulos são comparados sem acento, porque a codificação do PDF varia. `service/fronteira/EfiscoExtractParser.java:38-143`.

**RN-02. Validações da importação.** A empresa precisa ser de PE (`company.uf`), senão a resposta é 400 `error.fronteira.company.not.pe`. O arquivo não pode estar vazio. Se a empresa tiver IE cadastrada e a IE do extrato não bater com nenhuma (comparação só por dígitos, sem zeros à esquerda), a resposta é 400 `error.fronteira.extract.other.ie`. Uma empresa sem IE cadastrada aceita qualquer extrato. `service/fronteira/FronteiraService.java:126-139, 191-203`.

**RN-03. Um extrato por empresa e período.** Importar o mesmo período de novo substitui o cabeçalho, os totais e todas as notas (`replaceNotes`). O banco garante isso com a unique `uk_efisco_extracts_company_periodo`. `importedBy` recebe o usuário logado ou `"e-Fisco (automático)"` quando quem importa é a rotina. O PDF fica guardado no storage dos XMLs, e o PDF anterior é apagado. Se o storage falhar, a importação não falha. `FronteiraService.java:141-188, 656-671`; `V45`, `V46`, `V52`.

### Cruzamento extrato × XML

**RN-04. Casamento da linha do extrato com a nota.** A chave é `CNPJ do emitente (dígitos) | número (dígitos, sem zeros à esquerda)`, e a série não entra. Se houver mais de um documento com a mesma chave, vence o de direção `INBOUND`. `FronteiraService.java:451-468, 572-574`.

**RN-05. Marcações (flags) por linha.** `FronteiraService.java:324-364`:
- `SEM_XML`: não há documento com a chave.
- `SO_RESUMO`: não há documento, mas existe um resumo da SEFAZ ainda não completado com a mesma chave. Emitente e número saem da chave de acesso: posições 6-20 e 25-34. `FronteiraService.java:510-519`.
- `NOTA_CANCELADA`: o documento está cancelado.
- `VALOR_DIFERENTE`: `|valorTotalNota do XML − valor da NF no extrato| > 0,01`.
- `DIVERGENCIA_FISCAL`: o motor de conferência fiscal achou divergência no documento.
- `FORA_DA_TAXA`: ver RN-06.

**RN-06. Taxa predominante e "fora da taxa".** É uma dica, não muda o resultado.
- `taxa da nota = ICMS devido × 100 ÷ valor da NF`, com 4 casas, HALF_UP. Fica nula quando o valor da NF é zero.
- `taxa predominante` = a moda das taxas arredondadas para 1 casa (HALF_UP). No empate vence a menor. Fica nula com menos de 3 notas com taxa.
- Uma nota é `FORA_DA_TAXA` quando o ICMS devido é maior que zero, `|taxa − predominante| > 0,15` ponto percentual **e** `|ICMS devido − round(valor NF × predominante ÷ 100, 2, HALF_UP)| > 0,05`. Nesse caso `diferencaParaTaxa = ICMS devido − esse esperado`.

`FronteiraService.java:61-66, 531-568`.

**RN-07. Entradas sem cobrança e prévia.** Entram na lista as notas da empresa emitidas no mês, não excluídas, não canceladas, de direção `INBOUND`, com UF do emitente diferente de PE e fora do extrato. Cada uma é calculada (RN-10 em diante). A `previaIcms` é a soma dos valores calculados. Sem extrato no mês, a tela mostra só essa prévia, e o credenciamento usado vem do extrato mais recente da empresa. `FronteiraService.java:283-316, 497-507`.

### Cálculo próprio (por item)

**RN-08. Contexto do cálculo (o "perfil" da empresa).** O código não tem uma entidade de perfil. O perfil sai de três coisas (`FronteiraService.java:289-294`):
- `simplesCompany`: `company.taxRegimeType == SIMPLES_NACIONAL`;
- `credenciamento`: o "credenciamento interno" lido do extrato do mês;
- `rules`: regras gerais mais as da empresa (`FronteiraRuleRepository.findForCompany`), além das regras fiscais por produto da empresa (CFOP de entrada correto).

**RN-09. Classificação do item (ordem de decisão).** `service/fronteira/FronteiraCalculator.java:159-187`:
1. **CFOP de entrada**: o `cfopCorreto` da regra fiscal do produto (casada por código do produto e fornecedor), se tiver 4 dígitos. Sem ela, o CFOP do fornecedor convertido para entrada (5/6/7 → 1/2/3; 405→403, 655→652, 656→653; `service/EntryCfops.java`). "Operação" é o CFOP sem o primeiro dígito.
2. **Sem antecipação** (`SEM_ANTECIPACAO`, valor 0): operação começando com `2` (devolução), operação começando com `9` exceto 910 (bonificação) e 949 (outras), e as operações 410 e 411. `FronteiraCalculator.java:170-174, 310`.
3. **ST retido** (`ST_RETIDO`, valor 0): `vICMSST` do item maior que 0. `:175-178`.
4. Tratamento padrão, na ordem:
   - `USO_CONSUMO` se a operação estiver em {551, 552, 553, 555, 556, 557, 406, 407} (`:313`);
   - senão `ST` se houver regra `ST_MVA` que case;
   - senão `MERCADORIA` se houver regra `AGREGACAO` que case;
   - senão `CARGA_LIQUIDA` se houver regra de carga líquida (RN-13) ou se a empresa for do Simples;
   - senão `SEM_ENCERRAMENTO`.
5. **Fórmula própria**: se uma regra `FORMULA` casar e o `aplicaEm` dela estiver vazio ou for igual ao tratamento padrão, ela substitui o cálculo (RN-16). Nunca substitui `SEM_ANTECIPACAO` nem `ST_RETIDO`.

**RN-10. Valor de partida (base).** `FronteiraCalculator.java:472-495`:
```
produto      = vProd do item (valorTotal)
frete_item   = frete da nota × produto ÷ soma dos vProd da nota        (sem arredondar)
outras_item  = (seguro + outras despesas da nota) × produto ÷ soma vProd (sem arredondar)
partida      = produto + IPI do item + frete_item + outras_item
```
O desconto não reduz a partida. Nota com soma de produtos igual a zero fica sem rateio. Toda a conta usa `MathContext(20, HALF_UP)`.

**RN-11. Alíquota interestadual.** Pela origem da mercadoria e pelas UFs (`service/fiscalcheck/OperationCheck.java:93-101`; o comentário cita a Res. Senado 22/1989 e 13/2012):
- origem 1, 2, 3 ou 8: 4%;
- emitente em SP, RJ, MG, PR, SC ou RS: 7%;
- demais: 12%.

Sem origem ou sem UF, vale a alíquota destacada no item. Se essa também for zero ou faltar, a interestadual é desconhecida e o item fica `SEM_REGRA` (exceto na carga líquida, que não precisa dela). `FronteiraCalculator.java:498-506, 246-248`.

**RN-12. Crédito de origem** (`FronteiraCalculator.java:320-326`):
```
limite  = produto × inter
crédito = limite                             se fornecedor do Simples e ICMS destacado = 0 (crédito outorgado)
crédito = min(ICMS destacado; limite)        nos demais casos
```
O fornecedor é do Simples quando o item tem CSOSN ou quando o CRT do emitente é 1, 2 ou 4. O crédito nunca incide sobre o frete. O comentário cita "CFLA" para o limite.

**RN-13. Carga líquida (`SIMPLES_PERCENTUAL`)** (`FronteiraCalculator.java:432-452, 231-245`):
- Escolha da regra: com credenciamento no extrato, vale a regra cuja `descricao`, normalizada (sem acento, espaços colapsados, maiúsculas), seja igual ao credenciamento, **qualquer que seja o regime da empresa**. Sem essa regra, e só se a empresa for do Simples, vale a regra sem descrição (art. 363-A). Se não houver regra sem descrição, vale a única regra de carga líquida existente.
- Fórmula: `ICMS = partida × percentual`, **sem abater crédito**.
- Arredondamento: valor = HALF_UP com 2 casas; mínimo = DOWN com 2 casas; máximo = HALF_UP com 2 casas.
- Empresa do Simples sem regra aplicável: `SEM_REGRA` ("Empresa do Simples sem a carga líquida (art. 363-A) na tabela...").
- Precedência: `ST_MVA` e `AGREGACAO` vencem a carga líquida (RN-09).

**RN-14. Regra de carga líquida criada pelo extrato.** Ao montar o relatório de um mês com extrato, se o extrato tiver credenciamento, a taxa predominante (RN-06) for maior que 0 e não existir nenhuma regra `SIMPLES_PERCENTUAL` (geral ou da empresa, **inclusive excluída**) com a mesma descrição normalizada, o sistema cria uma regra **da empresa** com `descricao = credenciamento`, `percentual = taxa predominante` e um fundamento que diz que foi criada na importação. A regra fica "a conferir". `service/fronteira/FronteiraRuleService.java:146-167`; chamada em `FronteiraService.java:279-281`.

**RN-15. Cálculo padrão com alíquota interna** (`SEM_ENCERRAMENTO`, `USO_CONSUMO`, `MERCADORIA`, `ST`). É obrigatório haver regra `ALIQUOTA_INTERNA` que case. Sem ela, o item fica `SEM_REGRA` ("Sem alíquota interna de PE na tabela de ICMS para o NCM ..."). `FronteiraCalculator.java:251-258`.
- **Sem encerramento e uso e consumo / ativo (DIFAL)** (`:283-291`):
  ```
  ICMS = max(0; partida × interna − crédito)
  ```
  Arredondamento: valor e máximo HALF_UP com 2 casas; mínimo DOWN com 2 casas. Uso e consumo e ativo nunca levam agregação nem carga líquida, mesmo em empresa credenciada.
- **Mercadoria com agregação** (`AGREGACAO`):
  ```
  base = partida × (1 + agregação)
  ICMS = max(0; base × interna − crédito)
  ```
  A regra casa como as demais (RN-17) e também pela região de origem: `SUL_SUDESTE` = SP, RJ, MG, ES, PR, SC, RS; `DEMAIS` = o resto; vazio = qualquer. Casa ainda por `fornecedorSimples` (true, false ou vazio = qualquer). `:81-84, 415-424`.
- **ST com MVA** (`ST_MVA`) (`:260-273, 341-368`):
  ```
  MVA ajustada = [(1 + MVA) × (1 − inter) ÷ (1 − interna)] − 1
  ICMS-ST      = max(0; partida × (1 + MVA ajustada) × interna − crédito)
  ```
  É calculado por 4 métodos de arredondamento:
  - A: sem arredondar até o fim, final HALF_UP;
  - B: cada etapa HALF_UP com 2 casas;
  - C: MVA ajustada HALF_UP com 4 casas, depois cada etapa HALF_UP com 2 casas;
  - D: cada etapa DOWN com 2 casas.

  `valor = C`, `mínimo = menor de A..D`, `máximo = maior de A..D`.
- **Base dupla (informativa)**, só para `SEM_ENCERRAMENTO` e `USO_CONSUMO`: `(partida − ICMS destacado) ÷ (1 − interna) × interna − crédito`, HALF_UP com 2 casas e mínimo 0. Vai para a memória com a "exposição" (base dupla − resultado). **Não muda o resultado**. O comentário cita "arts. 333/336". `:298-304`.

**RN-16. Fórmula própria (`FORMULA`).** `service/fronteira/FormulaEvaluator.java`; `FronteiraCalculator.java:209-229`.
- Gramática própria, sem engine de script. Aceita:
  - números com vírgula ou ponto e o sufixo `%` (÷100);
  - `+ - * /` (também `×` e `÷`) e parênteses;
  - as funções `min(a; b; ...)`, `max(a; b; ...)` e `arred(x)` (2 casas, HALF_UP), com argumentos separados por `;`.
- Variáveis (todas as taxas como fração): `partida`, `valor_produto`, `ipi`, `despesas`, `frete`, `icms_origem`, `credito_sn`, `credito`, `aliquota_origem`, `inter`, `interna`, `mva`, `mva_ajustada`, `agregacao`, `st_retido`, `percentual`. `FormulaEvaluator.java:30-50`.
- Limite de 500 caracteres. Nome desconhecido, caractere inválido, divisão por zero ou sintaxe errada geram erro com mensagem legível. Variável sem valor conta como 0.
- Para salvar, a fórmula é validada com todas as variáveis em 0,1 e `partida = 1000`. `FormulaEvaluator.java:113-118`.
- Resultado: valor e máximo HALF_UP com 2 casas; mínimo DOWN com 2 casas. **Não há piso em 0.** Erro em tempo de cálculo vira `SEM_REGRA` com o motivo.
- `aplicaEm` aceita: vazio ou `TODOS` (qualquer), `SEM_ENCERRAMENTO`, `ST`, `CARGA_LIQUIDA`, `USO_CONSUMO` ou `MERCADORIA`. `FronteiraRuleService.java:277-291`.
- Endpoint de teste: calcula com os valores de um item (`variaveis`) ou com a amostra fixa (`FormulaEvaluator.SAMPLE`: item de R$ 1.000,00 a 12%, interna 20,5%, MVA 40%, carga 5,5%).

**RN-17. Escolha da regra (a mais específica).** Para cada tipo, só contam regras ativas, não excluídas e vigentes na **data de emissão da nota** (`validFrom`/`validUntil` inclusivos; sem datas = sempre). Todo critério preenchido tem de casar:
- NCM como prefixo;
- CEST igual;
- código do produto igual, sem diferenciar maiúsculas;
- CNPJ do fornecedor igual.

Pontuação: produto +1000, fornecedor +500, CEST +100, NCM +10 + nº de dígitos, empresa +1. No empate vence a mais recente (`createdDate`). `FronteiraCalculator.java:385-409`; `domain/fronteira/FronteiraRule.java:111-114`.

**RN-18. Agregação na nota.** O valor, o mínimo e o máximo da nota são a soma dos itens que não estão `SEM_REGRA`. Se qualquer item estiver `SEM_REGRA`, a nota inteira fica `semRegra = true`. A nota é `conferida` só se todos os itens calculados usaram regras conferidas. `SEM_ANTECIPACAO` e `ST_RETIDO` contam como conferidos. `FronteiraCalculator.java:115-138`.

**RN-19. Memória de cálculo.** Cada item devolve uma lista de linhas em português com:
- a partida (produto, IPI, frete, outras despesas);
- o CFOP de entrada da regra do produto, quando existe;
- a interestadual (UF de origem, UF de destino e origem da mercadoria);
- a interna e o escopo da regra ("geral", "desta empresa", NCM etc., com ", a conferir" quando ainda não conferida);
- a linha do crédito;
- a MVA ajustada e o ICMS-ST, ou a carga líquida, ou a fórmula com o valor de cada variável usada e o resultado;
- a linha informativa da base dupla;
- o motivo do "sem regra", quando houver.

O item também devolve o mapa `variables`, que a tela usa para testar uma fórmula naquele item. `FronteiraCalculator.java:142-292`.

### Resultado por nota e resumo

**RN-20. Resultado da nota** (`FronteiraService.java:256-257, 385-408`), com `TOL = 0,03`, avaliado na ordem abaixo:
1. sem XML → `PENDENTE`;
2. XML cancelado → `COBROU_A_MAIS` se o cobrado for maior que 0, senão `CONFERE` (nota cancelada não é calculada);
3. sem itens → `PENDENTE`;
4. algum item sem regra → `SEM_REGRA`;
5. cobrado = 0 e Taxify > 0,03 → `NAO_CALCULOU`;
6. cobrado > máximo + 0,03 → `COBROU_A_MAIS`;
7. cobrado < mínimo − 0,03 → `COBROU_A_MENOS`;
8. senão → `CONFERE`.

`diferenca = cobrado − valor (método C)`. Fica nula em `SEM_REGRA`, em `PENDENTE` e quando não há cálculo (o que inclui a nota cancelada).

**RN-21. Resumo do mês.** `FronteiraService.java:375-381, 410-448`; `dto/fronteira/FronteiraDTOs.java:61-76`.
- `icmsCalculado`: soma dos cálculos das notas sem `semRegra`.
- `cobradoAMais`: soma das diferenças de `COBROU_A_MAIS`, quando a diferença não é nula.
- `cobradoAMenos`: soma de `−diferença` de `COBROU_A_MENOS` e de `NAO_CALCULOU`. É risco, não economia.
- `aConferir`: notas cujo cálculo usou regra não conferida, fora `SEM_REGRA` e `PENDENTE`.
- `valorGuia`: soma do ICMS devido das notas − `cobradoAMais`.

### Tabela de ICMS (regras)

**RN-22. Tipos de regra** (`enumerated/FronteiraRuleKind.java`; validação em `FronteiraRuleService.java:212-298`):

| Tipo | Campo obrigatório | Validação |
|---|---|---|
| `ALIQUOTA_INTERNA` | `aliquota` (%) | 0 a 100. Sem NCM é a modal; com NCM é exceção |
| `ST_MVA` | `mva` (%) | 0 a 1000. Exige NCM, CEST ou código do produto |
| `AGREGACAO` | `agregacao` (%) | 0 a 1000 (20 = coeficiente 1,20). Exige NCM, CEST ou código; região `SUL_SUDESTE`, `DEMAIS` ou vazio; `fornecedorSimples` true, false ou vazio |
| `SIMPLES_PERCENTUAL` | `percentual` (%) | 0 a 100. `descricao` = credenciamento como sai no extrato; vazio = Simples (art. 363-A) |
| `FORMULA` | `formula` | RN-16; `aplicaEm` opcional |

Campos comuns:
- `descricao` (255);
- filtros: NCM (só dígitos, 10), CEST (10), código do produto (60) e CNPJ do fornecedor (14 dígitos);
- `fundamento` (500) e `link` (500);
- vigência, que não pode terminar antes de começar.

Os percentuais ficam guardados em % (20,5) com `decimal(9,4)`. O cálculo divide por 100 (`fraction`). Ao salvar, os campos que não pertencem ao tipo são zerados.

**RN-23. Escopo, conferência e ciclo de vida.**
- Uma regra é **geral** (`company_id` nulo) ou **da empresa**. A regra geral é criada pelo endpoint `/regras/geral` e não pode ter empresa. A regra da empresa exige empresa. Cada endpoint só altera regras do seu escopo. `FronteiraRuleService.java:182-210`.
- Qualquer alteração (`update`) devolve a regra para "a conferir": zera `reviewedAt` e `reviewedBy`. Conferir grava o usuário e o horário. Ligar ou desligar não muda o estado de conferência. Excluir é lógico (`deleted = true`).
- **Alíquota modal**: se não existir regra `ALIQUOTA_INTERNA` geral sem NCM (excluída conta), o sistema cria uma de **20,5%** "a conferir" com fundamento "Lei 15.730/2016 - alíquota interna modal de PE (a conferir)". `FronteiraRuleService.java:31-32, 169-180`.
- A regra não conferida continua calculando, mas o resultado aparece como "a conferir" (RN-18, RN-21).

**RN-24. Quem altera o quê** (`rest/FronteiraResource.java`; `common/PlatformAdminService.java`):

| Ação | Permissão no endpoint | Checagem extra |
|---|---|---|
| Ver comparativo, relatório, pedido de revisão, PDF do extrato, regras, variáveis e testar fórmula | `INVOICES_VIEW` | acesso à empresa (`CompanyValidation.checkExist`) |
| Importar extrato, sincronizar com o e-Fisco, buscar memória do e-Fisco | `INVOICES_AUDIT` | empresa de PE |
| Criar, alterar ou excluir regra **da empresa** | `INVOICES_AUDIT` | acesso à empresa |
| Criar, alterar ou excluir regra **geral** | `FISCAL_RULES_MANAGE` | **admin da plataforma** |
| Conferir ou ligar uma regra | `FISCAL_RULES_MANAGE` | se a regra for geral, **admin da plataforma** |

Admin da plataforma = login listado em `taxify.platform.admin-logins` (`PLATFORM_ADMIN_LOGINS`) **e** perfil `ADMIN`. Com a lista vazia, ninguém altera as regras gerais. Os demais recebem 403 ("Regras gerais valem para todos os escritórios: só a CS Hub altera."). O token leva a claim `platformAdmin`, que o front usa para mostrar ou esconder os botões.

### Integração com o e-Fisco

**RN-25. Rotina de sincronização.** `service/fronteira/EfiscoSyncService.java`, `EfiscoSyncScheduler.java`.
- **Agendada**: `efisco.sync.cron` (padrão `0 0 7 * * *`), desligável com `efisco.sync.enabled=false`. Roda numa virtual thread e não duplica se a anterior ainda estiver rodando. Para cada certificado de contador ativo e não vencido, pega as empresas de PE do escritório e busca o mês anterior e o atual. O mês passado com extrato **fechado** não é buscado de novo. Fechado quer dizer vencimento anterior a hoje, ou pago > 0 com a recolher zero ou nulo (`FronteiraService.java:608-615`).
- **Manual** ("Sincronizar com e-Fisco"): uma empresa e um período.
- Em cada execução:
  - sem certificado → `SEM_CERTIFICADO`;
  - certificado vencido → `ERRO`;
  - outra execução do mesmo escritório em andamento → `EM_ANDAMENTO`;
  - empresa sem IE de PE → `SEM_IE`;
  - o e-Fisco não gerou extrato → `SEM_EXTRATO` (com a mensagem dele);
  - o extrato é igual ao importado (mesmo nº, mesma quantidade de notas, mesmos a recolher e pago, mesma soma do ICMS devido) → `SEM_MUDANCA`, e o PDF é guardado se ainda faltar;
  - senão importa (RN-03) → `IMPORTADO`.
- Pausa de 1,5 s entre empresas. Desafio da Cloudflare ou HTTP 403, 429 ou 503 → `BLOQUEADO` e a execução para; nada é contornado. Login recusado → `BLOQUEADO`. Resultado geral: `OK`, ou `PARCIAL` quando há `ERRO` ou `SEM_IE`. O resultado fica gravado no certificado (`recordSync`).

**RN-26. Cliente do e-Fisco** (`integration/efisco/EfiscoClient.java`):
1. **Login**: com o certificado A1 do contador no handshake TLS (`PRGerenciarLoginUsuario`, `evento=tratarLoginCertificado`). A sessão alcança as empresas vinculadas ao contador.
2. **Extrato**: serviço `cd_menu=190696`, "Calculadas" (`emitirRelatorioExtratoCalculadas`), com `DtPeriodoFiscal=mm/aaaa` e a IE mascarada (9 dígitos → `NNNNNNN-NN`). Depois `exibirDocumento` devolve o PDF.
3. **Memória**: serviço `cd_menu=190701` (`PRConsultarNotaFiscalMemoria`), filtrado pelo registro. Quando vem uma lista, abre a memória mais recente (4ª parte da chave = data e hora de geração).

**RN-27. Memória de cálculo do e-Fisco.** É buscada pelo registro da linha do extrato e guardada em `efisco_memories` (única por empresa e registro, substitui a anterior), com o texto completo. O parser lê:
- chave de acesso;
- "regras de ..." e a tabela "Tipos de Cálculo";
- e, por "Cálculo Mercadoria", as variáveis: `ValorPartidaAntecipado`, `CoefAgregacao*`, `BaseCalculoItemAntecipado`, `AliquotaInterna`, `AliquotaInterEstadual`, `PercentualCargaLiquida*`, `CreditoUtilizado` e `ICMSAntecipadoItem`.

O total do e-Fisco é a soma dos `ICMSAntecipadoItem`. `service/fronteira/EfiscoMemoryService.java`; `integration/efisco/EfiscoMemoryParser.java`.

### Saídas

**RN-28. Relatório do mês (PDF/CSV).** Sem extrato, o PDF só diz que não há extrato. Com extrato, traz:
- os cards: cobrado, cálculo do Taxify, cobrado a mais e guia ajustada;
- as observações: cobrado a menos como risco, já pago, a conferir, sem regra e pendentes;
- as "Notas para pedir revisão" (toda linha `COBROU_A_MAIS`);
- todas as notas com o cálculo usado;
- as regras e fórmulas usadas, com o fundamento.

O CSV traz as mesmas notas. `service/fronteira/FronteiraReportPdf.java:102-192, 249`.

**RN-29. Pedido de revisão (M8).** `service/fronteira/FronteiraRevisionRequestPdf.java`; `FronteiraService.java:223-248`.
- Sem extrato → 409 `error.fronteira.revision.no.extract`. Sem nota a revisar → 409 `error.fronteira.revision.none`.
- Entram só as linhas `COBROU_A_MAIS` com `diferenca > 0`.
- Totais:
  - `cobradoNasNotas` = soma do ICMS devido;
  - `devidoNasNotas` = soma do cálculo (método C);
  - `contestado` = soma das diferenças;
  - `valorExtrato` = ICMS a recolher do extrato (se for nulo, a soma do ICMS devido);
  - `extratoAposRevisao` = `valorExtrato − contestado`.
- O fundamento legal é **só** o que está escrito nas regras usadas. Sem fundamento, o texto traz o marcador "[fundamento não informado na regra - informe antes de protocolar]". O nome do serviço do e-Fisco fica como marcador para o contador preencher.
- Aviso quando há regra "a conferir" (as notas afetadas ficam destacadas). Leva a memória do Taxify por item, local e data, e espaço para assinatura (nome, CPF e CRC). É sempre "RASCUNHO".
- Mesmo conteúdo em PDF (A4 paisagem) e em texto UTF-8.

## Critérios de aceite

- CA-01 (RN-01). **Dado** um PDF que não é o extrato do ICMS antecipado, **quando** importo, **então** recebo 400 com "O PDF não parece ser o extrato...".
- CA-02 (RN-02). **Dado** uma empresa com IE cadastrada diferente da do extrato, **quando** importo, **então** recebo 400 `error.fronteira.extract.other.ie`. **Dado** uma empresa de outra UF, **então** recebo 400 `error.fronteira.company.not.pe`.
- CA-03 (RN-03). **Dado** um extrato de 2026-10 já importado, **quando** importo outro de 2026-10, **então** as notas do primeiro são substituídas e continua existindo um extrato só no período.
- CA-04 (RN-04, RN-05). **Dado** uma linha do extrato sem XML na base, **quando** abro o comparativo, **então** a linha vem com `SEM_XML` (ou `SO_RESUMO`, se houver resumo pendente) e com o resultado `PENDENTE`.
- CA-05 (RN-05). **Dado** que o XML da linha está cancelado e o extrato cobra mais de R$ 0,00, **então** a linha vem com `NOTA_CANCELADA` e `COBROU_A_MAIS`, e com a diferença nula.
- CA-06 (RN-06). **Dado** 3 ou mais notas a 5,5% e uma a 12%, **então** a de 12% vem com `FORA_DA_TAXA` e `diferencaParaTaxa = devido − valor × 5,5%`. **Dado** menos de 3 notas com taxa, **então** não há taxa predominante e nenhuma nota vem com `FORA_DA_TAXA`.
- CA-07 (RN-07). **Dado** uma entrada de fornecedor de outra UF emitida no mês e fora do extrato, **então** ela aparece em `semCobranca` com cálculo e entra na `previaIcms`. Entrada de PE ou nota cancelada não aparece.
- CA-08 (RN-09). **Dado** um item com CFOP de devolução (x2xx) ou remessa (x9xx, fora 910 e 949), **então** o tratamento é `SEM_ANTECIPACAO` com valor 0, mesmo que exista fórmula para "qualquer" tratamento.
- CA-09 (RN-09). **Dado** um item com `vICMSST > 0`, **então** o tratamento é `ST_RETIDO` com valor 0.
- CA-10 (RN-09, RN-15). **Dado** um item que a regra fiscal do produto lança com CFOP x551 ou x556, **então** o tratamento é `USO_CONSUMO`, com `partida × interna − crédito`, sem carga líquida mesmo em empresa credenciada.
- CA-11 (RN-13). **Dado** um extrato com credenciamento "X" e uma regra `SIMPLES_PERCENTUAL` com descrição "x" (diferença só de caixa ou acento), **então** os itens sem ST e sem agregação são calculados por `partida × percentual`, sem crédito, em qualquer regime.
- CA-12 (RN-14). **Dado** um extrato com credenciamento e taxa predominante, sem regra de carga líquida com essa descrição (nem excluída), **quando** o comparativo é montado, **então** é criada uma regra da empresa "a conferir" com o percentual da taxa predominante. **Dado** que uma regra assim já existiu e foi excluída, **então** nada é criado.
- CA-13 (RN-15). **Dado** um item sem regra de alíquota interna que case, **então** o item fica `SEM_REGRA` e a nota fica `SEM_REGRA`, nunca `COBROU_A_MAIS`.
- CA-14 (RN-15). **Dado** um item com regra `ST_MVA`, **então** a memória mostra a MVA ajustada com 2 casas e o valor é o método C, com mínimo e máximo dos métodos A a D.
- CA-15 (RN-16, RN-17). **Dado** duas fórmulas que casam com o item, uma por NCM e outra por código do produto, **então** vale a do produto, e o item fica "a conferir" se essa fórmula não estiver conferida.
- CA-16 (RN-16). **Dado** uma fórmula com variável desconhecida, **quando** salvo, **então** recebo 400 `error.fronteira.rule.invalid` com a lista de variáveis aceitas.
- CA-17 (RN-17). **Dado** uma regra com `validUntil` anterior à emissão da nota, **então** ela não é usada.
- CA-18 (RN-20). **Dado** um cálculo de R$ 85,00 (faixa 85,00 a 85,00), **quando** o extrato cobra 85,02, **então** o resultado é `CONFERE`. Com 150,00 é `COBROU_A_MAIS` e diferença 65,00. Com 50,00 é `COBROU_A_MENOS`. Com 0,00 é `NAO_CALCULOU`.
- CA-19 (RN-21). **Dado** o caso do CA-18, **então** `cobradoAMais = 65,00`, `cobradoAMenos = 120,00` e `valorGuia = soma do devido − 65,00`.
- CA-20 (RN-23). **Dado** uma regra conferida, **quando** ela é alterada, **então** volta a "a conferir".
- CA-21 (RN-24). **Dado** um usuário com `FISCAL_RULES_MANAGE` que não é admin da plataforma, **quando** cria, altera, exclui, confere ou liga uma regra geral, **então** recebe 403.
- CA-22 (RN-25). **Dado** um escritório sem certificado do contador, **quando** sincronizo, **então** recebo `SEM_CERTIFICADO` com a orientação de cadastrar o e-CPF em Integrações.
- CA-23 (RN-25). **Dado** que o e-Fisco devolve o mesmo extrato já importado, **então** o status é `SEM_MUDANCA` e o extrato não é reimportado.
- CA-24 (RN-25, RN-26). **Dado** um desafio da Cloudflare, **então** a execução para com `BLOQUEADO`, e as empresas já importadas ficam como estão.
- CA-25 (RN-29). **Dado** um extrato sem nota `COBROU_A_MAIS` com diferença positiva, **quando** peço o rascunho, **então** recebo 409 `error.fronteira.revision.none`.
- CA-26 (RN-29). **Dado** duas notas cobradas a mais, **então** o rascunho lista só essas duas, com os totais `contestado` e `extratoAposRevisao` e o fundamento das regras usadas (ou o marcador de fundamento ausente).

## Casos de teste com números fictícios

Premissas comuns: empresa de PE, interna modal de 20,5% (regra conferida), destino PE, origem da mercadoria 0, salvo indicação. Coluna "Coberto?": **Sim** = há teste automatizado do cenário (com outros números); **Parcial** = parte do cenário; **Não** = sem teste.

| # | Cenário | Entrada | Cálculo esperado | Resultado | Coberto? |
|---|---|---|---|---|---|
| CT-01 | Carga líquida do credenciamento (tecidos), regime normal | SP, produto 2.000,00, ICMS destacado 140,00; regra `SIMPLES_PERCENTUAL` "TECIDOS X" 5,5%; extrato com credenciamento "Tecidos X" | 2.000,00 × 0,055 = 110,00, sem crédito | cobrado 110,00 → `CONFERE` | Sim (`FronteiraCalculatorTest.credenciamentoCargaLiquidaWhateverTheRegime`) |
| CT-02 | Carga líquida no limite da tolerância | produto 1.234,50 | 67,8975 → valor 67,90, mín. 67,89, máx. 67,90 | 67,93 → `CONFERE`; 67,94 → `COBROU_A_MAIS` (dif. 0,04); 67,86 → `CONFERE`; 67,85 → `COBROU_A_MENOS` | Parcial (tolerância em `FronteiraServiceTest.theChargeAgainstTaxifysCalculation`) |
| CT-03 | Frete rateado | 2 itens: 600,00 e 400,00; frete da nota 50,00; carga 5,5% | item 1: 630,00 × 0,055 = 34,65; item 2: 420,00 × 0,055 = 23,10; nota 57,75 | — | Sim (`freightIsSharedByProductValue`) |
| CT-04 | Sem encerramento, regime normal sem credenciamento | CE (12%), produto 2.000,00, ICMS 240,00 | 2.000,00 × 0,205 − 240,00 = 170,00 | cobrado 170,00 → `CONFERE` | Sim (`regimeNormalWithoutEncerramento`) |
| CT-05 | Crédito limitado à interestadual | CE (12%), produto 1.000,00, ICMS destacado 150,00 | crédito = min(150,00; 120,00) = 120,00; 205,00 − 120,00 = 85,00 | — | Não |
| CT-06 | Uso e consumo (DIFAL) pela regra fiscal do produto | SP (7%), produto 1.000,00, IPI 50,00, ICMS 70,00; regra do produto CFOP 2556; empresa credenciada | partida 1.050,00; 1.050,00 × 0,205 − 70,00 = 145,25. Base dupla informativa: (1.050,00 − 70,00) ÷ 0,795 × 0,205 − 70,00 = 182,70 (exposição 37,45) | `USO_CONSUMO` 145,25 | Sim, cenário com CFOP 1551 (`anAtivoByTheProductRuleIsOnlyTheDiferencial`) |
| CT-07 | ST com MVA | RJ (7%), produto 1.000,00, ICMS 70,00; `ST_MVA` 40% | MVA aj. = 1,40 × 0,93 ÷ 0,795 − 1 = 63,77%. C: 1.637,70 × 0,205 = 335,73 − 70,00 = 265,73. A e B: 265,74. D: 265,73 | valor 265,73, faixa 265,73 a 265,74; cobrado 265,77 → `CONFERE`; 265,78 → `COBROU_A_MAIS` (dif. 0,05) | Sim, cenário (`stOfLocksFromRioDeJaneiro`) |
| CT-08 | ST com fornecedor do Simples sem ICMS destacado | RN (12%), produto 1.000,00, CSOSN 102; `ST_MVA` 40% | crédito outorgado = 1.000,00 × 12% = 120,00 | `ST` ≈ 197,69 | Sim (`stFromASimplesSupplierTakesTheCreditoOutorgado`) |
| CT-09 | Mercadoria com agregação | MG, fornecedor do Simples, produto 1.000,00; `AGREGACAO` NCM 8504, 20%, `SUL_SUDESTE`, Simples = sim | base 1.200,00 × 0,205 = 246,00 − crédito outorgado 70,00 = 176,00 | `MERCADORIA` 176,00 | Sim, cenário (`efiscoMemoryOfNote1810Agregacao`) |
| CT-10 | Agregação de outra região | o mesmo do CT-09, com a regra só `DEMAIS` | a regra não casa → `SEM_ENCERRAMENTO`: 1.000,00 × 0,205 − 70,00 = 135,00 | — | Sim (`anAgregacaoOfAnotherRegionIsNotUsed`) |
| CT-11 | Devolução | CFOP do fornecedor 6202 | entrada 2202 → operação 202 | `SEM_ANTECIPACAO` 0,00 | Sim (`devolutionAndStAlreadyWithheldHaveNoAntecipacao`) |
| CT-12 | ST já retido | CFOP 6403, vICMSST 150,00 | — | `ST_RETIDO` 0,00 | Sim (o mesmo teste) |
| CT-13 | Bonificação × remessa | 6910 e 6949 com carga 5,5%; 6915 | 6910 e 6949 calculam como compra; 6915 não | 6915 → `SEM_ANTECIPACAO` | Sim (`bonificacaoAndOutrasAreChargedLikeAPurchase`) |
| CT-14 | Importado | origem 1, SP | interestadual 4% | — | Sim (`importedGoodsUseFourPercent`, `importedGoodsTakeTheFourPercentCredit`) |
| CT-15 | Nota cancelada cobrada | XML cancelado, extrato cobra 55,00 | não calcula | `NOTA_CANCELADA` + `COBROU_A_MAIS`, diferença nula, fora de `cobradoAMais` e do pedido de revisão | Parcial (só a flag, em `theNotesOffTheUsualRateTheMissingXmlsAndTheCanceledOnes`) |
| CT-16 | Nota cancelada sem cobrança | XML cancelado, extrato 0,00 | — | `CONFERE` | Não |
| CT-17 | Nota no extrato sem XML | chave sem documento | — | `SEM_XML`, `PENDENTE` | Sim (flag) |
| CT-18 | Nota no extrato só com resumo | resumo pendente com a mesma chave | — | `SO_RESUMO`, `PENDENTE` | Não |
| CT-19 | Entrada fora do extrato | CE, 1.000,00, ICMS 120,00, sem extrato no mês | 85,00 | `semCobranca` com 1 nota, `previaIcms` 85,00, "a conferir" se a modal não estiver conferida | Sim (`withoutAnExtractTheMonthsEntriesComeWithThePrevia`) |
| CT-20 | Extrato cobra zero | Taxify 170,00; cobrado 0,00 | — | `NAO_CALCULOU`, `cobradoAMenos` +170,00 | Sim (`theChargeAgainstTaxifysCalculation`) |
| CT-21 | Extrato cobra zero e Taxify quase zero | Taxify 0,03; cobrado 0,00 | 0,03 não passa de 0,03 | `CONFERE`. Com Taxify 0,04 → `NAO_CALCULOU` | Não |
| CT-22 | Valor da NF zero no extrato | valor 0,00, devido 0,00 | taxa nula | sem `FORA_DA_TAXA`, fora da taxa predominante | Não |
| CT-23 | Fora da taxa | 1.000,00/55,00; 2.000,00/110,00; 500,00/27,50; 1.000,00/120,00 | predominante 5,5; última: 12% | `FORA_DA_TAXA`, `diferencaParaTaxa` 65,00 | Sim (`theNotesOffTheUsualRateTheMissingXmlsAndTheCanceledOnes`) |
| CT-24 | Nota pequena com taxa arredondada | 3,00/0,17 (5,6667%) em extrato com predominante 5,5 | esperado 3,00 × 5,5% = 0,165 → 0,17; diferença 0 ≤ 0,05 | sem `FORA_DA_TAXA` | Sim, cenário com 2,00/0,11 |
| CT-25 | Sem alíquota interna | nenhuma `ALIQUOTA_INTERNA` | — | `SEM_REGRA` | Sim (`withoutARateItIsSemRegraNeverCobrouAMais`) |
| CT-26 | Empresa do Simples sem carga líquida | `SIMPLES_NACIONAL`, sem regra `SIMPLES_PERCENTUAL` | — | `SEM_REGRA` "Empresa do Simples sem a carga líquida (art. 363-A)..." | Não |
| CT-27 | Fórmula mais específica | NCM 6109 `partida * 10%`; produto P-1 `(partida - icms_origem) * 5,5%`; SP, 1.000,00, ICMS 70,00 | (1.000,00 − 70,00) × 0,055 = 51,15 | `FORMULA` 51,15, "a conferir" se a do produto não estiver conferida | Sim (`theAccountantsFormulaWinsAndTheMostSpecificOneIsUsed`) |
| CT-28 | Fórmula com `aplicaEm` | `aplicaEm = SEM_ENCERRAMENTO` | substitui só no regime normal; ST e devolução seguem o padrão | — | Sim (`aGeneralFormulaReplacesOnlyTheTreatmentItIsFor`) |
| CT-29 | Fórmula fora da vigência | `validUntil` = 30/09; nota de 10/10 | — | cálculo padrão | Sim (`aFormulaOutOfItsValidityIsNotUsed`) |
| CT-30 | Fórmula com resultado negativo | `partida * interna - credito - 1000` | valor negativo (sem piso) | — | Não |
| CT-31 | Regra da empresa × geral | geral NCM 2203 23%; da empresa NCM 2203 25% | vale a da empresa | — | Sim (`theCompanysRateBeatsTheGlobalOne`) |
| CT-32 | Carga líquida criada pelo extrato | credenciamento "Y", predominante 5,5, sem regra "Y" | cria regra da empresa 5,5% "a conferir" | — | Sim (`FronteiraCargaLiquidaDefaultTest`) |
| CT-33 | Pedido de revisão | a recolher 1.000,00; nota A: cobrado 150,00, calc. 85,00; nota B: cobrado 300,00, calc. 250,00; nota C `CONFERE` | contestado 115,00; devido 335,00; cobrado 450,00; após revisão 885,00 | o rascunho lista A e B | Sim, cenário (`FronteiraRevisionRequestPdfTest`) |
| CT-34 | Pedido sem nota a mais | só `CONFERE` | — | 409 | Parcial (`withoutNotesChargedBeyondThereIsNoDraft` testa `hasItems`, não o 409) |
| CT-35 | IE de outro contribuinte / empresa de outra UF | — | — | 400 | Sim (`anExtractOfAnotherStateRegistrationIsRefused`, `onlyPernambucoCompanies`) |
| CT-36 | Regra geral por não-admin | `FISCAL_RULES_MANAGE` sem estar em `PLATFORM_ADMIN_LOGINS` | — | 403 | Parcial (`PlatformAdminServiceTest` cobre a checagem, não o `FronteiraRuleService`) |

## Endpoints e telas do front

Base: `/api/v1/fronteira` (`rest/FronteiraResource.java`). Todos exigem `INVOICES_VIEW` e acesso à empresa.

| Método e caminho | Permissão extra | O que faz |
|---|---|---|
| `GET /?companyId&periodo` | — | comparativo (`Report`); sem período = o último importado |
| `POST /importar?companyId` (multipart `file`) | `INVOICES_AUDIT` | importa o PDF |
| `POST /sincronizar?companyId&periodo` | `INVOICES_AUDIT` | busca no e-Fisco e importa; devolve `{sync, report}` |
| `GET /extrato/pdf?companyId&periodo` | — | PDF guardado (404 se não houver) |
| `GET /memoria?companyId&registro` | — | memória do e-Fisco já guardada (204 se nunca buscada) |
| `POST /memoria/buscar?companyId&registro` | `INVOICES_AUDIT` | busca a memória agora |
| `GET /relatorio.pdf`, `GET /relatorio.csv` | — | relatório do mês |
| `GET /pedido-revisao.pdf`, `GET /pedido-revisao.txt` | — | rascunho do pedido (409 se não houver o que pedir) |
| `GET /regras?companyId` | — | regras gerais + da empresa (sem empresa: só as gerais) |
| `GET /regras/variaveis` | — | variáveis da fórmula com a descrição |
| `POST /regras/testar` `{formula, variaveis}` | — | valida e calcula |
| `POST/PUT/DELETE /regras[/{id}]` | `INVOICES_AUDIT` | regra da empresa |
| `POST/PUT/DELETE /regras/geral[/{id}]` | `FISCAL_RULES_MANAGE` + admin da plataforma | regra geral |
| `POST /regras/{id}/conferencia?conferida` | `FISCAL_RULES_MANAGE` (+ admin, se a regra for geral) | conferir ou desconferir |
| `POST /regras/{id}/ativa?ativa` | idem | ligar ou desligar |

Frontend (`C:\workspace\taxify-front`):
- Rota `/fronteira` (`permissionGuard('INVOICES_VIEW')`), em `src/app/features/fronteira/fronteira.component.{ts,html,scss}`; serviço em `src/app/core/services/fronteira.service.ts`; modelos em `src/app/core/models/fronteira.model.ts`; editor de fórmula em `src/app/features/fronteira/formula-editor/`.
- **Conferência**:
  - seleção de empresa de PE (autocomplete), período (extratos importados e navegação por mês) e situação da última sincronização;
  - botões "Sincronizar com e-Fisco" (só com certificado e `INVOICES_AUDIT`), "Importar extrato do e-Fisco" (modal), "Ver PDF", relatório PDF/CSV e "Pedido de revisão" (modal com texto, copiar e baixar PDF);
  - cards: cobrado no extrato, cálculo do Taxify, cobrado a mais, cobrado a menos e valor da guia ajustada; aviso de "a conferir";
  - filtros rápidos por resultado e flag, tabela das linhas e tabela "Entradas interestaduais do mês que o extrato não cobra" com a prévia;
  - aviso de rodapé: documento de trabalho, pauta/PMPF, frete FOB (CT-e) e alíquotas especiais à parte.
- **Modal do cálculo**:
  - memória por item e faixa mínimo a máximo;
  - busca da memória do e-Fisco e comparação etapa a etapa: tipo de cálculo, partida, coeficiente, carga líquida ou interna, interestadual, crédito e ICMS. A nota "bate" com diferença de até R$ 0,05 entre os dois cálculos (`fronteira.component.ts:656-691`);
  - "Adotar o cálculo do e-Fisco": abre o formulário de regra já preenchido com carga líquida, agregação por NCM de 4 dígitos ou fórmula (`:698-728`);
  - "Usar fórmula própria" no item, com a fórmula do tratamento padrão (`:68-74, 731-748`).
- **Tabela de ICMS e fórmulas**: lista, formulário por tipo, escopo empresa ou geral (a geral só aparece para `FISCAL_RULES_MANAGE` + `platformAdmin`), filtros, vigência, fundamento, link, teste da fórmula com os valores do item, e ações de conferir, ligar e excluir conforme a permissão (`:118-122, 850-857`).

## Dados e integração com o e-Fisco

Tabelas:
- `efisco_extracts` (V45 + `pdf_path` na V52): cabeçalho e totais do extrato. Unique (`company_id`, `periodo`).
- `efisco_extract_notes` (V46): as linhas do extrato como vieram (registro, data de passagem, nº, UF, valor da NF, ICMS devido, CNPJ do emitente). A nota do Taxify é achada na leitura, não fica gravada.
- `fronteira_rules` (V47 + `aplica_em` na V49 + `agregacao`, `regiao_origem` e `fornecedor_simples` na V50): a tabela de ICMS e as fórmulas, com auditoria (`created_by/date`, `last_modified_*`), conferência (`reviewed_by/at`), `enabled` e `deleted`.
- `efisco_memories` (V53): o texto da memória do e-Fisco por empresa e registro.
- O comparativo também lê: `fiscal_xml_document(s)` e itens (XMLs capturados), resumos da SEFAZ pendentes, regras fiscais por produto e divergências do motor de conferência.

Como o extrato chega:
1. **Automático**: com o certificado A1 (e-CPF) do contador do escritório, cadastrado em Integrações (`FirmCertificateService`), o Taxify faz login no e-Fisco como o próprio contador e emite o extrato "Calculadas" de cada IE vinculada a ele (RN-25, RN-26).
2. **Manual**: upload do PDF baixado no e-Fisco (RN-01 a RN-03).

Sigilo e acesso (LGPD e sigilo profissional), como está hoje:
- Toda leitura passa por `CompanyValidation.checkExist`, que restringe o acesso às empresas do escritório do usuário.
- Escrita exige `INVOICES_AUDIT`. A tabela geral exige `FISCAL_RULES_MANAGE` e admin da plataforma.
- O certificado do contador é guardado cifrado quando `CERT_ENCRYPTION_KEY` está configurada. Sem ela, é guardado **sem cifra**, com um aviso no log (`FirmCertificateService.java:77-79`).
- O PDF do extrato fica no storage dos XMLs (local ou S3).
- O teste com extrato real só roda com `EFISCO_SAMPLE_PDF` apontando para um arquivo fora do repositório.

## Configuração

| Chave | Padrão | Efeito |
|---|---|---|
| `efisco.sync.enabled` | `true` | liga ou desliga a rotina agendada |
| `efisco.sync.cron` | `0 0 7 * * *` | horário da rotina |
| `taxify.platform.admin-logins` (`PLATFORM_ADMIN_LOGINS`) | vazio | logins (com perfil ADMIN) que alteram as regras gerais; vazio = ninguém |
| `taxify.secrets.key` (`CERT_ENCRYPTION_KEY`) | vazio | chave de cifra do certificado |
| `FISCAL_XML_STORAGE_TYPE` / `FISCAL_XML_STORAGE_PATH` | `local` | onde fica o PDF do extrato |

Constantes no código (não configuráveis):
- `CALC_TOLERANCE` = 0,03;
- `RATE_TOLERANCE` = 0,15 p.p. e `MONEY_TOLERANCE` = 0,05;
- `MIN_NOTES_FOR_RATE` = 3;
- tolerância de valor diferente = 0,01;
- pausa entre empresas = 1,5 s;
- timeouts do e-Fisco: 30 s de conexão, 60 s no GET e 90 s no POST;
- alíquota modal criada automaticamente = 20,5.

## Testes existentes e lacunas

Existentes (em `src/test/java/br/com/cshub/taxify/`):
- `service/fronteira/FronteiraCalculatorTest` (24 casos): carga líquida, frete, ST, agregação, ativo, devolução, ST retido, bonificação, importado, fórmula, vigência, sem regra, prioridade da empresa. Vários casos reproduzem memórias do e-Fisco.
- `service/fronteira/FronteiraServiceTest`: flags, taxa predominante, IE de outro contribuinte, empresa de outra UF, resultados (confere, a mais, a menos, não calculou) e prévia.
- `service/fronteira/FormulaEvaluatorTest`, `FronteiraRuleServiceTest` (só o teste de fórmula) e `FronteiraCargaLiquidaDefaultTest`.
- `service/fronteira/EfiscoExtractParserTest` (inclui o teste com PDF real, opcional via variável de ambiente).
- `integration/efisco/EfiscoClientTest` (formulário, IE mascarada, Cloudflare, memória mais recente) e `EfiscoMemoryParserTest`.
- `service/fronteira/FronteiraReportPdfTest` e `FronteiraRevisionRequestPdfTest`.
- `common/PlatformAdminServiceTest`.

Lacunas (sem teste automatizado):
- `EfiscoSyncService` (status por empresa, `SEM_MUDANCA`, mês fechado, `EM_ANDAMENTO`, bloqueio no meio da execução) e `EfiscoSyncScheduler`.
- `EfiscoMemoryService` (busca e substituição).
- `FronteiraResource`: permissões por endpoint e 403 de regra geral pelo `FronteiraRuleService`.
- `FronteiraRuleService`: criar, alterar (volta a "a conferir"), excluir, escopo errado, validação por tipo, criação da modal.
- `importExtract` no caminho feliz (substituição do período, PDF guardado, `importedBy`), `sameAsImported`, `extractClosed` e `attachPdfIfMissing`.
- Resultado de nota cancelada (`COBROU_A_MAIS` sem diferença e `CONFERE` com zero), `SO_RESUMO`, Simples sem carga líquida, crédito limitado à interestadual, fórmula negativa, nota com valor zero, limite exato da tolerância.
- Endpoint do pedido de revisão (409).
- Frontend: nenhum `.spec.ts` para `fronteira` nem para `formula-editor`.

## Fora do escopo / limitações (como está)

- Pauta fiscal, PMPF, frete FOB por CT-e e alíquotas especiais não são calculados; o próprio aviso da tela e do pedido de revisão diz isso.
- Só PE. Não há perfil PROIND nem entidade de "perfil por tipo de empresa": o perfil é implícito (regime + credenciamento do extrato + regras da empresa; RN-08).
- O pedido de revisão é só rascunho. Nada é protocolado no e-Fisco, e o nome do serviço de revisão fica como marcador.
- A rotina não resolve desafio da Cloudflare nem tenta de novo; para e registra o motivo.
- A comparação item a item com a memória do e-Fisco só fica lado a lado quando o e-Fisco tem um único grupo "Cálculo Mercadoria". Com vários, compara pelo total.
- A base dupla (arts. 333/336) só aparece como informação.
- O casamento extrato × XML não considera série nem data de passagem.

## Perguntas em aberto

Dúvidas fiscais e inconsistências encontradas no código. Nada aqui foi resolvido; cabe ao Tony decidir.

1. **Base de cálculo.** O contexto do produto diz "a base de cálculo é o valor total dos produtos". O código usa `partida = produto + IPI + frete + seguro + outras despesas` rateados, sem abater o desconto (RN-10, `FronteiraCalculator.java:472-490`). Qual vale para a carga líquida de tecidos (5,5%) e para os demais tratamentos?
2. **Alcance da carga líquida do credenciamento.** Hoje ela vale para todo item do credenciado que não tenha regra `ST_MVA` ou `AGREGACAO` (RN-09). Os 5,5% valem para qualquer NCM, ou só para os produtos da sistemática de tecidos? Qual é a fonte legal (decreto ou portaria do credenciamento)? O código não cita nenhuma.
3. **Regra de carga líquida criada pelo próprio extrato** (RN-14). O percentual vem da taxa predominante cobrada pelo e-Fisco, e o Taxify passa a comparar o e-Fisco com ele mesmo. A regra fica "a conferir", mas calcula. É aceitável no paralelo? Essa criação acontece num `GET` com `INVOICES_VIEW`; o mesmo vale para a alíquota modal.
4. **Alíquota modal de 20,5%.** É criada automaticamente com o fundamento "Lei 15.730/2016 (a conferir)". O valor e a vigência estão corretos para os períodos da conferência? Quais exceções por NCM precisam entrar na tabela?
5. **DIFAL de uso e consumo e ativo.** O código calcula `partida × interna − crédito`, com IPI e frete na partida e crédito só sobre o produto. O comentário e o commit `2ec266d` falam em `partida × (interna − inter)`. As duas fórmulas só coincidem sem IPI e sem frete. A base dupla (arts. 333/336) é só informativa. Qual base vale para o DIFAL?
6. **CFOPs de uso e consumo e ativo.** A lista é {551, 552, 553, 555, 556, 557, 406, 407} (`FronteiraCalculator.java:313`). Está completa? Como o fornecedor normalmente emite 6102, só a regra fiscal do produto leva o item ao DIFAL. É esse o comportamento desejado?
7. **Sem antecipação.** As exclusões são x2xx, x9xx (exceto 910 e 949), 410 e 411. Bonificação (910) e "outras" (949) são cobradas como compra, "como o e-Fisco". Confirmar a regra e a fonte.
8. **"Sem encerramento" do regime normal** é calculado como diferencial (`partida × interna − crédito`). Confirmar a regra (antecipação parcial?) e a fonte.
9. **Nota cancelada cobrada.** Vira `COBROU_A_MAIS` sem diferença: não entra em `cobradoAMais`, não reduz a guia ajustada e não entra no pedido de revisão (que exige `diferenca > 0`), mas aparece na seção "Notas para pedir revisão" do relatório PDF (`FronteiraReportPdf.java:149-151`). Qual é o tratamento correto?
10. **Valor de referência do pedido de revisão.** O rascunho usa o "ICMS a recolher" do extrato menos o contestado; a guia ajustada usa a soma do ICMS devido das notas menos o cobrado a mais. Com parte já paga, os dois números divergem, e o "após revisão" pode ficar negativo. Qual base usar?
11. **Crédito desconhecido.** O comentário da classe diz que em ST o item vira "sem regra" quando não dá para saber o crédito (fornecedor do Simples num XML lido antes da versão 6). O código calcula `creditUnknown`, mas não usa a variável: o item recebe o crédito outorgado. Qual comportamento vale?
12. **Fórmula própria sem piso em zero.** Ela pode dar valor negativo, ao contrário do cálculo padrão (`max(0; ...)`). Deve ter piso?
13. **Tolerâncias.** 0,03 para confere; 0,15 p.p. e 0,05 para fora da taxa; 0,01 para valor diferente; 0,05 no front para "bate" com o e-Fisco. Esses valores servem para o paralelo?
14. **Região da agregação × interestadual.** `SUL_SUDESTE` da agregação inclui ES, mas a regra dos 7% exclui ES (ES → PE paga 12%). Confirmar que é intencional, como o e-Fisco faz.
15. **Vigência das regras.** A vigência é avaliada pela data de emissão da nota, não pela data de passagem nem pelo período do extrato. Está certo?
16. **Casamento sem série.** Duas notas do mesmo fornecedor com o mesmo número e séries diferentes colidem (RN-04). É um risco aceitável?
17. **Perfis por tipo de empresa.** Não existe entidade de perfil (Simples, tecidos, PROIND); o perfil é implícito (RN-08). Esse modelo basta, ou o perfil precisa ser explícito por empresa (por exemplo, quando o credenciamento não vem no extrato)?
18. **Sigilo e LGPD:**
    - a) `POST /memoria/buscar` aceita qualquer `registro` e grava a memória na empresa informada sem checar se o registro pertence a um extrato dela. Dados de outro contribuinte vinculado ao contador podem ficar na empresa errada.
    - b) `EfiscoClient.logDiagnostic` grava no log (CloudWatch) o HTML inteiro da página do e-Fisco quando a memória não vem como esperado. Isso conflita com "nada de dado real em log".
    - c) Sem `CERT_ENCRYPTION_KEY`, o e-CPF do contador fica guardado sem cifra.
    - d) Os testes do calculador citam números de NF e valores de memórias do e-Fisco (o cabeçalho do teste diz "identificação fictícia"). Confirmar que nenhum é dado real de cliente.
19. **Conferência de regra da empresa.** Qualquer usuário com `FISCAL_RULES_MANAGE` do escritório confere as regras da empresa, e ligar ou desligar não volta a regra para "a conferir". Confirmar que está de acordo com o processo do time Fiscal.
