package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacySourceNames;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                             boolean adnRow, LocalDateTime adnLastRunAt, String adnLastStatus, LocalDateTime adnNextQueryAt,
                             Integer receivedStoredAnySource, Integer issuedStoredAnySource) {
    }

    /** Uma linha da fila SIEG da plataforma (passo 3): o que a ordem do agendador usa. */
    public record QueueRow(UUID controlId, UUID companyId, UUID firmId, LocalDateTime lastAttemptAt,
                           LocalDateTime receivedCheckedAt, LocalDateTime issuedCheckedAt) {

        /** A verificação real: a mais antiga das duas; nula enquanto um dos lados nunca foi verificado. */
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

    /** {@code service}: an NFS-e (otherwise an NF-e, NFC-e or CT-e). */
    public record LastNoteRow(UUID companyId, boolean service, String source, LocalDateTime at) {
    }

    public record RecoveryRow(UUID companyId, LocalDate periodStart) {
    }

    private static final String COMPANY_SELECT = "SELECT c.id, c.name, c.active, c.uf, c.accounting_firm_id, af.name AS firm_name,"
            + " d.number AS document,"
            + " x.id AS x_id, x.last_attempt_at,"
            + " x.received_events_checked_on, x.issued_events_checked_on, x.service_invoices_checked_on,"
            + " sd.id AS sd_id, sd.last_run_at AS sd_last_run_at, sd.last_status AS sd_last_status, sd.next_query_at AS sd_next_query_at,"
            + " na.id AS na_id, na.last_run_at AS na_last_run_at, na.last_status AS na_last_status, na.next_query_at AS na_next_query_at"
            + " FROM companies c"
            + " JOIN accounting_firms af ON af.id = c.accounting_firm_id"
            + " LEFT JOIN documents d ON d.id = c.document_id"
            + " LEFT JOIN fiscal_xml_sync_control x ON x.company_id = c.id AND x.deleted = 0"
            + " LEFT JOIN sefaz_dist_control sd ON sd.company_id = c.id AND sd.deleted = 0"
            + " LEFT JOIN nfse_adn_control na ON na.company_id = c.id AND na.deleted = 0"
            + " WHERE c.deleted = 0";

    /** The SIEG NF-e counts per side and month (FD-RN-32) of the companies of the same scope - one query. */
    private static final String COUNTS_SELECT = "SELECT p.company_id, p.side, p.period_month, p.window_start, p.window_end,"
            + " p.provider_total, p.imported_total, p.imported_counted_at, p.stored_any_source_total, p.closed_at"
            + " FROM capture_period_counts p JOIN companies c ON c.id = p.company_id"
            + " JOIN fiscal_xml_sync_control x ON x.company_id = c.id AND x.deleted = 0"
            + " WHERE c.deleted = 0 AND p.channel = 'SIEG' AND p.document_type = 'NFE'";

    /** One row of {@code capture_period_counts} as the panel reads it. */
    record CountRow(String side, YearMonth month, LocalDate windowStart, LocalDate windowEnd, Integer providerTotal,
                    int importedTotal, LocalDateTime importedCountedAt, Integer storedAnySource, LocalDateTime closedAt) {

        boolean hasWindow() {
            return windowStart != null;
        }
    }

    /**
     * As empresas não excluídas do escopo: do escritório {@code firmId} (null = todos), da carteira do usuário
     * {@code portfolioUserId} (null = sem carteira - o filtro vai na consulta, CP-RN-01), ou só {@code companyId}. Os
     * contadores da SIEG vêm de {@code capture_period_counts} (uma consulta a mais, no mesmo escopo), lidos como as
     * colunas antigas de {@code fiscal_xml_sync_control}: o mês mais recente verificado de cada lado, a janela do mês
     * mais recente e o último mês fechado (CaptureCounts).
     */
    public List<CompanyRow> companies(UUID firmId, UUID portfolioUserId, UUID companyId) {
        StringBuilder scope = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (firmId != null) {
            scope.append(" AND c.accounting_firm_id = :firmId");
            params.addValue("firmId", firmId.toString());
        }
        if (portfolioUserId != null) {
            scope.append(" AND EXISTS (SELECT 1 FROM users_companies uc WHERE uc.company_id = c.id AND uc.user_id = :userId"
                    + " AND uc.deleted = 0)");
            params.addValue("userId", portfolioUserId.toString());
        }
        if (companyId != null) {
            scope.append(" AND c.id = :companyId");
            params.addValue("companyId", companyId.toString());
        }
        Map<UUID, List<CountRow>> counts = new HashMap<>();
        jdbc.query(COUNTS_SELECT + scope, params, (ResultSet rs) -> {
            String month = rs.getString("period_month");
            counts.computeIfAbsent(uuid(rs, "company_id"), id -> new ArrayList<>()).add(new CountRow(rs.getString("side"),
                    month == null ? null : YearMonth.parse(month.strip()), date(rs, "window_start"), date(rs, "window_end"),
                    integer(rs, "provider_total"), intOf(rs, "imported_total"), time(rs, "imported_counted_at"),
                    integer(rs, "stored_any_source_total"), time(rs, "closed_at")));
        });
        return jdbc.query(COMPANY_SELECT + scope, params, (rs, i) -> {
            UUID id = uuid(rs, "id");
            boolean siegRow = rs.getString("x_id") != null;
            List<CountRow> rows = siegRow ? counts.getOrDefault(id, List.of()) : List.of();
            CountRow received = current(rows, "RECEIVED");
            CountRow issued = current(rows, "ISSUED");
            CountRow receivedPrev = previous(rows, "RECEIVED");
            CountRow issuedPrev = previous(rows, "ISSUED");
            CountRow window = rows.stream().filter(CountRow::hasWindow).max(Comparator.comparing(CountRow::month)).orElse(null);
            return new CompanyRow(
                    id, rs.getString("name"), !Boolean.FALSE.equals(bool(rs, "active")), rs.getString("uf"),
                    uuid(rs, "accounting_firm_id"), rs.getString("firm_name"), rs.getString("document"),
                    siegRow, time(rs, "last_attempt_at"),
                    received == null || received.providerTotal() == null ? 0 : received.providerTotal(),
                    received == null ? 0 : received.importedTotal(), received == null ? null : received.importedCountedAt(),
                    issued == null ? null : issued.providerTotal(), issued == null ? null : issued.importedTotal(),
                    issued == null ? null : issued.importedCountedAt(),
                    window == null ? null : window.windowStart(), window == null ? null : window.windowEnd(),
                    date(rs, "received_events_checked_on"), date(rs, "issued_events_checked_on"), date(rs, "service_invoices_checked_on"),
                    monthOf(receivedPrev), receivedPrev == null ? null : receivedPrev.providerTotal(),
                    receivedPrev == null ? null : receivedPrev.importedTotal(),
                    monthOf(issuedPrev), issuedPrev == null ? null : issuedPrev.providerTotal(),
                    issuedPrev == null ? null : issuedPrev.importedTotal(),
                    rs.getString("sd_id") != null, time(rs, "sd_last_run_at"), rs.getString("sd_last_status"), time(rs, "sd_next_query_at"),
                    rs.getString("na_id") != null, time(rs, "na_last_run_at"), rs.getString("na_last_status"), time(rs, "na_next_query_at"),
                    received == null ? null : received.storedAnySource(), issued == null ? null : issued.storedAnySource());
        });
    }

    /** The side's latest month written by a sync (CaptureCounts#current). */
    private static CountRow current(List<CountRow> rows, String side) {
        return rows.stream().filter(row -> side.equals(row.side()) && row.hasWindow())
                .max(Comparator.comparing(CountRow::month)).orElse(null);
    }

    /** The side's latest closed month with a count (CaptureCounts#previous). */
    private static CountRow previous(List<CountRow> rows, String side) {
        return rows.stream().filter(row -> side.equals(row.side()) && row.closedAt() != null && row.providerTotal() != null)
                .max(Comparator.comparing(CountRow::month)).orElse(null);
    }

    private static String monthOf(CountRow row) {
        return row == null ? null : row.month().toString();
    }

    /**
     * A fila SIEG da plataforma inteira (passo 3), como o agendador a lê ({@code findAllWithCompany}: linhas de controle
     * não excluídas, de qualquer empresa) - quem tem direito e passa pelo bloqueio é filtrado em memória. A última
     * verificação de cada lado vem de {@code capture_period_counts}.
     */
    public List<QueueRow> queue() {
        return jdbc.query("SELECT x.id, x.company_id, c.accounting_firm_id, x.last_attempt_at, r.checked_at AS received_checked_at,"
                        + " i.checked_at AS issued_checked_at FROM fiscal_xml_sync_control x JOIN companies c ON c.id = x.company_id"
                        + " LEFT JOIN (SELECT company_id, MAX(imported_counted_at) AS checked_at FROM capture_period_counts"
                        + "            WHERE channel = 'SIEG' AND document_type = 'NFE' AND side = 'RECEIVED' GROUP BY company_id) r"
                        + "        ON r.company_id = x.company_id"
                        + " LEFT JOIN (SELECT company_id, MAX(imported_counted_at) AS checked_at FROM capture_period_counts"
                        + "            WHERE channel = 'SIEG' AND document_type = 'NFE' AND side = 'ISSUED' GROUP BY company_id) i"
                        + "        ON i.company_id = x.company_id"
                        + " WHERE x.deleted = 0",
                new MapSqlParameterSource(), (rs, i) -> new QueueRow(uuid(rs, "id"), uuid(rs, "company_id"),
                        uuid(rs, "accounting_firm_id"), time(rs, "last_attempt_at"), time(rs, "received_checked_at"),
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

    /**
     * Último documento gravado por origem, só das empresas da página - uma consulta em {@code fiscal_documents}
     * (índice idx_fd_company_type_source_created): NF-e, NFC-e e CT-e de um lado, NFS-e do outro, como as duas tabelas
     * antigas; a origem com o nome de antes (SEFAZ_DIST, ADN).
     */
    public List<LastNoteRow> lastDocuments(Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT company_id, document_type, source_system, MAX(created_date) AS at FROM fiscal_documents"
                        + " WHERE company_id IN (:ids) AND document_type IN ('NFE', 'NFCE', 'CTE', 'NFSE')"
                        + " GROUP BY company_id, document_type, source_system",
                new MapSqlParameterSource("ids", ids(companyIds)),
                (rs, i) -> new LastNoteRow(uuid(rs, "company_id"), "NFSE".equals(rs.getString("document_type")),
                        LegacySourceNames.toLegacyName(DocumentSourceKey.of(rs.getString("source_system"))), time(rs, "at")));
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
