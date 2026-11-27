package br.com.cshub.taxify.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * As consultas de leitura do painel da captura (CP-RN-14): projeções sem entidade, um número fixo de consultas por
 * requisição, que não cresce com o número de empresas. Todas parametrizadas. As consultas "da página" recebem só os ids
 * das empresas da página. Nunca lê {@code last_detail}.
 */
@Repository
public class CapturePanelRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CapturePanelRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** Uma empresa do escopo com o controle da SIEG, da SEFAZ e do ADN (passo 1). */
    public record CompanyRow(UUID companyId, String name, boolean active, String uf, UUID firmId, String firmName,
                             String document, boolean siegRow, LocalDateTime lastAttemptAt, int receivedTotalSieg,
                             int receivedImported, LocalDateTime receivedCheckedAt, Integer issuedTotalSieg,
                             Integer issuedImported, LocalDateTime issuedCheckedAt, LocalDate windowFrom, LocalDate windowTo,
                             LocalDate receivedEventsOn, LocalDate issuedEventsOn, LocalDate serviceInvoicesOn,
                             String receivedPrevMonth, Integer receivedPrevTotalSieg, Integer receivedPrevImported,
                             String issuedPrevMonth, Integer issuedPrevTotalSieg, Integer issuedPrevImported,
                             boolean sefazRow, LocalDateTime sefazLastRunAt, String sefazLastStatus, LocalDateTime sefazNextQueryAt,
                             boolean adnRow, LocalDateTime adnLastRunAt, String adnLastStatus, LocalDateTime adnNextQueryAt) {
    }

    /** Uma linha da fila SIEG da plataforma (passo 3): o que a ordem do agendador usa. */
    public record QueueRow(UUID controlId, UUID companyId, UUID firmId, LocalDateTime lastAttemptAt,
                           LocalDateTime receivedCheckedAt, LocalDateTime issuedCheckedAt) {

        /** FiscalXmlSyncControl#getVerifiedAt: a mais antiga das duas; nula enquanto o lado emitido nunca foi verificado. */
        public LocalDateTime verifiedAt() {
            if (issuedCheckedAt == null || receivedCheckedAt == null) {
                return null;
            }
            return issuedCheckedAt.isBefore(receivedCheckedAt) ? issuedCheckedAt : receivedCheckedAt;
        }
    }

    public record CertificateRow(UUID companyId, UUID firmId, String document, LocalDateTime validUntil) {
    }

    public record StageRow(UUID companyId, String stage, String lastOutcome, LocalDateTime lastOutcomeAt,
                           LocalDateTime lastSuccessAt, LocalDateTime lastFailureAt, String failureCode, String providerStatus,
                           String warningCode, Integer warningCount, int consecutiveFailures) {
    }

    public record LastNoteRow(UUID companyId, String source, LocalDateTime at) {
    }

    public record RecoveryRow(UUID companyId, LocalDate periodStart) {
    }

    private static final String COMPANY_SELECT = "SELECT c.id, c.name, c.active, c.uf, c.accounting_firm_id, af.name AS firm_name,"
            + " d.number AS document,"
            + " x.id AS x_id, x.last_attempt_at, x.total_sieg, x.total_importado, x.ultima_verificacao_em, x.issued_total_sieg,"
            + " x.issued_total_imported, x.issued_checked_at, x.data_emissao_inicio, x.data_emissao_fim,"
            + " x.received_events_checked_on, x.issued_events_checked_on, x.service_invoices_checked_on,"
            + " x.received_prev_month, x.received_prev_total_sieg, x.received_prev_imported,"
            + " x.issued_prev_month, x.issued_prev_total_sieg, x.issued_prev_imported,"
            + " sd.id AS sd_id, sd.last_run_at AS sd_last_run_at, sd.last_status AS sd_last_status, sd.next_query_at AS sd_next_query_at,"
            + " na.id AS na_id, na.last_run_at AS na_last_run_at, na.last_status AS na_last_status, na.next_query_at AS na_next_query_at"
            + " FROM companies c"
            + " JOIN accounting_firms af ON af.id = c.accounting_firm_id"
            + " LEFT JOIN documents d ON d.id = c.document_id"
            + " LEFT JOIN fiscal_xml_sync_control x ON x.company_id = c.id AND x.deleted = 0"
            + " LEFT JOIN sefaz_dist_control sd ON sd.company_id = c.id AND sd.deleted = 0"
            + " LEFT JOIN nfse_adn_control na ON na.company_id = c.id AND na.deleted = 0"
            + " WHERE c.deleted = 0";

    /**
     * As empresas não excluídas do escopo: do escritório {@code firmId} (null = todos), da carteira do usuário
     * {@code portfolioUserId} (null = sem carteira - o filtro vai na consulta, CP-RN-01), ou só {@code companyId}.
     */
    public List<CompanyRow> companies(UUID firmId, UUID portfolioUserId, UUID companyId) {
        StringBuilder sql = new StringBuilder(COMPANY_SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (firmId != null) {
            sql.append(" AND c.accounting_firm_id = :firmId");
            params.addValue("firmId", firmId.toString());
        }
        if (portfolioUserId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM users_companies uc WHERE uc.company_id = c.id AND uc.user_id = :userId"
                    + " AND uc.deleted = 0)");
            params.addValue("userId", portfolioUserId.toString());
        }
        if (companyId != null) {
            sql.append(" AND c.id = :companyId");
            params.addValue("companyId", companyId.toString());
        }
        return jdbc.query(sql.toString(), params, (rs, i) -> new CompanyRow(
                uuid(rs, "id"), rs.getString("name"), !Boolean.FALSE.equals(bool(rs, "active")), rs.getString("uf"),
                uuid(rs, "accounting_firm_id"), rs.getString("firm_name"), rs.getString("document"),
                rs.getString("x_id") != null, time(rs, "last_attempt_at"), intOf(rs, "total_sieg"), intOf(rs, "total_importado"),
                time(rs, "ultima_verificacao_em"), integer(rs, "issued_total_sieg"), integer(rs, "issued_total_imported"),
                time(rs, "issued_checked_at"), date(rs, "data_emissao_inicio"), date(rs, "data_emissao_fim"),
                date(rs, "received_events_checked_on"), date(rs, "issued_events_checked_on"), date(rs, "service_invoices_checked_on"),
                rs.getString("received_prev_month"), integer(rs, "received_prev_total_sieg"), integer(rs, "received_prev_imported"),
                rs.getString("issued_prev_month"), integer(rs, "issued_prev_total_sieg"), integer(rs, "issued_prev_imported"),
                rs.getString("sd_id") != null, time(rs, "sd_last_run_at"), rs.getString("sd_last_status"), time(rs, "sd_next_query_at"),
                rs.getString("na_id") != null, time(rs, "na_last_run_at"), rs.getString("na_last_status"), time(rs, "na_next_query_at")));
    }

    /**
     * A fila SIEG da plataforma inteira (passo 3), como o agendador a lê ({@code findAllWithCompany}: linhas de controle
     * não excluídas, de qualquer empresa) - quem tem direito e passa pelo bloqueio é filtrado em memória.
     */
    public List<QueueRow> queue() {
        return jdbc.query("SELECT x.id, x.company_id, c.accounting_firm_id, x.last_attempt_at, x.ultima_verificacao_em,"
                        + " x.issued_checked_at FROM fiscal_xml_sync_control x JOIN companies c ON c.id = x.company_id"
                        + " WHERE x.deleted = 0",
                new MapSqlParameterSource(), (rs, i) -> new QueueRow(uuid(rs, "id"), uuid(rs, "company_id"),
                        uuid(rs, "accounting_firm_id"), time(rs, "last_attempt_at"), time(rs, "ultima_verificacao_em"),
                        time(rs, "issued_checked_at")));
    }

    /** Os certificados A1 (não excluídos) do escritório, ou de todos - a raiz do CNPJ é casada em memória. */
    public List<CertificateRow> certificates(UUID firmId) {
        StringBuilder sql = new StringBuilder("SELECT cc.company_id, c.accounting_firm_id, d.number, cc.valid_until"
                + " FROM company_certificates cc JOIN companies c ON c.id = cc.company_id"
                + " LEFT JOIN documents d ON d.id = c.document_id WHERE cc.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (firmId != null) {
            sql.append(" AND c.accounting_firm_id = :firmId");
            params.addValue("firmId", firmId.toString());
        }
        return jdbc.query(sql.toString(), params, (rs, i) -> new CertificateRow(uuid(rs, "company_id"),
                uuid(rs, "accounting_firm_id"), rs.getString("number"), time(rs, "valid_until")));
    }

    /** O resultado das etapas das empresas do escritório, ou de todas. */
    public List<StageRow> stages(UUID firmId) {
        StringBuilder sql = new StringBuilder("SELECT s.company_id, s.stage, s.last_outcome, s.last_outcome_at, s.last_success_at,"
                + " s.last_failure_at, s.failure_code, s.provider_status, s.warning_code, s.warning_count, s.consecutive_failures"
                + " FROM capture_stage_status s");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (firmId != null) {
            sql.append(" JOIN companies c ON c.id = s.company_id WHERE s.deleted = 0 AND c.accounting_firm_id = :firmId");
            params.addValue("firmId", firmId.toString());
        } else {
            sql.append(" WHERE s.deleted = 0");
        }
        return jdbc.query(sql.toString(), params, (rs, i) -> new StageRow(uuid(rs, "company_id"), rs.getString("stage"),
                rs.getString("last_outcome"), time(rs, "last_outcome_at"), time(rs, "last_success_at"),
                time(rs, "last_failure_at"), rs.getString("failure_code"), rs.getString("provider_status"),
                rs.getString("warning_code"), integer(rs, "warning_count"), intOf(rs, "consecutive_failures")));
    }

    /** Última NF-e gravada por origem, só das empresas da página (índice company_id, source_system, created_date). */
    public List<LastNoteRow> lastInvoices(Collection<UUID> companyIds) {
        return lastBySource("fiscal_xml_documents", companyIds);
    }

    /** Última NFS-e gravada por origem, só das empresas da página. */
    public List<LastNoteRow> lastServiceInvoices(Collection<UUID> companyIds) {
        return lastBySource("service_invoices", companyIds);
    }

    private List<LastNoteRow> lastBySource(String table, Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return List.of();
        }
        // table is one of the two constants above - never input
        return jdbc.query("SELECT company_id, source_system, MAX(created_date) AS at FROM " + table
                        + " WHERE company_id IN (:ids) GROUP BY company_id, source_system",
                new MapSqlParameterSource("ids", ids(companyIds)),
                (rs, i) -> new LastNoteRow(uuid(rs, "company_id"), rs.getString("source_system"), time(rs, "at")));
    }

    /** Meses bloqueados ainda em recuperação (tarefas PENDING) das empresas da página. */
    public List<RecoveryRow> openRecoveries(Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT company_id, period_start FROM recovery_tasks WHERE deleted = 0 AND status = 'PENDING'"
                        + " AND company_id IN (:ids)",
                new MapSqlParameterSource("ids", ids(companyIds)),
                (rs, i) -> new RecoveryRow(uuid(rs, "company_id"), date(rs, "period_start")));
    }

    /** Nome dos usuários (de um escritório) que pediram "Sincronizar agora" - só para quem é do mesmo escritório. */
    public List<Object[]> userNames(Collection<String> userIds, UUID firmId) {
        if (userIds.isEmpty() || firmId == null) {
            return List.of();
        }
        return jdbc.query("SELECT id, name FROM users WHERE id IN (:ids) AND accounting_firm_id = :firmId",
                new MapSqlParameterSource("ids", userIds).addValue("firmId", firmId.toString()),
                (rs, i) -> new Object[]{rs.getString("id"), rs.getString("name")});
    }

    private static List<String> ids(Collection<UUID> companyIds) {
        return companyIds.stream().map(UUID::toString).toList();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static int intOf(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column);
    }

    private static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
