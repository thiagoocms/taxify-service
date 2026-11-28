package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import legacy.xml.FiscalIbsCbsTotals;
import legacy.xml.FiscalItemIbsCbs;
import legacy.xml.FiscalItemTaxDetail;
import legacy.xml.FiscalXmlDocument;
import legacy.xml.FiscalXmlDocumentItem;
import legacy.xml.FiscalXmlEvent;
import legacy.xml.FiscalXmlHeaderExtras;
import legacy.xml.FiscalXmlSyncControl;
import legacy.xml.ServiceInvoice;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacyNfseDirection;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacySourceNames;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.E1_CNPJ;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.PERSON_CPF;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.SUPPLIER_CNPJ;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.e1;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.key44;
import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentFixtures.nationalNfseKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Table tests of every conversion of spec 3.4 to 3.11 - the reference of the copy of V87. */
class LegacyFiscalDocumentTranslatorTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 3, 14, 0);
    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 10, 31, 23, 59, 59);

    private static void audited(AbstractAuditingEntity entity) {
        ReflectionTestUtils.setField(entity, "createdBy", "system");
        ReflectionTestUtils.setField(entity, "createdDate", CREATED);
        ReflectionTestUtils.setField(entity, "lastModifiedBy", "fiscal1");
        ReflectionTestUtils.setField(entity, "lastModifiedDate", CREATED.plusDays(1));
    }

    private static FiscalXmlDocument goodsRow(String documentType, String model) {
        FiscalXmlDocument legacy = new FiscalXmlDocument();
        legacy.setId(UUID.randomUUID());
        legacy.setCompany(e1());
        legacy.setDocumentType(documentType);
        legacy.setModelo(model);
        legacy.setAccessKey(key44(model == null ? "57" : model, 1));
        legacy.setSerie("1");
        legacy.setNumero("1001");
        legacy.setIssueDate(ISSUED);
        legacy.setNaturezaOperacao("Venda");
        legacy.setSourceSystem("SIEG");
        legacy.setDirection(FiscalDirection.INBOUND);
        legacy.setOwnIssue(false);
        legacy.setIssuerCnpj(SUPPLIER_CNPJ);
        legacy.setEmitenteNome("Fornecedor Exemplo");
        legacy.setEmitenteUf("PE");
        legacy.setEmitenteCodigoMunicipio("2611606");
        legacy.setRecipientCnpj(E1_CNPJ);
        legacy.setDestinatarioNome("Empresa E1");
        legacy.setValorTotalNota(new BigDecimal("1000.00"));
        legacy.setXmlFilePath("xml/e1/k1.xml");
        legacy.setMetadataVersion(7);
        audited(legacy);
        return legacy;
    }

    private static ServiceInvoice serviceRow(String direction, String layout) {
        ServiceInvoice legacy = new ServiceInvoice();
        legacy.setId(UUID.randomUUID());
        legacy.setCompany(e1());
        legacy.setLayout(layout);
        legacy.setAccessKey("ABRASF".equals(layout) ? "ABRASF-2611606-" + SUPPLIER_CNPJ + "-15" : nationalNfseKey(1));
        legacy.setDirection(direction);
        legacy.setNumero("15");
        legacy.setIssueDate(ISSUED);
        legacy.setSourceSystem("ADN");
        legacy.setXmlFilePath("xml/e1/s1.xml");
        legacy.setPrestadorDocumento("PRESTADA".equals(direction) ? E1_CNPJ : SUPPLIER_CNPJ);
        legacy.setPrestadorNome("Prestador");
        legacy.setPrestadorMunicipio("2611606");
        legacy.setTomadorDocumento("TOMADA".equals(direction) ? E1_CNPJ : PERSON_CPF);
        legacy.setTomadorNome("Tomador");
        legacy.setValorServico(new BigDecimal("1200.00"));
        audited(legacy);
        return legacy;
    }

    @Nested
    class DocumentType {
        /** FD-RN-14/15, CT-FD-04: model 65 becomes Nfce; CTE becomes Cte; NFE becomes Nfe. */
        @ParameterizedTest
        @CsvSource({"NFE, 55, NFE", "NFE, 65, NFCE", "NFE, ' 65', NFCE", "NFE, , NFE", "CTE, 57, CTE", "CTE, , CTE"})
        void typeByLegacyTypeAndModel(String legacyType, String model, FiscalDocumentType expected) {
            assertThat(LegacyFiscalDocumentTranslator.typeOf(legacyType, model)).isEqualTo(expected);
            assertThat(LegacyFiscalDocumentTranslator.fromGoodsRow(goodsRow(legacyType, model)).getDocumentType())
                    .isEqualTo(expected);
        }

        @Test
        void anUnknownTypeStopsTheCopy() {
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.typeOf("XYZ", "55"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theNfceKeepsTheLegacyKeyLabelNfe() {
            FiscalDocument nfce = LegacyFiscalDocumentTranslator.fromGoodsRow(goodsRow("NFE", "65"));
            assertThat(nfce).isInstanceOf(Nfce.class).isInstanceOf(GoodsInvoice.class);
            assertThat(nfce.legacyKeyLabel()).isEqualTo("NFE");
        }
    }

    @Nested
    class DirectionAndRole {
        /** FD-RN-08: INBOUND/OUTBOUND + own_issue; own_issue null (CT-FD-11) becomes RECIPIENT. */
        @ParameterizedTest
        @CsvSource({"INBOUND, false, RECIPIENT", "OUTBOUND, true, ISSUER", "INBOUND, true, ISSUER", "INBOUND, , RECIPIENT"})
        void goodsRowRole(FiscalDirection direction, Boolean ownIssue, CompanyDocumentRole role) {
            FiscalXmlDocument legacy = LegacyFiscalDocumentTranslatorTest.goodsRow("NFE", "55");
            legacy.setDirection(direction);
            legacy.setOwnIssue(ownIssue);
            FiscalDocument document = LegacyFiscalDocumentTranslator.fromGoodsRow(legacy);
            assertThat(document.getDirection()).isEqualTo(direction);
            assertThat(document.getCompanyRole()).isEqualTo(role);
            assertThat(document.isOwnIssue()).isEqualTo(Boolean.TRUE.equals(ownIssue));
        }

        /** FD-RN-56: a null direction is derived - received = INBOUND; own issue by ide_tipo_operacao (only ' ' trimmed). */
        @ParameterizedTest
        @CsvSource({"false, , INBOUND", ", 1, INBOUND", "true, 0, INBOUND", "true, 1, OUTBOUND", "true, ' 1 ', OUTBOUND"})
        void nullDirectionIsDerived(Boolean ownIssue, String operationType, FiscalDirection expected) {
            FiscalXmlDocument legacy = LegacyFiscalDocumentTranslatorTest.goodsRow("NFE", "55");
            legacy.setDirection(null);
            legacy.setOwnIssue(ownIssue);
            legacy.setTipoOperacao(operationType);
            assertThat(LegacyFiscalDocumentTranslator.fromGoodsRow(legacy).getDirection()).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"''", "2", "'\t1'"})
        void anOwnIssueWithoutUsableOperationTypeStopsTheCopy(String operationType) {
            FiscalXmlDocument legacy = LegacyFiscalDocumentTranslatorTest.goodsRow("NFE", "55");
            legacy.setDirection(null);
            legacy.setOwnIssue(true);
            legacy.setTipoOperacao(operationType);
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.fromGoodsRow(legacy)).isInstanceOf(IllegalStateException.class);
            legacy.setTipoOperacao(null);
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.fromGoodsRow(legacy)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aStoredDirectionIsKeptAsIs() {
            FiscalXmlDocument legacy = LegacyFiscalDocumentTranslatorTest.goodsRow("NFE", "55");
            legacy.setDirection(FiscalDirection.OUTBOUND);
            legacy.setOwnIssue(false);
            legacy.setTipoOperacao("0");
            assertThat(LegacyFiscalDocumentTranslator.directionOfGoodsRow(legacy)).isEqualTo(FiscalDirection.OUTBOUND);
        }

        /** FD-RN-08: PRESTADA, TOMADA and TERCEIRO. */
        @ParameterizedTest
        @CsvSource({"PRESTADA, OUTBOUND, ISSUER", "TOMADA, INBOUND, TAKER", "TERCEIRO, , THIRD_PARTY"})
        void serviceRowRole(String legacyDirection, FiscalDirection direction, CompanyDocumentRole role) {
            Nfse nfse = LegacyFiscalDocumentTranslator.fromServiceInvoice(
                    LegacyFiscalDocumentTranslatorTest.serviceRow(legacyDirection, "NACIONAL"));
            assertThat(nfse.getDirection()).isEqualTo(direction);
            assertThat(nfse.getCompanyRole()).isEqualTo(role);
            assertThat(LegacyNfseDirection.ofRole(role).name()).isEqualTo(legacyDirection);
            assertThatCode(() -> nfse.verifyInvariants(InvariantScope.CREATE)).doesNotThrowAnyException();
        }

        @Test
        void anUnknownNfseDirectionOrLayoutStopsTheCopy() {
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.fromServiceInvoice(serviceRow("OUTRO", "NACIONAL")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.layoutOf("X")).isInstanceOf(IllegalArgumentException.class);
            assertThat(LegacyFiscalDocumentTranslator.layoutOf("NACIONAL")).isEqualTo(NfseLayout.NATIONAL);
            assertThat(LegacyFiscalDocumentTranslator.layoutOf("ABRASF")).isEqualTo(NfseLayout.ABRASF);
        }
    }

    @Nested
    class Parties {
        /** FD-RN-09: CNPJ before CPF before foreign id; blank counts as absent. */
        @ParameterizedTest
        @CsvSource({
                // recipient cnpj, dest cpf, foreign id, expected document, expected type
                "12345678000195, 12345678909, X1, 12345678000195, CNPJ",
                ",               12345678909, X1, 12345678909,    CPF",
                "' ',            ,            AB-123, AB-123,     FOREIGN",
                ",               ,            ,     ,             "
        })
        void recipientDocument(String cnpj, String cpf, String foreign, String expectedDocument, TaxpayerDocumentType type) {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            legacy.setRecipientCnpj(cnpj);
            legacy.setDestinatarioCpf(cpf);
            legacy.setDestinatarioIdEstrangeiro(foreign);
            Party recipient = LegacyFiscalDocumentTranslator.fromGoodsRow(legacy).getRecipient();
            assertThat(recipient.document()).isEqualTo(expectedDocument);
            assertThat(recipient.documentType()).isEqualTo(type);
            assertThat(recipient.name()).isEqualTo("Empresa E1");
        }

        @Test
        void issuerCnpjBeforeCpf() {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            legacy.setIssuerCnpj(null);
            legacy.setEmitenteCpf(PERSON_CPF);
            Party issuer = LegacyFiscalDocumentTranslator.fromGoodsRow(legacy).getIssuer();
            assertThat(issuer).isEqualTo(new Party(PERSON_CPF, TaxpayerDocumentType.CPF, "Fornecedor Exemplo", "PE", "2611606"));
        }

        @Test
        void nfseDocumentTypeComesFromTheSize() {
            Nfse nfse = LegacyFiscalDocumentTranslator.fromServiceInvoice(serviceRow("TERCEIRO", "NACIONAL"));
            assertThat(nfse.getIssuer()).isEqualTo(new Party(SUPPLIER_CNPJ, TaxpayerDocumentType.CNPJ, "Prestador", null, "2611606"));
            assertThat(nfse.getRecipient()).isEqualTo(new Party(PERSON_CPF, TaxpayerDocumentType.CPF, "Tomador", null, null));
        }
    }

    @Nested
    class Cancellation {
        @Test
        void statusNullIsAuthorizedAndCanceledKeepsTheDate() {
            FiscalXmlDocument authorized = goodsRow("NFE", "55");
            assertThat(LegacyFiscalDocumentTranslator.fromGoodsRow(authorized).getStatus()).isEqualTo(FiscalDocumentStatus.AUTHORIZED);

            FiscalXmlDocument canceled = goodsRow("NFE", "55");
            canceled.setStatus(FiscalDocumentStatus.CANCELED);
            canceled.setCanceledAt(ISSUED.plusDays(1));
            FiscalDocument copy = LegacyFiscalDocumentTranslator.fromGoodsRow(canceled);
            assertThat(copy.isCanceled()).isTrue();
            assertThat(copy.getCanceledAt()).isEqualTo(ISSUED.plusDays(1));
        }

        @Test
        void anAuthorizedRowKeepsNoCancellationDate() {
            FiscalXmlDocument inconsistent = goodsRow("NFE", "55");
            inconsistent.setCanceledAt(ISSUED);
            FiscalDocument copy = LegacyFiscalDocumentTranslator.fromGoodsRow(inconsistent);
            assertThat(copy.getCanceledAt()).isNull();
            assertThatCode(() -> copy.verifyInvariants(InvariantScope.UPDATE)).doesNotThrowAnyException();
        }

        /** CT-FD-02: S3 provided and canceled by the bit = CANCELED with canceled_at null. */
        @Test
        void nfseCanceledBit() {
            ServiceInvoice legacy = serviceRow("PRESTADA", "NACIONAL");
            legacy.setCanceled(true);
            Nfse nfse = LegacyFiscalDocumentTranslator.fromServiceInvoice(legacy);
            assertThat(nfse.getStatus()).isEqualTo(FiscalDocumentStatus.CANCELED);
            assertThat(nfse.getCanceledAt()).isNull();
            assertThat(nfse.getCompanyRole()).isEqualTo(CompanyDocumentRole.ISSUER);
            assertThatCode(() -> nfse.verifyInvariants(InvariantScope.CREATE)).doesNotThrowAnyException();
        }
    }

    @Nested
    class Sources {
        /** CT-FD-27 and 3.10: found_in_sieg_at, found_in_sefaz_at and found_in_adn_at become origins. */
        @Test
        void goodsRowOrigins() {
            FiscalXmlDocument k1 = goodsRow("NFE", "55");
            k1.setFoundInSiegAt(LocalDateTime.of(2026, 11, 5, 8, 0));
            k1.setFoundInSefazAt(LocalDateTime.of(2026, 11, 5, 9, 15));
            FiscalDocument copy = LegacyFiscalDocumentTranslator.fromGoodsRow(k1);
            assertThat(copy.getSources()).extracting(FiscalDocumentSource::getSource)
                    .containsExactly(DocumentSourceKey.SIEG, DocumentSourceKey.SEFAZ_DFE);
            assertThat(copy.getSources()).extracting(FiscalDocumentSource::getRetrievalMode)
                    .containsExactly(RetrievalMode.PERIOD, RetrievalMode.CURSOR);
            FiscalDocumentSource sefaz = copy.sourceOf(DocumentSourceKey.SEFAZ_DFE, RetrievalMode.CURSOR).orElseThrow();
            assertThat(sefaz.getFirstSeenAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 9, 15));
            assertThat(sefaz.getLastSeenAt()).isEqualTo(sefaz.getFirstSeenAt());
        }

        @Test
        void uploadWithoutDatesGetsTheUploadOriginAtCreatedDate() {
            FiscalXmlDocument k3 = goodsRow("NFE", "55");
            k3.setSourceSystem("UPLOAD");
            FiscalDocument copy = LegacyFiscalDocumentTranslator.fromGoodsRow(k3);
            assertThat(copy.getSources()).singleElement().satisfies(source -> {
                assertThat(source.getSource()).isEqualTo(DocumentSourceKey.UPLOAD);
                assertThat(source.getRetrievalMode()).isEqualTo(RetrievalMode.NONE);
                assertThat(source.getFirstSeenAt()).isEqualTo(CREATED);
            });
        }

        @Test
        void aRowWithoutAnyDateAndNotUploadedHasNoOrigin() {
            assertThat(LegacyFiscalDocumentTranslator.fromGoodsRow(goodsRow("NFE", "55")).getSources()).isEmpty();
        }

        @Test
        void nfseOrigins() {
            ServiceInvoice s1 = serviceRow("TOMADA", "NACIONAL");
            s1.setFoundInAdnAt(LocalDateTime.of(2026, 11, 6, 7, 0));
            Nfse copy = LegacyFiscalDocumentTranslator.fromServiceInvoice(s1);
            assertThat(copy.getSources()).extracting(FiscalDocumentSource::getSource, FiscalDocumentSource::getRetrievalMode)
                    .containsExactly(tuple(DocumentSourceKey.NFSE_ADN, RetrievalMode.CURSOR));
        }

        /** CT-FD-31: SEFAZ_DIST and ADN are renamed in the database and shown with the old names by the API. */
        @ParameterizedTest
        @CsvSource({"SEFAZ_DIST, SEFAZ_DFE", "ADN, NFSE_ADN", "SIEG, SIEG", "UPLOAD, UPLOAD"})
        void sourceSystemTranslation(String legacy, String current) {
            assertThat(LegacyFiscalDocumentTranslator.sourceFromLegacy(legacy)).isEqualTo(DocumentSourceKey.of(current));
            assertThat(LegacySourceNames.toLegacyName(DocumentSourceKey.of(current))).isEqualTo(legacy);
        }

        @Test
        void retrievalModeOfEachLegacySource() {
            assertThat(LegacyFiscalDocumentTranslator.retrievalModeOf(DocumentSourceKey.SIEG)).isEqualTo(RetrievalMode.PERIOD);
            assertThat(LegacyFiscalDocumentTranslator.retrievalModeOf(DocumentSourceKey.SEFAZ_DFE)).isEqualTo(RetrievalMode.CURSOR);
            assertThat(LegacyFiscalDocumentTranslator.retrievalModeOf(DocumentSourceKey.NFSE_ADN)).isEqualTo(RetrievalMode.CURSOR);
            assertThat(LegacyFiscalDocumentTranslator.retrievalModeOf(DocumentSourceKey.UPLOAD)).isEqualTo(RetrievalMode.NONE);
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.retrievalModeOf(DocumentSourceKey.of("FAKE")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void sourceSystemOfTheRowIsTranslated() {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            legacy.setSourceSystem("SEFAZ_DIST");
            assertThat(LegacyFiscalDocumentTranslator.fromGoodsRow(legacy).getSourceSystem()).isEqualTo(DocumentSourceKey.SEFAZ_DFE);
            assertThat(LegacyFiscalDocumentTranslator.fromServiceInvoice(serviceRow("TOMADA", "NACIONAL")).getSourceSystem())
                    .isEqualTo(DocumentSourceKey.NFSE_ADN);
        }
    }

    @Nested
    class GoodsColumns {
        @Test
        void commonAndBlockGColumnsAreCopiedExactly() {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            legacy.setRetIrrf(new BigDecimal("15.00"));
            legacy.setValorIcms(new BigDecimal("180.00"));
            legacy.setValorPis(new BigDecimal("16.50"));
            legacy.setIbsCbsTotals(new FiscalIbsCbsTotals(new BigDecimal("1000.00"), new BigDecimal("1.00"),
                    BigDecimal.ZERO, new BigDecimal("1.00"), new BigDecimal("9.00")));
            legacy.setHeaderExtras(new FiscalXmlHeaderExtras("1", "1", "5", "0", "SIEG 1.0", "1058", "Brasil", null, null));
            legacy.setCodigoUf("26");
            legacy.setEmitenteLogradouro("Rua Exemplo");
            legacy.setDestinatarioEmail("e1@exemplo.test");
            legacy.setRectifiedFilePath("xml/e1/k1-ret.xml");
            legacy.review(ReviewStatus.REVIEWED, "fiscal1", "ok", CREATED);
            ReflectionTestUtils.setField(legacy, "deleted", true);

            Nfe copy = (Nfe) LegacyFiscalDocumentTranslator.fromGoodsRow(legacy);

            assertThat(copy.getId()).isEqualTo(legacy.getId());
            assertThat(copy.getCompany()).isSameAs(legacy.getCompany());
            assertThat(copy.getAccessKey()).isEqualTo(legacy.getAccessKey());
            assertThat(copy.getModel()).isEqualTo("55");
            assertThat(copy.getSeries()).isEqualTo("1");
            assertThat(copy.getNumber()).isEqualTo("1001");
            assertThat(copy.getIssueDate()).isEqualTo(ISSUED);
            assertThat(copy.getOperationNature()).isEqualTo("Venda");
            assertThat(copy.getTotalAmount()).isEqualByComparingTo("1000.00");
            assertThat(copy.getWithholdings()).isEqualTo(new Withholdings(null, null, null, new BigDecimal("15.00"), null));
            assertThat(copy.getIbsCbsTotals().cbsAmount()).isEqualByComparingTo("9.00");
            assertThat(copy.getGoodsTotals().icmsAmount()).isEqualByComparingTo("180.00");
            assertThat(copy.getGoodsTotals().pisAmount()).isEqualByComparingTo("16.50");
            assertThat(copy.getHeader().ufCode()).isEqualTo("26");
            assertThat(copy.getHeader().emissionProcessVersion()).isEqualTo("SIEG 1.0");
            assertThat(copy.getIssuerDetails().address().street()).isEqualTo("Rua Exemplo");
            assertThat(copy.getIssuerDetails().address().countryName()).isEqualTo("Brasil");
            assertThat(copy.getRecipientDetails().email()).isEqualTo("e1@exemplo.test");
            assertThat(copy.getRectifiedFilePath()).isEqualTo("xml/e1/k1-ret.xml");
            assertThat(copy.getXmlFilePath()).isEqualTo("xml/e1/k1.xml");
            assertThat(copy.getXmlPurgedAt()).isNull();
            assertThat(copy.getMetadataVersion()).isEqualTo(7);
            assertThat(copy.getReview()).isEqualTo(new DocumentReview(ReviewStatus.REVIEWED, CREATED, "fiscal1", "ok"));
            assertThat(copy.getCreatedBy()).isEqualTo("system");
            assertThat(copy.getCreatedDate()).isEqualTo(CREATED);
            assertThat(copy.getLastModifiedBy()).isEqualTo("fiscal1");
            assertThat(copy.getLastModifiedDate()).isEqualTo(CREATED.plusDays(1));
            assertThat(copy.isDeleted()).isTrue();
        }

        @Test
        void metadataVersionNullBecomesZeroAndEmptyGroupsStayNull() {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            legacy.setMetadataVersion(null);
            Nfe copy = (Nfe) LegacyFiscalDocumentTranslator.fromGoodsRow(legacy);
            assertThat(copy.getMetadataVersion()).isZero();
            // an empty group is stored as NULL columns and read as the group's EMPTY value (never null to the callers)
            assertThat(copy.getHeader()).isEqualTo(GoodsInvoiceHeader.EMPTY);
            assertThat(copy.getGoodsTotals()).isEqualTo(GoodsTotals.EMPTY);
            assertThat(copy.getWithholdings()).isEqualTo(Withholdings.EMPTY);
            assertThat(copy.getIbsCbsTotals()).isNull();
            assertThat(copy.getReview()).isNull();
            assertThat(copy.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        }

        /** CT-FD-11 style: an old row without issue date or key is still a valid stored document. */
        @Test
        void anOldRowWithNullsPassesTheUpdateInvariants() {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            legacy.setIssueDate(null);
            legacy.setAccessKey(null);
            FiscalDocument copy = LegacyFiscalDocumentTranslator.fromGoodsRow(legacy);
            assertThatCode(() -> copy.verifyInvariants(InvariantScope.UPDATE)).doesNotThrowAnyException();
            assertThat(copy.accessKeyOrId()).isEqualTo(legacy.getId().toString());
        }
    }

    @Nested
    class CteRow {
        /** A Cte has no items: an old CT-e row with items stops the copy (V87 pre-checks it). */
        @Test
        void aCteRowWithItemsStopsTheCopy() {
            FiscalXmlDocument legacy = goodsRow("CTE", "57");
            legacy.setItens(List.of(new FiscalXmlDocumentItem()));
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.fromGoodsRow(legacy))
                    .isInstanceOf(IllegalStateException.class);
        }

        /** FD-RN-15: CTE without model gets 57, metadata_version 0, the shared ICMS and no participation. */
        @Test
        void cteKeepsCommonColumnsAndSharedIcms() {
            FiscalXmlDocument legacy = goodsRow("CTE", null);
            legacy.setXmlFilePath(null);
            legacy.setBaseCalculoIcms(new BigDecimal("350.00"));
            legacy.setValorIcms(new BigDecimal("42.00"));
            Cte cte = (Cte) LegacyFiscalDocumentTranslator.fromGoodsRow(legacy);
            assertThat(cte.getModel()).isEqualTo("57");
            assertThat(cte.getMetadataVersion()).isZero();
            assertThat(cte.getIcms()).isEqualTo(new CteIcms(null, null, new BigDecimal("350.00"), null, new BigDecimal("42.00")));
            assertThat(cte.getParticipation()).isNull();
            assertThat(cte.getCompanyRole()).isEqualTo(CompanyDocumentRole.RECIPIENT);
            assertThat(cte.getDirection()).isEqualTo(FiscalDirection.INBOUND);
            assertThat(cte.legacyKeyLabel()).isEqualTo("CTE");
            assertThatCode(() -> cte.verifyInvariants(InvariantScope.UPDATE)).doesNotThrowAnyException();
        }
    }

    @Nested
    class NfseColumns {
        /** CT-FD-02 (S1) and CT-FD-19: values copied exactly, without rounding. */
        @Test
        void serviceColumnsAreCopiedExactly() {
            ServiceInvoice legacy = serviceRow("TOMADA", "NACIONAL");
            legacy.setValorServico(new BigDecimal("9999999999999.99"));
            legacy.setAliquotaIss(new BigDecimal("5.0000"));
            legacy.setIssRetido(true);
            legacy.setValorIssRetido(new BigDecimal("24.00"));
            legacy.setValorIrrf(new BigDecimal("18.00"));
            legacy.setValorPis(new BigDecimal("7.80"));
            legacy.setValorCofins(new BigDecimal("36.00"));
            legacy.setPisCofinsRetidos(true);
            legacy.setValorCsll(new BigDecimal("12.00"));
            legacy.setValorInss(new BigDecimal("132.00"));
            legacy.setValorLiquido(new BigDecimal("900.00"));
            legacy.setValorDeducoes(new BigDecimal("10.00"));
            legacy.setCodigoVerificacao("ABC123");
            legacy.setCompetence(LocalDate.of(2026, 10, 1));
            legacy.setMunicipioIncidencia("2611606");
            legacy.setCodigoServico("010101");
            legacy.setCodigoTributacaoMunicipal("620910001");
            legacy.setDescricaoServico("Serviço de exemplo");

            Nfse copy = LegacyFiscalDocumentTranslator.fromServiceInvoice(legacy);

            assertThat(copy.getId()).isEqualTo(legacy.getId());
            assertThat(copy.getLayout()).isEqualTo(NfseLayout.NATIONAL);
            assertThat(copy.getModel()).isNull();
            assertThat(copy.getNumber()).isEqualTo("15");
            assertThat(copy.getTotalAmount()).isEqualTo(new BigDecimal("9999999999999.99"));
            assertThat(copy.getIss()).isEqualTo(new IssTax(null, new BigDecimal("5.0000"), null, true, new BigDecimal("24.00")));
            assertThat(copy.getWithholdings()).isEqualTo(new Withholdings(null, null, new BigDecimal("12.00"),
                    new BigDecimal("18.00"), new BigDecimal("132.00")));
            assertThat(copy.withheldPis()).isEqualByComparingTo("7.80");
            assertThat(copy.withheldCofins()).isEqualByComparingTo("36.00");
            assertThat(copy.getNetAmount()).isEqualByComparingTo("900.00");
            assertThat(copy.getDeductionsAmount()).isEqualByComparingTo("10.00");
            assertThat(copy.getVerificationCode()).isEqualTo("ABC123");
            assertThat(copy.getCompetence()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(copy.getIncidenceCityCode()).isEqualTo("2611606");
            assertThat(copy.getServiceCode()).isEqualTo("010101");
            assertThat(copy.getMunicipalTaxCode()).isEqualTo("620910001");
            assertThat(copy.getServiceDescription()).isEqualTo("Serviço de exemplo");
            assertThat(copy.getMetadataVersion()).isZero();
            assertThat(copy.getCreatedDate()).isEqualTo(CREATED);
        }

        @Test
        void abrasfRowIsValid() {
            Nfse copy = LegacyFiscalDocumentTranslator.fromServiceInvoice(serviceRow("TOMADA", "ABRASF"));
            assertThat(copy.getLayout()).isEqualTo(NfseLayout.ABRASF);
            assertThatCode(() -> copy.verifyInvariants(InvariantScope.CREATE)).doesNotThrowAnyException();
        }
    }

    @Nested
    class Items {
        private FiscalXmlDocumentItem legacyItem(String cfop) {
            FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
            item.setId(UUID.randomUUID());
            item.setNumeroItem(1);
            item.setCodigo("P1");
            item.setDescricao("Tecido");
            item.setNcm("52081100");
            item.setCfop(cfop);
            item.setUnidadeComercial("M");
            item.setQuantidadeComercial(new BigDecimal("10.0000"));
            item.setValorUnitarioComercial(new BigDecimal("60.0000"));
            item.setValorTotal(new BigDecimal("600.00"));
            item.setSituacaoTributariaIcms("00");
            item.setBaseCalculoIcms(new BigDecimal("600.00"));
            item.setAliquotaIcms(new BigDecimal("18.0000"));
            item.setValorIcms(new BigDecimal("108.00"));
            item.setSituacaoTributariaPis("01");
            item.setValorPis(new BigDecimal("9.90"));
            return item;
        }

        /** CT-FD-03: CFOP "1102 " and "1102" are both "1102"; same id; rates widened to scale 4. */
        @Test
        void itemsAreCopiedWithTrimmedCfop() {
            FiscalXmlDocument legacy = goodsRow("NFE", "55");
            FiscalXmlDocumentItem withSpace = legacyItem("1102 ");
            FiscalXmlDocumentItem without = legacyItem("1102");
            without.setValorTotal(new BigDecimal("400.00"));
            withSpace.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", null, "3", new BigDecimal("10.0000"), null,
                    null, null, null, null, null, null, null, new BigDecimal("600.00"), new BigDecimal("1.6500"), null,
                    null, new BigDecimal("1.2500"), new BigDecimal("7.50")));
            withSpace.setIbsCbs(new FiscalItemIbsCbs("000", "000001", new BigDecimal("600.00"), new BigDecimal("0.1000"),
                    null, new BigDecimal("0.60"), BigDecimal.ZERO, null, BigDecimal.ZERO, new BigDecimal("0.60"),
                    new BigDecimal("0.9000"), null, new BigDecimal("5.40"), false));
            legacy.setItens(List.of(withSpace, without));

            Nfe copy = (Nfe) LegacyFiscalDocumentTranslator.fromGoodsRow(legacy);

            assertThat(copy.getItems()).extracting(FiscalDocumentItem::cfop).containsExactly("1102", "1102");
            assertThat(copy.getItems()).extracting(FiscalDocumentItem::getId).containsExactly(withSpace.getId(), without.getId());
            assertThat(copy.getItems().stream().map(FiscalDocumentItem::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("1000.00");
            FiscalDocumentItem first = copy.getItems().getFirst();
            assertThat(first.getDocument()).isSameAs(copy);
            assertThat(first.getProduct().code()).isEqualTo("P1");
            assertThat(first.getCommercial()).isEqualTo(new TradeUnit("M", new BigDecimal("10.0000"), new BigDecimal("60.0000")));
            assertThat(first.getTaxable()).isEqualTo(TradeUnit.EMPTY);
            assertThat(first.getIcms()).isEqualTo(new ItemIcms("0", "00", null, "3", new BigDecimal("600.00"),
                    new BigDecimal("18.0000"), new BigDecimal("10.0000"), new BigDecimal("108.00"), null));
            assertThat(first.getPis()).isEqualTo(new ItemFederalTax("01", new BigDecimal("600.00"), new BigDecimal("1.6500"),
                    new BigDecimal("9.90")));
            assertThat(first.getSimplesCredit()).isEqualTo(new SimplesCredit(new BigDecimal("1.2500"), new BigDecimal("7.50")));
            assertThat(first.getIbsCbs().cbsAmount()).isEqualByComparingTo("5.40");
            assertThat(first.getIbsCbs().stateRate()).hasToString("0.1000");
            FiscalDocumentItem second = copy.getItems().get(1);
            assertThat(second.getIcmsSt()).isEqualTo(ItemIcmsSt.EMPTY);
            assertThat(second.getSimplesCredit()).isEqualTo(SimplesCredit.EMPTY);
            assertThat(second.getIbsCbs()).isNull();
        }
    }

    @Nested
    class Events {
        private FiscalXmlEvent legacyEvent(FiscalXmlDocument document) {
            FiscalXmlEvent event = new FiscalXmlEvent();
            event.setId(UUID.randomUUID());
            event.setCompany(e1());
            event.setDocument(document);
            event.setAccessKey(key44("55", 1));
            event.setEventType("110111");
            event.setSequenceNumber(1);
            event.setDescription("Cancelamento");
            event.setEventAt(ISSUED.plusDays(1));
            event.setProtocol("126260000000001");
            event.setText("Erro");
            event.setRegistered(true);
            audited(event);
            return event;
        }

        @Test
        void eventIsCopiedWithItsLinkAndWithoutApplyingItAgain() {
            FiscalXmlDocument legacyDocument = goodsRow("NFE", "55");
            FiscalDocument document = LegacyFiscalDocumentTranslator.fromGoodsRow(legacyDocument);
            FiscalXmlEvent legacy = legacyEvent(legacyDocument);

            FiscalDocumentEvent copy = LegacyFiscalDocumentTranslator.fromEvent(legacy, document);

            assertThat(copy.getId()).isEqualTo(legacy.getId());
            assertThat(copy.getDocument()).isSameAs(document);
            assertThat(copy.getEventType()).isEqualTo("110111");
            assertThat(copy.getSequenceNumber()).isEqualTo(1);
            assertThat(copy.getEventAt()).isEqualTo(ISSUED.plusDays(1));
            assertThat(copy.getProtocol()).isEqualTo("126260000000001");
            assertThat(copy.getText()).isEqualTo("Erro");
            assertThat(copy.isRegistered()).isTrue();
            assertThat(copy.getCreatedDate()).isEqualTo(CREATED);
            assertThat(document.isCanceled()).isFalse();
        }

        @Test
        void anEventWithoutDocumentStaysUnlinkedAndAWrongLinkIsRefused() {
            assertThat(LegacyFiscalDocumentTranslator.fromEvent(legacyEvent(null), null).isLinked()).isFalse();
            FiscalDocument other = LegacyFiscalDocumentTranslator.fromGoodsRow(goodsRow("NFE", "55"));
            assertThatThrownBy(() -> LegacyFiscalDocumentTranslator.fromEvent(legacyEvent(goodsRow("NFE", "55")), other))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class SyncControl {
        private static final LocalDateTime COPIED_AT = LocalDateTime.of(2026, 11, 27, 21, 0);

        /** CT-FD-29: three rows, the previous month closed at the copy. */
        @Test
        void currentAndPreviousMonths() {
            FiscalXmlSyncControl control = new FiscalXmlSyncControl();
            audited(control);
            control.setCompany(e1());
            control.setIssueDateFrom(LocalDate.of(2026, 11, 1));
            control.setIssueDateTo(LocalDate.of(2026, 11, 27));
            control.setTotalSieg(40);
            control.setTotalImported(37);
            control.setLastCheckedAt(LocalDateTime.of(2026, 11, 27, 10, 0));
            control.setIssuedTotalSieg(12);
            control.setIssuedTotalImported(12);
            control.setIssuedCheckedAt(LocalDateTime.of(2026, 11, 27, 10, 5));
            control.closeReceivedMonth("2026-10", 55, 55);

            List<CapturePeriodCount> counts = LegacyFiscalDocumentTranslator.fromSyncControl(control, COPIED_AT);

            assertThat(counts).extracting(CapturePeriodCount::getSide, CapturePeriodCount::getPeriodMonth,
                            CapturePeriodCount::getProviderTotal, CapturePeriodCount::getImportedTotal, CapturePeriodCount::isClosed)
                    .containsExactly(
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 11), 40, 37, false),
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 10), 55, 55, true),
                            tuple(CaptureSide.ISSUED, YearMonth.of(2026, 11), 12, 12, false));
            assertThat(counts).allSatisfy(count -> {
                assertThat(count.getChannel()).isEqualTo(ProviderKey.SIEG);
                assertThat(count.getDocumentType()).isEqualTo(FiscalDocumentType.NFE);
            });
            CapturePeriodCount received = counts.getFirst();
            assertThat(received.getWindowStart()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(received.getProviderCountedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
            assertThat(received.getImportedCountedAt()).isEqualTo(received.getProviderCountedAt());
            assertThat(received.missing()).isEqualTo(3);
            assertThat(counts.get(2).getProviderCountedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 5));
            assertThat(counts.get(1).getClosedAt()).isEqualTo(COPIED_AT);
            assertThat(counts).allSatisfy(count -> assertThat(count.getCreatedDate()).isEqualTo(CREATED));
        }

        @Test
        void aSideNeverCheckedAndAPreviousMonthEqualToTheCurrentAreNotCopied() {
            FiscalXmlSyncControl control = new FiscalXmlSyncControl();
            control.setCompany(e1());
            control.setIssueDateFrom(LocalDate.of(2026, 11, 1));
            control.setLastCheckedAt(COPIED_AT);
            control.closeIssuedMonth("2026-11", 3, 3);
            assertThat(LegacyFiscalDocumentTranslator.fromSyncControl(control, COPIED_AT))
                    .extracting(CapturePeriodCount::getSide).containsExactly(CaptureSide.RECEIVED);
        }

        private FiscalXmlSyncControl novemberControl() {
            FiscalXmlSyncControl control = new FiscalXmlSyncControl();
            audited(control);
            control.setCompany(e1());
            control.setIssueDateFrom(LocalDate.of(2026, 11, 1));
            control.setIssueDateTo(LocalDate.of(2026, 11, 27));
            control.setTotalSieg(44);
            control.setTotalImported(40);
            control.setLastCheckedAt(LocalDateTime.of(2026, 11, 27, 10, 0));
            control.setIssuedTotalSieg(12);
            control.setIssuedTotalImported(12);
            control.setIssuedCheckedAt(LocalDateTime.of(2026, 11, 27, 10, 5));
            control.closeReceivedMonth("2026-10", 55, 53);
            return control;
        }

        /** CT-FD-36 / D-09: stored_any_source on the current month; each gap slot on its month's row. */
        @Test
        void v85StoredCountAndGapSlotsGoToTheirMonths() {
            FiscalXmlSyncControl control = novemberControl();
            control.setStoredAnySource(false, 42);
            control.setStoredAnySource(true, 12);
            control.recordKnownGap(false, "2026-11", 1);
            control.recordKnownGap(false, "2026-10", 2);

            List<CapturePeriodCount> counts = LegacyFiscalDocumentTranslator.fromSyncControl(control, COPIED_AT);

            assertThat(counts).extracting(CapturePeriodCount::getSide, CapturePeriodCount::getPeriodMonth,
                            CapturePeriodCount::getStoredAnySourceTotal, CapturePeriodCount::getKnownGap, CapturePeriodCount::isClosed)
                    .containsExactly(
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 11), 42, 1, false),
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 10), null, 2, true),
                            tuple(CaptureSide.ISSUED, YearMonth.of(2026, 11), 12, null, false));
            assertThat(counts.getFirst().missing()).isEqualTo(3);
        }

        /** D-09: a gap slot of a month without row creates the row, closed unless it is the current month. */
        @Test
        void aGapSlotWithoutRowCreatesAClosedRow() {
            FiscalXmlSyncControl control = novemberControl();
            control.recordKnownGap(true, "2026-09", 4);

            List<CapturePeriodCount> issued = LegacyFiscalDocumentTranslator.fromSyncControl(control, COPIED_AT).stream()
                    .filter(count -> count.getSide() == CaptureSide.ISSUED).toList();

            assertThat(issued).extracting(CapturePeriodCount::getPeriodMonth, CapturePeriodCount::getProviderTotal,
                            CapturePeriodCount::getImportedTotal, CapturePeriodCount::getKnownGap, CapturePeriodCount::getClosedAt)
                    .containsExactly(
                            tuple(YearMonth.of(2026, 11), 12, 12, null, null),
                            tuple(YearMonth.of(2026, 9), null, 0, 4, COPIED_AT));
        }

        @Test
        void aGapSlotOfTheCurrentMonthWithoutCurrentRowStaysOpen() {
            FiscalXmlSyncControl control = new FiscalXmlSyncControl();
            control.setCompany(e1());
            control.setIssueDateFrom(LocalDate.of(2026, 11, 1));
            control.setLastCheckedAt(COPIED_AT);
            ReflectionTestUtils.setField(control, "issuedKnownGapMonth", "2026-11");
            ReflectionTestUtils.setField(control, "issuedKnownGap", 2);

            List<CapturePeriodCount> issued = LegacyFiscalDocumentTranslator.fromSyncControl(control, COPIED_AT).stream()
                    .filter(count -> count.getSide() == CaptureSide.ISSUED).toList();

            assertThat(issued).singleElement().satisfies(count -> {
                assertThat(count.getKnownGap()).isEqualTo(2);
                assertThat(count.isClosed()).isFalse();
                assertThat(count.getProviderTotal()).isNull();
            });
        }

        /** D-09: both slots on the same month - the first slot wins; a slot without month or value is not copied. */
        @Test
        void firstSlotWinsAndIncompleteSlotsAreIgnored() {
            FiscalXmlSyncControl control = novemberControl();
            ReflectionTestUtils.setField(control, "receivedKnownGapMonth", "2026-08");
            ReflectionTestUtils.setField(control, "receivedKnownGap", 5);
            ReflectionTestUtils.setField(control, "receivedEarlierGapMonth", "2026-08");
            ReflectionTestUtils.setField(control, "receivedEarlierGap", 9);
            ReflectionTestUtils.setField(control, "issuedKnownGap", 7);
            ReflectionTestUtils.setField(control, "issuedEarlierGapMonth", "2026-07");

            List<CapturePeriodCount> counts = LegacyFiscalDocumentTranslator.fromSyncControl(control, COPIED_AT);

            assertThat(counts).extracting(CapturePeriodCount::getSide, CapturePeriodCount::getPeriodMonth, CapturePeriodCount::getKnownGap)
                    .containsExactly(
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 11), null),
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 10), null),
                            tuple(CaptureSide.RECEIVED, YearMonth.of(2026, 8), 5),
                            tuple(CaptureSide.ISSUED, YearMonth.of(2026, 11), null));
        }
    }
}
