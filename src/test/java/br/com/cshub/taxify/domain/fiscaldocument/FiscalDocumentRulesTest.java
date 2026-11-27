package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.E2_CNPJ;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.company;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.e1;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.issuedNfce;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.receivedNfe;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.takenCte;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.takenNfse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pure FD-RN rules of the new model (no database). */
class FiscalDocumentRulesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 8, 0);

    @Nested
    class Fd01HierarchyAndLegacyLabel {
        /** FD-RN-21: the NFC-e is "NFE" in the natural keys; the discriminator is not. */
        @Test
        void legacyKeyLabelIsTheTextOfTheStoredKeys() {
            assertThat(receivedNfe().legacyKeyLabel()).isEqualTo("NFE");
            assertThat(issuedNfce().legacyKeyLabel()).isEqualTo("NFE");
            assertThat(takenNfse().legacyKeyLabel()).isEqualTo("NFSE");
            assertThat(takenCte().legacyKeyLabel()).isEqualTo("CTE");
            assertThat(issuedNfce().getDocumentType()).isEqualTo(FiscalDocumentType.NFCE);
        }

        @Test
        void subtypesStartWithTheirModel() {
            assertThat(new Nfe(e1(), DocumentSourceKey.SIEG).getModel()).isEqualTo("55");
            assertThat(new Nfce(e1(), DocumentSourceKey.SIEG).getModel()).isEqualTo("65");
            assertThat(new Cte(e1(), DocumentSourceKey.SIEG).getModel()).isEqualTo("57");
        }
    }

    @Nested
    class Fd08DirectionAndRole {
        @Test
        void ownIssueIsTheIssuerRole() {
            assertThat(issuedNfce().isOwnIssue()).isTrue();
            assertThat(receivedNfe().isOwnIssue()).isFalse();
        }

        /** Precedence ISSUER > TAKER > RECIPIENT > SENDER > SHIPPER > RECEIVER; direction only for issuer and taker. */
        @ParameterizedTest
        @CsvSource({
                // issuer, sender, shipper, receiver, recipient, taker, role, direction
                "true,  true,  false, false, true,  true,  ISSUER,    OUTBOUND",
                "false, true,  false, false, true,  true,  TAKER,     INBOUND",
                "false, true,  true,  true,  true,  false, RECIPIENT, ",
                "false, true,  true,  true,  false, false, SENDER,    ",
                "false, false, true,  true,  false, false, SHIPPER,   ",
                "false, false, false, true,  false, false, RECEIVER,  ",
                "false, false, false, false, false, false, THIRD_PARTY, "
        })
        void cteRoleByPrecedence(boolean issuer, boolean sender, boolean shipper, boolean receiver, boolean recipient,
                                 boolean taker, CompanyDocumentRole role, FiscalDirection direction) {
            Cte cte = new Cte(e1(), DocumentSourceKey.SIEG);
            CteParticipation participation = new CteParticipation(issuer, sender, shipper, receiver, recipient, taker);
            cte.assignParticipation(participation);
            assertThat(cte.getCompanyRole()).isEqualTo(role);
            assertThat(cte.getDirection()).isEqualTo(direction);
            assertThat(cte.getParticipation()).isEqualTo(participation);
        }

        @Test
        void participationWithNullFlagsCountsOnlyTheTrueOnes() {
            CteParticipation participation = new CteParticipation(null, null, true, null, null, null);
            assertThat(participation.hasAnyRole()).isTrue();
            assertThat(participation.primaryRole()).isEqualTo(CompanyDocumentRole.SHIPPER);
            assertThat(new CteParticipation(null, null, null, null, null, null).hasAnyRole()).isFalse();
        }
    }

    @Nested
    class Fd09Party {
        @ParameterizedTest
        @CsvSource({"11222333000181, CNPJ", "12345678909, CPF", "123456, ", "1122233300018X, ", "'', "})
        void brazilianDocumentTypeBySize(String document, TaxpayerDocumentType expected) {
            assertThat(TaxpayerDocumentType.bySize(document)).isEqualTo(expected);
        }

        @Test
        void aPartyWithoutDocumentAndTypeIsCoherent() {
            assertThat(new Party(null, null, "Consumidor", null, null).hasCoherentDocument()).isTrue();
            assertThat(new Party(null, TaxpayerDocumentType.CPF, "Consumidor", null, null).hasCoherentDocument()).isFalse();
        }
    }

    @Nested
    class Fd10Cancellation {
        @Test
        void cancelIsIdempotentAndTheFirstDateWins() {
            Nfe nfe = receivedNfe();
            assertThat(nfe.cancel(AT)).isTrue();
            assertThat(nfe.cancel(AT.plusHours(1))).isFalse();
            assertThat(nfe.getCanceledAt()).isEqualTo(AT);
        }

        @Test
        void canceledInvoiceAndOwnIssueAreNotRectifiable() {
            assertThat(receivedNfe().isRectifiable()).isTrue();
            assertThat(issuedNfce().isRectifiable()).isFalse();
            Nfe canceled = receivedNfe();
            canceled.cancel(AT);
            assertThat(canceled.isRectifiable()).isFalse();
        }
    }

    @Nested
    class Fd06Items {
        @Test
        void itemsBelongToTheInvoiceAndTheCfopIsStoredWithoutSpaces() {
            Nfe nfe = receivedNfe();
            FiscalDocumentItem item = new FiscalDocumentItem(1, new Product("P1", null, "Tecido", "52081100", null, null, "1102 "));
            nfe.addItem(item);
            assertThat(nfe.getItems()).containsExactly(item);
            assertThat(item.getDocument()).isSameAs(nfe);
            assertThat(item.cfop()).isEqualTo("1102");
            assertThatThrownBy(() -> receivedNfe().addItem(item)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> nfe.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Fd07Events {
        private FiscalDocumentEvent event(Company company, String key, String type, boolean registered) {
            return new FiscalDocumentEvent(company, key, type, 1, "Cancelamento", AT, "123", "Erro de digitação", registered);
        }

        @Test
        void aRegisteredCancellationCancelsTheDocumentOfAnyTypeWhenLinked() {
            Nfe nfe = receivedNfe();
            FiscalDocumentEvent cancellation = event(nfe.getCompany(), nfe.getAccessKey(), "110111", true);
            assertThat(cancellation.isLinked()).isFalse();
            assertThat(cancellation.linkTo(nfe)).isTrue();
            assertThat(cancellation.isLinked()).isTrue();
            assertThat(nfe.getCanceledAt()).isEqualTo(AT);

            Cte cte = takenCte();
            assertThat(event(cte.getCompany(), cte.getAccessKey(), "110112", true).linkTo(cte)).isTrue();

            Nfse nfse = takenNfse();
            assertThat(event(nfse.getCompany(), nfse.getAccessKey(), "101101", true).linkTo(nfse)).isTrue();
            assertThat(nfse.isCanceled()).isTrue();
        }

        @Test
        void otherEventsAndUnregisteredCancellationsOnlyLink() {
            Nfe nfe = receivedNfe();
            assertThat(event(nfe.getCompany(), nfe.getAccessKey(), "110110", true).linkTo(nfe)).isFalse();
            assertThat(event(nfe.getCompany(), nfe.getAccessKey(), "110111", false).linkTo(nfe)).isFalse();
            assertThat(nfe.isCanceled()).isFalse();
        }

        @Test
        void anEventNeverLinksToAnotherCompanyOrKey() {
            Nfe nfe = receivedNfe();
            FiscalDocumentEvent otherCompany = event(company(E2_CNPJ), nfe.getAccessKey(), "110111", true);
            assertThatThrownBy(() -> otherCompany.linkTo(nfe)).isInstanceOf(IllegalArgumentException.class);
            FiscalDocumentEvent otherKey = event(nfe.getCompany(), "x" + nfe.getAccessKey(), "110111", true);
            assertThatThrownBy(() -> otherKey.linkTo(nfe)).isInstanceOf(IllegalArgumentException.class);
            assertThat(nfe.isCanceled()).isFalse();
        }

        @Test
        void descriptionIsCutAt120AndKeyIsRequired() {
            String longText = "x".repeat(130);
            FiscalDocumentEvent event = new FiscalDocumentEvent(e1(), "K", "110110", 1, longText, AT, null, null, true);
            assertThat(event.getDescription()).hasSize(120);
            assertThatThrownBy(() -> new FiscalDocumentEvent(e1(), " ", "110110", 1, null, AT, null, null, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Fd16Purge {
        @Test
        void theFirstPurgeMarkWinsAndTheDocumentStays() {
            Nfe nfe = receivedNfe();
            assertThat(nfe.markXmlPurged(AT)).isTrue();
            assertThat(nfe.markXmlPurged(AT.plusMonths(1))).isFalse();
            assertThat(nfe.getXmlPurgedAt()).isEqualTo(AT);
            assertThat(nfe.isXmlPurged()).isTrue();
            assertThat(nfe.getXmlFilePath()).isNotNull();
        }
    }

    @Nested
    class Fd31Sources {
        /** CT-FD-28: two deliveries of the same source = one origin, last_seen_at = the greater. */
        @Test
        void theSameSourceTwiceIsOneOriginWithTheGreaterLastSeen() {
            Nfe nfe = receivedNfe();
            nfe.markSeenIn(DocumentSourceKey.SIEG, AT.plusMinutes(5));
            nfe.markSeenIn(DocumentSourceKey.SIEG, AT);
            assertThat(nfe.getSources()).hasSize(1);
            FiscalDocumentSource source = nfe.sourceOf(DocumentSourceKey.SIEG).orElseThrow();
            assertThat(source.getFirstSeenAt()).isEqualTo(AT.plusMinutes(5));
            assertThat(source.getLastSeenAt()).isEqualTo(AT.plusMinutes(5));
            nfe.markSeenIn(DocumentSourceKey.SIEG, AT.plusHours(2));
            assertThat(source.getLastSeenAt()).isEqualTo(AT.plusHours(2));
            assertThat(source.getCompanyId()).isEqualTo(nfe.getCompany().getId());
        }

        /** CA-FD-20: SIEG and SEFAZ deliver the same note: one document, two origins. */
        @Test
        void twoSourcesAreTwoOrigins() {
            Nfe nfe = receivedNfe();
            nfe.markSeenIn(DocumentSourceKey.SIEG, AT);
            nfe.markSeenIn(DocumentSourceKey.SEFAZ_DFE, AT.plusMinutes(75));
            assertThat(nfe.getSources()).extracting(FiscalDocumentSource::getSource)
                    .containsExactly(DocumentSourceKey.SIEG, DocumentSourceKey.SEFAZ_DFE);
            assertThat(nfe.firstSeenIn(DocumentSourceKey.SEFAZ_DFE)).contains(AT.plusMinutes(75));
            assertThat(nfe.firstSeenIn(DocumentSourceKey.NFSE_ADN)).isEmpty();
        }
    }

    @Nested
    class Fd40OpenKeys {
        @Test
        void aNewProviderNeedsNoEnumChange() {
            assertThat(ProviderKey.of("FAKE").asSource()).isEqualTo(DocumentSourceKey.of("FAKE"));
            assertThat(DocumentSourceKey.UPLOAD.isUpload()).isTrue();
            assertThat(DocumentSourceKey.SIEG.isUpload()).isFalse();
            assertThat(DocumentSourceKey.of("A".repeat(30)).value()).hasSize(30);
        }

        @Test
        void aKeyOfAnotherShapeIsRefused() {
            assertThatThrownBy(() -> DocumentSourceKey.of("sieg")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DocumentSourceKey.of("A".repeat(31))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProviderKey.of("1SIEG")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProviderKey.of("SIEG ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void convertersKeepTheText() {
            DocumentSourceKeyConverter sources = new DocumentSourceKeyConverter();
            assertThat(sources.convertToDatabaseColumn(DocumentSourceKey.SEFAZ_DFE)).isEqualTo("SEFAZ_DFE");
            assertThat(sources.convertToEntityAttribute("NFSE_ADN")).isEqualTo(DocumentSourceKey.NFSE_ADN);
            assertThat(sources.convertToEntityAttribute(null)).isNull();
            assertThat(new ProviderKeyConverter().convertToEntityAttribute("SIEG")).isEqualTo(ProviderKey.SIEG);
            assertThat(new YearMonthConverter().convertToDatabaseColumn(YearMonth.of(2026, 1))).isEqualTo("2026-01");
            assertThat(new YearMonthConverter().convertToEntityAttribute("2026-10")).isEqualTo(YearMonth.of(2026, 10));
        }
    }

    @Nested
    class NfseWithholdings {
        @Test
        void pisAndCofinsAreWithheldOnlyWithTheFlag() {
            Nfse nfse = takenNfse();
            nfse.setPisAmount(new BigDecimal("7.80"));
            nfse.setCofinsAmount(new BigDecimal("36.00"));
            assertThat(nfse.withheldPis()).isNull();
            assertThat(nfse.withheldCofins()).isNull();
            nfse.setPisCofinsWithheld(true);
            assertThat(nfse.withheldPis()).isEqualByComparingTo("7.80");
            assertThat(nfse.withheldCofins()).isEqualByComparingTo("36.00");
            assertThat(nfse.getIss().isWithheld()).isTrue();
        }
    }

    @Nested
    class Review {
        @Test
        void sameRuleAsTheOldModel() {
            Nfe nfe = receivedNfe();
            assertThat(nfe.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
            nfe.review(ReviewStatus.DIVERGENT, "fiscal1", "  CFOP errado  ", AT);
            assertThat(nfe.getReview()).isEqualTo(new DocumentReview(ReviewStatus.DIVERGENT, AT, "fiscal1", "CFOP errado"));
            nfe.review(ReviewStatus.PENDING, "fiscal1", " ", AT);
            assertThat(nfe.getReview()).isEqualTo(new DocumentReview(ReviewStatus.PENDING, null, null, null));
            nfe.review(ReviewStatus.REVIEWED, "fiscal1", "y".repeat(600), AT);
            assertThat(nfe.getReview().note()).hasSize(500);
        }
    }

    @Nested
    class Fd32CapturePeriodCount {
        private CapturePeriodCount count() {
            return new CapturePeriodCount(e1(), ProviderKey.SIEG, FiscalDocumentType.NFE, CaptureSide.RECEIVED,
                    YearMonth.of(2026, 11));
        }

        /** CT-FD-29: 40 counted, 37 imported = 3 missing. CT-FD-36: a known gap of 2 is not missing. */
        @Test
        void missingIsCountMinusGapMinusImported() {
            CapturePeriodCount count = count();
            assertThat(count.missing()).isNull();
            count.recordProviderCount(40, AT);
            count.recordImported(37, AT);
            assertThat(count.missing()).isEqualTo(3);
            count.recordKnownGap(2);
            assertThat(count.missing()).isEqualTo(1);
            count.recordImported(45, AT);
            assertThat(count.missing()).isZero();
        }

        /** CT-FD-30: closing a month is idempotent. */
        @Test
        void closeOnce() {
            CapturePeriodCount count = count();
            assertThat(count.close(AT)).isTrue();
            assertThat(count.close(AT.plusDays(1))).isFalse();
            assertThat(count.getClosedAt()).isEqualTo(AT);
            assertThat(count.isClosed()).isTrue();
        }

        @Test
        void negativeCountsAndInvertedWindowsAreRefused() {
            CapturePeriodCount count = count();
            assertThatThrownBy(() -> count.recordImported(-1, AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> count.recordProviderCount(-1, AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> count.askedWindow(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            count.askedWindow(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
            assertThat(count.getWindowEnd()).isEqualTo(LocalDate.of(2026, 11, 30));
        }
    }
}
