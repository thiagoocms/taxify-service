-- Pré-check da véspera da remodelagem dos documentos fiscais (spec 2026-11-27, seção 8 e runbook 6.2, passo 0).
--
-- SÓ LEITURA: nenhum INSERT/UPDATE/DELETE/DDL. Roda no schema da aplicação, com a versão atual no ar, e o resultado vai
-- para o registro da release. Sem dado de cliente na saída: só nomes de verificação e contagens.
--
-- Como rodar (senha só por arquivo de opções, nunca na linha de comando):
--   docker exec -i taxify-mysql mysql --defaults-extra-file=<arquivo .cnf no container> <schema> \
--     < scripts/fiscal-documents/precheck.sql
--
-- Cada linha da saída: verificacao | quantidade | esperado. A release só segue com toda linha "deve ser 0" em 0.

-- FD-RN-57 (CFOP efetivo): a V87 escolhe a regra do produto pelo documento do fornecedor só com dígitos, como o
-- DocumentNumbers.onlyDigits do Java. Ela tira a máscara ('.', '/', '-', espaço) com REPLACE; qualquer outro caractere
-- faria o SQL e o Java escolherem regras diferentes. Deve ser 0: corrigir as regras na tela antes do deploy.
SELECT 'regras_fornecedor_com_caractere_fora_da_mascara' AS verificacao,
       COUNT(*) AS quantidade,
       'deve ser 0' AS esperado
FROM product_fiscal_rules
WHERE deleted = 0
  AND NULLIF(REPLACE(REPLACE(REPLACE(REPLACE(documento_fornecedor, '.', ''), '/', ''), '-', ''), ' ', ''), '') IS NOT NULL
  AND NOT (REPLACE(REPLACE(REPLACE(REPLACE(documento_fornecedor, '.', ''), '/', ''), '-', ''), ' ', '') REGEXP '^[0-9]+$')
UNION ALL
-- Informativo: regras com máscara no documento do fornecedor (a V87 trata).
SELECT 'regras_fornecedor_com_mascara', COUNT(*), 'informativo'
FROM product_fiscal_rules
WHERE deleted = 0
  AND NULLIF(documento_fornecedor, '') IS NOT NULL
  AND NOT (documento_fornecedor REGEXP '^[0-9]+$')
UNION ALL
-- Informativo: duas regras ativas para a mesma chave (a tela recusa; dado antigo pode ter). V87 e Java ficam com a de
-- menor id; vale conferir se a escolhida é a certa.
SELECT 'regras_duplicadas_mesma_chave', COUNT(*), 'informativo'
FROM (SELECT company_id, CAST(codigo_produto AS BINARY) AS codigo,
             REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(documento_fornecedor, ''), '.', ''), '/', ''), '-', ''), ' ', '') AS fornecedor
      FROM product_fiscal_rules
      WHERE deleted = 0
      GROUP BY company_id, codigo, fornecedor
      HAVING COUNT(*) > 1) duplicadas
UNION ALL
-- FD-RN-56 / QA B1: notas sem own_issue e sem direção. A V87 deriva a emissão própria como o FiscalDirectionBackfill
-- (documento da empresa = CNPJ ou CPF do emitente) e a direção pelo tpNF. O ideal é 0 (o backfill da versão atual
-- termina na subida dela); acima de 0, conferir que a V87 loga a mesma contagem em fiscal.documents.cutover.direction.derived.
SELECT 'notas_sem_own_issue_e_sem_direcao', COUNT(*), 'ideal 0 (a V87 deriva)'
FROM fiscal_xml_documents
WHERE own_issue IS NULL AND direction IS NULL
UNION ALL
-- Informativo: notas retificadas cujos itens a V87 vai recalcular (FD-RN-57).
SELECT 'notas_retificadas', COUNT(*), 'informativo'
FROM fiscal_xml_documents
WHERE rectified_file_path IS NOT NULL AND document_type <> 'CTE';
