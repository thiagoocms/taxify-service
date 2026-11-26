# Regras fiscais por produto (spec retroativa)

## Status
**Implementado.** Spec as-built: descreve o código como está em 2026-11-26. Não propõe nada novo.

- Migrations: `V14__ProductFiscalRules` (tabela, CFOP/NCM), `V26__ProductFiscalRuleTaxes` (classificação tributária opcional), `V31__ProductFiscalRuleSupplier` (regra por fornecedor, chave única nova), `V32__RecheckRectifiedNotes` (reprocessa notas retificadas quando o CFOP passou a seguir a operação).
- Código principal: `rest/ProductFiscalRuleResource`, `service/ProductFiscalRuleService`, `service/CompanyProductService`, `service/ProductRuleSuggestionService`, `service/ProductFiscalDivergenceService`, `service/EntryCfops`, `service/fiscalcheck/OperationCfops`, `domain/fiscalrule/{ProductFiscalRule, ProductTaxRule, ProductRules}`, `validation/ProductFiscalRuleValidation`, `mapper/ProductFiscalRuleMapper`, `service/ProductFiscalRuleSavedEvent` + `ProductFiscalRuleRectificationListener`.
- Commits: não há commit único da feature; use `git log -- src/main/java/br/com/cshub/taxify/domain/fiscalrule` para o histórico.

Specs relacionadas: [Motor de conferência fiscal](2026-11-26-motor-de-conferencia-fiscal.md) (onde a regra é comparada com a nota) e [Retificação e trilha de auditoria](2026-11-26-retificacao-e-trilha-de-auditoria.md) (onde a regra reescreve o XML).

## Problema e objetivo
O analista fiscal confere à mão se o CFOP, o NCM e os códigos tributários de cada item das notas recebidas batem com o que a empresa deveria escriturar. A regra fiscal por produto guarda, uma vez por produto (e, se preciso, por fornecedor), a classificação correta. A partir dela o Taxify:
1. aponta os itens das notas já baixadas que divergem da regra (`/divergencias` e o check `ProductRuleCheck`);
2. gera a versão retificada do XML (ver spec de retificação).

Como medir (hoje não há métrica implementada): quantidade de produtos com regra por empresa e quantidade de itens divergentes por período, que é o que o endpoint `/divergencias` devolve.

## Usuários e cenário
- **Analista fiscal** (permissão `FISCAL_RULES_MANAGE`): cadastra, edita e exclui regras na tela da empresa, normalmente partindo das sugestões calculadas a partir das notas recebidas.
- **Quem só consulta** (`FISCAL_RULES_VIEW`): vê as regras, as sugestões e as divergências.
- Frequência: o cadastro inicial acontece quando a empresa entra na carteira (sugestões em lote). Depois disso, quando aparece um produto novo ou um fornecedor novo.

## Histórias de usuário
- Como analista fiscal, quero buscar o produto pelo código ou pela descrição que aparece nas notas, para cadastrar a regra contra um código que existe de verdade.
- Como analista fiscal, quero ver uma regra sugerida com o valor mais frequente de cada campo e o percentual de concordância das notas, para confirmar em vez de digitar campo por campo.
- Como analista fiscal, quero que a regra de CFOP valha para compras internas e interestaduais sem cadastrar duas regras, para não duplicar cadastro.
- Como analista fiscal, quero uma regra específica para um fornecedor quando dois fornecedores usam o mesmo código para produtos diferentes, para não conferir errado.
- Como analista fiscal, quero deixar em branco os campos que não quero conferir, para que a regra só cobre o que eu defini.
- Como analista fiscal, quero que, ao salvar a regra, as notas com aquele produto sejam retificadas em seguida, para não esperar a rotina agendada.

## Regras de negócio

**RN-01. Escopo da regra.** Uma regra pertence a uma empresa (`company_id`) e a um código de produto (`codigo_produto`, até 60 caracteres). `documento_fornecedor` (CNPJ ou CPF, gravado só com dígitos) é opcional: vazio quer dizer "regra geral do código", que vale para qualquer fornecedor.
Ref.: `ProductFiscalRule.java:18-40`, `ProductFiscalRuleMapper.java:95-99`.

