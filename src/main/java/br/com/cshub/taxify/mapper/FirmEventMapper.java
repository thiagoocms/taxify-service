package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionEvent;
import br.com.cshub.taxify.dto.firmaccess.FirmEventDTO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Um item da trilha da assinatura do escritório (F1-RN-18) para o console: o antes e o depois, gravados em JSON, saem
 * como objeto. Só leitura - a trilha é gravada pelo FirmEventService e nunca editada.
 */
@Component
public class FirmEventMapper extends AbstractMapper<FirmSubscriptionEvent, FirmEventDTO> {

    private final ObjectMapper objectMapper;

    public FirmEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A trilha não é criada a partir do DTO. */
    @Override
    protected FirmSubscriptionEvent toEntity(FirmEventDTO source) {
        throw new UnsupportedOperationException("the firm subscription trail is written by FirmEventService only");
    }

    @Override
    public FirmEventDTO toModel(FirmSubscriptionEvent source) {
        return new FirmEventDTO(source.getId().toString(), source.getType().name(), readFields(source.getBeforeJson()),
                readFields(source.getAfterJson()), source.getReason(), source.getActor(), source.getCreatedDate());
    }

    /** A trilha é imutável. */
    @Override
    public void merge(FirmSubscriptionEvent source, FirmSubscriptionEvent target) {
        throw new UnsupportedOperationException("the firm subscription trail is immutable");
    }

    /** Lê de volta um antes/depois gravado; nulo quando não há. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readFields(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return objectMapper.readValue(json, Map.class);
    }
}
