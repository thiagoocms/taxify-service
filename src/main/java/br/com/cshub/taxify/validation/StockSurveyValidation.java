package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurvey;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyCalendar;
import br.com.cshub.taxify.dto.reform.StockSurveyFilter;
import br.com.cshub.taxify.dto.reform.StockSurveyFilter.Sort;
import br.com.cshub.taxify.dto.reform.StockSurveyFilter.SortField;
import br.com.cshub.taxify.enumerated.StockSurveyClassification;
import br.com.cshub.taxify.enumerated.StockSurveyContactStatus;
import br.com.cshub.taxify.enumerated.StockSurveyCoverage;
import br.com.cshub.taxify.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Entry rules of the stock credit survey (spec 2026-11-27, F5a): the year (CE-RN-01, only 2026), the list filters,
 * page and sort (section 7) and the contact status body (CE-RN-10). Each with its error code; the filter errors bring
 * {@code invalidField} at the top of the body.
 */
@Component
public class StockSurveyValidation {

    static final String YEAR_INVALID = "error.reform.stock.year.invalid";
    static final String FILTER_INVALID = "error.reform.stock.filter.invalid";
    static final String STATUS_INVALID = "error.reform.stock.status.invalid";
    static final String NOTE_TOO_LONG = "error.reform.stock.note.too.long";
    public static final int MAX_PAGE_SIZE = 200;
    static final int MAX_PAGE = 100_000;
    static final int SEARCH_MIN = 3;
    static final int SEARCH_MAX = 150;

    /** The checked body of the status change: the note trimmed, blank = null. */
    public record StatusChange(int year, StockSurveyContactStatus status, String note, Integer version) {
    }

    private final MessageService messageService;

    public StockSurveyValidation(MessageService messageService) {
        this.messageService = messageService;
    }

    /** CE-RN-01: only 2026 in this phase. */
    public int checkYear(Integer year) {
        if (!StockSurveyCalendar.isValidYear(year)) {
            throw new BadRequestException(YEAR_INVALID, messageService.getMessage(YEAR_INVALID));
        }
        return year;
    }

    public StockSurveyFilter checkFilter(Integer year, List<String> classifications, List<String> statuses, List<String> coverages,
                              String search, Integer page, Integer size, String sort) {
        int checkedYear = checkYear(year);
        int checkedPage = page == null ? 0 : page;
        int checkedSize = size == null ? 50 : size;
        if (checkedPage < 0 || checkedPage > MAX_PAGE) {
            throw filterInvalid("page");
        }
        if (checkedSize < 1 || checkedSize > MAX_PAGE_SIZE) {
            throw filterInvalid("size");
        }
        String term = search == null || search.isBlank() ? null : search.strip();
        if (term != null && (term.length() < SEARCH_MIN || term.length() > SEARCH_MAX)) {
            throw filterInvalid("search");
        }
        String digits = term == null ? null : term.replaceAll("\\D", "");
        return new StockSurveyFilter(checkedYear, parseAll(StockSurveyClassification.class, classifications, "classification"),
                parseAll(StockSurveyContactStatus.class, statuses, "status"),
                parseAll(StockSurveyCoverage.class, coverages, "coverage"), term,
                digits == null || digits.length() < SEARCH_MIN ? null : digits, parseSort(sort), checkedPage, checkedSize);
    }

    /** CE-RN-10: a known status and a note of up to 500 characters (trimmed; blank = null). */
    public StatusChange checkStatusChange(Integer year, String status, String note, Integer version) {
        int checkedYear = checkYear(year);
        StockSurveyContactStatus parsed = parse(StockSurveyContactStatus.class, status)
                .orElseThrow(() -> new BadRequestException(STATUS_INVALID, messageService.getMessage(STATUS_INVALID)));
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (trimmed != null && trimmed.length() > StockCreditSurvey.NOTE_MAX) {
            throw new BadRequestException(NOTE_TOO_LONG, messageService.getMessage(NOTE_TOO_LONG));
        }
        return new StatusChange(checkedYear, parsed, trimmed, version);
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return new Sort(null, true);
        }
        String[] parts = sort.strip().split(",");
        if (parts.length > 2) {
            throw filterInvalid("sort");
        }
        String field = parts[0].strip().replaceAll("([a-z])([A-Z])", "$1_$2");
        SortField parsed = parse(SortField.class, field).orElseThrow(() -> filterInvalid("sort"));
        if (parts.length == 1) {
            return new Sort(parsed, parsed == SortField.COMPANY_NAME || parsed == SortField.CLASSIFICATION);
        }
        String direction = parts[1].strip().toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw filterInvalid("sort");
        }
        return new Sort(parsed, direction.equals("asc"));
    }

    /** {@code ?x=A,B} or {@code ?x=A&x=B}. */
    private <E extends Enum<E>> Set<E> parseAll(Class<E> type, List<String> values, String field) {
        Set<E> parsed = EnumSet.noneOf(type);
        if (values == null) {
            return parsed;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    parsed.add(parse(type, part).orElseThrow(() -> filterInvalid(field)));
                }
            }
        }
        return parsed;
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private BadRequestException filterInvalid(String field) {
        return new BadRequestException(FILTER_INVALID, messageService.getMessage(FILTER_INVALID), Map.of("invalidField", field));
    }
}
