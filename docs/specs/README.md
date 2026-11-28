# Specs do Taxify

Aqui ficam as especificações funcionais do Taxify, o módulo fiscal da CS Hub. O po-taxify escreve cada spec, os devs implementam a partir dela, o reviewer revisa contra ela e o QA valida os critérios de aceite. A esteira completa está no `CLAUDE.md` da raiz.

## Convenções
- Nome do arquivo: `AAAA-MM-DD-<feature>.md`.
- Estrutura: problema e objetivo, usuários, histórias, regras de negócio (RN-xx), critérios de aceite (Dado/Quando/Então), casos de teste, dados e integrações, fora do escopo e perguntas em aberto.
- Regra fiscal sem fonte não entra. Ela vai para "Perguntas em aberto" e quem decide é o Tony.
- Sem dado real de cliente: CNPJ, chave de acesso, nome e valor nos exemplos são sempre fictícios.

## Specs retroativas (estado em 2026-11-26)
Descrevem o que já estava implementado quando a pasta foi criada.

| Área | Spec |
|---|---|
| Plataforma | [Acesso, escritórios, usuários e empresas](2026-11-26-plataforma-acesso-e-empresas.md) |
| Plataforma | [Planos, assinaturas e cobrança](2026-11-26-planos-assinaturas-e-cobranca.md) |
| Plataforma | [Integrações do escritório (Acessórias, links, Conta Azul)](2026-11-26-integracoes-do-escritorio.md) |
| Captura | [Captura de XML: SIEG e SEFAZ](2026-11-26-captura-xml-sieg-e-sefaz.md) |
| Captura | [NFS-e: notas de serviço](2026-11-26-nfse-notas-de-servico.md) |
| Captura | [Gestão de documentos fiscais](2026-11-26-gestao-de-documentos-fiscais.md) |
| Conferência | [Regras fiscais por produto](2026-11-26-regras-fiscais-por-produto.md) |
| Conferência | [Motor de conferência fiscal](2026-11-26-motor-de-conferencia-fiscal.md) |
| Conferência | [Retificação e trilha de auditoria](2026-11-26-retificacao-e-trilha-de-auditoria.md) |
| Apuração | [Apuração do mês](2026-11-26-apuracao-do-mes.md) |
| Apuração | [Retenções](2026-11-26-retencoes.md) |
| Apuração | [Relatórios](2026-11-26-relatorios.md) |
| Estadual PE | [Fronteira PE (e-Fisco)](2026-11-26-fronteira-pe-efisco.md) |
| Estadual PE | [Certidões SEFAZ-PE](2026-11-26-certidoes-sefaz-pe.md) |
| Carteira | [Pendências](2026-11-26-pendencias.md) |
| Carteira | [Auditoria da carteira](2026-11-26-auditoria-da-carteira.md) |
| Técnico | [Arquitetura técnica: backend e infra](2026-11-26-arquitetura-tecnica-backend-e-infra.md) |
| Técnico | [Arquitetura técnica: frontend](2026-11-26-arquitetura-tecnica-frontend.md) |
| Técnico | [Pipeline de CI no GitHub Actions](2026-11-26-pipeline-ci-github-actions.md) |

## Specs de features novas
Cada feature nova entra aqui com a data em que a spec foi escrita.

