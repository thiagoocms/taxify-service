package br.com.cshub.taxify.dto.firmaccess;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/** Um item da trilha: {@code before}/{@code after} com status, trialEndsOn, accessUntil, blockedSince, blockedReason, migrated, reviewed. */
public record FirmEventDTO(String id, String type, Map<String, Object> before, Map<String, Object> after,
                            String reason, String actor, LocalDateTime createdDate) {
}
