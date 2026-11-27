package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.ClockConfig;
import br.com.cshub.taxify.domain.reform.stock.PurchaseCfops;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurvey;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurveyEvent;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyRule;
import br.com.cshub.taxify.dto.reform.StockSurveyDetailDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyEventDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyRowDTO;
import br.com.cshub.taxify.enumerated.StockSurveyRegimeSource;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.repository.StockSurveyListRepository.ListRow;
import br.com.cshub.taxify.service.reform.StockSurveyJson;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The stock credit survey to the API: list row, detail (breakdowns, memory of CE-RN-14) and history event. The
 * reason of the classification is the fixed text of its CE-RN-03 rule, derived from the stored regimes.
 */
@Component
public class StockSurveyMapper {

    public static final String NOTICE = "reform.stock.notice";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final MessageService messageService;
    private final StockSurveyJson json;

    public StockSurveyMapper(MessageService messageService, StockSurveyJson json) {
        this.messageService = messageService;
        this.json = json;
    }

    public StockSurveyRowDTO toRow(ListRow row) {
        return new StockSurveyRowDTO(row.companyId(), row.companyName(), row.cnpj(), row.referenceRegime(), row.nextYearRegime(),
                row.classification(), reason(StockSurveyRule.of(regime(row.referenceRegime()), regime(row.nextYearRegime()))),
                row.purchasesTotal(), row.purchaseItems(), row.inboundNotes(), row.monthsWithInbound(), row.monthsEvaluated(),
                row.coverage(), row.firstInbound(), row.lastInbound(), row.contactStatus(), row.statusChangedBy(),
                recife(row.statusChangedAt()), row.note(), row.version(), recife(row.calculatedAt()));
    }

    public StockSurveyRowDTO toRow(StockCreditSurvey survey, String companyName, String cnpj) {
        return new StockSurveyRowDTO(survey.getCompanyId(), companyName, cnpj, name(survey.getReferenceRegime()),
                name(survey.getNextYearRegime()), name(survey.getClassification()), reason(survey.rule()),
                survey.getPurchasesTotal(), orZero(survey.getPurchaseItems()), orZero(survey.getInboundNotes()),
                orZero(survey.getMonthsWithInbound()), orZero(survey.getMonthsEvaluated()), name(survey.getCoverage()),
                survey.getFirstInbound(), survey.getLastInbound(), name(survey.getContactStatus()), survey.getStatusChangedBy(),
                recife(survey.getStatusChangedAt()), survey.getNote(), survey.getVersion(), recife(survey.getCalculatedAt()));
    }

    public StockSurveyDetailDTO toDetail(StockCreditSurvey survey, String companyName, String cnpj,
                                         LocalDateTime refreshAvailableAt, boolean closed) {
        List<StockSurveyDetailDTO.Month> months = json.readMonths(survey.getPurchasesByMonthJson()).stream()
                .map(month -> new StockSurveyDetailDTO.Month(month.month(), month.value(), month.notes())).toList();
        List<StockSurveyDetailDTO.Cst> csts = json.readGroups(survey.getPurchasesByCstJson()).stream()
                .map(group -> new StockSurveyDetailDTO.Cst(group.key(), group.value(), group.items())).toList();
        List<StockSurveyDetailDTO.Ncm> ncms = json.readGroups(survey.getTopNcmJson()).stream()
                .map(group -> new StockSurveyDetailDTO.Ncm(group.key(), group.value(), group.items())).toList();
        return new StockSurveyDetailDTO(toRow(survey, companyName, cnpj), notice(), survey.getReferenceRegimeSince(),
                name(survey.getReferenceRegimeSource()), months, csts, ncms, memory(survey),
                recife(refreshAvailableAt), closed);
    }

    public StockSurveyEventDTO toEvent(StockCreditSurveyEvent event) {
        return new StockSurveyEventDTO(name(event.getFromStatus()), name(event.getToStatus()), event.getNote(),
                event.getChangedBy(), recife(event.getChangedAt()));
    }

    /** CE-RN-13. */
    public String notice() {
        return messageService.getMessage(NOTICE);
    }

    public String reason(StockSurveyRule rule) {
        return messageService.getMessage(rule.reasonKey());
    }

    /**
     * CE-RN-14: the reference regime and the period it came from, the CE-RN-03 rule that matched, the CE-RN-04
     * criterion in words, the purchase CFOPs used (compacted in ranges) and when it was calculated.
     */
    List<String> memory(StockCreditSurvey survey) {
        return List.of(
                messageService.getMessage("reform.stock.memory.regime", regimeLabel(survey.getReferenceRegime()),
                        regimeOrigin(survey)),
                messageService.getMessage("reform.stock.memory.next.year", regimeLabel(survey.getNextYearRegime())),
                messageService.getMessage("reform.stock.memory.rule", reason(survey.rule())),
                messageService.getMessage("reform.stock.memory.criterion"),
                messageService.getMessage("reform.stock.memory.cfops", PurchaseCfops.compacted()),
                messageService.getMessage("reform.stock.memory.calculated.at",
                        survey.getCalculatedAt() == null ? "-" : DATE_TIME.format(survey.getCalculatedAt())));
    }

    private String regimeOrigin(StockCreditSurvey survey) {
        if (survey.getReferenceRegimeSource() == StockSurveyRegimeSource.REGISTRATION) {
            return messageService.getMessage("reform.stock.memory.origin.registry");
        }
        LocalDate since = survey.getReferenceRegimeSince();
        return since == null ? messageService.getMessage("reform.stock.memory.origin.always")
                : messageService.getMessage("reform.stock.memory.origin.since", DATE.format(since));
    }

    private String regimeLabel(TaxRegime regime) {
        return regime == null ? messageService.getMessage("reform.stock.memory.regime.none") : regime.label();
    }

    static TaxRegime regime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TaxRegime.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static OffsetDateTime recife(LocalDateTime at) {
        return at == null ? null : at.atZone(ClockConfig.RECIFE).toOffsetDateTime();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
