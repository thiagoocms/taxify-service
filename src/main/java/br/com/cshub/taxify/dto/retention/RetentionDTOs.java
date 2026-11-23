package br.com.cshub.taxify.dto.retention;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The retentions screen (roadmap M1). */
public final class RetentionDTOs {

    private RetentionDTOs() {
    }

    public record Row(String key, UUID companyId, String companyName, String companyDocument, String documentType,
                      UUID documentId, String numero, LocalDateTime issueDate, String competence, String counterpartName,
                      String counterpartDocument, String nature, String tax, BigDecimal amount, BigDecimal serviceValue,
                      LocalDate dueDate, String action, boolean undue, boolean reviewed, String reviewedBy,
                      LocalDateTime reviewedAt) {
    }

    /** One nature's card: total, notes and the amount per tax. */
    public record Card(String nature, BigDecimal total, long notes, Map<String, BigDecimal> byTax, long pendingReview) {
    }

    public record Screen(String competence, List<Card> cards, List<Row> rows) {
    }

    /** @param reviewed true = mark as checked, false = undo */
    public record ReviewRequest(String companyId, @NotEmpty List<String> keys, boolean reviewed) {
    }
}
