package br.com.cshub.taxify.domain.reform;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The IBS/CBS reference rates of one calendar year, in percent - what the conferência
 * compares each note item's pCBS/pIBSUF/pIBSMun with. A table, not constants: the rates
 * change every year of the transition (and the reference rates are set by the Senate).
 * 2026 (test year) is seeded by V24; later years are kept by the platform admin in the console
 * (spec 2026-11-27, F1a-0).
 * <p>
 * Each year has a {@link IbsCbsRateStatus status}: only {@code LAW} with the three rates filled in
 * {@link #generatesFindings() generates findings} (AL-RN-02). {@code version} is the optimistic
 * concurrency of the console (AL-RN-04); fields in English, columns kept ({@code ano}, {@code aliquota_*}); {@code changedBy}/{@code changedAt} the last change (login, Recife).
 */
@Entity
@Table(name = "ibs_cbs_rates")
public class IbsCbsRate extends AbstractAuditingEntity {

    @Id
    @Column(name = "ano", nullable = false)
    private Integer year;

    @Column(name = "aliquota_cbs", precision = 7, scale = 4)
    private BigDecimal cbsRate;

    @Column(name = "aliquota_ibs_uf", precision = 7, scale = 4)
    private BigDecimal ibsStateRate;

    @Column(name = "aliquota_ibs_mun", precision = 7, scale = 4)
    private BigDecimal ibsMunicipalRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    private IbsCbsRateStatus status;

    @Column(name = "legal_basis", length = 500)
    private String legalBasis;

    @Column(name = "source_url", length = 300)
    private String sourceUrl;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "changed_by", length = 50)
    private String changedBy;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    public IbsCbsRate() {
    }

    /** An official (LAW) year - the conferência's tests and simple callers. */
    public IbsCbsRate(Integer year, BigDecimal cbsRate, BigDecimal ibsStateRate, BigDecimal ibsMunicipalRate) {
        this(year, new IbsCbsRateValues(IbsCbsRateStatus.LAW, cbsRate, ibsStateRate, ibsMunicipalRate, null, null));
    }

    public IbsCbsRate(Integer year, IbsCbsRateValues values) {
        this.year = year;
        apply(values);
    }

    /** The editable content of the year. */
    public IbsCbsRateValues values() {
        return new IbsCbsRateValues(status, cbsRate, ibsStateRate, ibsMunicipalRate, legalBasis, sourceUrl);
    }

    /** Replaces the editable content (already checked by IbsCbsRatePolicy). */
    public void apply(IbsCbsRateValues values) {
        this.status = values.status();
        this.cbsRate = values.cbsRate();
        this.ibsStateRate = values.ibsStateRate();
        this.ibsMunicipalRate = values.ibsMunicipalRate();
        this.legalBasis = values.legalBasis();
        this.sourceUrl = values.sourceUrl();
    }

    /** Who changed it and when (Recife) - set only when a save really changes something. */
    public void markChanged(String login, LocalDateTime at) {
        this.changedBy = login;
        this.changedAt = at;
    }

    /** AL-RN-02: LAW with the three rates - the only rows the conferência compares with. */
    public boolean generatesFindings() {
        return values().generatesFindings();
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public BigDecimal getCbsRate() {
        return cbsRate;
    }

    public BigDecimal getIbsStateRate() {
        return ibsStateRate;
    }

    public BigDecimal getIbsMunicipalRate() {
        return ibsMunicipalRate;
    }

    public IbsCbsRateStatus getStatus() {
        return status;
    }

    public String getLegalBasis() {
        return legalBasis;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Integer getVersion() {
        return version;
    }

    /** The version the client sent (mapper only); the persisted one is managed by JPA. */
    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
