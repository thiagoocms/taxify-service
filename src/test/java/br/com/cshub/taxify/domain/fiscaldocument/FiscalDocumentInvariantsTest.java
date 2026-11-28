package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.E1_CNPJ;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.ISSUED_AT;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.SUPPLIER_CNPJ;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.e1;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.issuedNfce;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.key44;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.party;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.receivedNfe;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.takenCte;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.takenNfse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 3.3: one test per invariant, in the scope where it applies and in the one where it does not. */
class FiscalDocumentInvariantsTest {

    private static void assertViolates(FiscalDocument document, InvariantScope scope, FiscalDocumentInvariant invariant) {
        assertThatThrownBy(() -> document.verifyInvariants(scope))
                .isInstanceOfSatisfying(FiscalDocumentInvariantException.class, e -> {
                    assertThat(e.getInvariant()).isEqualTo(invariant);
                    assertThat(e.getDocumentType()).isEqualTo(document.getDocumentType());
                });
    }

    private static void assertValid(FiscalDocument document, InvariantScope scope) {
        assertThatCode(() -> document.verifyInvariants(scope)).doesNotThrowAnyException();
    }

    @Test
    void theFixturesAreValidNewDocumentsOfEachSubtype() {
        assertValid(receivedNfe(), InvariantScope.CREATE);
        assertValid(issuedNfce(), InvariantScope.CREATE);
        assertValid(takenNfse(), InvariantScope.CREATE);
        assertValid(takenCte(), InvariantScope.CREATE);
    }

    @Test
    void theMessageCarriesOnlyTheCodeAndTheType() {
        Nfe nfe = receivedNfe();
        nfe.setAccessKey(null);
        assertThatThrownBy(() -> nfe.verifyInvariants(InvariantScope.CREATE))
                .hasMessage("fiscal.document.invariant.violated invariant=I07_ACCESS_KEY type=NFE")
                .hasMessageNotContaining(E1_CNPJ);
    }

    @Test
    void persistHookChecksCreateAndUpdateHookChecksUpdate() {
        Nfe withoutDate = receivedNfe();
        withoutDate.setIssueDate(null);
        assertThatThrownBy(withoutDate::beforeInsert).isInstanceOf(FiscalDocumentInvariantException.class);
        assertThatCode(withoutDate::beforeUpdate).doesNotThrowAnyException();
    }

    @Nested
    class I01MandatoryFields {
        @ParameterizedTest
        @EnumSource(InvariantScope.class)
        void withoutRoleIsRefusedAlways(InvariantScope scope) {
            Nfe nfe = new Nfe(e1(), DocumentSourceKey.SIEG);
            assertViolates(nfe, scope, FiscalDocumentInvariant.I01_MANDATORY_FIELDS);
        }

        @Test
        void withoutCompanyOrSourceOrVersionIsRefused() {
            Nfe noCompany = new Nfe(null, DocumentSourceKey.SIEG);
            noCompany.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            assertViolates(noCompany, InvariantScope.UPDATE, FiscalDocumentInvariant.I01_MANDATORY_FIELDS);

            Nfe noSource = new Nfe(e1(), null);
            noSource.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            assertViolates(noSource, InvariantScope.UPDATE, FiscalDocumentInvariant.I01_MANDATORY_FIELDS);

            Nfe noVersion = receivedNfe();
            noVersion.setMetadataVersion(null);
            assertViolates(noVersion, InvariantScope.UPDATE, FiscalDocumentInvariant.I01_MANDATORY_FIELDS);
        }

        @Test
        void aNewDocumentStartsAuthorizedWithVersionZero() {
            Nfe nfe = new Nfe(e1(), DocumentSourceKey.SIEG);
            assertThat(nfe.getStatus()).hasToString("AUTHORIZED");
            assertThat(nfe.getMetadataVersion()).isZero();
        }
    }

    @Nested
    class I02AuthorizedWithoutCanceledAt {
        @Test
        void authorizedHasNoCancellationDateAndCancelSetsBoth() {
            Nfe nfe = receivedNfe();
            assertThat(nfe.getCanceledAt()).isNull();
            nfe.cancel(ISSUED_AT.plusDays(1));
            assertValid(nfe, InvariantScope.UPDATE);
            assertThat(nfe.isCanceled()).isTrue();
            assertThat(nfe.getCanceledAt()).isEqualTo(ISSUED_AT.plusDays(1));
        }
    }

