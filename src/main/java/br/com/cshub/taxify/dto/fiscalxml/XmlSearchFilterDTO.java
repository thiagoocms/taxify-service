package br.com.cshub.taxify.dto.fiscalxml;

import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.dto.PageRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of the stored-XML search and of its cards ({@code /totais}): company/period filter plus the page to return. Kept
 * apart from the SIEG XmlDownloadFilterDTO, whose take/skip/xmlType belong to the SIEG
 * API call, not to our own database pagination.
 */
public record XmlSearchFilterDTO(
        @NotBlank
        String companyId,
        LocalDate dataEmissaoInicio,
        LocalDate dataEmissaoFim,
        /** Optional exact match on the note number (nNF); blank means "any". */
        String numero,
        /** Entries or exits only; null = both. */
        FiscalDirection direction,
        /** Piece of the other party's name, or digits of its CNPJ/CPF; blank = any. */
        String participant,
        /** Conferência status; null = any. */
        ReviewStatus reviewStatus,
        @Valid
        PageRequestDTO pagination,
        /** SIEG, SEFAZ_DIST or UPLOAD; blank = any. */
        String origem,
        /**
         * SIEG x SEFAZ comparison: SO_SIEG = SIEG has had it for over 48h and SEFAZ never handed it
         * over (third-party notes only - SEFAZ doesn't distribute the company's own); SO_SEFAZ = the
         * other way round. Blank = no comparison.
         */
        String fonte,
        /**
         * <b>Deprecated</b> (CF-RN-09, one release): the old single CFOP - only digits, never checked; ignored when
         * {@code cfops} comes filled. Removed in the next release (B-CF-06).
         */
        String cfop,
        /** true = only notes the fiscal checks found something on, or marked "com divergência"; null/false = all. */
        Boolean divergencia,
        /**
         * Notes with some item whose effective CFOP is one of these (CF-RN-01 to 07): "1.102" reads as 1102, 4 digits
         * each, at most 20 distinct; null or empty = any.
         */
        List<String> cfops
) {

    public static final String ONLY_SIEG = "SO_SIEG";
    public static final String ONLY_SEFAZ = "SO_SEFAZ";

    /** Without origin / comparison. */
    public XmlSearchFilterDTO(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim, String numero,
                              FiscalDirection direction, String participant, ReviewStatus reviewStatus,
                              PageRequestDTO pagination) {
        this(companyId, dataEmissaoInicio, dataEmissaoFim, numero, direction, participant, reviewStatus, pagination, null, null, null, null, null);
    }

    public boolean onlyDivergent() {
        return Boolean.TRUE.equals(divergencia);
    }
}
