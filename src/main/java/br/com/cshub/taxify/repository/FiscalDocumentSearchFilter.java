package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.fiscaldocument.CfopFilter;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.ReviewStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The filters of the notes screen, already read ({@code FiscalXmlQueryService}), and {@link #WHERE}: <b>the one
 * predicate</b> the list ({@link FiscalDocumentRepository#search}), the cards ({@link GoodsInvoiceRepository#totalsByDirection(FiscalDocumentSearchFilter)})
 * and the items in the filtered CFOPs ({@link FiscalDocumentItemRepository#cfopItemTotalsByDirection}) all apply - so the
 * cards never drift from the list (spec 2026-11-27, CF-RN-10, risk R-01). The queries bind the record as
 * {@code @Param(PARAM)} and the predicate reads its accessors ({@code :#{#filter.x()}}).
 *
 * <p>The period is half-open, {@code [issuedFrom, issuedBefore)} ({@code IssuePeriod}). Every field but the company and the period is optional (null = not applied): {@code number} exact,
 * {@code direction} entries/exits, {@code participant} a lower-case {@code %piece%} of the issuer's or recipient's name
 * or digits of their document, {@code reviewStatus} (null review = PENDING), {@code source} the first origin,
 * {@code onlySiegBefore}/{@code onlySefazBefore} the SIEG x SEFAZ comparison (FD-RN-13) and {@code cfops} some item with
 * one of them as its <b>effective</b> CFOP - the rectified one when the note has a rectified XML (FD-RN-57; P-CF-02,
 * decision of 2026-11-28), index idx_fdi_document_effective_cfop.
 *
 * <p>Each SpEL expression is a parameter of its own, so a null check never shares the type of the comparison next to it:
 * it binds a boolean ({@code :#{#filter.x() == null} = true}) - a bare {@code :param is null} of a converted type
 * ({@link DocumentSourceKey}) has no JDBC type.
 *
 * <p>A new filter of the screen: a component here, a clause in {@link #WHERE} (the same {@code :#{#filter.x()}}
 * style; for a list, a flag first and {@code valuesOrSentinel}, as {@code cfops}) and, when it narrows what the screen
 * shows, a term in {@link #narrowsTheScope()}. No query signature changes.
 */
public record FiscalDocumentSearchFilter(
        UUID companyId,
        LocalDateTime issuedFrom,
        LocalDateTime issuedBefore,
        String number,
        FiscalDirection direction,
        String participant,
        ReviewStatus reviewStatus,
        DocumentSourceKey source,
        LocalDateTime onlySiegBefore,
        LocalDateTime onlySefazBefore,
        CfopFilter cfops
) {

    /** The {@code @Param} name the queries bind this record as. */
    public static final String PARAM = "filter";

    /** The predicate over {@code d} (a FiscalDocument, or a subtype); the caller adds the document type. */
    public static final String WHERE = " d.company.id = :#{#filter.companyId()} and d.deleted = false"
            + " and d.issueDate >= :#{#filter.issuedFrom()} and d.issueDate < :#{#filter.issuedBefore()}"
            + " and (:#{#filter.number() == null} = true or d.number = :#{#filter.number()})"
            + " and (:#{#filter.direction() == null} = true or d.direction = :#{#filter.direction()})"
            + " and (:#{#filter.participant() == null} = true"
            + "      or lower(d.issuer.name) like :#{#filter.participant()} or lower(d.recipient.name) like :#{#filter.participant()}"
            + "      or d.issuer.document like :#{#filter.participant()} or d.recipient.document like :#{#filter.participant()})"
            + " and (:#{#filter.reviewStatus() == null} = true"
            + "      or coalesce(d.review.status, br.com.cshub.taxify.enumerated.ReviewStatus.PENDING) = :#{#filter.reviewStatus()})"
            + " and (:#{#filter.source() == null} = true or d.sourceSystem = :#{#filter.source()})"
            + " and (:#{#filter.onlySiegBefore() == null} = true"
            + "      or (d.companyRole <> br.com.cshub.taxify.enumerated.CompanyDocumentRole.ISSUER"
            + "      and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :#{#filter.sieg()}"
            + "                  and s.firstSeenAt < :#{#filter.onlySiegBefore()})"
            + "      and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :#{#filter.sefaz()})))"
            + " and (:#{#filter.onlySefazBefore() == null} = true"
            + "      or (exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :#{#filter.sefaz()}"
            + "                  and s.firstSeenAt < :#{#filter.onlySefazBefore()})"
            + "      and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :#{#filter.sieg()})))"
            + " and (:#{#filter.noCfops()} = true"
            + "      or exists (select ci.id from FiscalDocumentItem ci where ci.document = d"
            + "                 and ci.effectiveCfop in :#{#filter.cfopValues()}))";

    public FiscalDocumentSearchFilter {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(issuedFrom, "issuedFrom");
        Objects.requireNonNull(issuedBefore, "issuedBefore");
        cfops = cfops == null ? CfopFilter.none() : cfops;
    }

    /** CF-RN-11: the cards are the tab selector - the same filter without the tab. */
    public FiscalDocumentSearchFilter withoutDirection() {
        return new FiscalDocumentSearchFilter(companyId, issuedFrom, issuedBefore, number, null, participant, reviewStatus,
                source, onlySiegBefore, onlySefazBefore, cfops);
    }

    /**
     * CF-RN-17: some filter beyond the company, the period and the tab is on - the cards show "Totais do filtro".
     * ({@code divergencia} runs in memory and is added by the caller.)
     */
    public boolean narrowsTheScope() {
        return number != null || participant != null || reviewStatus != null || source != null
                || onlySiegBefore != null || onlySefazBefore != null || !cfops.isEmpty();
    }

    public boolean noCfops() {
        return cfops.isEmpty();
    }

    /** What {@code in (...)} binds: never an empty list (see {@link CfopFilter#valuesOrSentinel()}). */
    public List<String> cfopValues() {
        return cfops.valuesOrSentinel();
    }

    public DocumentSourceKey sieg() {
        return DocumentSourceKey.SIEG;
    }

    public DocumentSourceKey sefaz() {
        return DocumentSourceKey.SEFAZ_DFE;
    }
}
