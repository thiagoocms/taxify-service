package br.com.cshub.taxify.domain.firmaccess;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.RecoveryTaskSource;
import br.com.cshub.taxify.enumerated.RecoveryTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Um pedaço da recuperação dos meses bloqueados: uma fonte (SIEG NF-e de um lado, SIEG NFS-e, e-Fisco) num período,
 * para uma empresa (SIEG) ou para o escritório (e-Fisco). Persistente: sobrevive a restart e roda uma vez só.
 * Nunca é excluída pela aplicação ({@code deleted} fica false); o expurgo do cadastro não verificado não a alcança
 * (escritório não verificado não tem recuperação).
 */
@Entity
@Table(name = "recovery_tasks")
public class RecoveryTask extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accounting_firm_id", length = 36, nullable = false, updatable = false)
    private UUID accountingFirmId;

    /** A empresa (fontes da SIEG) ou o próprio escritório (e-Fisco). */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "scope_id", length = 36, nullable = false, updatable = false)
    private UUID scopeId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "company_id", length = 36, updatable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", length = 20, nullable = false, updatable = false)
    private RecoveryTaskSource source;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** SIEG NF-e: a próxima página a pedir (skip = página × tamanho). */
    @Column(name = "page_cursor", nullable = false)
    private int pageCursor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 10, nullable = false)
    private RecoveryTaskStatus status = RecoveryTaskStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Só a classe da exceção ou um código - nunca dado de cliente. */
    @Column(name = "last_error", length = 200)
    private String lastError;

    @Column(name = "done_at")
    private LocalDateTime doneAt;

    public RecoveryTask() {
    }

    public RecoveryTask(UUID accountingFirmId, UUID companyId, RecoveryTaskSource source, LocalDate periodStart,
                        LocalDate periodEnd) {
        this.accountingFirmId = accountingFirmId;
        this.companyId = companyId;
        this.scopeId = companyId == null ? accountingFirmId : companyId;
        this.source = source;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public RecoveryTaskSource getSource() {
        return source;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public int getPageCursor() {
        return pageCursor;
    }

    public void setPageCursor(int pageCursor) {
        this.pageCursor = pageCursor;
    }

    public RecoveryTaskStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getDoneAt() {
        return doneAt;
    }

    public boolean isPending() {
        return status == RecoveryTaskStatus.PENDING;
    }

    public void finish(RecoveryTaskStatus status, String note, LocalDateTime now) {
        this.status = status;
        this.lastError = note;
        this.doneAt = now;
    }

    /** Reabre (novo bloqueio cobrindo o mesmo mês): começa da primeira página; as notas repetidas são ignoradas. */
    public void reopen() {
        this.status = RecoveryTaskStatus.PENDING;
        this.pageCursor = 0;
        this.doneAt = null;
        this.lastError = null;
    }

    /** Falha: continua PENDING e tenta na rodada seguinte. */
    public void failed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.length() > 200 ? error.substring(0, 200) : error;
    }
}
