package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionSnapshot;

import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionEvent;
import br.com.cshub.taxify.repository.FirmSubscriptionEventRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trilha da assinatura do escritório (F1-RN-18): toda mudança grava um evento com antes, depois, motivo e quem fez.
 * O JSON só tem os campos da assinatura - nunca nome, CNPJ, e-mail ou IP. A leitura de volta (o console) fica no
 * FirmEventMapper.
 */
@Service
public class FirmEventService {

    public static final String SYSTEM = "system";

    private final FirmSubscriptionEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FirmEventService(FirmSubscriptionEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public FirmSubscriptionEvent record(UUID firmId, FirmEventType type, FirmSubscriptionSnapshot before,
                                        FirmSubscriptionSnapshot after, String reason, String actor) {
        FirmSubscriptionEvent event = new FirmSubscriptionEvent(firmId, type, json(before), json(after),
                truncate(reason, 500), truncate(actor == null ? SYSTEM : actor, 50), LocalDateTime.now(clock));
        return repository.save(event);
    }

    /** Evento com antes/depois livres (ex.: os dados de contato do escritório). Fica na trilha do banco, nunca no log. */
    public FirmSubscriptionEvent recordFields(UUID firmId, FirmEventType type, Map<String, Object> before,
                                              Map<String, Object> after, String reason, String actor) {
        FirmSubscriptionEvent event = new FirmSubscriptionEvent(firmId, type,
                before == null ? null : objectMapper.writeValueAsString(before),
                after == null ? null : objectMapper.writeValueAsString(after),
                truncate(reason, 500), truncate(actor == null ? SYSTEM : actor, 50), LocalDateTime.now(clock));
        return repository.save(event);
    }

    private String json(FirmSubscriptionSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", snapshot.status() == null ? null : snapshot.status().name());
        fields.put("trialEndsOn", snapshot.trialEndsOn() == null ? null : snapshot.trialEndsOn().toString());
        fields.put("accessUntil", snapshot.accessUntil() == null ? null : snapshot.accessUntil().toString());
        fields.put("blockedSince", snapshot.blockedSince() == null ? null : snapshot.blockedSince().toString());
        fields.put("blockedReason", snapshot.blockedReason() == null ? null : snapshot.blockedReason().name());
        fields.put("migrated", snapshot.migrated());
        fields.put("reviewed", snapshot.reviewed());
        return objectMapper.writeValueAsString(fields);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
