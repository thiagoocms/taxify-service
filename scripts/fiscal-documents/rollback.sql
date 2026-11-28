-- Volta da remodelagem dos documentos fiscais quando a V87 ou a V88 FALHOU (spec 2026-11-27, 6.5 item 0).
--
-- Nesse caso a aplicação nova não subiu, nada foi gravado no modelo novo e as quatro tabelas antigas estão intactas
-- como legacy_*. Não há restauração de backup: o script só
--   a. devolve os nomes originais às quatro legacy_* (um RENAME atômico);
--   b. apaga as cinco tabelas novas;
--   c. apaga do flyway_schema_history as linhas da V86 à V89 (86, 86.1 a 86.4, 87, 88, 89).
-- fiscal_xml_sync_control não muda (a V89, que remove as colunas de contador, não rodou).
--
-- Se a V89 JÁ rodou (legacy_* apagadas), este script para sem mexer em nada: use rollback-from-backup.sql.
-- Se só parte das legacy_* existe, ou se tabelas antigas e legacy_* existem juntas, também para sem mexer em nada.
--
-- Como rodar (todas as instâncias da aplicação paradas; a senha só por arquivo de opções, nunca na linha de comando):
--   docker exec -i taxify-mysql mysql --defaults-extra-file=<arquivo .cnf no container> <schema> \
--     < scripts/fiscal-documents/rollback.sql
-- Depois: subir a imagem anterior e religar as flags do passo 2.

SET @fd_legacy = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name IN ('legacy_fiscal_xml_documents', 'legacy_fiscal_xml_document_items',
                                     'legacy_service_invoices', 'legacy_fiscal_xml_events'));
SET @fd_original = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                    AND table_name IN ('fiscal_xml_documents', 'fiscal_xml_document_items', 'service_invoices',
                                       'fiscal_xml_events'));
-- para no erro (de propósito, com o motivo no nome da tabela inexistente) quando o estado não é o deste caso
SET @fd_step = CASE
    WHEN @fd_legacy = 4 AND @fd_original = 0 THEN
        'RENAME TABLE legacy_fiscal_xml_documents TO fiscal_xml_documents, legacy_fiscal_xml_document_items TO fiscal_xml_document_items, legacy_service_invoices TO service_invoices, legacy_fiscal_xml_events TO fiscal_xml_events'
    WHEN @fd_legacy = 0 AND @fd_original = 0 THEN 'SELECT 1 FROM fd_rollback_stopped_legacy_already_dropped_use_rollback_from_backup'
    WHEN @fd_legacy = 0 AND @fd_original = 4 THEN 'SELECT 1 FROM fd_rollback_stopped_nothing_was_renamed'
    ELSE 'SELECT 1 FROM fd_rollback_stopped_legacy_and_original_tables_mixed' END;
PREPARE fd_statement FROM @fd_step;
EXECUTE fd_statement;
DEALLOCATE PREPARE fd_statement;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS fiscal_document_sources, fiscal_document_events, fiscal_document_items, fiscal_documents,
    capture_period_counts;
SET FOREIGN_KEY_CHECKS = 1;

DELETE FROM flyway_schema_history WHERE version IN ('86', '86.1', '86.2', '86.3', '86.4', '87', '88', '89');

SELECT 'fiscal_xml_documents' AS table_name, COUNT(*) AS row_count FROM fiscal_xml_documents
UNION ALL SELECT 'fiscal_xml_document_items', COUNT(*) FROM fiscal_xml_document_items
UNION ALL SELECT 'service_invoices', COUNT(*) FROM service_invoices
UNION ALL SELECT 'fiscal_xml_events', COUNT(*) FROM fiscal_xml_events
UNION ALL SELECT 'flyway V86-V89', COUNT(*) FROM flyway_schema_history WHERE version IN ('86', '86.1', '86.2', '86.3', '86.4', '87', '88', '89');
