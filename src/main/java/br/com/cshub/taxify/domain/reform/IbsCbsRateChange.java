package br.com.cshub.taxify.domain.reform;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.IbsCbsRateChangeAction;
import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * One change of a year of IBS/CBS reference rates (AL-RN-04, H-AL-5): before and after, who (login) and when (Recife).
 * Created only by {@link #between}, which returns nothing when the save changes no field.
 */
@Entity
@Table(name = "ibs_cbs_rate_changes")
public class IbsCbsRateChange extends AbstractAuditingEntity implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "rate_year", nullable = false, updatable = false)
    private Integer rateYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 10, nullable = false, updatable = false)
    private IbsCbsRateChangeAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", length = 12, updatable = false)
    private IbsCbsRateStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", length = 12, updatable = false)
    private IbsCbsRateStatus statusAfter;

    @Column(name = "cbs_before", precision = 7, scale = 4, updatable = false)
    private BigDecimal cbsBefore;

    @Column(name = "cbs_after", precision = 7, scale = 4, updatable = false)
    private BigDecimal cbsAfter;

    @Column(name = "ibs_state_before", precision = 7, scale = 4, updatable = false)
    private BigDecimal ibsStateBefore;

    @Column(name = "ibs_state_after", precision = 7, scale = 4, updatable = false)
    private BigDecimal ibsStateAfter;

    @Column(name = "ibs_municipal_before", precision = 7, scale = 4, updatable = false)
    private BigDecimal ibsMunicipalBefore;

    @Column(name = "ibs_municipal_after", precision = 7, scale = 4, updatable = false)
    private BigDecimal ibsMunicipalAfter;

    @Column(name = "legal_basis_after", length = 500, updatable = false)
    private String legalBasisAfter;

    @Column(name = "source_url_after", length = 300, updatable = false)
    private String sourceUrlAfter;

    @Column(name = "changed_by", length = 50, nullable = false, updatable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    /** Always created by the factory with its id: {@code save} persists without a SELECT first. */
    @Transient
    private boolean newEvent = true;

    protected IbsCbsRateChange() {
    }

    @Override
    public boolean isNew() {
        return newEvent;
    }

    @PostPersist
    @PostLoad
    void markStored() {
        this.newEvent = false;
    }

    /**
     * The event of a save: CREATED when there was no year before; UPDATED when some field changed; empty when the
     * save changes nothing (no event, AL-RN-04).
     */
    public static Optional<IbsCbsRateChange> between(int year, IbsCbsRateValues before, IbsCbsRateValues after,
                                                     String login, LocalDateTime at) {
        if (before != null && before.sameAs(after)) {
            return Optional.empty();
        }
        IbsCbsRateChange change = new IbsCbsRateChange();
        change.id = UUID.randomUUID();
        change.rateYear = year;
        change.action = before == null ? IbsCbsRateChangeAction.CREATED : IbsCbsRateChangeAction.UPDATED;
        if (before != null) {
            change.statusBefore = before.status();
            change.cbsBefore = before.cbsRate();
            change.ibsStateBefore = before.ibsStateRate();
            change.ibsMunicipalBefore = before.ibsMunicipalRate();
        }
        change.statusAfter = after.status();
        change.cbsAfter = after.cbsRate();
        change.ibsStateAfter = after.ibsStateRate();
        change.ibsMunicipalAfter = after.ibsMunicipalRate();
        change.legalBasisAfter = after.legalBasis();
        change.sourceUrlAfter = after.sourceUrl();
        change.changedBy = login;
        change.changedAt = at;
        return Optional.of(change);
    }

    public UUID getId() {
        return id;
    }

    public Integer getRateYear() {
        return rateYear;
    }

    public IbsCbsRateChangeAction getAction() {
        return action;
    }

    public IbsCbsRateStatus getStatusBefore() {
        return statusBefore;
    }

    public IbsCbsRateStatus getStatusAfter() {
        return statusAfter;
    }

    public BigDecimal getCbsBefore() {
        return cbsBefore;
    }

    public BigDecimal getCbsAfter() {
        return cbsAfter;
    }

    public BigDecimal getIbsStateBefore() {
        return ibsStateBefore;
    }

    public BigDecimal getIbsStateAfter() {
        return ibsStateAfter;
    }

    public BigDecimal getIbsMunicipalBefore() {
        return ibsMunicipalBefore;
    }

    public BigDecimal getIbsMunicipalAfter() {
        return ibsMunicipalAfter;
    }

    public String getLegalBasisAfter() {
        return legalBasisAfter;
    }

    public String getSourceUrlAfter() {
        return sourceUrlAfter;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
