package br.com.cshub.taxify.domain.pendency;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Uma pendência já avisada a um usuário num episódio (AV-RN-04): o episódio é o {@code first_seen_at} da pendência,
 * que só muda quando ela reabre. Único por (pendência, usuário, episódio).
 */
@Entity
@Table(name = "pendency_alert_items")
public class PendencyAlertItem extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    /** O resumo que levou a pendência; nulo depois que o resumo é apagado na limpeza de 365 dias. */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "digest_id", length = 36)
    private UUID digestId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "pendency_id", length = 36, nullable = false, updatable = false)
    private UUID pendencyId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "episode_started_at", nullable = false, updatable = false)
    private LocalDateTime episodeStartedAt;

    public PendencyAlertItem() {
    }

    public PendencyAlertItem(UUID digestId, UUID pendencyId, UUID userId, LocalDateTime episodeStartedAt) {
        this.digestId = digestId;
        this.pendencyId = pendencyId;
        this.userId = userId;
        this.episodeStartedAt = episodeStartedAt;
        setDeleted(false);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDigestId() {
        return digestId;
    }

    public UUID getPendencyId() {
        return pendencyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDateTime getEpisodeStartedAt() {
        return episodeStartedAt;
    }
}
