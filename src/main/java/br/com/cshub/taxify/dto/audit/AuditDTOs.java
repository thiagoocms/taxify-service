package br.com.cshub.taxify.dto.audit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The Auditoria tipo malha screen (roadmap M9). */
public final class AuditDTOs {

    private AuditDTOs() {
    }

    /**
     * @param reviewed the fiscal team checked the rule - otherwise the finding is "a conferir"
     */
    public record Rule(String code, String title, String group, String severity, String legalBasis, String link,
                       LocalDate validFrom, LocalDate validUntil, boolean enabled, boolean reviewed, String reviewedBy,
                       LocalDateTime reviewedAt) {
    }

    /** One finding of the month (a pendency of type AUDIT_FINDING). */
    public record Finding(UUID pendencyId, String ruleCode, String ruleTitle, String group, String severity,
                          boolean ruleReviewed, String title, String detail, String objectType, String objectId, String status,
                          LocalDateTime firstSeenAt, LocalDateTime closedAt, String closedBy, String closeComment,
                          boolean autoClosed) {
    }

    public record Run(LocalDateTime ranAt, String ranBy, int findings, int notesChecked) {
    }

    /** @param run null = the month was never audited */
    public record Report(UUID companyId, String companyName, String companyDocument, String competence, Run run,
                         List<Finding> findings) {
    }
}
