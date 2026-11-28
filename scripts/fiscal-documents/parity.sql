-- Paridade antigo x novo da remodelagem dos documentos fiscais (spec 2026-11-27, FD-RN-25). SÓ LEITURA.
--
-- A V88 já faz esta comparação dentro do deploy (e falha com qualquer diferença). Este script é para conferir de
-- novo por fora: no ensaio local (Q-FD-1, Q-FD-5) ou depois da V89, contra o backup do passo 1 restaurado num schema
-- à parte. Cada consulta devolve SÓ os grupos divergentes (resultado vazio = paridade), com as chaves (empresa, mês,
-- tipo, direção...) e nunca os valores.
--
-- Como rodar:
--   1. restaurar o backup num schema à parte chamado fd_old:
--        KEEP_CHECK_SCHEMA=1 CHECK_SCHEMA=fd_old ... scripts/fiscal-documents/backup.sh   (ensaio, antes da V86)
--      ou, com um backup já feito: CREATE DATABASE fd_old; mysql fd_old < fiscal-documents-backup-<data>.sql
--      (para comparar com outro nome de schema, troque "fd_old." neste arquivo);
--   2. no schema da aplicação (já com V86 a V89):
--        mysql -uroot <schema> < scripts/fiscal-documents/parity.sql
--   3. apagar o fd_old depois (tem dado de cliente): DROP DATABASE fd_old;
-- MySQL 8.0 ou mais novo. As traduções (tipo, direção, papel, origem) são as da V87 (CopyMappings).

-- ids: documentos, NFS-e, itens e eventos do antigo que faltam no novo, e o contrário
SELECT 'ids' AS block, 'documents missing' AS what, COUNT(*) AS divergent FROM fd_old.fiscal_xml_documents o
    WHERE NOT EXISTS (SELECT 1 FROM fiscal_documents n WHERE n.id = o.id) HAVING COUNT(*) > 0
UNION ALL SELECT 'ids', 'service invoices missing', COUNT(*) FROM fd_old.service_invoices o
    WHERE NOT EXISTS (SELECT 1 FROM fiscal_documents n WHERE n.id = o.id) HAVING COUNT(*) > 0
UNION ALL SELECT 'ids', 'documents extra', COUNT(*) FROM fiscal_documents n
    WHERE NOT EXISTS (SELECT 1 FROM fd_old.fiscal_xml_documents o WHERE o.id = n.id)
      AND NOT EXISTS (SELECT 1 FROM fd_old.service_invoices o WHERE o.id = n.id) HAVING COUNT(*) > 0
UNION ALL SELECT 'ids', 'items missing', COUNT(*) FROM fd_old.fiscal_xml_document_items o
    WHERE NOT EXISTS (SELECT 1 FROM fiscal_document_items n WHERE n.id = o.id) HAVING COUNT(*) > 0
UNION ALL SELECT 'ids', 'items extra', COUNT(*) FROM fiscal_document_items n
    WHERE NOT EXISTS (SELECT 1 FROM fd_old.fiscal_xml_document_items o WHERE o.id = n.id) HAVING COUNT(*) > 0
UNION ALL SELECT 'ids', 'events missing', COUNT(*) FROM fd_old.fiscal_xml_events o
    WHERE NOT EXISTS (SELECT 1 FROM fiscal_document_events n WHERE n.id = o.id) HAVING COUNT(*) > 0
UNION ALL SELECT 'ids', 'events extra', COUNT(*) FROM fiscal_document_events n
    WHERE NOT EXISTS (SELECT 1 FROM fd_old.fiscal_xml_events o WHERE o.id = n.id) HAVING COUNT(*) > 0;

