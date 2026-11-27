package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.GroupFigure;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.MonthFigure;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

/**
 * The breakdowns of the survey (CE-RN-05) as stored in the text columns: arrays with the values as decimal text
 * ({@code "120000.00"}), so no float ever touches them.
 */
@Component
public class StockSurveyJson {

    record StoredMonth(String month, String value, int notes) {
    }

    record StoredGroup(String key, String value, int items) {
    }

    private final ObjectMapper objectMapper;

    public StockSurveyJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String months(List<MonthFigure> months) {
        return objectMapper.writeValueAsString(months.stream()
                .map(month -> new StoredMonth(month.month(), month.value().toPlainString(), month.notes())).toList());
    }

    public String groups(List<GroupFigure> groups) {
        return objectMapper.writeValueAsString(groups.stream()
                .map(group -> new StoredGroup(group.key(), group.value().toPlainString(), group.items())).toList());
    }

    public List<MonthFigure> readMonths(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<StoredMonth> stored = objectMapper.readValue(json, new TypeReference<List<StoredMonth>>() { });
        return stored.stream().map(month -> new MonthFigure(month.month(), new BigDecimal(month.value()), month.notes())).toList();
    }

    public List<GroupFigure> readGroups(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<StoredGroup> stored = objectMapper.readValue(json, new TypeReference<List<StoredGroup>>() { });
        return stored.stream().map(group -> new GroupFigure(group.key(), new BigDecimal(group.value()), group.items())).toList();
    }
}
