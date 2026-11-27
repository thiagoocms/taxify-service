-- Volta da remodelagem dos documentos fiscais DEPOIS da V89, pelo backup do passo 1 (spec 2026-11-27, 6.5 item 1,
-- FD-RN-52, CA-FD-13). Só com as rotinas ainda paradas (antes do passo 6): depois dele, perde tudo o que entrou desde o
-- backup (6.5 item 2, saída b) e só com a decisão do Tony.
--
-- Se as legacy_* ainda existem (a V87 ou a V88 falhou), este script para sem mexer em nada: use rollback.sql, que só
-- desfaz o RENAME e nunca restaura por cima das tabelas intactas.
--
-- Por que restaurar no próprio schema e não num temporário + RENAME: as tabelas do dump têm FK para companies, e um
-- RENAME entre schemas mantém a FK apontando para o schema de origem. A conferência do arquivo é a do backup.sh
-- (restauração completa num schema fd_* descartável, com contagens e somas), feita antes do deploy.
--
-- Pré-requisitos:
--   1. Todas as instâncias da aplicação paradas.
--   2. SHA-256 do arquivo conferido com o anotado na release: sha256sum -c fiscal-documents-backup-<data>.sql.sha256
--   3. O arquivo com o nome fixo fiscal-documents-backup.sql no diretório de onde o cliente mysql roda (SOURCE relativo).
--
-- Como rodar (na EC2; a senha só por arquivo de opções):
--   docker exec taxify-mysql mkdir -p /tmp/fd-rollback
--   docker cp fiscal-documents-backup-<data>.sql taxify-mysql:/tmp/fd-rollback/fiscal-documents-backup.sql
--   docker exec -i -w /tmp/fd-rollback taxify-mysql mysql --defaults-extra-file=<arquivo .cnf no container> <schema> \
--     < scripts/fiscal-documents/rollback-from-backup.sql
--   docker exec taxify-mysql rm -rf /tmp/fd-rollback
-- O cliente para no primeiro erro (sem --force): se o SOURCE falhar, as tabelas novas ainda não foram apagadas.
--
-- O que faz: confere o estado (sem legacy_* e sem as tabelas antigas); restaura o backup (as quatro tabelas antigas e
-- fiscal_xml_sync_control inteira, com as colunas de contador); apaga as cinco tabelas novas; apaga do
-- flyway_schema_history as linhas da V86 à V89; mostra as contagens para conferir com as do backup.sh.
-- Depois: subir a imagem anterior, conferir as contagens e religar as flags do passo 2.

SET @fd_legacy = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name IN ('legacy_fiscal_xml_documents', 'legacy_fiscal_xml_document_items',
                                     'legacy_service_invoices', 'legacy_fiscal_xml_events'));
SET @fd_original = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                    AND table_name IN ('fiscal_xml_documents', 'fiscal_xml_document_items', 'service_invoices',
                                       'fiscal_xml_events'));
SET @fd_step = CASE
    WHEN @fd_legacy = 0 AND @fd_original = 0 THEN 'DO 0'
    WHEN @fd_legacy > 0 THEN 'SELECT 1 FROM fd_rollback_stopped_legacy_still_there_use_rollback_sql'
    ELSE 'SELECT 1 FROM fd_rollback_stopped_old_tables_already_present' END;
PREPARE fd_statement FROM @fd_step;
EXECUTE fd_statement;
DEALLOCATE PREPARE fd_statement;

SOURCE fiscal-documents-backup.sql

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS fiscal_document_sources, fiscal_document_events, fiscal_document_items, fiscal_documents,
    capture_period_counts;
SET FOREIGN_KEY_CHECKS = 1;

DELETE FROM flyway_schema_history WHERE version IN ('86', '86.1', '86.2', '86.3', '86.4', '87', '88', '89');

SELECT 'fiscal_xml_documents' AS table_name, COUNT(*) AS row_count FROM fiscal_xml_documents
UNION ALL SELECT 'fiscal_xml_document_items', COUNT(*) FROM fiscal_xml_document_items
UNION ALL SELECT 'service_invoices', COUNT(*) FROM service_invoices
UNION ALL SELECT 'fiscal_xml_events', COUNT(*) FROM fiscal_xml_events
UNION ALL SELECT 'fiscal_xml_sync_control', COUNT(*) FROM fiscal_xml_sync_control
UNION ALL SELECT 'flyway V86-V89', COUNT(*) FROM flyway_schema_history WHERE version IN ('86', '86.1', '86.2', '86.3', '86.4', '87', '88', '89');