    @Nested
    class I03CanceledWithCanceledAt {
        @Test
        void aNewGoodsInvoiceCanceledWithoutDateIsRefusedOnCreateOnly() {
            Nfe nfe = receivedNfe();
            nfe.cancel(null);
            assertViolates(nfe, InvariantScope.CREATE, FiscalDocumentInvariant.I03_CANCELED_WITH_CANCELED_AT);
            assertValid(nfe, InvariantScope.UPDATE);
        }

        @Test
        void theNfseCanceledByTheLayoutHasNoDateAndIsValid() {
            Nfse nfse = takenNfse();
            assertThat(nfse.cancelByLayout()).isTrue();
            assertThat(nfse.getCanceledAt()).isNull();
            assertValid(nfse, InvariantScope.CREATE);
        }
    }

    @Nested
    class I04DirectionMatchesRole {
        @ParameterizedTest
        @EnumSource(value = CompanyDocumentRole.class, names = {"ISSUER", "RECIPIENT", "TAKER"})
        void bookingRolesNeedADirection(CompanyDocumentRole role) {
            Nfe nfe = receivedNfe();
            nfe.assignRole(role, null);
            assertViolates(nfe, InvariantScope.UPDATE, FiscalDocumentInvariant.I04_DIRECTION_MATCHES_ROLE);
        }

        @ParameterizedTest
        @EnumSource(value = CompanyDocumentRole.class, names = {"THIRD_PARTY", "SENDER", "SHIPPER", "RECEIVER"})
        void rolesWithoutBookingHaveNoDirection(CompanyDocumentRole role) {
            Nfse nfse = takenNfse();
            nfse.assignRole(role, FiscalDirection.INBOUND);
            assertViolates(nfse, InvariantScope.UPDATE, FiscalDocumentInvariant.I04_DIRECTION_MATCHES_ROLE);
            nfse.assignRole(role, null);
            assertValid(nfse, InvariantScope.UPDATE);
        }

