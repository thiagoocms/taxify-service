# Taxify — instruções do projeto

O Taxify é o módulo fiscal da plataforma CS Hub (backend Java, frontend taxify-ui, MySQL e XMLs fiscais; deploy via Docker Compose numa EC2 da AWS). Ele é usado pelo time Fiscal da CSHUB para automatizar conferências que hoje são feitas à mão.

## Fluxo de trabalho com agentes

Toda feature passa por esta esteira. A sessão principal é a orquestradora: ela chama cada agente em ordem e passa o resultado de um para o outro.

1. **po-taxify** → escreve a spec em `docs/specs/`. Se houver "Perguntas em aberto" sobre regra fiscal, **pare e pergunte ao Tony** antes de seguir.
2. **dev-backend-taxify** e **dev-frontend-taxify** → implementam as tarefas da spec. O backend vem primeiro quando o front depende do contrato da API; quando o contrato já está definido na spec, os dois podem rodar em paralelo.
3. **reviewer-taxify** → revisa o diff. Se o veredito for REPROVADO, os bloqueantes voltam para o dev correspondente e depois são revisados de novo (no máximo 2 ciclos; depois disso, escale para o Tony).
4. **qa-taxify** → valida contra os critérios de aceite. Bugs voltam para o dev, passam pelo reviewer e voltam ao QA.
5. Ao final, um resumo para o Tony: o que foi entregue, os pareceres do reviewer e do QA, e os riscos que ficaram.

## Regras gerais
- Regra fiscal nunca é inventada. Na dúvida, pergunte.
- Dinheiro usa `BigDecimal`; o resultado sempre traz a memória de cálculo.
- Dados de cliente são sigilosos: nada de dado real em fixture, log ou commit.
- Deploy em produção (EC2) só com aprovação explícita do Tony.
