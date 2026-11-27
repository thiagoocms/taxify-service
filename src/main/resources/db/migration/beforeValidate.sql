-- Flyway callback beforeValidate (roda antes da validação de toda subida - validate-on-migrate - e do "flyway validate").
-- MySQL não tem DDL transacional: uma migration que falha fica no flyway_schema_history com success = 0 e a subida seguinte para no validate ("Detected failed
-- migration"), exigindo "flyway repair" manual. Aqui o registro de falha é apagado SÓ para migrations conhecidas como
-- idempotentes e atômicas, que podem simplesmente rodar de novo:
--   80 = V80__IbsCbsRateStatus: um ALTER TABLE único (aplica tudo ou nada) e seeds com WHERE; desiste em 30 s quando
--        o lock de metadados de ibs_cbs_rates não sai, sem mudar nada.
-- Qualquer outra migration que falhar continua exigindo a análise humana (e o repair). Sem a tabela de histórico
-- (banco novo), não faz nada.
SET @taxify_history = (SELECT COUNT(*) FROM information_schema.tables
                       WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history');
SET @taxify_repair = IF(@taxify_history > 0,
                        'DELETE FROM flyway_schema_history WHERE version IN (''80'') AND success = 0',
                        'SELECT 1');
PREPARE taxify_repair_statement FROM @taxify_repair;
EXECUTE taxify_repair_statement;
DEALLOCATE PREPARE taxify_repair_statement;
