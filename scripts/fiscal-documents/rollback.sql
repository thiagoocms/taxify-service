-- Volta da remodelagem dos documentos fiscais quando a V87 ou a V88 FALHOU (spec 2026-11-27, 6.5 item 0).
--
-- Nesse caso a aplicação nova não subiu, nada foi gravado no modelo novo pela aplicação e as quatro tabelas antigas
-- estão intactas (com o nome original, se a V87 parou nos pré-checks, ou como legacy_*, se parou depois do rename).
-- Não há restauração de backup. Os dois estados que o script aceita:
--   A. V87 falhou ANTES do rename (pré-check: colisão de id, valor sem tradução, CT-e fora do I-04-T...): as quatro
--      tabelas antigas com o nome original e nenhuma legacy_*. O script exige as tabelas novas VAZIAS (as que a V86
--      chegou a criar) e só as apaga; não renomeia nada.
--   B. V87 falhou depois do rename, ou a V88 (paridade) falhou: as quatro legacy_* e nenhuma tabela antiga. O script
--      devolve os nomes originais (um RENAME atômico) e apaga as tabelas novas (só têm o que a V87 copiou).
-- Nos dois: apaga do flyway_schema_history as linhas da V86 à V89 (86, 86.1 a 86.4, 87, 88, 89), e a imagem anterior
-- sobe (o validate dela não conhece as tabelas novas, e o Flyway dela não acha versão desconhecida no histórico).
-- fiscal_xml_sync_control não muda (a V89, que remove as colunas de contador, não rodou).
--
-- Fora desses dois estados, o script para no primeiro comando, sem mexer em nada (o nome da tabela inexistente no erro
-- diz o motivo):
--   - V89 já rodou inteira (nem legacy_* nem tabelas antigas): use rollback-from-backup.sql;
--   - tabelas novas com linhas no estado A (fd_rollback_stopped_new_tables_not_empty): algo gravou nelas depois da
--     falha; confira à mão antes de apagar;
--   - V89 PARCIAL (fd_rollback_stopped_legacy_and_original_tables_mixed com só parte das legacy_*): a V89 caiu no meio
--     dos DROP (a V88 tinha passado, então as tabelas novas estão completas e conferidas). Duas saídas:
--       (1) corrigir e subir de novo (recomendado): resolver a causa (lock, conexão), `flyway repair` e subir a imagem
--           nova com o MESMO FD_BACKUP_SHA256; a V89 é idempotente, confere o backup só contra as legacy_* que
--           sobraram e termina os DROP e a remoção das colunas de contador;
--       (2) voltar pelo backup: apagar à mão as legacy_* que sobraram (DROP TABLE IF EXISTS legacy_fiscal_xml_events,
--           legacy_fiscal_xml_document_items, legacy_fiscal_xml_documents, legacy_service_invoices) e rodar
--           rollback-from-backup.sql com o arquivo do passo 1 (todas as linhas estão nele: a V89 confere as contagens
--           antes do primeiro DROP).
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
-- linhas nas tabelas novas que existirem (a V86 pode ter caído no meio): uma soma de COUNT(*) montada na hora
SET @fd_new_rows = 0;
SET @fd_count = (SELECT CONCAT('SELECT ', COALESCE(GROUP_CONCAT(CONCAT('(SELECT COUNT(*) FROM `', table_name, '`)')
                                                                SEPARATOR ' + '), '0'), ' INTO @fd_new_rows')
                 FROM information_schema.tables WHERE table_schema = DATABASE()
                 AND table_name IN ('fiscal_documents', 'fiscal_document_items', 'fiscal_document_events',
                                    'fiscal_document_sources', 'capture_period_counts'));
PREPARE fd_statement FROM @fd_count;
EXECUTE fd_statement;
DEALLOCATE PREPARE fd_statement;

-- para no erro (de propósito, com o motivo no nome da tabela inexistente) quando o estado não é o deste caso
SET @fd_step = CASE
    WHEN @fd_legacy = 4 AND @fd_original = 0 THEN
        'RENAME TABLE legacy_fiscal_xml_documents TO fiscal_xml_documents, legacy_fiscal_xml_document_items TO fiscal_xml_document_items, legacy_service_invoices TO service_invoices, legacy_fiscal_xml_events TO fiscal_xml_events'
    WHEN @fd_legacy = 0 AND @fd_original = 4 AND @fd_new_rows = 0 THEN 'DO 0'
    WHEN @fd_legacy = 0 AND @fd_original = 4 THEN 'SELECT 1 FROM fd_rollback_stopped_new_tables_not_empty'
    WHEN @fd_legacy = 0 AND @fd_original = 0 THEN 'SELECT 1 FROM fd_rollback_stopped_legacy_already_dropped_use_rollback_from_backup'
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