**RN-02. Campos obrigatórios e formatos aceitos** (Bean Validation no DTO, `ProductFiscalRuleDTO.java:17-58`):
| Campo | Regra |
|---|---|
| `companyId`, `codigoProduto`, `cfopCorreto` | obrigatórios (`@NotBlank`) |
| `ncmCorreto` | opcional, sem validação de formato |
| `origemMercadoria` | vazio ou um dígito de 0 a 8 |
| `cstIcms`, `cstIpi`, `cstPis`, `cstCofins` | vazio ou 2 dígitos |
| `csosn`, `cstIbsCbs` | vazio ou 3 dígitos |
| `cest` | vazio ou 7 dígitos |
| `classificacaoTributaria` (cClassTrib) | vazio ou 6 dígitos |
| `aliquotaIcms`, `aliquotaIpi`, `aliquotaPis`, `aliquotaCofins` | de 0 a 100 (percentual), `decimal(7,4)` no banco |
| `documentoFornecedor` | vazio, 11 ou 14 dígitos, ou CNPJ/CPF com máscara |

O formulário do front repete os padrões de CST/CSOSN/CEST/cClassTrib (`rule-form.component.ts:99-119`).

**RN-03. Campo vazio = não conferir.** Todos os campos tributários são opcionais. Texto vazio vira `null`. Se nenhum campo tributário for preenchido, a regra não tem `ProductTaxRule` (embutido nulo) e só CFOP/NCM são usados.
Ref.: `ProductFiscalRuleMapper.java:73-93`, `ProductTaxRule.java:8-16`.

**RN-04. cClassTrib precisa pertencer ao CST de IBS/CBS.** Quando a regra tem os dois, o cClassTrib deve começar pelo CST. Se não começar, a resposta é 400 com a mensagem `error.productFiscalRule.classTrib.cst` ("O cClassTrib precisa começar pelo CST de IBS/CBS informado.").
Ref.: `ProductFiscalRuleValidation.java:91-95`.

**RN-05. Unicidade.** Não pode haver duas regras **ativas** com a mesma empresa, o mesmo código e o mesmo fornecedor. A regra geral (fornecedor nulo) conta como um "fornecedor" à parte. Se houver duplicata, a resposta é 409 com a mensagem `error.productFiscalRule.duplicate`.
Ref.: `ProductFiscalRuleRepository.java:20-26`, `ProductFiscalRuleValidation.java:83-90`. No banco: `uk_product_fiscal_rules_company_product_supplier (company_id, codigo_produto, documento_fornecedor)` (V31). Ver a pergunta P-01.

**RN-06. Qual regra vale para o item.** Numa nota recebida, o código do produto é o do fornecedor. Por isso a busca é: primeiro a regra do código **para o emitente da nota** (CNPJ do emitente ou, se não houver, o CPF); se ela não existir, a regra geral do código. Regras sem código são ignoradas. Se houver mais de uma regra para a mesma chave, vale a primeira carregada (`putIfAbsent`).
Ref.: `ProductRules.java:19-79`.

**RN-07. O CFOP da regra segue a operação da nota.** A regra guarda a operação (os três últimos dígitos) e o sentido (entrada 1/2/3 ou saída 5/6/7). O primeiro dígito esperado vem do `ide/idDest` da nota:
- `idDest` 1 (interna): 1xxx ou 5xxx;
- `idDest` 2 (interestadual): 2xxx ou 6xxx;
- `idDest` 3 (exterior): 3xxx ou 7xxx.

Se o CFOP da regra não tiver 4 dígitos numéricos, ou se o `idDest` for desconhecido, vale o CFOP como foi cadastrado.
Fórmula: `esperado = (base_do_sentido + idDest - 1) || cfop[1..3]`, em que a base é '1' para entrada e '5' para saída.
Ref.: `OperationCfops.java:15-38`; uso em `ProductRuleCheck.expectedCfop` (`ProductRuleCheck.java:63-67`).

**RN-08. Lista de produtos da empresa.** O Taxify não tem cadastro de produtos. Os produtos são os códigos distintos que aparecem nos itens das notas não excluídas da empresa, filtrados por código ou descrição (`LIKE %termo%`, sem diferenciar maiúsculas). A lista traz no máximo 20 produtos, ordenados pelo número de ocorrências (maior primeiro) e depois pelo código. Descrição, NCM e CFOP vêm de `max()` entre as notas. `possuiRegra` é verdadeiro se existir **qualquer** regra ativa com o código, sem olhar o fornecedor.
Ref.: `CompanyProductService.java:27-70`, `FiscalXmlDocumentItemRepository.java:54-66` (`findDistinctProductsByCompany`).

