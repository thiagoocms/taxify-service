-- Registro das conferências do backup da remodelagem dos documentos fiscais (spec 2026-11-27, FD-RN-52).
--
-- Criada (se não existir) pelo scripts/fiscal-documents/backup.sh no schema da aplicação, e preenchida por ele só
-- quando o backup confere (restauração de teste com as mesmas contagens e somas): uma linha por tabela do dump, com
-- o SHA-256 do arquivo e o COUNT(*) da origem. A V89 só apaga as legacy_* quando o FD_BACKUP_SHA256 informado é o do
-- último registro daqui e as contagens das legacy_* são as registradas (V89__DropLegacyFiscalDocuments).
-- Sem dado de cliente: só hash, nome de tabela e contagem. MySQL 5.7 e 8.x.
CREATE TABLE IF NOT EXISTS fiscal_documents_backup_checks (
    id            bigint      NOT NULL AUTO_INCREMENT,
    backup_sha256 char(64)    NOT NULL,
    table_name    varchar(64) NOT NULL,
    row_count     bigint      NOT NULL,
    created_at    datetime    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_fdbc_sha (backup_sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 ROW_FORMAT = DYNAMIC;
