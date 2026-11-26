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
| — | — | — |