**RN-09. Sugestão de regra a partir das notas recebidas.** A sugestão considera só as notas em que a empresa é destinatária: não excluídas, não canceladas, não emitidas pela própria empresa (`ownIssue` falso ou nulo) e com código de produto preenchido. Os dados são agrupados por **código + fornecedor**, e cada combinação de campos conta o número de itens. Para cada campo:
- o valor sugerido é a moda (o valor de maior contagem). No empate, vence o menor valor em ordem lexicográfica;
- o `percentual` é `ocorrências_da_moda × 100 / itens_que_têm_o_campo`, com 1 casa decimal (HALF_UP).

Campos sugeridos: NCM, CFOP, origem, CST de ICMS (só quando o item não tem CSOSN), CSOSN, alíquota interna de ICMS (só em notas com `idDest = 1`), CEST, CST e alíquota de IPI/PIS/COFINS, CST e cClassTrib de IBS/CBS. Alíquotas viram texto sem zeros à direita.
Limites: no máximo 500 produtos, ordenados pelo total de itens (decrescente), depois pelo código e depois pelo fornecedor. Com `somenteSemRegra=true` (o padrão), ficam de fora os produtos que já têm regra pela RN-06. Isso inclui os cobertos por uma regra geral.
Ref.: `ProductRuleSuggestionService.java:63-185`, `FiscalXmlDocumentItemRepository.java:68-96`.

**RN-10. CFOP sugerido = entrada equivalente ao CFOP de saída do fornecedor.** O primeiro dígito passa de 5/6/7 para 1/2/3. A operação se mantém, com estas exceções fixas no código:
| Saída (fornecedor) | Entrada sugerida | Comentário no código |
|---|---|---|
| x405 | x403 | venda com ST retida anteriormente → compra p/ comercialização com ST |
| x655 | x652 | venda de combustível a comerciante → compra p/ comercialização |
| x656 | x653 | venda de combustível a consumidor final → compra p/ consumo |

Depois o CFOP é normalizado para operação interna (primeiro dígito 1), porque a regra se adapta a cada nota pela RN-07. O CFOP de saída mais usado é devolvido à parte, em `cfopFornecedor`. Um CFOP que não é de saída com 4 dígitos fica como está. O próprio código avisa que uso e consumo (1556) e ativo imobilizado (1551) dependem do destino do produto, e que a sugestão é só um ponto de partida que o contador confirma.
Ref.: `EntryCfops.java:5-43`, `ProductRuleSuggestionService.java:141-145`.

**RN-11. Divergência de CFOP/NCM (resposta "o que retificar").** O endpoint `/divergencias` roda o motor de conferência sobre os itens do período e devolve uma linha por item que tem achado `RULE_CFOP` ou `RULE_NCM`, com o CFOP/NCM atual e o correto. O CFOP correto já vem ajustado pela RN-07. Ficam de fora:
- notas canceladas ou excluídas;
- notas emitidas pela própria empresa;
- itens sem regra;
- notas já retificadas com a versão atual da regra (ver o motor de conferência, RN de `ProductRuleCheck`).

Sem datas, o período fica aberto: `IssuePeriod.start/end` usa `LocalDateTime.MIN/MAX`.
Ref.: `ProductFiscalDivergenceService.java:35-91`.

**RN-12. Salvar a regra dispara a retificação das notas do produto.** Depois do commit do `create` ou do `update`, é publicado o `ProductFiscalRuleSavedEvent(companyId, codigoProduto, documentoFornecedor)`. O listener retifica, numa thread virtual, as NF-e recebidas daquele produto (e daquele fornecedor, se a regra tiver um) emitidas dentro da janela `lookback-days`. **A exclusão não publica o evento.**
Ref.: `ProductFiscalRuleService.java:37-59`, `ProductFiscalRuleRectificationListener.java:39-67`. Detalhes na spec de retificação.

**RN-13. Exclusão lógica.** `DELETE` marca `deleted = true` (`AbstractCrudService.java:129-133`). A listagem esconde as regras excluídas, a menos que o filtro peça `deleted` (`AbstractCrudService.java:48-61`).