-- NF-e, NFC-e e CT-e por empresa x mês de emissão x tipo x direção (sem data = mês nulo)
WITH o AS (
    SELECT company_id, DATE_FORMAT(issue_date, '%Y-%m') AS period,
           CASE WHEN document_type = 'CTE' THEN 'CTE' WHEN TRIM(ide_modelo) = '65' THEN 'NFCE' ELSE 'NFE' END AS type,
           COALESCE(direction, CASE WHEN own THEN CASE TRIM(ide_tipo_operacao) WHEN '0' THEN 'INBOUND'
                    WHEN '1' THEN 'OUTBOUND' END ELSE 'INBOUND' END) AS direction,
           COUNT(*) AS n, SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END) AS canceled,
           SUM(total_valor_total_nota) AS total, SUM(total_valor_icms) AS icms,
           SUM(CASE WHEN document_type = 'CTE' THEN NULL ELSE total_valor_icms_st END) AS icms_st,
           SUM(CASE WHEN document_type = 'CTE' THEN NULL ELSE total_valor_ipi END) AS ipi,
           SUM(CASE WHEN document_type = 'CTE' THEN NULL ELSE total_valor_pis END) AS pis,
           SUM(CASE WHEN document_type = 'CTE' THEN NULL ELSE total_valor_cofins END) AS cofins,
           SUM(ret_pis) AS w_pis, SUM(ret_cofins) AS w_cofins, SUM(ret_csll) AS w_csll, SUM(ret_irrf) AS w_irrf,
           SUM(ret_inss) AS w_inss, SUM(total_valor_ibs) AS ibs, SUM(total_valor_cbs) AS cbs,
           SUM(CASE WHEN own THEN 1 ELSE 0 END) AS issuer
    -- own issue as the V87 (CopyMappings.ownIssue): own_issue, or for a row without it and without direction the old
    -- FiscalDirectionBackfill's rule (the company's document is the issuer's CNPJ or CPF)
    FROM (SELECT x.*, CASE WHEN x.own_issue IS NOT NULL THEN x.own_issue = 1 WHEN x.direction IS NULL THEN COALESCE(NULLIF(REPLACE(REPLACE(REPLACE(REPLACE((SELECT od.number FROM companies oc JOIN documents od ON od.id = oc.document_id WHERE oc.id = x.company_id), '.', ''), '/', ''), '-', ''), ' ', ''), '') IS NOT NULL AND (REPLACE(REPLACE(REPLACE(REPLACE((SELECT od.number FROM companies oc JOIN documents od ON od.id = oc.document_id WHERE oc.id = x.company_id), '.', ''), '/', ''), '-', ''), ' ', '') = REPLACE(REPLACE(REPLACE(REPLACE(x.issuer_cnpj, '.', ''), '/', ''), '-', ''), ' ', '') OR REPLACE(REPLACE(REPLACE(REPLACE((SELECT od.number FROM companies oc JOIN documents od ON od.id = oc.document_id WHERE oc.id = x.company_id), '.', ''), '/', ''), '-', ''), ' ', '') = REPLACE(REPLACE(REPLACE(REPLACE(x.emit_cpf, '.', ''), '/', ''), '-', ''), ' ', '')), FALSE) ELSE FALSE END AS own
          FROM fd_old.fiscal_xml_documents x) t GROUP BY 1, 2, 3, 4),
n AS (
    SELECT company_id, DATE_FORMAT(issue_date, '%Y-%m') AS period, document_type AS type, direction, COUNT(*) AS n,
           SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END) AS canceled, SUM(total_amount) AS total,
           SUM(icms_amount) AS icms, SUM(icms_st_amount) AS icms_st, SUM(ipi_amount) AS ipi, SUM(pis_amount) AS pis,
           SUM(cofins_amount) AS cofins, SUM(withheld_pis_amount) AS w_pis, SUM(withheld_cofins_amount) AS w_cofins,
           SUM(withheld_csll_amount) AS w_csll, SUM(withheld_irrf_amount) AS w_irrf, SUM(withheld_inss_amount) AS w_inss,
           SUM(ibs_amount) AS ibs, SUM(cbs_amount) AS cbs, SUM(CASE WHEN company_role = 'ISSUER' THEN 1 ELSE 0 END) AS issuer
    FROM fiscal_documents WHERE document_type IN ('NFE', 'NFCE', 'CTE') GROUP BY 1, 2, 3, 4)
SELECT 'goods' AS block, o.company_id, o.period, o.type, o.direction FROM o
    LEFT JOIN n ON n.company_id = o.company_id AND n.period <=> o.period AND n.type = o.type AND n.direction <=> o.direction
    WHERE NOT (n.n <=> o.n AND n.canceled <=> o.canceled AND n.total <=> o.total AND n.icms <=> o.icms
               AND n.icms_st <=> o.icms_st AND n.ipi <=> o.ipi AND n.pis <=> o.pis AND n.cofins <=> o.cofins
               AND n.w_pis <=> o.w_pis AND n.w_cofins <=> o.w_cofins AND n.w_csll <=> o.w_csll AND n.w_irrf <=> o.w_irrf
               AND n.w_inss <=> o.w_inss AND n.ibs <=> o.ibs AND n.cbs <=> o.cbs AND n.issuer <=> o.issuer)
