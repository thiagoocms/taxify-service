-- Volta da remodelagem dos documentos fiscais a partir do backup (spec 2026-11-27, 6.5 itens 0 e 1, CA-FD-13).
--
-- Cobre os dois casos de volta sem perda:
--   * a V87 ou a V88 falhou (a aplicação nova não subiu; as legacy_* ainda existem);
--   * a V89 já rodou (legado apagado), mas as rotinas ainda estão paradas (antes do passo 6 do runbook).
-- Depois do passo 6 (a captura já gravou no modelo novo), esta volta PERDE tudo o que entrou depois do backup
-- (6.5 item 2, saída b): só com a decisão do Tony.
--
-- Pré-requisitos:
--   1. Todas as instâncias da aplicação paradas (nada pode gravar durante a volta).
--   2. O arquivo do backup do passo 1 (scripts/fiscal-documents/backup.sh) com o SHA-256 conferido:
--        sha256sum -c fiscal-documents-backup-<data>.sql.sha256
--   3. O arquivo copiado com o nome fixo fiscal-documents-backup.sql no diretório de onde o cliente mysql roda
--      (o SOURCE abaixo é relativo a ele).
--
-- Como rodar (na EC2, com o MySQL no container do compose; a senha só por variável):
--   export MYSQL_PWD='...'
--   docker exec taxify-mysql mkdir -p /tmp/fd-rollback
--   docker cp fiscal-documents-backup-<data>.sql taxify-mysql:/tmp/fd-rollback/fiscal-documents-backup.sql
--   docker exec -i -w /tmp/fd-rollback -e MYSQL_PWD taxify-mysql mysql -uroot <schema> < scripts/fiscal-documents/rollback.sql
--   docker exec taxify-mysql rm -rf /tmp/fd-rollback
-- O cliente para no primeiro erro (sem --force): se o SOURCE falhar, as tabelas novas ainda não foram apagadas.
--
-- O que faz, em ordem:
--   a. se as quatro legacy_* existem (V87/V88 falhou), devolve os nomes originais (RENAME atômico) - o backup ainda
--      é restaurado por cima em seguida; se só parte delas existe, para com erro sem mexer em nada;
--   b. restaura o backup: as quatro tabelas antigas com os nomes originais e fiscal_xml_sync_control inteira, com as
--      colunas de contador que a V89 removeu (o dump faz DROP TABLE IF EXISTS + CREATE + INSERT de cada uma);
--   c. apaga as cinco tabelas novas;
--   d. apaga do flyway_schema_history as linhas da V86 à V89 (86, 86.1 a 86.4, 87, 88, 89);
--   e. mostra as contagens para conferir com as do backup (backup.sh imprime as da origem).
-- Depois: subir a imagem anterior, conferir as contagens e religar as flags do passo 2.

SET @fd_legacy = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name IN ('legacy_fiscal_xml_documents', 'legacy_fiscal_xml_document_items',
                                     'legacy_service_invoices', 'legacy_fiscal_xml_events'));
SET @fd_original = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                    AND table_name IN ('fiscal_xml_documents', 'fiscal_xml_document_items', 'service_invoices',
                                       'fiscal_xml_events'));
-- a parte das legacy_* sem a outra parte: estado inesperado, para aqui (tabela inexistente de propósito, com o motivo no nome)
SET @fd_step_a = CASE
    WHEN @fd_legacy = 4 AND @fd_original = 0 THEN
        'RENAME TABLE legacy_fiscal_xml_documents TO fiscal_xml_documents, legacy_fiscal_xml_document_items TO fiscal_xml_document_items, legacy_service_invoices TO service_invoices, legacy_fiscal_xml_events TO fiscal_xml_events'
    WHEN @fd_legacy = 0 THEN 'DO 0'
    ELSE 'SELECT 1 FROM fd_rollback_stopped_legacy_and_original_tables_mixed' END;
PREPARE fd_step FROM @fd_step_a;
EXECUTE fd_step;
DEALLOCATE PREPARE fd_step;

-- b. o backup conferido
SOURCE fiscal-documents-backup.sql

-- c. as tabelas novas (filhas antes da mãe; as FKs delas não apontam para as tabelas restauradas)
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS fiscal_document_sources, fiscal_document_events, fiscal_document_items, fiscal_documents,
    capture_period_counts;
SET FOREIGN_KEY_CHECKS = 1;

-- d. o histórico do Flyway volta ao da imagem anterior
DELETE FROM flyway_schema_history WHERE version IN ('86', '86.1', '86.2', '86.3', '86.4', '87', '88', '89');

-- e. conferência (comparar com a saída do backup.sh)
SELECT 'fiscal_xml_documents' AS table_name, COUNT(*) AS row_count FROM fiscal_xml_documents
UNION ALL SELECT 'fiscal_xml_document_items', COUNT(*) FROM fiscal_xml_document_items
UNION ALL SELECT 'service_invoices', COUNT(*) FROM service_invoices
UNION ALL SELECT 'fiscal_xml_events', COUNT(*) FROM fiscal_xml_events
UNION ALL SELECT 'fiscal_xml_sync_control', COUNT(*) FROM fiscal_xml_sync_control
UNION ALL SELECT 'flyway V86-V89', COUNT(*) FROM flyway_schema_history WHERE version IN ('86', '86.1', '86.2', '86.3', '86.4', '87', '88', '89')
UNION ALL SELECT 'new tables left', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name IN ('fiscal_documents', 'fiscal_document_items', 'fiscal_document_events', 'fiscal_document_sources',
                       'capture_period_counts');
