package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.CapturePanelProperties;
import br.com.cshub.taxify.enumerated.CaptureChannel;
import br.com.cshub.taxify.enumerated.CaptureSituation;
import br.com.cshub.taxify.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validações de entrada do painel da captura (CP-RN-13, CP-RN-14): filtros, ordem e página. Valor desconhecido,
 * página negativa ou tamanho fora de 1..{@code TAXIFY_CAPTURE_PANEL_MAX_PAGE_SIZE}: 400 {@code error.generic.bad.request}.
 */
@Component
public class CapturePanelValidation {

    /** O filtro "tipo" (CP-RN-13). */
    public enum ItemType {
        NFE_RECEBIDAS, NFE_EMITIDAS, EVENTOS, NFSE
    }

    /** A ordem (CP-RN-14): situação (pior primeiro, padrão), nome, próxima tentativa. */
    public enum Sort {
        STATUS, NAME, NEXT_ATTEMPT
    }

    /** Os filtros já validados. */
    public record Filter(Set<CaptureSituation> situations, CaptureChannel channel, ItemType type, String search,
                         boolean includeInactive, Sort sort, int page, int size) {
    }

    /** Bigger than any real scope (thousands of companies): a nonsense page is a 400, never a 500. */
    static final int MAX_PAGE = 100_000;

    private final MessageService messageService;
    private final CapturePanelProperties properties;

    public CapturePanelValidation(MessageService messageService, CapturePanelProperties properties) {
        this.messageService = messageService;
        this.properties = properties;
    }

    public Filter check(List<String> situations, String channel, String type, String search, boolean includeInactive,
                        String sort, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > properties.getMaxPageSize()) {
            throw badRequest();
        }
        Set<CaptureSituation> parsed = EnumSet.noneOf(CaptureSituation.class);
        if (situations != null) {
            for (String value : situations) {
                if (value == null) {
                    continue;
                }
                // ?situacao=COM_ERRO,ATRASADA or ?situacao=COM_ERRO&situacao=ATRASADA
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        parsed.add(parse(CaptureSituation.class, part));
                    }
                }
            }
        }
        String term = search == null || search.isBlank() ? null : search.strip();
        if (term != null && term.length() > 150) {
            throw badRequest();
        }
        return new Filter(parsed, blank(channel) ? null : parse(CaptureChannel.class, channel),
                blank(type) ? null : parse(ItemType.class, type), term, includeInactive,
                blank(sort) ? Sort.STATUS : parseSort(sort), page, size);
    }

    private Sort parseSort(String value) {
        String normalized = value.strip().replace("-", "_").replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return parse(Sort.class, normalized);
    }

    private <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BadRequestException badRequest() {
        return new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
    }
}
