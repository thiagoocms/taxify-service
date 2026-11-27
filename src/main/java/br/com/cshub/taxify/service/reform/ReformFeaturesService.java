package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyCalendar;
import br.com.cshub.taxify.dto.reform.ReformFeaturesDTO;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/** CE-RN-15: which "Reforma Tributária" features are on, for the front's menu. No query. */
@Service
public class ReformFeaturesService {

    private final StockSurveyProperties stockSurveyProperties;
    private final Clock clock;

    public ReformFeaturesService(StockSurveyProperties stockSurveyProperties, Clock clock) {
        this.stockSurveyProperties = stockSurveyProperties;
        this.clock = clock;
    }

    public ReformFeaturesDTO features() {
        return new ReformFeaturesDTO(new ReformFeaturesDTO.StockCredit(stockSurveyProperties.isEnabled(),
                StockSurveyCalendar.isClosed(LocalDateTime.now(clock)), StockSurveyCalendar.CLOSES_ON));
    }
}
