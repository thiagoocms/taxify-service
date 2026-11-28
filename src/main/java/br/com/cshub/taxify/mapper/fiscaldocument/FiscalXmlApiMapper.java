package br.com.cshub.taxify.mapper.fiscaldocument;

import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoiceHeader;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsTotals;
import br.com.cshub.taxify.domain.fiscaldocument.IbsCbsTotals;
import br.com.cshub.taxify.domain.fiscaldocument.IssuerDetails;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIbsCbs;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIcms;
import br.com.cshub.taxify.domain.fiscaldocument.Party;
import br.com.cshub.taxify.domain.fiscaldocument.PartyAddress;
import br.com.cshub.taxify.domain.fiscaldocument.Product;
import br.com.cshub.taxify.domain.fiscaldocument.RecipientDetails;
import br.com.cshub.taxify.dto.fiscalxml.FiscalIbsCbsTotalsDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalItemIbsCbsDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalItemTaxDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * NF-e, NFC-e and CT-e of the model as the API of the notes screen shows them today (FD-RN-23): the same DTOs and JSON
 * - {@code documentType} {@code NFE} for NF-e and NFC-e and {@code CTE} ({@link FiscalDocument#legacyKeyLabel()}),
 * CNPJ and CPF in their own fields, {@code ownIssue} from the role, the origin with its old name ({@code SEFAZ_DIST}),
 * {@code foundInSiegAt}/{@code foundInSefazAt} from {@code fiscal_document_sources}.
 */
public final class FiscalXmlApiMapper {

    private FiscalXmlApiMapper() {
    }

    /** The document type the API shows: NFE (NF-e and NFC-e) or CTE. */
    public static String documentType(FiscalDocument document) {
        return document.legacyKeyLabel();
    }

    /** The first origin with its API name. */
    public static String sourceName(FiscalDocument document) {
        return document.getSourceSystem() == null ? null : LegacySourceNames.toLegacyName(document.getSourceSystem());
    }

    public static LocalDateTime foundInSieg(FiscalDocument document) {
        return document.firstSeenIn(DocumentSourceKey.SIEG).orElse(null);
    }

    public static LocalDateTime foundInSefaz(FiscalDocument document) {
        return document.firstSeenIn(DocumentSourceKey.SEFAZ_DFE).orElse(null);
    }

    public static boolean isRectified(FiscalDocument document) {
        return document instanceof GoodsInvoice goods && goods.getRectifiedFilePath() != null;
    }

    private static GoodsInvoiceHeader header(FiscalDocument document) {
        return document instanceof GoodsInvoice goods ? goods.getHeader() : GoodsInvoiceHeader.EMPTY;
    }

    public static FiscalXmlSummaryDTO toSummary(FiscalDocument document, long itemCount, List<String> cfops,
                                                FiscalXmlSummaryDTO.DivergenceSummary divergences) {
        Party issuer = document.getIssuer();
        Party recipient = document.getRecipient();
        return new FiscalXmlSummaryDTO(
                document.getId(),
                document.getAccessKey(),
                documentType(document),
                document.getIssueDate(),
                document.getModel(),
                document.getSeries(),
                document.getNumber(),
                header(document).operationType(),
                document.getOperationNature(),
                issuer.name(),
                issuer.cnpj(),
                issuer.cpf(),
                recipient.name(),
                recipient.cnpj(),
                recipient.cpf(),
                document.getTotalAmount(),
                itemCount,
                isRectified(document),
                document.getDirection(),
                document.isOwnIssue(),
                document.getStatus(),
                document.getReviewStatus(),
                cfops,
                sourceName(document),
                divergences
        );
    }

    /** The detail from the stored columns - no file read (parser version 4 on, or the file is gone). */
    public static FiscalXmlDetailDTO detailFromColumns(FiscalDocument d, long itemCount) {
        GoodsInvoiceHeader h = header(d);
        IssuerDetails issuerDetails = d instanceof GoodsInvoice goods ? goods.getIssuerDetails() : IssuerDetails.EMPTY;
        RecipientDetails recipientDetails = d instanceof GoodsInvoice goods ? goods.getRecipientDetails() : RecipientDetails.EMPTY;
        Party issuer = d.getIssuer();
        Party recipient = d.getRecipient();
        FiscalXmlIdentificacaoDTO identificacao = new FiscalXmlIdentificacaoDTO(
                h.ufCode(), d.getOperationNature(), d.getModel(), d.getSeries(), d.getNumber(),
                d.getIssueDate(), h.exitEntryAt(), h.operationType(), h.destinationType(),
                h.taxableEventCityCode(), h.printType(), h.emissionType(), h.checkDigit(),
                h.environment(), h.purpose(), h.finalConsumer(), h.presenceIndicator(),
                h.emissionProcess(), h.emissionProcessVersion());

        PartyAddress ia = issuerDetails.addressOrEmpty();
        FiscalXmlEmitenteDTO emitente = new FiscalXmlEmitenteDTO(
                issuer.cnpj(), issuer.cpf(), issuer.name(), issuerDetails.tradeName(),
                issuerDetails.stateRegistration(), issuerDetails.stStateRegistration(),
                issuerDetails.municipalRegistration(), issuerDetails.cnae(), issuerDetails.taxRegimeCode(),
                new FiscalXmlEnderecoDTO(ia.street(), ia.number(), ia.complement(), ia.district(), issuer.cityCode(),
                        ia.cityName(), issuer.uf(), ia.zipCode(), ia.countryCode(), ia.countryName(), ia.phone()));

        PartyAddress ra = recipientDetails.addressOrEmpty();
        FiscalXmlDestinatarioDTO destinatario = new FiscalXmlDestinatarioDTO(
                recipient.cnpj(), recipient.cpf(), recipient.foreignId(), recipient.name(),
                recipientDetails.stateRegistration(), recipientDetails.stateRegistrationIndicator(), recipientDetails.email(),
                new FiscalXmlEnderecoDTO(ra.street(), ra.number(), ra.complement(), ra.district(), recipient.cityCode(),
                        ra.cityName(), recipient.uf(), ra.zipCode(), ra.countryCode(), ra.countryName(), ra.phone()));

        return new FiscalXmlDetailDTO(d.getId(), d.getCompany().getId(), d.getAccessKey(), documentType(d),
                issuer.cnpj(), recipient.cnpj(), d.getIssueDate(), isRectified(d),
                itemCount, identificacao, emitente, destinatario, totais(d),
                d.getDirection(), d.isOwnIssue(), d.getStatus(), d.getCanceledAt(),
                d.getReviewStatus(), d.getReviewedAt(), d.getReviewedBy(), d.getReviewNote(),
                sourceName(d), foundInSieg(d), foundInSefaz(d));
    }

    /** The detail with the header read again from the file (older parser versions), the rest from the document. */
    public static FiscalXmlDetailDTO detailFromFile(FiscalDocument document, FiscalXmlMetadata metadata, long itemCount) {
        return new FiscalXmlDetailDTO(
                document.getId(),
                document.getCompany().getId(),
                metadata.accessKey(),
                metadata.documentType(),
                metadata.issuerCnpj(),
                metadata.recipientCnpj(),
                metadata.issueDate(),
                isRectified(document),
                itemCount,
                metadata.identificacao(),
                metadata.emitente(),
                metadata.destinatario(),
                metadata.totais(),
                document.getDirection(),
                document.isOwnIssue(),
                document.getStatus(),
                document.getCanceledAt(),
                document.getReviewStatus(),
                document.getReviewedAt(),
                document.getReviewedBy(),
                document.getReviewNote(),
                sourceName(document), foundInSieg(document), foundInSefaz(document));
    }

    private static FiscalXmlTotaisDTO totais(FiscalDocument d) {
        GoodsTotals t = d instanceof GoodsInvoice goods ? goods.getGoodsTotals() : GoodsTotals.EMPTY;
        BigDecimal icmsBase = t.icmsBase();
        BigDecimal icmsAmount = t.icmsAmount();
        if (d instanceof Cte cte && cte.getIcms() != null) {
            // the CT-e keeps the ICMS base and value in the columns shared with the goods totals
            icmsBase = cte.getIcms().base();
            icmsAmount = cte.getIcms().amount();
        }
        return new FiscalXmlTotaisDTO(
                icmsBase, icmsAmount, t.icmsReliefAmount(), t.fcpAmount(),
                t.icmsStBase(), t.icmsStAmount(), t.fcpStAmount(), t.productsAmount(),
                t.freightAmount(), t.insuranceAmount(), t.discountAmount(), t.importTaxAmount(),
                t.ipiAmount(), t.pisAmount(), t.cofinsAmount(), t.otherExpensesAmount(),
                d.getTotalAmount(), t.approximateTaxesAmount(), ibsCbsTotals(d.getIbsCbsTotals()));
    }

    public static FiscalIbsCbsTotalsDTO ibsCbsTotals(IbsCbsTotals totals) {
        if (totals == null) {
            return null;
        }
        return new FiscalIbsCbsTotalsDTO(totals.base(), totals.ibsStateAmount(), totals.ibsCityAmount(), totals.ibsAmount(),
                totals.cbsAmount());
    }

    /** An item as issued (the DANFE prints these). */
    public static FiscalXmlProdutoDTO toProduto(FiscalDocumentItem item) {
        Product p = item.getProduct();
        ItemIcms icms = item.getIcms();
        return new FiscalXmlProdutoDTO(
                item.getItemNumber(),
                p.code(),
                p.barcode(),
                p.description(),
                p.ncm(),
                p.cfop(),
                item.getCommercial().unit(),
                item.getCommercial().quantity(),
                item.getCommercial().unitPrice(),
                item.getTotalAmount(),
                item.getTaxable().unit(),
                item.getTaxable().quantity(),
                item.getTaxable().unitPrice(),
                new FiscalXmlImpostoItemDTO(
                        icms.cst(),
                        icms.base(),
                        icms.rate(),
                        icms.amount(),
                        item.getIpi().cst(),
                        item.getIpi().amount(),
                        item.getPis().cst(),
                        item.getPis().amount(),
                        item.getCofins().cst(),
                        item.getCofins().amount(),
                        taxDetail(item),
                        ibsCbs(item.getIbsCbs())
                )
        );
    }

    /** The tax detail of the item as the API shows it; null when the item has none (read by an older parser). */
    public static FiscalItemTaxDetailDTO taxDetail(FiscalDocumentItem item) {
        if (!item.hasTaxDetail()) {
            return null;
        }
        Product p = item.getProduct();
        ItemIcms icms = item.getIcms();
        return new FiscalItemTaxDetailDTO(p.cest(), p.benefitCode(), icms.origin(), icms.csosn(), icms.baseModality(),
                icms.baseReduction(), icms.reliefAmount(), item.getIcmsSt().baseModality(), item.getIcmsSt().mva(),
                item.getIcmsSt().base(), item.getIcmsSt().rate(), item.getIcmsSt().amount(), item.getIpi().base(),
                item.getIpi().rate(), item.getPis().base(), item.getPis().rate(), item.getCofins().base(),
                item.getCofins().rate(), item.getSimplesCredit().rate(), item.getSimplesCredit().amount());
    }

    public static FiscalItemIbsCbsDTO ibsCbs(ItemIbsCbs group) {
        if (group == null) {
            return null;
        }
        return new FiscalItemIbsCbsDTO(group.cst(), group.classCode(), group.base(), group.stateRate(),
                group.stateRateReduction(), group.stateAmount(), group.cityRate(), group.cityRateReduction(),
                group.cityAmount(), group.ibsAmount(), group.cbsRate(), group.cbsRateReduction(), group.cbsAmount(),
                group.deferralOrRefund());
    }
}