UNION ALL
SELECT 'goods (only new)', n.company_id, n.period, n.type, n.direction FROM n
    LEFT JOIN o ON n.company_id = o.company_id AND n.period <=> o.period AND n.type = o.type AND n.direction <=> o.direction
    WHERE o.company_id IS NULL;

-- NFS-e por empresa x mês x direção (PRESTADA = OUTBOUND, TOMADA = INBOUND, TERCEIRO = nula)
WITH o AS (
    SELECT company_id, DATE_FORMAT(issue_date, '%Y-%m') AS period,
           CASE direction WHEN 'PRESTADA' THEN 'OUTBOUND' WHEN 'TOMADA' THEN 'INBOUND' END AS direction,
           COUNT(*) AS n, SUM(CASE WHEN canceled = 1 THEN 1 ELSE 0 END) AS canceled, SUM(valor_servico) AS service,
           SUM(valor_iss) AS iss, SUM(valor_iss_retido) AS iss_withheld, SUM(valor_irrf) AS irrf, SUM(valor_pis) AS pis,
           SUM(valor_cofins) AS cofins, SUM(valor_csll) AS csll, SUM(valor_inss) AS inss, SUM(valor_liquido) AS net
    FROM fd_old.service_invoices GROUP BY 1, 2, 3),
n AS (
    SELECT company_id, DATE_FORMAT(issue_date, '%Y-%m') AS period, direction, COUNT(*) AS n,
           SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END) AS canceled, SUM(total_amount) AS service,
           SUM(iss_amount) AS iss, SUM(withheld_iss_amount) AS iss_withheld, SUM(withheld_irrf_amount) AS irrf,
           SUM(pis_amount) AS pis, SUM(cofins_amount) AS cofins, SUM(withheld_csll_amount) AS csll,
           SUM(withheld_inss_amount) AS inss, SUM(nfse_net_amount) AS net
    FROM fiscal_documents WHERE document_type = 'NFSE' GROUP BY 1, 2, 3)
SELECT 'service-invoices' AS block, o.company_id, o.period, o.direction FROM o
    LEFT JOIN n ON n.company_id = o.company_id AND n.period <=> o.period AND n.direction <=> o.direction
    WHERE NOT (n.n <=> o.n AND n.canceled <=> o.canceled AND n.service <=> o.service AND n.iss <=> o.iss
               AND n.iss_withheld <=> o.iss_withheld AND n.irrf <=> o.irrf AND n.pis <=> o.pis AND n.cofins <=> o.cofins
               AND n.csll <=> o.csll AND n.inss <=> o.inss AND n.net <=> o.net)
UNION ALL
SELECT 'service-invoices (only new)', n.company_id, n.period, n.direction FROM n
    LEFT JOIN o ON n.company_id = o.company_id AND n.period <=> o.period AND n.direction <=> o.direction
    WHERE o.company_id IS NULL;

-- itens por empresa x mês x CFOP (aparado)
WITH o AS (
    SELECT d.company_id, DATE_FORMAT(d.issue_date, '%Y-%m') AS period, TRIM(i.cfop) AS cfop, COUNT(*) AS n,
           SUM(i.valor_total) AS total, SUM(i.valor_icms) AS icms, SUM(i.ibs_valor) AS ibs, SUM(i.cbs_valor) AS cbs
    FROM fd_old.fiscal_xml_document_items i JOIN fd_old.fiscal_xml_documents d ON d.id = i.document_id GROUP BY 1, 2, 3),
n AS (
    SELECT d.company_id, DATE_FORMAT(d.issue_date, '%Y-%m') AS period, i.cfop, COUNT(*) AS n, SUM(i.total_amount) AS total,
           SUM(i.icms_amount) AS icms, SUM(i.ibs_amount) AS ibs, SUM(i.cbs_amount) AS cbs
    FROM fiscal_document_items i JOIN fiscal_documents d ON d.id = i.document_id
    WHERE d.document_type IN ('NFE', 'NFCE', 'CTE') GROUP BY 1, 2, 3)
SELECT 'items' AS block, o.company_id, o.period, o.cfop FROM o
    LEFT JOIN n ON n.company_id = o.company_id AND n.period <=> o.period AND n.cfop <=> o.cfop
    WHERE NOT (n.n <=> o.n AND n.total <=> o.total AND n.icms <=> o.icms AND n.ibs <=> o.ibs AND n.cbs <=> o.cbs)
UNION ALL
SELECT 'items (only new)', n.company_id, n.period, n.cfop FROM n
    LEFT JOIN o ON n.company_id = o.company_id AND n.period <=> o.period AND n.cfop <=> o.cfop
    WHERE o.company_id IS NULL;

