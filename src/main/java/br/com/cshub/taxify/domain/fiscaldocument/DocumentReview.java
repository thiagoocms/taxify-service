package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Conferência of a document by the fiscal team; a null review (or status) means PENDING. */
@Embeddable
public record DocumentReview(
        @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
        @Column(name = "review_status", length = 12) ReviewStatus status,
        @Column(name = "reviewed_at") LocalDateTime reviewedAt,
        @Column(name = "reviewed_by", length = 50) String reviewedBy,
        @Column(name = "review_note", length = 500) String note
) {
    public static final int MAX_NOTE = 500;

    /**
     * The same rule as the old model (the review of the old notes table): back to PENDING clears who and when; a blank
     * note is null, a note longer than 500 characters is cut (not stripped), a shorter one is stripped.
     */
    public static DocumentReview of(ReviewStatus status, String by, String note, LocalDateTime at) {
        boolean pending = status == ReviewStatus.PENDING;
        return new DocumentReview(status, pending ? null : at, pending ? null : by, normalizedNote(note));
    }

    private static String normalizedNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note.strip();
    }
}