        /** I-04-T: the CT-e direction is derived from the role, in both scopes. */
        @ParameterizedTest
        @EnumSource(InvariantScope.class)
        void cteDirectionIsDerivedFromTheRole(InvariantScope scope) {
            Cte cte = takenCte();
            cte.assignRole(CompanyDocumentRole.ISSUER, FiscalDirection.OUTBOUND);
            cte.setIssuer(party(E1_CNPJ, "Empresa E1"));
            assertValid(cte, scope);
            cte.assignRole(CompanyDocumentRole.ISSUER, FiscalDirection.INBOUND);
            assertViolates(cte, scope, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
            cte.setIssuer(party(SUPPLIER_CNPJ, "Transportadora"));
            cte.assignRole(CompanyDocumentRole.TAKER, null);
            assertViolates(cte, scope, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
            cte.assignRole(CompanyDocumentRole.RECIPIENT, null);
            assertValid(cte, scope);
            cte.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            assertViolates(cte, scope, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
            cte.assignRole(CompanyDocumentRole.SENDER, FiscalDirection.OUTBOUND);
            assertViolates(cte, scope, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
        }

        /** I-04-T: the only tolerance - a copied CT-e (metadataVersion 0) RECIPIENT keeps INBOUND on UPDATE. */
        @Test
        void aCopiedCteRecipientMayKeepInboundOnUpdateOnly() {
            Cte cte = takenCte();
            cte.setMetadataVersion(Cte.NOT_PARSED_VERSION);
            cte.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            assertValid(cte, InvariantScope.UPDATE);
            assertViolates(cte, InvariantScope.CREATE, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
            cte.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.OUTBOUND);
            assertViolates(cte, InvariantScope.UPDATE, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
            Cte parsed = takenCte();
            parsed.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            assertViolates(parsed, InvariantScope.UPDATE, FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE);
        }
    }

    @Nested
    class I05IssuerRoleMatchesCompany {
        @Test
        void issuerDocumentOfTheCompanyMustBeIssuerOnCreate() {
            Nfe nfe = receivedNfe();
            nfe.setIssuer(party(E1_CNPJ, "Empresa E1"));
            assertViolates(nfe, InvariantScope.CREATE, FiscalDocumentInvariant.I05_ISSUER_ROLE_MATCHES_COMPANY);
            assertValid(nfe, InvariantScope.UPDATE);
        }

        @Test
        void issuerRoleWithAnotherIssuerDocumentIsRefused() {
            Nfce nfce = issuedNfce();
            nfce.setIssuer(party(SUPPLIER_CNPJ, "Outro"));
            assertViolates(nfce, InvariantScope.CREATE, FiscalDocumentInvariant.I05_ISSUER_ROLE_MATCHES_COMPANY);
        }

        @Test
        void withoutIssuerDocumentTheRuleDoesNotApply() {
            Nfe nfe = receivedNfe();
            nfe.setIssuer(null);
            assertValid(nfe, InvariantScope.CREATE);
        }
    }

    @Nested
    class I06PartyDocumentType {
        @ParameterizedTest
        @ValueSource(strings = {"1122233300018", "112223330001811", "1122233300018A"})
        void cnpjMustHave14Digits(String document) {
            Nfe nfe = receivedNfe();
            nfe.setRecipient(new Party(document, TaxpayerDocumentType.CNPJ, "X", null, null));
            assertViolates(nfe, InvariantScope.UPDATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
        }

        @Test
        void cpfMustHave11DigitsAndForeignUpTo20Characters() {
            Nfe nfe = receivedNfe();
            nfe.setRecipient(new Party("1234567890", TaxpayerDocumentType.CPF, "X", null, null));
            assertViolates(nfe, InvariantScope.UPDATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
            nfe.setRecipient(new Party("123456789012345678901", TaxpayerDocumentType.FOREIGN, "X", null, null));
            assertViolates(nfe, InvariantScope.UPDATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
            nfe.setRecipient(new Party("PASSPORT-AB123", TaxpayerDocumentType.FOREIGN, "X", null, null));
            assertValid(nfe, InvariantScope.UPDATE);
            nfe.setRecipient(new Party(FiscalDocumentFixtures.PERSON_CPF, TaxpayerDocumentType.CPF, "X", null, null));
            assertValid(nfe, InvariantScope.UPDATE);
        }

        @Test
        void aDocumentWithoutTypeIsIncoherent() {
            Nfe nfe = receivedNfe();
            nfe.setRecipient(new Party("123", null, "X", null, null));
            assertViolates(nfe, InvariantScope.UPDATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
        }

        /** Coordinator decision: a copied NFS-e with a document of no recognizable type is tolerated on UPDATE only. */
        @Test
        void aCopiedRowWithADocumentWithoutTypeIsToleratedOnUpdate() {
            Nfse nfse = takenNfse();
            nfse.setMetadataVersion(0);
            nfse.setRecipient(Party.brazilian("123456789", "Tomador", null, null));
            assertValid(nfse, InvariantScope.UPDATE);
            assertViolates(nfse, InvariantScope.CREATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
            nfse.setMetadataVersion(1);
            assertViolates(nfse, InvariantScope.UPDATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
        }

        @Test
        void cteParticipantsAreCheckedToo() {
            Cte cte = takenCte();
            cte.setShipper(new CteParty("999", TaxpayerDocumentType.CNPJ, "Expedidor"));
            assertViolates(cte, InvariantScope.UPDATE, FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE);
        }
    }

    @Nested
    class I07AccessKey {
        @Test
        void aNewDocumentNeedsAKey() {
            Nfse nfse = takenNfse();
            nfse.setAccessKey(" ");
            assertViolates(nfse, InvariantScope.CREATE, FiscalDocumentInvariant.I07_ACCESS_KEY);
        }

        @Test
        void anUploadedGoodsInvoiceMayComeWithoutKeyAndIsIdentifiedById() {
            Nfe nfe = new Nfe(e1(), DocumentSourceKey.UPLOAD);
            nfe.setIssueDate(ISSUED_AT);
            nfe.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            nfe.setXmlFilePath("xml/e1/odd.xml");
            assertValid(nfe, InvariantScope.CREATE);
            assertThat(nfe.accessKeyOrId()).isEqualTo("null");
        }

        @Test
        void anOldRowWithoutKeyCanStillBeUpdated() {
            Nfe nfe = receivedNfe();
            nfe.setAccessKey(null);
            assertValid(nfe, InvariantScope.UPDATE);
        }
    }

    @Nested
    class I08IssueDate {
        @Test
        void aNewDocumentNeedsTheIssueDateButAnOldOneCanBeReviewed() {
            Nfe nfe = receivedNfe();
            nfe.setIssueDate(null);
            assertViolates(nfe, InvariantScope.CREATE, FiscalDocumentInvariant.I08_ISSUE_DATE);
            nfe.review(ReviewStatus.REVIEWED, "fiscal1", null, ISSUED_AT);
            assertValid(nfe, InvariantScope.UPDATE);
        }
    }

    @Nested
    class I09XmlFilePath {
        @Test
        void aNewDocumentNeedsItsFileUnlessPurged() {
            Nfe nfe = receivedNfe();
            nfe.setXmlFilePath(null);
            assertViolates(nfe, InvariantScope.CREATE, FiscalDocumentInvariant.I09_XML_FILE_PATH);
            nfe.markXmlPurged(ISSUED_AT);
            assertValid(nfe, InvariantScope.CREATE);
        }

        @Test
        void aCteCopiedWithoutParserMayHaveNoFile() {
            Cte cte = takenCte();
            cte.setXmlFilePath(null);
            assertViolates(cte, InvariantScope.CREATE, FiscalDocumentInvariant.I09_XML_FILE_PATH);
            cte.setMetadataVersion(Cte.NOT_PARSED_VERSION);
            assertValid(cte, InvariantScope.CREATE);
        }
    }

    @Nested
    class I10ToI12ModelAndKey {
        @Test
        void nfeNeedsModel55AndA44DigitKey() {
            Nfe nfe = receivedNfe();
            nfe.setModel("65");
            assertViolates(nfe, InvariantScope.CREATE, FiscalDocumentInvariant.I10_NFE_MODEL_AND_KEY);
            Nfe shortKey = receivedNfe();
            shortKey.setAccessKey(shortKey.getAccessKey().substring(1));
            assertViolates(shortKey, InvariantScope.CREATE, FiscalDocumentInvariant.I10_NFE_MODEL_AND_KEY);
            assertValid(shortKey, InvariantScope.UPDATE);
        }

        /** CT-FD-21: an NFC-e with a 43-digit key is not stored. */
        @Test
        void nfceWithA43DigitKeyIsRefused() {
            Nfce nfce = issuedNfce();
            nfce.setAccessKey(key44("65", 6).substring(0, 43));
            assertViolates(nfce, InvariantScope.CREATE, FiscalDocumentInvariant.I11_NFCE_MODEL_AND_KEY);
        }

        @Test
        void cteNeedsModel57AndA44DigitKey() {
            Cte cte = takenCte();
            cte.setModel("55");
            assertViolates(cte, InvariantScope.CREATE, FiscalDocumentInvariant.I12_CTE_MODEL_AND_KEY);
        }

        @Test
        void anUploadWithoutKeyStillNeedsTheModelAndAPresentKeyNeeds44Digits() {
            Nfe upload = new Nfe(e1(), DocumentSourceKey.UPLOAD);
            upload.setIssueDate(ISSUED_AT);
            upload.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
            upload.setXmlFilePath("xml/e1/odd.xml");
            upload.setAccessKey("123");
            assertViolates(upload, InvariantScope.CREATE, FiscalDocumentInvariant.I10_NFE_MODEL_AND_KEY);
            upload.setAccessKey(null);
            upload.setModel(null);
            assertViolates(upload, InvariantScope.CREATE, FiscalDocumentInvariant.I10_NFE_MODEL_AND_KEY);
        }
    }

    @Nested
    class I13NfseLayoutAndKey {
        @Test
        void nationalNeedsA50DigitKeyAlways() {
            Nfse nfse = takenNfse();
            nfse.setAccessKey("ABRASF-2611606-1-1");
            assertViolates(nfse, InvariantScope.UPDATE, FiscalDocumentInvariant.I13_NFSE_LAYOUT_AND_KEY);
        }

        @Test
        void abrasfNeedsTheSyntheticKey() {
            Nfse nfse = new Nfse(e1(), DocumentSourceKey.SIEG, NfseLayout.ABRASF);
            nfse.assignRole(CompanyDocumentRole.TAKER, FiscalDirection.INBOUND);
            nfse.setMetadataVersion(1);
            nfse.setAccessKey(FiscalDocumentFixtures.nationalNfseKey(2));
            assertViolates(nfse, InvariantScope.UPDATE, FiscalDocumentInvariant.I13_NFSE_LAYOUT_AND_KEY);
            nfse.setAccessKey("ABRASF-2611606-" + SUPPLIER_CNPJ + "-15");
            assertValid(nfse, InvariantScope.UPDATE);
        }

        @Test
        void withoutLayoutIsRefused() {
            Nfse nfse = new Nfse(e1(), DocumentSourceKey.SIEG, null);
            nfse.assignRole(CompanyDocumentRole.TAKER, FiscalDirection.INBOUND);
            nfse.setAccessKey(FiscalDocumentFixtures.nationalNfseKey(3));
            assertViolates(nfse, InvariantScope.UPDATE, FiscalDocumentInvariant.I13_NFSE_LAYOUT_AND_KEY);
            nfse.setMetadataVersion(0);
            assertViolates(nfse, InvariantScope.UPDATE, FiscalDocumentInvariant.I13_NFSE_LAYOUT_AND_KEY);
        }

        /** Coordinator decision: a copied row (metadataVersion 0) keeps an off-size national key on UPDATE only. */
        @Test
        void aCopiedRowWithAnOffSizeNationalKeyIsToleratedOnUpdate() {
            Nfse nfse = takenNfse();
            nfse.setMetadataVersion(0);
            nfse.setAccessKey("123");
            assertValid(nfse, InvariantScope.UPDATE);
            assertViolates(nfse, InvariantScope.CREATE, FiscalDocumentInvariant.I13_NFSE_LAYOUT_AND_KEY);
        }
    }

    @Nested
    class I14NfseWithheldFlags {
        @Test
        void bothFlagsDefaultToFalseAndNullIsRefused() {
            Nfse nfse = new Nfse(e1(), DocumentSourceKey.SIEG, NfseLayout.NATIONAL);
            assertThat(nfse.getIss().withheld()).isFalse();
            assertThat(nfse.getPisCofinsWithheld()).isFalse();

            Nfse noIssFlag = takenNfse();
            noIssFlag.setIss(new IssTax(null, null, null, null, null));
            assertViolates(noIssFlag, InvariantScope.UPDATE, FiscalDocumentInvariant.I14_NFSE_WITHHELD_FLAGS);

            Nfse noPisFlag = takenNfse();
            noPisFlag.setPisCofinsWithheld(null);
            assertViolates(noPisFlag, InvariantScope.UPDATE, FiscalDocumentInvariant.I14_NFSE_WITHHELD_FLAGS);
        }
    }

    @Nested
    class I15CteParticipation {
        /** R-b: a CT-e in which the company is in no group is THIRD_PARTY and is accepted (upload). */
        @Test
        void aThirdPartyCteNeedsNoRole() {
            Cte cte = takenCte();
            cte.assignParticipation(new CteParticipation(false, false, false, false, false, false));
            assertThat(cte.getCompanyRole()).isEqualTo(CompanyDocumentRole.THIRD_PARTY);
            assertValid(cte, InvariantScope.CREATE);
            Cte withoutParticipation = takenCte();
            withoutParticipation.assignParticipation(null);
            assertValid(withoutParticipation, InvariantScope.CREATE);
        }

        @Test
        void aRoleOtherThanThirdPartyNeedsAParticipationFlag() {
            Cte cte = takenCte();
            cte.assignParticipation(new CteParticipation(false, false, false, false, false, false));
            cte.assignRole(CompanyDocumentRole.TAKER, FiscalDirection.INBOUND);
            assertViolates(cte, InvariantScope.CREATE, FiscalDocumentInvariant.I15_CTE_PARTICIPATION);
            assertValid(cte, InvariantScope.UPDATE);
        }
    }

    @Nested
    class I17DecimalScale {
        @Test
        void moneyThatFitsIsKeptWithScale2AndNothingIsRecorded() {
            Nfe nfe = receivedNfe();
            ImportAdjustments.Collected<BigDecimal> kept = ImportAdjustments.collect(() -> {
                nfe.setTotalAmount(new BigDecimal("4000.5"));
                nfe.setTotalAmount(new BigDecimal("4000.5000"));
                return nfe.getTotalAmount();
            });
            assertThat(kept.value()).hasToString("4000.50");
            assertThat(kept.adjustments()).as("trailing zeros are not extra places").isEmpty();
        }

        /** Decision of 2026-11-28: more places than the column = rounded HALF_UP (as MySQL did) and recorded, never refused. */
        @Test
        void moneyWithMorePlacesIsRoundedHalfUpAndRecorded() {
            Nfe nfe = receivedNfe();
            ImportAdjustments.Collected<BigDecimal> rounded = ImportAdjustments.collect(() -> {
                nfe.setTotalAmount(new BigDecimal("4000.005"));
                return nfe.getTotalAmount();
            });
            assertThat(rounded.value()).hasToString("4000.01");
            assertThat(rounded.adjustments()).containsExactly(new ImportAdjustments.Adjustment(ImportAdjustments.Kind.DECIMAL_ROUNDED, "FiscalDocument.totalAmount", 3));
            nfe.setTotalAmount(new BigDecimal("4000.004"));
            assertThat(nfe.getTotalAmount()).as("outside a collection: rounded, nothing recorded").hasToString("4000.00");
        }

        /** CT-FD-19: the maximum of decimal(15,2) and the rate 5.0000 fit unchanged. */
        @Test
        void legacyMaximumsFitExactly() {
            BigDecimal max = new BigDecimal("9999999999999.99");
            IssTax iss = new IssTax(max, new BigDecimal("5"), max, false, null);
            assertThat(iss.base()).isEqualTo(max);
            assertThat(iss.rate()).hasToString("5.0000");
        }

        /** D-07: quantity and unit price are not under I-17; they are rounded to 4 places HALF_UP. */
        @Test
        void quantitiesWith10PlacesAreRoundedTo4() {
            TradeUnit unit = new TradeUnit("UN", new BigDecimal("3.1234567890"), new BigDecimal("12.3456500001"));
            assertThat(unit.quantity()).hasToString("3.1235");
            assertThat(unit.unitPrice()).hasToString("12.3457");
            assertThat(new TradeUnit("UN", new BigDecimal("2.00005"), null).quantity()).hasToString("2.0001");
            assertThat(new TradeUnit("UN", new BigDecimal("2"), null).quantity()).hasToString("2.0000");
            assertThat(new TradeUnit("UN", null, null).quantity()).isNull();
        }

        @Test
        void ratesWithMoreThan4PlacesAreRoundedAndRecorded() {
            ImportAdjustments.Collected<ItemFederalTax> pis = ImportAdjustments.collect(
                    () -> new ItemFederalTax("01", new BigDecimal("100.125"), new BigDecimal("1.65005"), null));
            assertThat(pis.value().rate()).hasToString("1.6501");
            assertThat(pis.value().base()).hasToString("100.13");
            assertThat(pis.adjustments()).containsExactlyInAnyOrder(new ImportAdjustments.Adjustment(ImportAdjustments.Kind.DECIMAL_ROUNDED, "ItemFederalTax.rate", 5),
                    new ImportAdjustments.Adjustment(ImportAdjustments.Kind.DECIMAL_ROUNDED, "ItemFederalTax.base", 3));
            assertThat(new ItemFederalTax("01", null, new BigDecimal("1.65"), null).rate()).hasToString("1.6500");
        }
    }

    @Test
    void i16ItemsExistOnlyInGoodsInvoices() {
        assertThat(GoodsInvoice.class.getDeclaredFields()).anyMatch(f -> f.getName().equals("items"));
        assertThat(Nfse.class).isNotInstanceOf(GoodsInvoice.class);
        assertThat(FiscalDocument.class.isAssignableFrom(Nfse.class)).isTrue();
        assertThat(GoodsInvoice.class.isAssignableFrom(Nfse.class)).isFalse();
        assertThat(GoodsInvoice.class.isAssignableFrom(Cte.class)).isFalse();
        assertThat(takenNfse().getDocumentType()).isEqualTo(FiscalDocumentType.NFSE);
    }

    @Test
    void invariantScopesFollowTheSpecTable() {
        assertThat(FiscalDocumentInvariant.values())
                .filteredOn(i -> !i.appliesTo(InvariantScope.UPDATE))
                .containsExactlyInAnyOrder(
                        FiscalDocumentInvariant.I03_CANCELED_WITH_CANCELED_AT,
                        FiscalDocumentInvariant.I05_ISSUER_ROLE_MATCHES_COMPANY,
                        FiscalDocumentInvariant.I07_ACCESS_KEY,
                        FiscalDocumentInvariant.I08_ISSUE_DATE,
                        FiscalDocumentInvariant.I09_XML_FILE_PATH,
                        FiscalDocumentInvariant.I10_NFE_MODEL_AND_KEY,
                        FiscalDocumentInvariant.I11_NFCE_MODEL_AND_KEY,
                        FiscalDocumentInvariant.I12_CTE_MODEL_AND_KEY,
                        FiscalDocumentInvariant.I15_CTE_PARTICIPATION);
        assertThat(FiscalDocumentInvariant.values()).allMatch(i -> i.appliesTo(InvariantScope.CREATE));
    }

    @Test
    void canceledAtOfAnAuthorizedDocumentIsNeverSet() {
        Nfe nfe = receivedNfe();
        assertThat(nfe.cancel(LocalDateTime.of(2026, 10, 20, 9, 0))).isTrue();
        assertThat(nfe.cancel(LocalDateTime.of(2026, 10, 21, 9, 0))).isFalse();
        assertThat(nfe.getCanceledAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
    }
}
