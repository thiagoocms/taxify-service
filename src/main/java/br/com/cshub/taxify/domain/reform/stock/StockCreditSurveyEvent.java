package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.StockSurveyContactStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

/** One contact status change of a stock credit survey (CE-RN-10): from, to, note, who (login) and when (Recife). */
@Entity
@Table(name = "stock_credit_survey_events")
public class StockCreditSurveyEvent extends AbstractAuditingEntity implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "survey_id", length = 36, nullable = false, updatable = false)
    private UUID surveyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 25, updatable = false)
    private StockSurveyContactStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 25, nullable = false, updatable = false)
    private StockSurveyContactStatus toStatus;

    @Column(name = "note", length = StockCreditSurvey.NOTE_MAX, updatable = false)
    private String note;

    @Column(name = "changed_by", length = 50, nullable = false, updatable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    /** Always created by the factory with its id: {@code save} persists without a SELECT first. */
    @Transient
    private boolean newEvent = true;

    protected StockCreditSurveyEvent() {
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

    static StockCreditSurveyEvent of(UUID surveyId, StockSurveyContactStatus from, StockSurveyContactStatus to, String note,
                                     String login, LocalDateTime at) {
        StockCreditSurveyEvent event = new StockCreditSurveyEvent();
        event.id = UUID.randomUUID();
        event.surveyId = surveyId;
        event.fromStatus = from;
        event.toStatus = to;
        event.note = note;
        event.changedBy = login;
        event.changedAt = at;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSurveyId() {
        return surveyId;
    }

    public StockSurveyContactStatus getFromStatus() {
        return fromStatus;
    }

    public StockSurveyContactStatus getToStatus() {
        return toStatus;
    }

    public String getNote() {
        return note;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
