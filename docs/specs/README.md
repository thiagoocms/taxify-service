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
| 2026-11-27 | [Captura ampliada no SIEG: contagens, NFC-e e CT-e](2026-11-27-captura-nfce-cte.md) | **Fases 2 e 3 substituídas pela Captura SIEG por NSU** (decisão do Tony de 2026-11-27); valem só a fase 1, o modelo do CT-e e as perguntas fiscais. **Ajustada pela remodelagem** (quadro no topo: T2 no lugar de `sieg_document_counts`, T1 no lugar de `document_capture_status`, CT-e como `Cte`; fase 3 parada até a remodelagem). Fase 1 (contagens) pronta para implementação. Fase 2 (NFC-e) pronta, desligada; ligar depende de P-CX-01 e P-CX-02. Fase 3a (CT-e) pronta, lado tomador depende de P-CX-03; 3b (SEFAZ) bloqueada por P-CX-04. Modelagem em inglês |
| 2026-11-27 | [NCM inexistente ou fora de vigência (F2)](2026-11-27-ncm-inexistente-ou-fora-de-vigencia.md) | **Pronta para implementação, revisão 2** (P-NC-01 a 03 fechadas): modelo novo da remodelagem, nomes em inglês (`/reform/ncm/{code}`, `ncm_codes`, regra de auditoria `NCM_INVALID` MEDIA fora do e-mail), migration V93. Depois da Parte A do F1a e da B-FD-05. Nomes do JSON configuráveis; primeiro arquivo real depende do D-03; nasce desligada |
| 2026-11-27 | [Remodelagem dos documentos fiscais (`fiscal_documents`, `SINGLE_TABLE`)](2026-11-27-remodelagem-documentos-fiscais.md) | Pronta para implementação (decisões do Tony de 2026-11-27). Plano principal: troca única numa release, migrations V86-V89 (V85 é da `fix/sieg-call-savings`; a V89 apaga as tabelas antigas depois da paridade, decisão do Tony; volta só pelo backup obrigatório); inclui origens (T4), contagens por período (T2) e expurgo com marca. Go-live depende de P-FD-02 (janela) e da remedição na véspera. Renumera F1a, F2 e captura para depois da V89. **Fase P (seção 7B): provedor de XML plugável** (decisão do Tony de 2026-11-27): contrato por capacidades, provedor por escritório, limite e orçamento por provedor, T1/T5; release logo depois da troca, perguntas com recomendação |
| 2026-11-27 | [Captura SIEG por NSU (NF-e, NFC-e, CT-e, NFS-e e eventos)](2026-11-27-captura-sieg-nsu.md) | Pronta para implementar, desligada (`TAXIFY_SIEG_NSU_ENABLED`), depois da remodelagem e da fase P. Substitui as fases 2 e 3 da captura ampliada. Ligar depende do teste real com a conta do Tony (P-NSU-01 a 07) e, para escritórios com NFC-e, da P-CX-02 |
| 2026-11-27 | [Capturas e controles: correções da captura e tabelas de controle](2026-11-27-capturas-e-controles.md) | Índice e justificativa (spec irmã da remodelagem). B1 (`Skip` do SIEG) recomendado como correção antes da remodelagem; demais itens precisam de detalhamento antes do dev |
