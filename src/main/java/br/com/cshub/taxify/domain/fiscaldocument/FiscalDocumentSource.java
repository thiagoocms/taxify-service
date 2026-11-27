package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One source in which a fiscal document was seen (FD-RN-31, T4) - one row per document, source and retrieval mode,
 * replacing the
 * {@code found_in_*_at} columns. Part of the document aggregate (no auditing columns, like the item); created only by
 * {@link FiscalDocument#markSeenIn(DocumentSourceKey, RetrievalMode, LocalDateTime)}.
 */
@Entity
@Table(name = "fiscal_document_sources",
        uniqueConstraints = @UniqueConstraint(name = "uk_fiscal_document_sources", columnNames = {"document_id", "source", "retrieval_mode"}),
        indexes = @Index(name = "idx_fds_company_source_seen", columnList = "company_id, source, first_seen_at"))
public class FiscalDocumentSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, foreignKey = @ForeignKey(name = "fk_fiscal_document_sources_document"))
    private FiscalDocument document;

    /** The company of the document, repeated on purpose for the indexes by company and source. */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "company_id", length = 36, nullable = false, updatable = false)
    private UUID companyId;

    @Convert(converter = DocumentSourceKeyConverter.class)
    @Column(name = "source", length = DocumentSourceKey.MAX_LENGTH, nullable = false, updatable = false)
    private DocumentSourceKey source;

    /** How the source delivered the document (spec 3.10). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "retrieval_mode", length = 10, nullable = false, updatable = false)
    private RetrievalMode retrievalMode;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    protected FiscalDocumentSource() {
    }

    FiscalDocumentSource(FiscalDocument document, DocumentSourceKey source, RetrievalMode retrievalMode,
                         LocalDateTime firstSeenAt, LocalDateTime lastSeenAt) {
        this.document = Objects.requireNonNull(document, "document");
        this.companyId = document.getCompany() == null ? null : document.getCompany().getId();
        this.source = Objects.requireNonNull(source, "source");
        this.retrievalMode = Objects.requireNonNull(retrievalMode, "retrievalMode");
        this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }

    /** The source delivered the document again: {@code last_seen_at} only moves forward (two instances, CT-FD-28). */
    void seenAgain(LocalDateTime at) {
        if (at.isAfter(lastSeenAt)) {
            this.lastSeenAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public FiscalDocument getDocument() {
        return document;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public DocumentSourceKey getSource() {
        return source;
    }

    public RetrievalMode getRetrievalMode() {
        return retrievalMode;
    }

    /** Whether this row is the origin ({@code source}, {@code mode}). */
    boolean is(DocumentSourceKey otherSource, RetrievalMode mode) {
        return source.equals(otherSource) && retrievalMode == mode;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }
}