-- CFOP efetivo (FD-RN-57) por empresa x CFOP: o lado antigo pela regra da V87 (CopyMappings.rectifiedEffectiveCfop),
-- com as regras de produto do schema da aplicação
WITH o AS (
    SELECT ld.company_id, CASE WHEN ld.document_type <> 'CTE' AND ld.rectified_file_path IS NOT NULL THEN
             CASE WHEN TRIM(l.cfop) IS NULL THEN NULL ELSE COALESCE((
                 SELECT CASE WHEN ld.last_rectification_checked_at IS NOT NULL AND (COALESCE(r.last_modified_date, r.created_date) IS NULL
                             OR COALESCE(r.last_modified_date, r.created_date) <= ld.last_rectification_checked_at)
                        THEN NULLIF(TRIM(CASE WHEN TRIM(r.cfop_correto) REGEXP '^[0-9]{4}$' AND TRIM(ld.ide_destino_operacao) IN ('1', '2', '3') THEN CASE WHEN LEFT(TRIM(r.cfop_correto), 1) IN ('1', '2', '3') THEN CONCAT(TRIM(ld.ide_destino_operacao), SUBSTRING(TRIM(r.cfop_correto), 2)) WHEN LEFT(TRIM(r.cfop_correto), 1) IN ('5', '6', '7') THEN CONCAT(CAST(TRIM(ld.ide_destino_operacao) + 4 AS CHAR), SUBSTRING(TRIM(r.cfop_correto), 2)) ELSE TRIM(r.cfop_correto) END ELSE TRIM(r.cfop_correto) END), '') END
                 FROM product_fiscal_rules r WHERE r.company_id = ld.company_id AND r.deleted = 0
                   AND CAST(r.codigo_produto AS BINARY) = CAST(l.codigo AS BINARY)
                   AND (CAST(REPLACE(REPLACE(REPLACE(REPLACE(r.documento_fornecedor, '.', ''), '/', ''), '-', ''), ' ', '') AS BINARY) = CAST(CASE WHEN NULLIF(TRIM(ld.issuer_cnpj), '') IS NOT NULL THEN ld.issuer_cnpj WHEN NULLIF(TRIM(ld.emit_cpf), '') IS NOT NULL THEN ld.emit_cpf END AS BINARY) OR NULLIF(REPLACE(REPLACE(REPLACE(REPLACE(r.documento_fornecedor, '.', ''), '/', ''), '-', ''), ' ', ''), '') IS NULL)
                 ORDER BY NULLIF(REPLACE(REPLACE(REPLACE(REPLACE(r.documento_fornecedor, '.', ''), '/', ''), '-', ''), ' ', ''), '') IS NULL, r.id LIMIT 1), TRIM(l.cfop)) END
         ELSE TRIM(l.cfop) END AS cfop, COUNT(*) AS n
    FROM fd_old.fiscal_xml_document_items l JOIN fd_old.fiscal_xml_documents ld ON ld.id = l.document_id GROUP BY 1, 2),
n AS (
    SELECT d.company_id, i.effective_cfop AS cfop, COUNT(*) AS n
    FROM fiscal_document_items i JOIN fiscal_documents d ON d.id = i.document_id
    WHERE d.document_type IN ('NFE', 'NFCE', 'CTE') GROUP BY 1, 2)
SELECT 'effective-cfop' AS block, o.company_id, o.cfop FROM o
    LEFT JOIN n ON n.company_id = o.company_id AND n.cfop <=> o.cfop
    WHERE NOT (n.n <=> o.n)
UNION ALL
SELECT 'effective-cfop (only new)', n.company_id, n.cfop FROM n
    LEFT JOIN o ON n.company_id = o.company_id AND n.cfop <=> o.cfop
    WHERE o.company_id IS NULL;

-- eventos por empresa x tipo x registrado
WITH o AS (SELECT company_id, event_type, registered = 1 AS registered, COUNT(*) AS n,
                  SUM(CASE WHEN document_id IS NOT NULL THEN 1 ELSE 0 END) AS linked
           FROM fd_old.fiscal_xml_events GROUP BY 1, 2, 3),
n AS (SELECT company_id, event_type, registered = 1 AS registered, COUNT(*) AS n,
             SUM(CASE WHEN document_id IS NOT NULL THEN 1 ELSE 0 END) AS linked
      FROM fiscal_document_events GROUP BY 1, 2, 3)
SELECT 'events' AS block, o.company_id, o.event_type, o.registered FROM o
    LEFT JOIN n ON n.company_id = o.company_id AND n.event_type = o.event_type AND n.registered = o.registered
    WHERE NOT (n.n <=> o.n AND n.linked <=> o.linked)