**RN-14. Isolamento por escritório e carteira.** Toda leitura e escrita é restrita ao escritório do usuário e, para quem não é admin, às empresas da carteira dele (`CompanyScopedCrudService`, `TenantAccessService.assertCompanyAccess`). Os endpoints que recebem `companyId` (`/produtos`, `/sugestoes`, `/divergencias`, `/conferencia`) validam o acesso por `CompanyValidation.checkExist`, que também chama `assertCompanyAccess` (`CompanyValidation.java:45-49`).

## Critérios de aceite
- **CA-01 (RN-02).** Dado um POST sem `cfopCorreto`, quando a API valida, então responde 400.
- **CA-02 (RN-02).** Dado `cstIcms = "0"` (1 dígito), quando a API valida, então responde 400 com "deve ter 2 dígitos".
- **CA-03 (RN-03).** Dada uma regra só com CFOP e NCM, quando é salva, então `taxRule` fica nulo e o motor só confere CFOP/NCM.
- **CA-04 (RN-04).** Dada uma regra com `cstIbsCbs = 200` e `classificacaoTributaria = 000001`, quando é salva, então a API responde 400 (`error.productFiscalRule.classTrib.cst`).
- **CA-05 (RN-05).** Dada uma regra ativa da empresa E para o código `TEC-001` sem fornecedor, quando se cria outra regra geral para `TEC-001` em E, então a API responde 409. Se a segunda regra for para o fornecedor `11222333000181`, então ela é aceita.
- **CA-06 (RN-06).** Dadas uma regra geral de `TEC-001` (CFOP 1102) e uma regra de `TEC-001` para o fornecedor A (CFOP 1101), quando chega uma nota do fornecedor A, então vale 1101. Quando chega uma do fornecedor B, então vale 1102.
- **CA-07 (RN-07).** Dada uma regra com CFOP 1102, quando a nota tem `idDest = 2`, então o CFOP esperado é 2102. Com `idDest` ausente, então o esperado é 1102.
- **CA-08 (RN-08).** Dado um termo de busca "tecido", quando se chama `/produtos`, então voltam no máximo 20 códigos cuja descrição ou código contém "tecido", do mais frequente ao menos frequente.
- **CA-09 (RN-09).** Dado um produto com 8 itens de CFOP 5102 e 2 de CFOP 6102 do mesmo fornecedor, quando se pedem as sugestões, então `campos.cfopCorreto = {valor: "1102", ocorrencias: 10, percentual: 100.0}` e `cfopFornecedor = {valor: "5102", ocorrencias: 8, percentual: 80.0}`.
- **CA-10 (RN-09).** Dado um produto que já tem regra geral, quando se chama `/sugestoes` com `somenteSemRegra=true`, então ele não aparece para nenhum fornecedor.
- **CA-11 (RN-11).** Dada uma nota emitida pela própria empresa com um item cujo CFOP difere da regra, quando se chama `/divergencias`, então o item não aparece.
- **CA-12 (RN-12).** Dada a regra salva com sucesso, quando a transação faz commit, então as NF-e recebidas daquele produto dentro da janela são retificadas sem bloquear a resposta. O front mostra "Regra salva. As notas com esse produto estão sendo retificadas agora".
- **CA-13 (RN-14).** Dado um usuário de outro escritório, quando ele chama `/produtos?companyId=<empresa de outro escritório>`, então o acesso é negado.

### Casos de teste com números fictícios
| # | Entrada | Esperado |
|---|---|---|
| T-01 | Regra `cfopCorreto=1102`, nota interestadual (`idDest=2`), item com CFOP 2102 | sem divergência |
| T-02 | Mesma regra, nota interna, item com CFOP 1101 | divergência `RULE_CFOP`, atual 1101, correto 1102 |
| T-03 | Regra de saída `5102`, `idDest=2` | esperado 6102 (a regra de saída continua saída) |
| T-04 | Sugestão: CFOPs do fornecedor 5405 | `cfopCorreto` sugerido 1403 |
| T-05 | Sugestão: CFOP do fornecedor 6656 | 2653 → normalizado para 1653 |
| T-06 | Sugestão: CFOP já de entrada 1102 (nota de entrada do fornecedor) | 1102 |
| T-07 | Duas regras ativas iguais (mesmo código, sem fornecedor) | a segunda dá 409 |
| T-08 | Nota cancelada com item divergente | fora do `/divergencias` |
| T-09 | Alíquota `aliquotaIcms = 100.0001` | 400 ("não pode passar de 100") |

