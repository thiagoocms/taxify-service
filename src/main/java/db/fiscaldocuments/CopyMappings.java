package db.fiscaldocuments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Column by column, what V87 writes in each new table from the old one (spec 3.4, 3.6, 3.7 and the normalization of
 * 3.9). The source alias is always {@code l} (the legacy row). Every value is copied as it is - decimals only widen
 * ({@code (15,2)} → {@code (19,2)}, {@code (7,4)} → {@code (9,4)}, FD-RN-11), dates keep their Recife wall clock
 * (FD-RN-29) - except the normalizations listed here.
 */
final class CopyMappings {

    private CopyMappings() {
    }

    /** {@code SEFAZ_DIST} → {@code SEFAZ_DFE}, {@code ADN} → {@code NFSE_ADN} (3.10, FD-RN-40); any other kept. */
    static String sourceKey(String column) {
        return "CASE " + column + " WHEN 'SEFAZ_DIST' THEN 'SEFAZ_DFE' WHEN 'ADN' THEN 'NFSE_ADN' ELSE " + column + " END";
    }

    /** NF-e: {@code CTE} stays, {@code NFE} with model 65 is the NFC-e (FD-RN-14). */
    static final String GOODS_DOCUMENT_TYPE =
            "CASE WHEN l.document_type = 'CTE' THEN 'CTE' WHEN TRIM(l.ide_modelo) = '65' THEN 'NFCE' ELSE 'NFE' END";

    /** NFS-e participant: one document column, the type by its length (I-06). */
    static String documentTypeByLength(String column) {
        return "CASE WHEN NULLIF(" + column + ", '') IS NULL THEN NULL WHEN CHAR_LENGTH(" + column + ") = 14 THEN 'CNPJ'"
                + " WHEN CHAR_LENGTH(" + column + ") = 11 THEN 'CPF' ELSE 'FOREIGN' END";
    }