UNION ALL
SELECT 'events (only new)', n.company_id, n.event_type, n.registered FROM n
    LEFT JOIN o ON n.company_id = o.company_id AND n.event_type = o.event_type AND n.registered = o.registered
    WHERE o.company_id IS NULL;

-- origens por empresa x fonte: documentos com a data antiga (ou de upload) x linhas, e as datas mínima e máxima
WITH old_sources AS (
    SELECT company_id, 'SIEG' AS source, 'PERIOD' AS mode, found_in_sieg_at AS seen FROM fd_old.fiscal_xml_documents WHERE found_in_sieg_at IS NOT NULL
    UNION ALL SELECT company_id, 'SEFAZ_DFE', 'CURSOR', found_in_sefaz_at FROM fd_old.fiscal_xml_documents WHERE found_in_sefaz_at IS NOT NULL
    UNION ALL SELECT company_id, 'UPLOAD', 'NONE', created_date FROM fd_old.fiscal_xml_documents WHERE source_system = 'UPLOAD'
    UNION ALL SELECT company_id, 'SIEG', 'PERIOD', found_in_sieg_at FROM fd_old.service_invoices WHERE found_in_sieg_at IS NOT NULL
    UNION ALL SELECT company_id, 'NFSE_ADN', 'CURSOR', found_in_adn_at FROM fd_old.service_invoices WHERE found_in_adn_at IS NOT NULL
    UNION ALL SELECT company_id, 'UPLOAD', 'NONE', created_date FROM fd_old.service_invoices WHERE source_system = 'UPLOAD'),
o AS (SELECT company_id, source, mode, COUNT(*) AS n, MIN(seen) AS first_min, MAX(seen) AS first_max FROM old_sources
      GROUP BY 1, 2, 3),
n AS (SELECT company_id, source, retrieval_mode AS mode, COUNT(*) AS n, MIN(first_seen_at) AS first_min,
             MAX(first_seen_at) AS first_max
      FROM fiscal_document_sources GROUP BY 1, 2, 3)
SELECT 'sources' AS block, o.company_id, o.source, o.mode FROM o
    LEFT JOIN n ON n.company_id = o.company_id AND n.source = o.source AND n.mode = o.mode
    WHERE NOT (n.n <=> o.n AND n.first_min <=> o.first_min AND n.first_max <=> o.first_max)
UNION ALL
SELECT 'sources (only new)', n.company_id, n.source, n.mode FROM n
    LEFT JOIN o ON n.company_id = o.company_id AND n.source = o.source AND n.mode = o.mode
    WHERE o.company_id IS NULL;

-- contagens da captura: mês corrente de cada lado (as diferenças conhecidas e o mês anterior a V88 confere campo a campo)
SELECT 'capture-counts' AS block, c.company_id, 'RECEIVED' AS side FROM fd_old.fiscal_xml_sync_control c
    LEFT JOIN capture_period_counts p ON p.company_id = c.company_id AND p.channel = 'SIEG' AND p.document_type = 'NFE'
         AND p.side = 'RECEIVED' AND p.period_month = DATE_FORMAT(c.data_emissao_inicio, '%Y-%m')
    WHERE c.data_emissao_inicio IS NOT NULL
      AND NOT (p.provider_total <=> c.total_sieg AND p.imported_total <=> c.total_importado
               AND p.provider_counted_at <=> c.ultima_verificacao_em AND p.window_start <=> c.data_emissao_inicio
               AND p.window_end <=> c.data_emissao_fim AND p.closed_at IS NULL)
UNION ALL
SELECT 'capture-counts', c.company_id, 'ISSUED' FROM fd_old.fiscal_xml_sync_control c
    LEFT JOIN capture_period_counts p ON p.company_id = c.company_id AND p.channel = 'SIEG' AND p.document_type = 'NFE'
         AND p.side = 'ISSUED' AND p.period_month = DATE_FORMAT(c.data_emissao_inicio, '%Y-%m')
    WHERE c.data_emissao_inicio IS NOT NULL
      AND (c.issued_total_sieg IS NOT NULL OR c.issued_total_imported IS NOT NULL OR c.issued_checked_at IS NOT NULL)
      AND NOT (p.provider_total <=> c.issued_total_sieg AND p.imported_total <=> COALESCE(c.issued_total_imported, 0)
               AND p.provider_counted_at <=> c.issued_checked_at AND p.closed_at IS NULL);
