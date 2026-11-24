package br.com.cshub.taxify.dto.fiscalxml;

import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.dto.PageRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * Body of the stored-XML search: company/period filter plus the page to return. Kept
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
        /** Notes with some item issued with this CFOP (4 digits); blank = any. */
        String cfop,
        /** true = only notes the fiscal checks found something on, or marked "com divergência"; null/false = all. */
        Boolean divergencia
) {

    public static final String ONLY_SIEG = "SO_SIEG";
    public static final String ONLY_SEFAZ = "SO_SEFAZ";

    /** Without origin / comparison. */
    public XmlSearchFilterDTO(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim, String numero,
                              FiscalDirection direction, String participant, ReviewStatus reviewStatus,
                              PageRequestDTO pagination) {
        this(companyId, dataEmissaoInicio, dataEmissaoFim, numero, direction, participant, reviewStatus, pagination, null, null, null, null);
    }

    public boolean onlyDivergent() {
        return Boolean.TRUE.equals(divergencia);
    }
}
