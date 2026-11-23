package br.com.cshub.taxify.dto.pendency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The pendencies screen (roadmap M13). */
public final class PendencyDTOs {

    private PendencyDTOs() {
    }

    /** One pendency. {@code objectType}/{@code objectId}: what to open (COMPANY, NOTE = a stored note, NOTE_KEY = a summary). */
    public record Pendency(
            UUID id,
            UUID companyId,
            String companyName,
            String companyDocument,
            String type,
            String typeLabel,
            String severity,
            String competence,
            String objectType,
            String objectId,
            String title,
            String detail,
            String status,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            LocalDateTime closedAt,
            String closedBy,
            String closeComment,
            boolean autoClosed
    ) {
    }

    /** Open pendencies per type, split by severity. */
    public record Counter(String type, String label, long total, long alta, long media, long baixa, long info) {
    }

    /** @param comentario required - why it was resolved or dismissed */
    public record CloseRequest(@NotBlank @Size(min = 3, max = 500) String comentario) {
    }

    /**
     * How a company's collectors are doing: the SEFAZ capture's last run and its runs of the last 7 days.
     */
    public record IntegrationHealth(
            UUID companyId,
            String companyName,
            String service,
            LocalDateTime lastRunAt,
            String lastStatus,
            String lastDetail,
            LocalDateTime nextQueryAt,
            long runsLast7Days,
            long errorsLast7Days
    ) {
    }

    public record Summary(List<Counter> counters, long totalOpen) {
    }
}