## Endpoints da API
Base: `/api/v1/product-fiscal-rules`. Na classe, `@RequiresPermission(value = FISCAL_RULES_VIEW, write = FISCAL_RULES_MANAGE)`: GET exige VIEW; POST, PUT e DELETE exigem MANAGE (ADMIN sempre passa).

| Método | Caminho | Permissão | O que faz |
|---|---|---|---|
| GET | `/product-fiscal-rules?companyId=&codigoProduto=&page=&size=` | FISCAL_RULES_VIEW | lista paginada. `codigoProduto` filtra por igualdade exata |
| GET | `/product-fiscal-rules/{id}` | FISCAL_RULES_VIEW | detalhe |
| POST | `/product-fiscal-rules` | FISCAL_RULES_MANAGE | cria e publica o evento de retificação |
| PUT | `/product-fiscal-rules/{id}` | FISCAL_RULES_MANAGE | edita e publica o evento de retificação |
| DELETE | `/product-fiscal-rules/{id}` | FISCAL_RULES_MANAGE | exclusão lógica (sem evento) |
| GET | `/product-fiscal-rules/produtos?companyId=&search=` | FISCAL_RULES_VIEW | RN-08 |
| GET | `/product-fiscal-rules/sugestoes?companyId=&somenteSemRegra=true&codigoProduto=&fornecedor=` | FISCAL_RULES_VIEW | RN-09/RN-10 |
| GET | `/product-fiscal-rules/divergencias?companyId=&dataEmissaoInicio=&dataEmissaoFim=` | FISCAL_RULES_VIEW | RN-11 |
| GET | `/product-fiscal-rules/conferencia?...` | FISCAL_RULES_VIEW | ver a spec do motor de conferência |

### Telas do front (`C:\workspace\taxify-front`)
- `features/companies/company-product-fiscal-rules/` (acordeão "Regras fiscais" na tela da empresa, visível com `FISCAL_RULES_VIEW`, `company-form.component.html:141-144`): tabela de regras, botão de sugestões em lote (`openSuggestions` → `/sugestoes`), "criar a partir da sugestão" e indicador de concordância (o menor `percentual` entre os campos). Editar e excluir só aparecem com `FISCAL_RULES_MANAGE`.
- `rule-form/rule-form.component.ts`: autocomplete de produto (`/produtos`), botão "preencher a partir das notas" (`/sugestoes?somenteSemRegra=false&codigoProduto=...&fornecedor=...`), seção de classificação tributária que se abre e fecha, e lista de origens de 0 a 8.
- `features/invoices/invoice-detail` (aba "Divergências"): chama `/divergencias` com o dia de emissão da nota e filtra pela chave de acesso no cliente.
- Serviço: `core/services/product-fiscal-rule.service.ts`.

## Dados
Tabela `product_fiscal_rules`:
- V14: `id`, `deleted`, auditoria (`created_*`, `last_modified_*`), `company_id` (FK `companies`), `codigo_produto` varchar(60), `descricao_produto` varchar(500), `ncm_correto` varchar(10), `cfop_correto` varchar(10) not null, `observacoes` varchar(1000).
- V26: `origem_mercadoria` (1), `cst_icms` (2), `csosn` (3), `aliquota_icms` decimal(7,4), `cest` (7), `cst_ipi`, `aliquota_ipi`, `cst_pis`, `aliquota_pis`, `cst_cofins`, `aliquota_cofins`, `cst_ibscbs` (3), `class_trib_ibscbs` (6). Todos anuláveis.
- V31: `documento_fornecedor` varchar(14). A chave única passa de `(company_id, codigo_produto)` para `(company_id, codigo_produto, documento_fornecedor)`.

Leitura das notas: `fiscal_xml_document_items` + `fiscal_xml_documents` (sem escrita por esta feature).

## Configuração
- `fiscal-xml.rectification.enabled` (env `FISCAL_XML_RECTIFICATION_ENABLED`): quando é falso, o evento da RN-12 é ignorado.
- `fiscal-xml.rectification.lookback-days` (env `FISCAL_XML_RECTIFICATION_LOOKBACK_DAYS`): janela de emissão das notas retificadas quando se salva a regra. Default da classe: 1. `application-default.yaml` e `docker-compose.yml`: 50.