| Data | Feature | Status |
|---|---|---|
| 2026-11-26 | [Taxify SaaS: teste grátis, assinatura e limites](2026-11-26-taxify-saas-assinatura-do-escritorio.md) | Fase 1 pronta para implementação; fases 2 e 3 em especificação |
| 2026-11-27 | [Esqueci a senha, troca de senha e aviso de pendência alta por e-mail](2026-11-27-senha-e-avisos-de-pendencia.md) | Pronta para implementação (decisões do Tony em 2026-11-27); go-live dos e-mails depende do SES fora do sandbox |
| 2026-11-27 | [Painel de controle da captura de XML](2026-11-27-painel-captura-xml.md) | Pronta para implementação (decisões do Tony em 2026-11-27) |
| 2026-11-27 | [Avaliação do RECUPERAC RT33: o que trazer para o Taxify](2026-11-27-avaliacao-recuperac-rt33.md) | Avaliação do PO, revisão 2, com as decisões do Tony de 2026-11-27 (P-01, P-02, P-05, P-06, P-07; demais pela recomendação). Onda 1 virou as quatro specs abaixo; Ondas 2 e 3 em avaliação |
| 2026-11-27 | [Reforma: levantamento do crédito presumido de CBS sobre o estoque (F5a)](2026-11-27-credito-estoque-cbs-levantamento.md) | Pronta para implementação (P-01 e P-02 decididas). Prazo: no ar até ~15/12/2026; nasce desligada (`TAXIFY_STOCK_SURVEY_ENABLED`) |
| 2026-11-27 | [Reforma: alíquotas de IBS/CBS por ano, com status (F1a-0)](2026-11-27-aliquotas-ibs-cbs-por-ano.md) | Pronta para implementação (P-05 decidida). Precisa estar em produção antes de 01/01/2027 |
| 2026-11-27 | [Reforma: validador IBS/CBS pela tabela oficial e painel de prontidão (F1a)](2026-11-27-validador-ibs-cbs-tabela-oficial.md) | **Pronta para implementação, revisão 2** (P-06, P-07 e AL-RN-02 aplicadas; P-VT-02 a 04 fechadas): modelo novo da remodelagem (`fiscal_documents`/`fiscal_document_items`), contrato e banco em inglês (`/platform/official-tables`, `/reform/readiness`, `/reform/class-codes`), migrations V90-V92. Parte A (tabelas oficiais) pode ser desenvolvida já, merge só depois da V89; Partes B e C depois da B-FD-05. Layout CSV com mapeamento configurável; primeiro arquivo real depende do D-01; nasce desligada |
| 2026-11-27 | [Captura ampliada no SIEG: contagens, NFC-e e CT-e](2026-11-27-captura-nfce-cte.md) | **Fases 2 e 3 substituídas pela Captura SIEG por NSU** (decisão do Tony de 2026-11-27); valem só a fase 1, o modelo do CT-e e as perguntas fiscais P-CX-10 a 13. **P-CX-02 fechada em 2026-11-28 (NFC-e soma já); as telas de NFC-e e CT-e foram para a spec do NSU (seção 5A).** **Ajustada pela remodelagem** (quadro no topo: T2 no lugar de `sieg_document_counts`, T1 no lugar de `document_capture_status`, CT-e como `Cte`; fase 3 parada até a remodelagem). Fase 1 (contagens) pronta para implementação. Modelagem em inglês |
| 2026-11-27 | [NCM inexistente ou fora de vigência (F2)](2026-11-27-ncm-inexistente-ou-fora-de-vigencia.md) | **Pronta para implementação, revisão 2** (P-NC-01 a 03 fechadas): modelo novo da remodelagem, nomes em inglês (`/reform/ncm/{code}`, `ncm_codes`), migration V93. **NCM só como alerta (`WARNING`) no paralelo, sem pendência nem e-mail** (decisão do Tony de 2026-11-27); a regra de auditoria `NCM_INVALID` (B-NC-3) fica para depois do paralelo. Depois da Parte A do F1a e da B-FD-05. Nomes do JSON configuráveis; primeiro arquivo real depende do D-03; nasce desligada |
| 2026-11-27 | [Remodelagem dos documentos fiscais (`fiscal_documents`, `SINGLE_TABLE`)](2026-11-27-remodelagem-documentos-fiscais.md) | Pronta para implementação (decisões do Tony de 2026-11-27). Plano principal: troca única numa release, migrations V86-V89 (V85 é da `fix/sieg-call-savings`; a V89 apaga as tabelas antigas depois da paridade, decisão do Tony; volta só pelo backup obrigatório); inclui origens (T4), contagens por período (T2) e expurgo com marca. Janela decidida: dia útil às 21h (P-FD-02). Go-live depende da remedição na véspera, do backup conferido e da aprovação do Tony no dia. Renumera F1a, F2 e captura para depois da V89. **Fase P (seção 7B): provedor de XML plugável** (decisão do Tony de 2026-11-27): contrato por capacidades, provedor por escritório, limite e orçamento por provedor, T1/T5; release logo depois da troca, perguntas com recomendação |
| 2026-11-27 | [Captura SIEG por NSU (NF-e, NFC-e, CT-e, NFS-e e eventos)](2026-11-27-captura-sieg-nsu.md) | **Implementação liberada logo após o deploy da remodelagem** (e da fase P), desligada (`TAXIFY_SIEG_NSU_ENABLED`). Substitui as fases 2 e 3 da captura ampliada e **inclui as telas de NFC-e e CT-e** (seção 5A, modelo novo, rotas `/transport-documents`). Teste real feito em 2026-11-27; P-NSU-01 fechada. **Atualizada em 2026-11-28:** P-NSU-03 fechada (SIEG não cobra por volume), P-NSU-09 fechada (descarte em memória; texto da política e dos termos na seção 5B), P-CX-02 fechada (NFC-e soma já, sem dupla contagem, NSU-RN-30). **Ligar depende só da empresa do piloto** (da CS Hub, indicada pelo Tony). Perguntas novas sem bloqueio: P-NSU-13 a 15 |
| 2026-11-27 | [Filtro de CFOP múltiplo (chips) e totais que acompanham o filtro](2026-11-27-filtro-cfop-multiplo.md) | **Componente `shared/components/chips-input` (F-CF-01) pronto para implementar já**, autocomplete pronto e não usado. Backend (notas fiscais e Fronteira) pronto, escrito contra o modelo novo: **depois do merge da remodelagem**. Contrato `cfops` (lista), com `cfop` antigo aceito por uma release. Totais aplicam todos os filtros menos a aba. **P-CF-01 (semântica do total com CFOP) bloqueia só a B-CF-04**; recomendação: nota inteira no card mais a linha dos itens. Sem migration e sem variável |
| 2026-11-28 | [Preço unitário nas sugestões de regras](2026-11-28-preco-unitario-sugestoes-regra.md) | **Pronta para implementação logo após o deploy da remodelagem** (modelo novo, `fiscal_document_items`). Só o unitário, decisão do Tony: média ponderada pela quantidade sobre o `vUnCom`, com menor e maior, na unidade predominante e com aviso das outras. Campo aditivo `unitPrice` no DTO da sugestão. Sem migration e sem variável. P-PU-01 a 03 com recomendação, nenhuma bloqueia |
| 2026-11-27 | [Capturas e controles: correções da captura e tabelas de controle](2026-11-27-capturas-e-controles.md) | Índice e justificativa (spec irmã da remodelagem). B1 (`Skip` do SIEG) recomendado como correção antes da remodelagem; demais itens precisam de detalhamento antes do dev |