    /** Common block (C) of a {@code fiscal_xml_documents} row - NF-e, NFC-e and CT-e. */
    static Map<String, String> goodsCommon(boolean cte) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "l.id");
        m.put("company_id", "l.company_id");
        m.put("document_type", GOODS_DOCUMENT_TYPE);
        m.put("access_key", "l.access_key");
        m.put("model", cte ? "CASE WHEN l.ide_modelo IS NULL OR TRIM(l.ide_modelo) = '' THEN '57' ELSE l.ide_modelo END" : "l.ide_modelo");
        m.put("series", "l.ide_serie");
        m.put("number", "l.ide_numero");
        m.put("issue_date", "l.issue_date");
        m.put("operation_nature", "l.ide_natureza_operacao");
        m.put("direction", "l.direction");
        m.put("company_role", "CASE WHEN l.own_issue = 1 THEN 'ISSUER' ELSE 'RECIPIENT' END");
        m.put("status", "COALESCE(l.status, 'AUTHORIZED')");
        m.put("canceled_at", "l.canceled_at");
        // FD-RN-09: CNPJ before CPF before foreign id (the XML brings only one of them)
        m.put("issuer_document", "COALESCE(NULLIF(l.issuer_cnpj, ''), NULLIF(l.emit_cpf, ''))");
        m.put("issuer_document_type", "CASE WHEN NULLIF(l.issuer_cnpj, '') IS NOT NULL THEN 'CNPJ'"
                + " WHEN NULLIF(l.emit_cpf, '') IS NOT NULL THEN 'CPF' END");
        m.put("issuer_name", "l.emit_nome");
        m.put("issuer_uf", "l.emit_end_uf");
        m.put("issuer_city_code", "l.emit_end_codigo_municipio");
        m.put("recipient_document", "COALESCE(NULLIF(l.recipient_cnpj, ''), NULLIF(l.dest_cpf, ''), NULLIF(l.dest_id_estrangeiro, ''))");
        m.put("recipient_document_type", "CASE WHEN NULLIF(l.recipient_cnpj, '') IS NOT NULL THEN 'CNPJ'"
                + " WHEN NULLIF(l.dest_cpf, '') IS NOT NULL THEN 'CPF' WHEN NULLIF(l.dest_id_estrangeiro, '') IS NOT NULL THEN 'FOREIGN' END");
        m.put("recipient_name", "l.dest_nome");
        m.put("recipient_uf", "l.dest_end_uf");
        m.put("recipient_city_code", "l.dest_end_codigo_municipio");
        m.put("total_amount", "l.total_valor_total_nota");
        m.put("ibs_cbs_base", "l.total_ibscbs_base_calculo");
        m.put("ibs_state_amount", "l.total_valor_ibs_uf");
        m.put("ibs_city_amount", "l.total_valor_ibs_mun");
        m.put("ibs_amount", "l.total_valor_ibs");
        m.put("cbs_amount", "l.total_valor_cbs");
        m.put("withheld_pis_amount", "l.ret_pis");
        m.put("withheld_cofins_amount", "l.ret_cofins");
        m.put("withheld_csll_amount", "l.ret_csll");
        m.put("withheld_irrf_amount", "l.ret_irrf");
        m.put("withheld_inss_amount", "l.ret_inss");
        m.put("source_system", sourceKey("l.source_system"));
        m.put("xml_file_path", "l.xml_file_path");
        // FD-RN-15: the CT-e is read again by the CT-e parser (capture phase 3)
        m.put("metadata_version", cte ? "0" : "COALESCE(l.metadata_version, 0)");
        m.put("review_status", "l.review_status");
        m.put("reviewed_at", "l.reviewed_at");
        m.put("reviewed_by", "l.reviewed_by");
        m.put("review_note", "l.review_note");
        audit(m);
        return m;
    }

    /** NF-e and NFC-e: common block plus the goods invoice block (G). */
    static Map<String, String> goodsInvoice() {
        Map<String, String> m = goodsCommon(false);
        m.put("rectified_file_path", "l.rectified_file_path");
        m.put("last_rectification_checked_at", "l.last_rectification_checked_at");
        m.put("header_uf_code", "l.ide_codigo_uf");
        m.put("header_operation_type", "l.ide_tipo_operacao");
        m.put("header_destination_type", "l.ide_destino_operacao");
        m.put("header_environment", "l.ide_ambiente");
        m.put("header_purpose", "l.ide_finalidade_emissao");
        m.put("header_final_consumer", "l.ide_consumidor_final");
        m.put("header_presence_indicator", "l.ide_indicador_presenca");
        m.put("header_print_type", "l.ide_tipo_impressao");
        m.put("header_emission_type", "l.ide_tipo_emissao");
        m.put("header_check_digit", "l.ide_digito_verificador");
        m.put("header_emission_process", "l.ide_processo_emissao");
        m.put("header_emission_process_version", "l.ide_versao_processo_emissao");
        m.put("header_exit_entry_at", "l.ide_data_saida_entrada");
        m.put("header_taxable_event_city_code", "l.ide_codigo_municipio_fato_gerador");
        m.put("issuer_trade_name", "l.emit_nome_fantasia");
        m.put("issuer_state_registration", "l.emit_inscricao_estadual");
        m.put("issuer_st_state_registration", "l.emit_inscricao_estadual_st");
        m.put("issuer_municipal_registration", "l.emit_inscricao_municipal");
        m.put("issuer_cnae", "l.emit_cnae");
        m.put("issuer_tax_regime_code", "l.emit_regime_tributario");
        m.put("recipient_state_registration", "l.dest_inscricao_estadual");
        m.put("recipient_state_registration_indicator", "l.dest_indicador_ie");
        m.put("recipient_email", "l.dest_email");
        for (String[] party : new String[][]{{"issuer", "emit"}, {"recipient", "dest"}}) {
            m.put(party[0] + "_address_street", "l." + party[1] + "_end_logradouro");
            m.put(party[0] + "_address_number", "l." + party[1] + "_end_numero");
            m.put(party[0] + "_address_complement", "l." + party[1] + "_end_complemento");
            m.put(party[0] + "_address_district", "l." + party[1] + "_end_bairro");
            m.put(party[0] + "_address_city_name", "l." + party[1] + "_end_municipio");
            m.put(party[0] + "_address_zip_code", "l." + party[1] + "_end_cep");
            m.put(party[0] + "_address_phone", "l." + party[1] + "_end_telefone");
            m.put(party[0] + "_address_country_code", "l." + party[1] + "_codigo_pais");
            m.put(party[0] + "_address_country_name", "l." + party[1] + "_pais");
        }
        m.put("icms_base", "l.total_base_calculo_icms");
        m.put("icms_amount", "l.total_valor_icms");
        m.put("icms_relief_amount", "l.total_valor_icms_desonerado");
        m.put("fcp_amount", "l.total_valor_fcp");
        m.put("icms_st_base", "l.total_base_calculo_icms_st");
        m.put("icms_st_amount", "l.total_valor_icms_st");
        m.put("fcp_st_amount", "l.total_valor_fcp_st");
        m.put("products_amount", "l.total_valor_produtos");
        m.put("freight_amount", "l.total_valor_frete");
        m.put("insurance_amount", "l.total_valor_seguro");
        m.put("discount_amount", "l.total_valor_desconto");
        m.put("import_tax_amount", "l.total_valor_importacao");
        m.put("ipi_amount", "l.total_valor_ipi");
        m.put("other_expenses_amount", "l.total_valor_outras_despesas");
        m.put("approximate_taxes_amount", "l.total_valor_aproximado_tributos");
        m.put("pis_amount", "l.total_valor_pis");
        m.put("cofins_amount", "l.total_valor_cofins");
        return m;
    }

    /** CT-e uploaded before the remodel: only the common block (FD-RN-15); the T block waits for the CT-e parser. */
    static Map<String, String> cte() {
        return goodsCommon(true);
    }

    /** {@code service_invoices} → {@code Nfse}: common block plus the S block (FD-RN-08, 10). */
    static Map<String, String> serviceInvoice() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "l.id");
        m.put("company_id", "l.company_id");
        m.put("document_type", "'NFSE'");
        m.put("access_key", "l.access_key");
        m.put("number", "l.numero");
        m.put("issue_date", "l.issue_date");
        m.put("direction", "CASE l.direction WHEN 'PRESTADA' THEN 'OUTBOUND' WHEN 'TOMADA' THEN 'INBOUND' END");
        m.put("company_role", "CASE l.direction WHEN 'PRESTADA' THEN 'ISSUER' WHEN 'TOMADA' THEN 'TAKER' ELSE 'THIRD_PARTY' END");
        m.put("status", "CASE WHEN l.canceled = 1 THEN 'CANCELED' ELSE 'AUTHORIZED' END");
        m.put("issuer_document", "NULLIF(l.prestador_documento, '')");
        m.put("issuer_document_type", documentTypeByLength("l.prestador_documento"));
        m.put("issuer_name", "l.prestador_nome");
        m.put("issuer_city_code", "l.prestador_municipio");
        m.put("recipient_document", "NULLIF(l.tomador_documento, '')");
        m.put("recipient_document_type", documentTypeByLength("l.tomador_documento"));
        m.put("recipient_name", "l.tomador_nome");
        m.put("total_amount", "l.valor_servico");
        m.put("withheld_csll_amount", "l.valor_csll");
        m.put("withheld_irrf_amount", "l.valor_irrf");
        m.put("withheld_inss_amount", "l.valor_inss");
        m.put("source_system", sourceKey("l.source_system"));
        m.put("xml_file_path", "l.xml_file_path");
        m.put("metadata_version", "0");
        audit(m);
        m.put("nfse_layout", "CASE l.layout WHEN 'NACIONAL' THEN 'NATIONAL' ELSE l.layout END");
        m.put("nfse_verification_code", "l.codigo_verificacao");
        m.put("nfse_competence", "l.competence");
        m.put("nfse_incidence_city_code", "l.municipio_incidencia");
        m.put("nfse_service_code", "l.codigo_servico");
        m.put("nfse_municipal_tax_code", "l.codigo_tributacao_municipal");
        m.put("nfse_service_description", "l.descricao_servico");
        m.put("nfse_deductions_amount", "l.valor_deducoes");
        m.put("iss_base", "l.base_calculo");
        m.put("iss_rate", "l.aliquota_iss");
        m.put("iss_amount", "l.valor_iss");
        m.put("iss_withheld", "l.iss_retido");
        m.put("withheld_iss_amount", "l.valor_iss_retido");
        // informed PIS/COFINS plus the withheld flag, exactly as today (3.4, S block)
        m.put("pis_amount", "l.valor_pis");
        m.put("cofins_amount", "l.valor_cofins");
        m.put("nfse_pis_cofins_withheld", "l.pis_cofins_retidos");
        m.put("nfse_net_amount", "l.valor_liquido");
        return m;
    }

    static Map<String, String> item() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "l.id");
        m.put("document_id", "l.document_id");
        m.put("item_number", "l.numero_item");
        m.put("product_code", "l.codigo");
        m.put("barcode", "l.codigo_barras");
        m.put("description", "l.descricao");
        m.put("ncm", "l.ncm");
        m.put("cest", "l.cest");
        m.put("benefit_code", "l.codigo_beneficio");
        // stored without spaces: the queries stop using trim() and use the index
        m.put("cfop", "TRIM(l.cfop)");
        m.put("commercial_unit", "l.unidade_comercial");
        m.put("taxable_unit", "l.unidade_tributavel");
        m.put("commercial_quantity", "l.quantidade_comercial");
        m.put("commercial_unit_price", "l.valor_unitario_comercial");
        m.put("taxable_quantity", "l.quantidade_tributavel");
        m.put("taxable_unit_price", "l.valor_unitario_tributavel");
        m.put("total_amount", "l.valor_total");
        m.put("goods_origin", "l.origem_mercadoria");
        m.put("icms_cst", "l.cst_icms");
        m.put("icms_csosn", "l.csosn");
        m.put("icms_base_modality", "l.modalidade_bc_icms");
        m.put("icms_st_base_modality", "l.modalidade_bc_icms_st");
        m.put("icms_base", "l.base_calculo_icms");
        m.put("icms_amount", "l.valor_icms");
        m.put("icms_relief_amount", "l.valor_icms_desonerado");
        m.put("icms_st_base", "l.base_calculo_icms_st");
        m.put("icms_st_amount", "l.valor_icms_st");
        m.put("icms_rate", "l.aliquota_icms");
        m.put("icms_base_reduction", "l.reducao_bc_icms");
        m.put("icms_st_mva", "l.mva_icms_st");
        m.put("icms_st_rate", "l.aliquota_icms_st");
        m.put("ipi_cst", "l.cst_ipi");
        m.put("pis_cst", "l.cst_pis");
        m.put("cofins_cst", "l.cst_cofins");
        m.put("ipi_base", "l.base_calculo_ipi");
        m.put("ipi_amount", "l.valor_ipi");
        m.put("pis_base", "l.base_calculo_pis");
        m.put("pis_amount", "l.valor_pis");
        m.put("cofins_base", "l.base_calculo_cofins");
        m.put("cofins_amount", "l.valor_cofins");
        m.put("ipi_rate", "l.aliquota_ipi");
        m.put("pis_rate", "l.aliquota_pis");
        m.put("cofins_rate", "l.aliquota_cofins");
        m.put("ibs_cbs_cst", "l.ibscbs_cst");
        m.put("ibs_cbs_class_code", "l.ibscbs_class_trib");
        m.put("ibs_cbs_base", "l.ibscbs_base_calculo");
        m.put("ibs_state_amount", "l.ibs_uf_valor");
        m.put("ibs_city_amount", "l.ibs_mun_valor");
        m.put("ibs_amount", "l.ibs_valor");
        m.put("cbs_amount", "l.cbs_valor");
        m.put("ibs_state_rate", "l.ibs_uf_aliquota");
        m.put("ibs_state_rate_reduction", "l.ibs_uf_reducao_aliquota");
        m.put("ibs_city_rate", "l.ibs_mun_aliquota");
        m.put("ibs_city_rate_reduction", "l.ibs_mun_reducao_aliquota");
        m.put("cbs_rate", "l.cbs_aliquota");
        m.put("cbs_rate_reduction", "l.cbs_reducao_aliquota");
        m.put("ibs_cbs_deferral_or_refund", "l.ibscbs_diferimento_devolucao");
        m.put("simples_credit_rate", "l.aliquota_credito_sn");
        m.put("simples_credit_amount", "l.valor_credito_sn");
        return m;
    }

    static Map<String, String> event() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String column : List.of("id", "company_id", "document_id", "access_key", "event_type", "sequence_number",
                "description", "event_at", "protocol", "event_text", "registered")) {
            m.put(column, "l." + column);
        }
        audit(m);
        return m;
    }

    /** Auditing copied without change (the panel and the way back use {@code created_date}). */
    private static void audit(Map<String, String> m) {
        for (String column : List.of("created_by", "created_date", "last_modified_by", "last_modified_date", "deleted")) {
            m.put(column, "l." + column);
        }
    }

    /** The legacy columns a mapping reads, to check that the old table has them before copying. */
    static List<String> legacyColumns(Map<String, String> mapping) {
        List<String> columns = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\bl\\.([a-z0-9_]+)").matcher(String.join(" ", mapping.values()));
        while (matcher.find()) {
            if (!columns.contains(matcher.group(1))) {
                columns.add(matcher.group(1));
            }
        }
        return columns;
    }
}