## Testes existentes
- `ProductFiscalDivergenceServiceTest`: UUID inválido, empresa inexistente, CFOP divergente, NCM divergente só quando a regra fixa NCM, item sem regra, nota retificada com a regra atual, notas próprias fora, CFOP pela operação, regra por fornecedor, canceladas e excluídas fora, regra editada depois da retificação volta a divergir.
- `ProductRuleSuggestionServiceTest`: moda e CFOP de entrada, filtro de um produto, conversão do CFOP de saída, mesmo código de dois fornecedores.
- `ProductFiscalRuleMapperTest`: campos vazios → sem `taxRule`, ida e volta dos campos tributários.
- `ProductFiscalRuleRectificationListenerTest`: notas do fornecedor, regra geral olha todos os fornecedores, desligado, sem código.
- `SharedHelpersTest`: `ProductRules` (a regra do fornecedor vence a geral) e `OperationCfops` (tabela de casos).

**Lacunas:** não há teste de `ProductFiscalRuleValidation` (409 de duplicata, 400 de cClassTrib×CST), de `CompanyProductService`, das restrições do DTO (`@Pattern`/`@DecimalMax`), da exclusão (que não dispara retificação) nem um teste dedicado de `EntryCfops` (só é coberto indiretamente).

## Fora do escopo e limitações conhecidas
- Não existe cadastro de produtos próprio: só dá para cadastrar regra de código que já apareceu em nota (a API aceita qualquer código, mas a tela parte das notas).
- Não há perfil por tipo de empresa (Simples, tecidos, PROIND): a regra é por produto e por empresa.
- A regra não distingue a finalidade da compra (revenda, industrialização, uso e consumo, imobilizado). Um mesmo código tem um único CFOP esperado por fornecedor.
- `ncmCorreto` e `cfopCorreto` não têm validação de formato.
- O filtro `codigoProduto` da listagem é por igualdade, mas o campo de busca da tela sugere "contém".
- `possuiRegra` em `/produtos` não considera o fornecedor (RN-08), ao contrário de `/sugestoes` (RN-06).
- Não há importação ou exportação de regras em lote nem cópia de regras entre empresas.

## Perguntas em aberto
- **P-01 (inconsistência de dados).** A chave única do banco `(company_id, codigo_produto, documento_fornecedor)` vale também para as linhas excluídas logicamente, mas a validação da aplicação ignora as excluídas. Excluir a regra do fornecedor X e recriar a mesma regra deve estourar a constraint do banco (erro não tratado) em vez de funcionar. Por outro lado, no MySQL `NULL` não colide, então o banco não impede duas regras gerais iguais, e a única proteção é a checagem da aplicação, sujeita a corrida. Confirmar o comportamento desejado (reativar a regra excluída? índice parcial?).
- **P-02 (regra fiscal).** O mapeamento saída→entrada de `EntryCfops` usa só três exceções (405→403, 655→652, 656→653). Para uma indústria (caso da KDM), a compra de matéria-prima com CFOP de saída 5101/5102 deveria virar 1101 (compra para industrialização), e não 1102/1101 por espelho. A sugestão deve considerar o perfil da empresa? O Tony precisa validar a tabela.
- **P-03 (regra fiscal).** Uso e consumo (1556/2556) e imobilizado (1551/2551) não são sugeridos automaticamente. Deve existir algum indicador por produto (finalidade) que, no futuro, alimente o DIFAL da fronteira?
- **P-04 (comportamento).** A exclusão de uma regra não dispara a retificação imediata. As notas já retificadas com ela continuam apontando para o XML retificado até a rotina agendada rodar (e só se a nota estiver na janela `lookback-days`). Isso é aceitável?
- **P-05 (permissão).** A aba "Divergências" do detalhe da nota chama `/product-fiscal-rules/divergencias` e `/conferencia`, que exigem `FISCAL_RULES_VIEW`. Um usuário só com `INVOICES_VIEW` vê a nota mas recebe 403 nessa aba. É intencional?
- **P-06 (escopo).** A alíquota interna de ICMS cadastrada é comparada com qualquer nota interna, sem considerar redução de base nem benefício (cBenef). Confirmar se basta para as empresas da carteira.
