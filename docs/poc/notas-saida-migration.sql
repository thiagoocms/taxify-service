-- POC notas de saída - migração MySQL.
-- ddl-auto=update cria a coluna "direcao" e as novas unique constraints, mas NÃO remove
-- as antigas (access_key sozinha / company_id sozinho), e elas passam a bloquear:
--   * a mesma nota em duas empresas (SAIDA de uma = ENTRADA da outra);
--   * a segunda linha de controle (SAIDA) da mesma empresa.
-- Rodar ANTES de subir a versão com SAIDA habilitada (ou com
-- FISCAL_XML_SYNC_DIRECTIONS=ENTRADA até a migração rodar).

-- 1) Coluna nova + backfill: tudo que existe hoje veio do CNPJdest, ou seja, é ENTRADA.
--    (Pular os dois ALTER se o Hibernate já tiver criado a coluna - MySQL não tem
--    "ADD COLUMN IF NOT EXISTS".)
ALTER TABLE fiscal_xml_documents    ADD COLUMN direcao VARCHAR(10) NULL;
ALTER TABLE fiscal_xml_sync_control ADD COLUMN direcao VARCHAR(10) NULL;
UPDATE fiscal_xml_documents    SET direcao = 'ENTRADA' WHERE direcao IS NULL;
UPDATE fiscal_xml_sync_control SET direcao = 'ENTRADA' WHERE direcao IS NULL;

-- 2) Descobrir o nome das unique antigas (o Hibernate gera nomes tipo UKxxxxxxxx):
--    SHOW INDEX FROM fiscal_xml_documents    WHERE Non_unique = 0 AND Column_name = 'access_key';
--    SHOW INDEX FROM fiscal_xml_sync_control WHERE Non_unique = 0 AND Column_name = 'company_id';

-- 3) Criar as novas ANTES de remover as antigas: company_id tem FK, e o MySQL exige um
--    índice começando por company_id - o composto novo passa a cumprir esse papel.
ALTER TABLE fiscal_xml_documents    ADD CONSTRAINT uk_fxd_company_access_key UNIQUE (company_id, access_key);
ALTER TABLE fiscal_xml_sync_control ADD CONSTRAINT uk_fxsc_company_direcao   UNIQUE (company_id, direcao);

-- 4) Remover as antigas (trocar pelos nomes encontrados no passo 2):
-- ALTER TABLE fiscal_xml_documents    DROP INDEX <UK_access_key>;
-- ALTER TABLE fiscal_xml_sync_control DROP INDEX <UK_company_id>;
