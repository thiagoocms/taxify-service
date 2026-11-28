package br.com.cshub.taxify.mapper.fiscaldocument;

import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentSource;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.dto.fiscalxml.ServiceInvoiceDTOs;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.integration.nfse.NfseXml;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The NFS-e of the model as the API shows it today (FD-RN-23): {@code direction} PRESTADA/TOMADA/TERCEIRO from the
 * company's role, {@code layout} NACIONAL/ABRASF, {@code origem} SIEG/ADN/UPLOAD/SEFAZ_DIST and the values with the
 * names of the screen. The translation lives here, never in the domain.
 */
public final class NfseApiMapper {

    private static final int SUMMARY_DESCRIPTION = 140;

    private NfseApiMapper() {
    }

    public static ServiceInvoiceDTOs.Summary toSummary(Nfse s) {
        String description = s.getServiceDescription();
        if (description != null && description.length() > SUMMARY_DESCRIPTION) {
            description = description.substring(0, SUMMARY_DESCRIPTION) + "…";
        }
        return new ServiceInvoiceDTOs.Summary(s.getId(), s.getNumber(), layoutName(s.getLayout()), s.getIssueDate(),
                s.getCompetence(), direction(s), s.getIssuer().name(), s.getIssuer().document(), s.getRecipient().name(),
                s.getRecipient().document(), description, s.getTotalAmount(), s.getIss().amount(), s.getIss().isWithheld(),
                totalWithheld(s), s.isCanceled(), sourceName(s));
    }

    public static ServiceInvoiceDTOs.Detail toDetail(Nfse s) {
        return new ServiceInvoiceDTOs.Detail(s.getId(), s.getCompany().getId(), s.getAccessKey(), layoutName(s.getLayout()),
                s.getNumber(), s.getVerificationCode(), s.getIssueDate(), s.getCompetence(), direction(s),
                s.getIssuer().document(), s.getIssuer().name(), s.getIssuer().cityCode(), s.getRecipient().document(),
                s.getRecipient().name(), s.getIncidenceCityCode(), s.getServiceCode(), s.getMunicipalTaxCode(),
                s.getServiceDescription(), s.getTotalAmount(), s.getDeductionsAmount(), s.getIss().base(), s.getIss().rate(),
                s.getIss().amount(), s.getIss().isWithheld(), s.getIss().withheldAmount(), s.getWithholdings().irrf(),
                s.getPisAmount(), s.getCofinsAmount(), s.isPisCofinsWithheld(), s.getWithholdings().csll(),
                s.getWithholdings().inss(), s.getNetAmount(), totalWithheld(s), s.isCanceled(), sourceName(s));
    }

    /** PRESTADA / TOMADA / TERCEIRO. */
    public static String direction(Nfse s) {
        return LegacyNfseDirection.ofRole(s.getCompanyRole()).name();
    }

    /** NACIONAL / ABRASF, the names of the old layout column. */
    public static String layoutName(NfseLayout layout) {
        if (layout == null) {
            return null;
        }
        return layout == NfseLayout.NATIONAL ? NfseXml.NACIONAL : NfseXml.ABRASF;
    }

    /** The first origin with the name the API shows (ADN for NFSE_ADN, SEFAZ_DIST for SEFAZ_DFE). */
    public static String sourceName(Nfse s) {
        return s.getSourceSystem() == null ? null : LegacySourceNames.toLegacyName(s.getSourceSystem());
    }

    /** When {@code source} first delivered the note (the old {@code found_in_*_at}); null = never. */
    public static LocalDateTime firstSeenIn(Nfse s, DocumentSourceKey source) {
        return s.getSources().stream().filter(origin -> origin.getSource().equals(source))
                .map(FiscalDocumentSource::getFirstSeenAt).min(LocalDateTime::compareTo).orElse(null);
    }

    /** ISS withheld + IRRF + CSLL + INSS + PIS/COFINS when withheld. */
    public static BigDecimal totalWithheld(Nfse s) {
        BigDecimal total = BigDecimal.ZERO;
        if (s.getIss().isWithheld()) {
            total = total.add(orZero(s.getIss().withheldAmount()));
        }
        return total.add(federalWithheld(s));
    }

    /** IRRF + CSLL + INSS + PIS/COFINS when withheld. */
    public static BigDecimal federalWithheld(Nfse s) {
        BigDecimal total = orZero(s.getWithholdings().irrf()).add(orZero(s.getWithholdings().csll()))
                .add(orZero(s.getWithholdings().inss()));
        if (s.isPisCofinsWithheld()) {
            total = total.add(orZero(s.getPisAmount())).add(orZero(s.getCofinsAmount()));
        }
        return total;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
