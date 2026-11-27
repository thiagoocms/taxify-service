package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.domain.company.CompanyTaxRegime;
import br.com.cshub.taxify.domain.reform.stock.PurchaseCfops;
import br.com.cshub.taxify.domain.reform.stock.ReferenceRegime;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyCalendar;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyRule;
import br.com.cshub.taxify.repository.CompanyTaxRegimeRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository.CalculatedRow;
import br.com.cshub.taxify.repository.StockSurveySourceRepository.CompanyRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calculates the stock credit survey of a batch of companies (spec 2026-11-27, F5a, section 8): the regime histories
 * in one query, four aggregate queries over the 2026 inbound notes, the pure rules of the domain (CE-RN-02 to
 * CE-RN-06) and one batched upsert - six statements per batch, whatever its size. A company whose rules fail is left
 * out of the batch (logged with its id and the exception class only) and the others are written.
 */
@Service
public class StockSurveyCalculator {

    private static final Logger log = LoggerFactory.getLogger(StockSurveyCalculator.class);

    /** The outcome of a batch. */
    public record BatchResult(int calculated, int failed) {
    }

    private final StockSurveySourceRepository sourceRepository;
    private final CompanyTaxRegimeRepository regimeRepository;
    private final StockSurveyJson json;
    private final Clock clock;

    public StockSurveyCalculator(StockSurveySourceRepository sourceRepository, CompanyTaxRegimeRepository regimeRepository,
                                 StockSurveyJson json, Clock clock) {
        this.sourceRepository = sourceRepository;
        this.regimeRepository = regimeRepository;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public BatchResult calculate(List<CompanyRef> companies) {
        if (companies.isEmpty()) {
            return new BatchResult(0, 0);
        }
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<UUID> ids = companies.stream().map(CompanyRef::companyId).toList();
        Set<String> cfops = PurchaseCfops.codes();
        LocalDateTime start = StockSurveyCalendar.yearStart();
        LocalDateTime end = StockSurveyCalendar.yearEnd();

        Map<UUID, List<ReferenceRegime.Period>> histories = histories(ids);
        var months = sourceRepository.monthlyPurchases(ids, cfops, start, end);
        var csts = sourceRepository.purchasesByCstPis(ids, cfops, start, end);
        var ncms = sourceRepository.purchasesByNcm(ids, cfops, start, end);
        var inbound = sourceRepository.inboundMonths(ids, start, end);

        List<CalculatedRow> rows = new ArrayList<>();
        int failed = 0;
        for (CompanyRef company : companies) {
            UUID id = company.companyId();
            try {
                ReferenceRegime regime = ReferenceRegime.resolve(histories.getOrDefault(id, List.of()), company.registryRegime());
                StockSurveyFigures figures = StockSurveyFigures.of(months.getOrDefault(id, List.of()), csts.getOrDefault(id, List.of()),
                        ncms.getOrDefault(id, List.of()), inbound.getOrDefault(id, List.of()), now.toLocalDate());
                rows.add(row(id, regime, figures, now));
            } catch (RuntimeException e) {
                failed++;
                log.warn("stock.survey.company.failed companyId={} error={}", id, e.getClass().getSimpleName());
            }
        }
        sourceRepository.upsert(rows);
        return new BatchResult(rows.size(), failed);
    }

    private CalculatedRow row(UUID companyId, ReferenceRegime regime, StockSurveyFigures figures, LocalDateTime now) {
        return new CalculatedRow(companyId, StockSurveyCalendar.YEAR, regime.regime(), regime.since(), regime.source().name(),
                regime.nextYearRegime(), StockSurveyRule.of(regime).classification().name(), figures.purchasesTotal(),
                figures.purchaseItems(), figures.inboundNotes(), figures.monthsWithInbound(), figures.monthsEvaluated(),
                figures.coverage().name(), figures.firstInbound(), figures.lastInbound(), json.months(figures.purchasesByMonth()),
                json.groups(figures.purchasesByCstPis()), json.groups(figures.topNcm()), now);
    }

    private Map<UUID, List<ReferenceRegime.Period>> histories(List<UUID> ids) {
        Map<UUID, List<ReferenceRegime.Period>> result = new HashMap<>();
        for (CompanyTaxRegime period : regimeRepository.historyOf(ids)) {
            result.computeIfAbsent(period.getCompany().getId(), key -> new ArrayList<>())
                    .add(new ReferenceRegime.Period(period.getValidFrom(), period.getTaxRegime()));
        }
        return result;
    }
}
