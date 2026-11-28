package db.migration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fictitious data in the pre-remodel schema (Flyway up to the master's last migration). Companies E1 and E2 of firm
 * F1, E3 of firm F2 (blocked); CNPJs and CPFs are fictitious; no real data.
 */
final class LegacyFixtures {

    static final String E1 = "c1000000-0000-4000-8000-000000000001";
    static final String E2 = "c1000000-0000-4000-8000-000000000002";
    static final String E3 = "c1000000-0000-4000-8000-000000000003";
    static final String E1_CNPJ = "11222333000181";
    static final String SUPPLIER_CNPJ = "12345678000195";
    static final String SUPPLIER_CPF = "52998224725";

    static String key(int n) {
        return "26261012345678000195550010000000" + String.format("%012d", n);
    }

    static String doc(String name) {
        return String.format("%-36s", "d-" + name + "-").replace(' ', 'x');
    }

    private LegacyFixtures() {
    }

    static void insert(JdbcTemplate jdbc, String table, Map<String, Object> values) {
        String columns = String.join(", ", values.keySet());
        String marks = String.join(", ", values.keySet().stream().map(k -> "?").toList());
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")", values.values().toArray());
    }

    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static Map<String, Object> audited(Object... pairs) {
        Map<String, Object> map = row("deleted", 0, "created_date", "2026-11-02 08:00:00", "created_by", "qa@exemplo.test");
        map.putAll(row(pairs));
        return map;
    }

    static void companies(JdbcTemplate jdbc) {
        insert(jdbc, "accounting_firms", audited("id", "f1", "name", "Escritório F1"));
        insert(jdbc, "accounting_firms", audited("id", "f2", "name", "Escritório F2"));
        String[][] companies = {{E1, "f1", E1_CNPJ}, {E2, "f1", "12345678000195"}, {E3, "f2", "11444777000161"}};
        for (String[] c : companies) {
            insert(jdbc, "documents", audited("id", "doc-" + c[0].substring(c[0].length() - 1), "number", c[2], "type", "CNPJ"));
            insert(jdbc, "companies", audited("id", c[0], "accounting_firm_id", c[1],
                    "document_id", "doc-" + c[0].substring(c[0].length() - 1), "name", "Empresa " + c[0].substring(c[0].length() - 1),
                    "active", 1));
        }
    }

    static Map<String, Object> nfe(String id, String company, Object... pairs) {
        Map<String, Object> map = audited("id", id, "company_id", company, "document_type", "NFE", "source_system", "SIEG",
                "xml_file_path", "xml/" + id + ".xml", "ide_modelo", "55");
        map.putAll(row(pairs));
        return map;
    }

    static Map<String, Object> nfse(String id, String company, Object... pairs) {
        Map<String, Object> map = audited("id", id, "company_id", company, "layout", "NACIONAL", "direction", "TOMADA",
                "iss_retido", 0, "pis_cofins_retidos", 0, "canceled", 0, "xml_file_path", "nfse/" + id + ".xml",
                "source_system", "SIEG");
        map.putAll(row(pairs));
        return map;
    }

    static Map<String, Object> item(String id, String document, Object... pairs) {
        Map<String, Object> map = row("id", id, "document_id", document);
        map.putAll(row(pairs));
        return map;
    }

    /**
     * The scenario of the spec's test cases (CT-FD-01 to 05, 11, 15 to 19, 27, 29, 31, 36): every conversion of 3.4 to
     * 3.11 appears at least once.
     */
    static void scenario(JdbcTemplate jdbc) {
        companies(jdbc);
        String fxd = "fiscal_xml_documents";
        // CT-FD-01: E1, Oct/2026, INBOUND K1 1,000.00, K2 2,500.50, K3 499.50, K4 300.00 canceled; OUTBOUND K5 750.25
        insert(jdbc, fxd, nfe(doc("k1"), E1, "access_key", key(1), "direction", "INBOUND", "own_issue", 0,
                "issue_date", "2026-10-05 10:00:00", "ide_serie", "1", "ide_numero", "101", "ide_natureza_operacao", "Compra",
                "issuer_cnpj", SUPPLIER_CNPJ, "emit_nome", "Fornecedor Exemplo", "emit_end_uf", "PE",
                "emit_end_codigo_municipio", "2611606", "recipient_cnpj", E1_CNPJ, "dest_nome", "Empresa 1", "dest_end_uf", "PE",
                "total_valor_total_nota", "1000.00", "total_valor_icms", "120.00", "total_base_calculo_icms", "1000.00",
                "total_valor_icms_st", "10.00", "total_valor_ipi", "5.00", "total_valor_pis", "6.50", "total_valor_cofins", "30.00",
                "ret_pis", "6.50", "ret_cofins", "30.00", "ret_csll", "10.00", "ret_irrf", "15.00", "ret_inss", "11.00",
                "total_ibscbs_base_calculo", "1000.00", "total_valor_ibs_uf", "1.00", "total_valor_ibs_mun", "0.00",
                "total_valor_ibs", "1.00", "total_valor_cbs", "9.00", "ide_codigo_uf", "26", "ide_tipo_operacao", "1",
                "ide_destino_operacao", "1", "ide_ambiente", "1", "ide_finalidade_emissao", "1", "ide_consumidor_final", "0",
                "ide_indicador_presenca", "9", "ide_tipo_impressao", "1", "ide_tipo_emissao", "1", "ide_digito_verificador", "7",
                "ide_processo_emissao", "0", "ide_versao_processo_emissao", "Emissor 4.0", "ide_data_saida_entrada", "2026-10-06 09:00:00",
                "ide_codigo_municipio_fato_gerador", "2611606", "emit_nome_fantasia", "Fornecedor", "emit_inscricao_estadual", "123456789",
                "emit_inscricao_estadual_st", "987", "emit_inscricao_municipal", "555", "emit_cnae", "4711302",
                "emit_regime_tributario", "3", "dest_inscricao_estadual", "111", "dest_indicador_ie", "1", "dest_email", "nfe@exemplo.test",
                "emit_end_logradouro", "Rua A", "emit_end_numero", "10", "emit_end_complemento", "Sala 1", "emit_end_bairro", "Centro",
                "emit_end_municipio", "Recife", "emit_end_cep", "50000000", "emit_end_telefone", "8133330000",
                "emit_codigo_pais", "1058", "emit_pais", "Brasil", "dest_end_logradouro", "Rua B", "dest_end_numero", "20",
                "dest_end_complemento", "Galpão", "dest_end_bairro", "Boa Vista", "dest_end_municipio", "Recife",
                "dest_end_cep", "50100000", "dest_end_telefone", "8133331111", "dest_codigo_pais", "1058", "dest_pais", "Brasil",
                "total_valor_icms_desonerado", "1.11", "total_valor_fcp", "2.22", "total_base_calculo_icms_st", "3.33",
                "total_valor_fcp_st", "4.44", "total_valor_produtos", "990.00", "total_valor_frete", "5.55",
                "total_valor_seguro", "6.66", "total_valor_desconto", "7.77", "total_valor_importacao", "8.88",
                "total_valor_outras_despesas", "9.99", "total_valor_aproximado_tributos", "12.34",
                "found_in_sieg_at", "2026-11-05 08:00:00", "found_in_sefaz_at", "2026-11-05 09:15:00",
                "metadata_version", 6, "review_status", "PENDING", "rectified_file_path", "rect/k1.xml",
                "last_rectification_checked_at", "2026-11-06 07:00:00", "last_modified_by", "fiscal1@exemplo.test",
                "last_modified_date", "2026-11-06 07:30:00"));
        insert(jdbc, fxd, nfe(doc("k2"), E1, "access_key", key(2), "direction", "INBOUND", "own_issue", 0,
                "issue_date", "2026-10-07 10:00:00", "issuer_cnpj", "  ", "emit_cpf", SUPPLIER_CPF, "recipient_cnpj", E1_CNPJ,
                "total_valor_total_nota", "2500.50", "found_in_sieg_at", "2026-11-06 08:00:00", "status", "AUTHORIZED",
                "review_status", "REVIEWED", "reviewed_at", "2026-11-07 10:00:00", "reviewed_by", "fiscal1@exemplo.test",
                "review_note", "ok"));
        insert(jdbc, fxd, nfe(doc("k3"), E1, "access_key", key(3), "direction", "INBOUND", "own_issue", 0, "ide_modelo", " 55 ",
                "issue_date", "2026-10-08 10:00:00", "total_valor_total_nota", "499.50", "source_system", "UPLOAD",
                "created_date", "2026-11-03 14:00:00"));
        insert(jdbc, fxd, nfe(doc("k4"), E1, "access_key", key(4), "direction", "INBOUND", "own_issue", 0,
                "issue_date", "2026-10-09 10:00:00", "total_valor_total_nota", "300.00", "status", "CANCELED",
                "canceled_at", "2026-10-20 10:00:00"));
        insert(jdbc, fxd, nfe(doc("k5"), E1, "access_key", key(5), "direction", "OUTBOUND", "own_issue", 1,
                "issue_date", "2026-10-10 10:00:00", "issuer_cnpj", E1_CNPJ, "dest_id_estrangeiro", "X-99887766",
                "total_valor_total_nota", "750.25", "source_system", "SEFAZ_DIST", "found_in_sefaz_at", "2026-11-07 08:00:00"));
        // CT-FD-04: model 65 = NFC-e; FD-RN-56: own issue without direction, tpNF 1 → OUTBOUND
        insert(jdbc, fxd, nfe(doc("k6"), E1, "access_key", key(6), "direction", null, "own_issue", 1, "ide_tipo_operacao", "1", "ide_modelo", "65",
                "issue_date", "2026-10-11 10:00:00", "issuer_cnpj", E1_CNPJ, "dest_cpf", "39053344705", "total_valor_total_nota", "89.90"));
        // CT-e of upload: common block + ICMS only, model 57, metadata version 0
        insert(jdbc, fxd, nfe(doc("k7"), E1, "document_type", "CTE", "ide_modelo", null, "access_key", key(7),
                "direction", "INBOUND", "own_issue", 0, "issue_date", "2026-10-12 10:00:00", "total_valor_total_nota", "150.00",
                "total_valor_icms", "18.00", "total_base_calculo_icms", "150.00", "total_valor_pis", "1.00",
                "ide_codigo_uf", "26", "metadata_version", 3, "source_system", "UPLOAD", "rectified_file_path", "rect/k7.xml"));
        // CT-FD-11 and 17: own_issue null, no issue date, no key (the "strange file" of an upload)
        insert(jdbc, fxd, nfe(doc("k8"), E1, "access_key", null, "direction", "INBOUND", "own_issue", null,
                "issue_date", null, "metadata_version", null, "total_valor_total_nota", "10.00", "source_system", "UPLOAD"));
        // CT-FD-16: month turn, no conversion; FD-RN-56: received without direction → INBOUND
        insert(jdbc, fxd, nfe(doc("k9"), E1, "access_key", key(9), "direction", null, "own_issue", 0,
                "issue_date", "2026-10-31 23:59:59", "total_valor_total_nota", "1.00"));
        insert(jdbc, fxd, nfe(doc("k10"), E1, "access_key", key(10), "direction", "INBOUND", "own_issue", 0,
                "issue_date", "2026-11-01 00:00:00", "total_valor_total_nota", "2.00"));
        // CT-FD-15: same key in E2, as issuer; and an authorized row with a stray canceled_at (I-02)
        insert(jdbc, fxd, nfe(doc("e2k1"), E2, "access_key", key(1), "direction", "OUTBOUND", "own_issue", 1,
                "issue_date", "2026-10-05 10:00:00", "issuer_cnpj", SUPPLIER_CNPJ, "total_valor_total_nota", "1000.00"));
        insert(jdbc, fxd, nfe(doc("e2k2"), E2, "access_key", key(22), "direction", "INBOUND", "own_issue", 0,
                "issue_date", "2026-10-06 10:00:00", "status", null, "canceled_at", "2026-10-07 10:00:00",
                "total_valor_total_nota", "50.00", "deleted", 1));
        // CT-FD-18: firm F2 (blocked) is copied like any other
        insert(jdbc, fxd, nfe(doc("e3k1"), E3, "access_key", key(31), "direction", "INBOUND", "own_issue", 0,
                "issue_date", "2026-09-15 10:00:00", "total_valor_total_nota", "77.70"));

        // CT-FD-03: items of K1, CFOP with a space
        insert(jdbc, "fiscal_xml_document_items", item("i1000000-0000-4000-8000-000000000001", doc("k1"), "numero_item", 1,
                "codigo", "P-1", "codigo_barras", "7890000000001", "descricao", "Tecido algodão", "ncm", "52081100", "cest", "1234567",
                "codigo_beneficio", "PE000001", "cfop", "1102 ", "unidade_comercial", "M", "unidade_tributavel", "KG",
                "quantidade_comercial", "10.5000", "valor_unitario_comercial", "57.1429", "quantidade_tributavel", "2.1234",
                "valor_unitario_tributavel", "282.5658", "valor_total", "600.00", "origem_mercadoria", "0", "cst_icms", "00",
                "csosn", "102", "modalidade_bc_icms", "3", "modalidade_bc_icms_st", "4", "base_calculo_icms", "600.00",
                "valor_icms", "72.00", "valor_icms_desonerado", "0.50", "base_calculo_icms_st", "700.00", "valor_icms_st", "6.00",
                "aliquota_icms", "12.0000", "reducao_bc_icms", "33.3333", "mva_icms_st", "40.0000", "aliquota_icms_st", "18.0000",
                "cst_ipi", "50", "cst_pis", "01", "cst_cofins", "01", "base_calculo_ipi", "600.00", "valor_ipi", "3.00",
                "base_calculo_pis", "600.00", "valor_pis", "3.90", "base_calculo_cofins", "600.00", "valor_cofins", "18.00",
                "aliquota_ipi", "0.5000", "aliquota_pis", "0.6500", "aliquota_cofins", "3.0000", "ibscbs_cst", "000",
                "ibscbs_class_trib", "000001", "ibscbs_base_calculo", "600.00", "ibs_uf_valor", "0.60", "ibs_mun_valor", "0.00",
                "ibs_valor", "0.60", "cbs_valor", "5.40", "ibs_uf_aliquota", "0.1000", "ibs_uf_reducao_aliquota", "0.0000",
                "ibs_mun_aliquota", "0.0000", "ibs_mun_reducao_aliquota", "0.0000", "cbs_aliquota", "0.9000",
                "cbs_reducao_aliquota", "0.0000", "ibscbs_diferimento_devolucao", 0, "aliquota_credito_sn", "1.2500",
                "valor_credito_sn", "7.50"));
        insert(jdbc, "fiscal_xml_document_items", item("i1000000-0000-4000-8000-000000000002", doc("k1"), "numero_item", 2,
                "cfop", "1102", "valor_total", "400.00", "valor_icms", "48.00"));
        insert(jdbc, "fiscal_xml_document_items", item("i1000000-0000-4000-8000-000000000003", doc("k6"), "numero_item", 1,
                "cfop", "5102", "valor_total", "89.90"));

        // NFS-e (CT-FD-02, 19)
        String si = "service_invoices";
        insert(jdbc, si, nfse(doc("s1"), E1, "access_key", "2611606" + "1".repeat(43), "numero", "77",
                "codigo_verificacao", "ABC123", "issue_date", "2026-10-10 11:00:00", "competence", "2026-10-01",
                "prestador_documento", SUPPLIER_CNPJ, "prestador_nome", "Prestador Exemplo", "prestador_municipio", "2611606",
                "tomador_documento", E1_CNPJ, "tomador_nome", "Empresa 1", "municipio_incidencia", "2611606",
                "codigo_servico", "1.07", "codigo_tributacao_municipal", "010701", "descricao_servico", "x".repeat(1000),
                "valor_servico", "1200.00", "valor_deducoes", "100.00", "base_calculo", "1200.00", "aliquota_iss", "2.0000",
                "valor_iss", "24.00", "iss_retido", 1, "valor_iss_retido", "24.00", "valor_irrf", "18.00", "valor_pis", "7.80",
                "valor_cofins", "36.00", "pis_cofins_retidos", 1, "valor_csll", "12.00", "valor_inss", "13.20",
                "valor_liquido", "1088.80", "source_system", "ADN", "found_in_adn_at", "2026-11-08 08:00:00"));
        insert(jdbc, si, nfse(doc("s2"), E1, "access_key", "ABRASF-2611606-12345678000195-123", "layout", "ABRASF",
                "issue_date", "2026-10-12 11:00:00", "prestador_documento", SUPPLIER_CPF, "tomador_documento", " ",
                "valor_servico", "800.00", "found_in_sieg_at", "2026-11-09 08:00:00"));
        insert(jdbc, si, nfse(doc("s3"), E1, "access_key", "2611606" + "3".repeat(43), "direction", "PRESTADA",
                "issue_date", "2026-10-13 11:00:00", "prestador_documento", E1_CNPJ, "valor_servico", "500.00", "canceled", 1));
        insert(jdbc, si, nfse(doc("s4"), E1, "access_key", "2611606" + "4".repeat(43), "direction", "TERCEIRO",
                "issue_date", "2026-10-14 11:00:00", "tomador_documento", "NIF123", "valor_servico", "60.00",
                "source_system", "UPLOAD", "created_date", "2026-11-04 09:00:00"));
        insert(jdbc, si, nfse(doc("s5"), E2, "access_key", "2611606" + "5".repeat(43), "issue_date", "2026-10-15 11:00:00",
                "valor_servico", "9999999999999.99", "aliquota_iss", "5.0000"));

        // events: one linked to K4 (cancelation), one waiting for its note
        insert(jdbc, "fiscal_xml_events", audited("id", "ev100000-0000-4000-8000-000000000001", "company_id", E1,
                "document_id", doc("k4"), "access_key", key(4), "event_type", "110111", "sequence_number", 1,
                "description", "Cancelamento", "event_at", "2026-10-20 10:00:00", "protocol", "126260000000001",
                "event_text", "<evento/>", "registered", 1));
        insert(jdbc, "fiscal_xml_events", audited("id", "ev100000-0000-4000-8000-000000000002", "company_id", E1,
                "document_id", null, "access_key", key(99), "event_type", "210210", "sequence_number", 1, "registered", 0));

        // keys that point to the documents and must not change (FD-RN-21)
        insert(jdbc, "pendencies", audited("id", "p1", "company_id", E1, "type", "MANIFESTATION_DEADLINE", "severity", "HIGH",
                "natural_key", "MANIFESTATION_DEADLINE:" + key(2), "object_type", "NOTE", "object_id", doc("k2"),
                "title", "Prazo", "status", "OPEN", "first_seen_at", "2026-11-10 08:00:00", "last_seen_at", "2026-11-20 08:00:00",
                "auto_closed", 0));
        insert(jdbc, "retention_reviews", audited("id", "r1", "company_id", E1, "natural_key", "NFSE:" + doc("s1") + ":IRRF:WITHHELD",
                "reviewed_by", "fiscal1@exemplo.test", "reviewed_at", "2026-11-11 08:00:00"));
    }

    /** CT-FD-29, 36 and the edge cases of 3.11 (the ten V85 columns are there: Flyway runs up to V85). */
    static void syncControls(JdbcTemplate jdbc) {
        insert(jdbc, "fiscal_xml_sync_control", audited("id", "s-e1", "company_id", E1,
                "data_emissao_inicio", "2026-11-01", "data_emissao_fim", "2026-11-30", "total_sieg", 40, "total_importado", 37,
                "ultima_verificacao_em", "2026-11-26 10:00:00", "issued_total_sieg", 12, "issued_total_imported", 12,
                "issued_checked_at", "2026-11-26 10:05:00", "received_prev_month", "2026-10", "received_prev_total_sieg", 55,
                "received_prev_imported", 55, "received_known_gap_month", "2026-10", "received_known_gap", 2,
                "received_earlier_gap_month", "2026-09", "received_earlier_gap", 1, "issued_known_gap_month", "2026-11",
                "issued_known_gap", 3, "received_stored_any_source", 42, "issued_stored_any_source", 12,
                "last_attempt_at", "2026-11-26 10:05:00", "issued_events_checked_on", "2026-11-26"));
        // issued side never checked; the received previous month equals the current one (window moved back)
        insert(jdbc, "fiscal_xml_sync_control", audited("id", "s-e2", "company_id", E2,
                "data_emissao_inicio", "2026-11-01", "data_emissao_fim", "2026-11-30", "total_sieg", 8, "total_importado", 8,
                "ultima_verificacao_em", "2026-11-25 10:00:00", "received_prev_month", "2026-11", "received_prev_total_sieg", 99,
                "received_prev_imported", 98, "issued_prev_month", "2026-10", "issued_prev_total_sieg", 5, "issued_prev_imported", 4));
        // no window: no current row; a known gap gets a closed row of its own
        insert(jdbc, "fiscal_xml_sync_control", audited("id", "s-e3", "company_id", E3, "total_sieg", 0, "total_importado", 0,
                "ultima_verificacao_em", "2026-11-25 10:00:00", "received_known_gap_month", "2026-11", "received_known_gap", 1));
    }
}
