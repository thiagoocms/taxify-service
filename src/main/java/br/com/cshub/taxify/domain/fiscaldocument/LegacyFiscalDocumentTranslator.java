package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.xml.FiscalIbsCbsTotals;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.domain.xml.FiscalXmlEvent;
import br.com.cshub.taxify.domain.xml.FiscalXmlHeaderExtras;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.domain.xml.ServiceInvoice;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure translation of the old model ({@code fiscal_xml_documents}, {@code fiscal_xml_document_items},
 * {@code service_invoices}, {@code fiscal_xml_events}, the counters of {@code fiscal_xml_sync_control}) into the new
 * one, applying the normalization of spec 3.9. It is the reference of the copy of V87 and of the parity test: every
 * column mapping of spec 3.4 to 3.11 is here, in Java, with a test per conversion. No I/O, no clock (the copy time is
 * a parameter).
 *
 * <p>Decisions where the spec is silent (all covered by tests, all to be reproduced by the SQL of V87):
 * <ul>
 *   <li>a blank text counts as absent when choosing the party document ({@code NULLIF(TRIM(x), '')} in SQL);</li>
 *   <li>an AUTHORIZED row keeps no {@code canceled_at} (I-02, {@code CASE WHEN status = 'CANCELED'} in SQL);</li>
 *   <li>a CT-e row keeps, of the goods columns, only the ICMS base and value (shared columns) - the rest of block G
 *       does not exist in a {@code Cte};</li>
 *   <li>the NFS-e document type comes from the size (14 = CNPJ, 11 = CPF, otherwise null);</li>
 *   <li>the counts of the issued side use the same window as the received side (the old row has a single window).</li>
 * </ul>
 */
public final class LegacyFiscalDocumentTranslator {

    /** {@code fiscal_xml_documents.document_type} values. */
    public static final String LEGACY_NFE = "NFE";
    public static final String LEGACY_CTE = "CTE";
    /** Old source names that change (spec 3.10); the API keeps showing the old ones (FD-RN-23). */
    public static final String LEGACY_SEFAZ_DIST = "SEFAZ_DIST";
    public static final String LEGACY_ADN = "ADN";
    /** {@code service_invoices.layout} values. */
    public static final String LEGACY_NATIONAL_LAYOUT = "NACIONAL";
    public static final String LEGACY_ABRASF_LAYOUT = "ABRASF";

    private LegacyFiscalDocumentTranslator() {
    }

    // ---------------------------------------------------------------- normalization rules (3.9, 3.10)

    /** FD-RN-14/15: {@code NFE} + model 65 = NFC-e; {@code NFE} = NF-e; {@code CTE} = CT-e. */
    public static FiscalDocumentType typeOf(String legacyDocumentType, String legacyModel) {
        if (LEGACY_CTE.equals(legacyDocumentType)) {
            return FiscalDocumentType.CTE;
        }
        if (LEGACY_NFE.equals(legacyDocumentType)) {
            return Nfce.MODEL.equals(trimmed(legacyModel)) ? FiscalDocumentType.NFCE : FiscalDocumentType.NFE;
        }
        throw new IllegalArgumentException("unknown legacy document type");
    }

    /** Spec 3.10: {@code SEFAZ_DIST} becomes {@code SEFAZ_DFE}, {@code ADN} becomes {@code NFSE_ADN}, the rest stays. */
    public static DocumentSourceKey sourceFromLegacy(String legacySource) {
        if (LEGACY_SEFAZ_DIST.equals(legacySource)) {
            return DocumentSourceKey.SEFAZ_DFE;
        }
        if (LEGACY_ADN.equals(legacySource)) {
            return DocumentSourceKey.NFSE_ADN;
        }
        return DocumentSourceKey.of(legacySource);
    }

    /** The other way, for the API that keeps the old values (FD-RN-23, CT-FD-31). */
    public static String sourceToLegacy(DocumentSourceKey source) {
        if (DocumentSourceKey.SEFAZ_DFE.equals(source)) {
            return LEGACY_SEFAZ_DIST;
        }
        if (DocumentSourceKey.NFSE_ADN.equals(source)) {
            return LEGACY_ADN;
        }
        return source.value();
    }

    /** FD-RN-08 for NF-e/NFC-e/CT-e rows: {@code own_issue = true} = ISSUER, anything else (false or null) = RECIPIENT. */
    public static CompanyDocumentRole roleOfGoodsRow(Boolean ownIssue) {
        return Boolean.TRUE.equals(ownIssue) ? CompanyDocumentRole.ISSUER : CompanyDocumentRole.RECIPIENT;
    }

    /** NF-e issuer: CNPJ before CPF (FD-RN-09). */
    public static Party issuerOf(FiscalXmlDocument legacy) {
        TypedDocument document = firstDocument(
                new TypedDocument(legacy.getIssuerCnpj(), TaxpayerDocumentType.CNPJ),
                new TypedDocument(legacy.getEmitenteCpf(), TaxpayerDocumentType.CPF));
        return partyOrNull(document, legacy.getEmitenteNome(), legacy.getEmitenteUf(), legacy.getEmitenteCodigoMunicipio());
    }

    /** NF-e recipient: CNPJ before CPF before foreign id (FD-RN-09). */
    public static Party recipientOf(FiscalXmlDocument legacy) {
        TypedDocument document = firstDocument(
                new TypedDocument(legacy.getRecipientCnpj(), TaxpayerDocumentType.CNPJ),
                new TypedDocument(legacy.getDestinatarioCpf(), TaxpayerDocumentType.CPF),
                new TypedDocument(legacy.getDestinatarioIdEstrangeiro(), TaxpayerDocumentType.FOREIGN));
        return partyOrNull(document, legacy.getDestinatarioNome(), legacy.getDestinatarioUf(),
                legacy.getDestinatarioCodigoMunicipio());
    }

    // ---------------------------------------------------------------- NF-e, NFC-e and CT-e rows

    /** A {@code fiscal_xml_documents} row (with its items) as {@link Nfe}, {@link Nfce} or {@link Cte}. */
    public static FiscalDocument fromGoodsRow(FiscalXmlDocument legacy) {
        FiscalDocumentType type = typeOf(legacy.getDocumentType(), legacy.getModelo());
        DocumentSourceKey source = sourceFromLegacy(legacy.getSourceSystem());
        FiscalDocument document = switch (type) {
            case NFE -> new Nfe(legacy.getCompany(), source);
            case NFCE -> new Nfce(legacy.getCompany(), source);
            case CTE -> new Cte(legacy.getCompany(), source);
            case NFSE -> throw new IllegalStateException("NFS-e is not a goods row");
        };
        copyCommon(legacy, document);
        if (document instanceof GoodsInvoice goods) {
            copyGoodsBlock(legacy, goods);
        } else if (document instanceof Cte cte) {
            copyCteBlock(legacy, cte);
        }
        copySources(document, legacy.getFoundInSiegAt(), DocumentSourceKey.SIEG);
        copySources(document, legacy.getFoundInSefazAt(), DocumentSourceKey.SEFAZ_DFE);
        copyUploadSource(document, legacy.getCreatedDate());
        return document;
    }

    private static void copyCommon(FiscalXmlDocument legacy, FiscalDocument document) {
        document.setId(legacy.getId());
        document.setAccessKey(legacy.getAccessKey());
        document.setModel(trimmed(legacy.getModelo()));
        document.setSeries(legacy.getSerie());
        document.setNumber(legacy.getNumero());
        document.setIssueDate(legacy.getIssueDate());
        document.setOperationNature(legacy.getNaturezaOperacao());
        document.assignRole(roleOfGoodsRow(legacy.getOwnIssue()), legacy.getDirection());
        if (legacy.isCanceled()) {
            document.cancel(legacy.getCanceledAt());
        }
        document.setIssuer(issuerOf(legacy));
        document.setRecipient(recipientOf(legacy));
        document.setTotalAmount(legacy.getValorTotalNota());
        document.setIbsCbsTotals(ibsCbsTotalsOf(legacy.getIbsCbsTotals()));
        document.setWithholdings(withholdingsOrNull(new Withholdings(legacy.getRetPis(), legacy.getRetCofins(),
                legacy.getRetCsll(), legacy.getRetIrrf(), legacy.getRetInss())));
        document.setXmlFilePath(legacy.getXmlFilePath());
        document.setMetadataVersion(legacy.getMetadataVersion() == null ? 0 : legacy.getMetadataVersion());
        document.restoreReview(reviewOf(legacy));
        document.copyAuditingFrom(legacy);
    }

    private static void copyGoodsBlock(FiscalXmlDocument legacy, GoodsInvoice goods) {
        FiscalXmlHeaderExtras extras = legacy.getHeaderExtras();
        goods.setHeader(headerOf(legacy, extras));
        goods.setIssuerDetails(issuerDetailsOf(legacy, extras));
        goods.setRecipientDetails(recipientDetailsOf(legacy, extras));
        goods.setGoodsTotals(goodsTotalsOf(legacy));
        goods.setRectifiedFilePath(legacy.getRectifiedFilePath());
        goods.setLastRectificationCheckedAt(legacy.getLastRectificationCheckedAt());
        for (FiscalXmlDocumentItem item : legacy.getItens()) {
            goods.addItem(fromItem(item));
        }
    }

    /** FD-RN-15: the CT-e keeps the common columns and the shared ICMS; {@code metadata_version = 0} (parsed later). */
    private static void copyCteBlock(FiscalXmlDocument legacy, Cte cte) {
        if (!hasText(cte.getModel())) {
            cte.setModel(Cte.MODEL);
        }
        cte.setMetadataVersion(Cte.NOT_PARSED_VERSION);
        if (legacy.getBaseCalculoIcms() != null || legacy.getValorIcms() != null) {
            cte.setIcms(new CteIcms(null, null, legacy.getBaseCalculoIcms(), null, legacy.getValorIcms()));
        }
    }

    private static GoodsInvoiceHeader headerOf(FiscalXmlDocument legacy, FiscalXmlHeaderExtras extras) {
        GoodsInvoiceHeader header = new GoodsInvoiceHeader(legacy.getCodigoUf(), legacy.getDataSaidaEntrada(),
                legacy.getTipoOperacao(), legacy.getDestinoOperacao(), legacy.getCodigoMunicipioFatoGerador(),
                legacy.getAmbiente(), legacy.getFinalidadeEmissao(), legacy.getConsumidorFinal(),
                legacy.getIndicadorPresenca(),
                extras == null ? null : extras.tipoImpressao(),
                extras == null ? null : extras.tipoEmissao(),
                extras == null ? null : extras.digitoVerificador(),
                extras == null ? null : extras.processoEmissao(),
                extras == null ? null : extras.versaoProcessoEmissao());
        return allNull(header.ufCode(), header.exitEntryAt(), header.operationType(), header.destinationType(),
                header.taxableEventCityCode(), header.environment(), header.purpose(), header.finalConsumer(),
                header.presenceIndicator(), header.printType(), header.emissionType(), header.checkDigit(),
                header.emissionProcess(), header.emissionProcessVersion()) ? null : header;
    }

    private static IssuerDetails issuerDetailsOf(FiscalXmlDocument legacy, FiscalXmlHeaderExtras extras) {
        PartyAddress address = addressOrNull(new PartyAddress(legacy.getEmitenteLogradouro(),
                legacy.getEmitenteEnderecoNumero(), legacy.getEmitenteComplemento(), legacy.getEmitenteBairro(),
                legacy.getEmitenteMunicipio(), legacy.getEmitenteCep(), legacy.getEmitenteTelefone(),
                extras == null ? null : extras.emitenteCodigoPais(), extras == null ? null : extras.emitentePais()));
        IssuerDetails details = new IssuerDetails(legacy.getEmitenteNomeFantasia(), legacy.getEmitenteInscricaoEstadual(),
                legacy.getEmitenteInscricaoEstadualSubstitutoTributario(), legacy.getEmitenteInscricaoMunicipal(),
                legacy.getEmitenteCnae(), legacy.getEmitenteRegimeTributario(), address);
        return allNull(details.tradeName(), details.stateRegistration(), details.stStateRegistration(),
                details.municipalRegistration(), details.cnae(), details.taxRegimeCode(), address) ? null : details;
    }

    private static RecipientDetails recipientDetailsOf(FiscalXmlDocument legacy, FiscalXmlHeaderExtras extras) {
        PartyAddress address = addressOrNull(new PartyAddress(legacy.getDestinatarioLogradouro(),
                legacy.getDestinatarioEnderecoNumero(), legacy.getDestinatarioComplemento(),
                legacy.getDestinatarioBairro(), legacy.getDestinatarioMunicipio(), legacy.getDestinatarioCep(),
                legacy.getDestinatarioTelefone(), extras == null ? null : extras.destinatarioCodigoPais(),
                extras == null ? null : extras.destinatarioPais()));
        RecipientDetails details = new RecipientDetails(legacy.getDestinatarioInscricaoEstadual(),
                legacy.getDestinatarioIndicadorIe(), legacy.getDestinatarioEmail(), address);
        return allNull(details.stateRegistration(), details.stateRegistrationIndicator(), details.email(), address)
                ? null : details;
    }

    private static GoodsTotals goodsTotalsOf(FiscalXmlDocument legacy) {
        GoodsTotals totals = new GoodsTotals(legacy.getBaseCalculoIcms(), legacy.getValorIcms(),
                legacy.getValorIcmsDesonerado(), legacy.getValorFcp(), legacy.getBaseCalculoIcmsSt(),
                legacy.getValorIcmsSt(), legacy.getValorFcpSt(), legacy.getValorProdutos(), legacy.getValorFrete(),
                legacy.getValorSeguro(), legacy.getValorDesconto(), legacy.getValorImportacao(), legacy.getValorIpi(),
                legacy.getValorPis(), legacy.getValorCofins(), legacy.getValorOutrasDespesas(),
                legacy.getValorAproximadoTributos());
        return allNull(totals.icmsBase(), totals.icmsAmount(), totals.icmsReliefAmount(), totals.fcpAmount(),
                totals.icmsStBase(), totals.icmsStAmount(), totals.fcpStAmount(), totals.productsAmount(),
                totals.freightAmount(), totals.insuranceAmount(), totals.discountAmount(), totals.importTaxAmount(),
                totals.ipiAmount(), totals.pisAmount(), totals.cofinsAmount(), totals.otherExpensesAmount(),
                totals.approximateTaxesAmount()) ? null : totals;
    }

    private static IbsCbsTotals ibsCbsTotalsOf(FiscalIbsCbsTotals legacy) {
        if (legacy == null || allNull(legacy.baseCalculo(), legacy.valorIbsUf(), legacy.valorIbsMun(), legacy.valorIbs(),
                legacy.valorCbs())) {
            return null;
        }
        return new IbsCbsTotals(legacy.baseCalculo(), legacy.valorIbsUf(), legacy.valorIbsMun(), legacy.valorIbs(),
                legacy.valorCbs());
    }

    private static DocumentReview reviewOf(FiscalXmlDocument legacy) {
        ReviewStatus status = legacy.getReviewStatus();
        if (status == ReviewStatus.PENDING && allNull(legacy.getReviewedAt(), legacy.getReviewedBy(), legacy.getReviewNote())) {
            return null;
        }
        return new DocumentReview(status, legacy.getReviewedAt(), legacy.getReviewedBy(), legacy.getReviewNote());
    }

    // ---------------------------------------------------------------- items

    /** A {@code fiscal_xml_document_items} row: same id, CFOP without spaces, rates widened to scale 4 (no rounding). */
    public static FiscalDocumentItem fromItem(FiscalXmlDocumentItem legacy) {
        FiscalItemTaxDetail detail = legacy.getTaxDetail();
        FiscalDocumentItem item = new FiscalDocumentItem(legacy.getNumeroItem(), new Product(legacy.getCodigo(),
                legacy.getCodigoBarras(), legacy.getDescricao(), legacy.getNcm(),
                detail == null ? null : detail.cest(), detail == null ? null : detail.codigoBeneficio(), legacy.getCfop()));
        item.setId(legacy.getId());
        item.setCommercial(tradeUnitOrNull(new TradeUnit(legacy.getUnidadeComercial(), legacy.getQuantidadeComercial(),
                legacy.getValorUnitarioComercial())));
        item.setTaxable(tradeUnitOrNull(new TradeUnit(legacy.getUnidadeTributavel(), legacy.getQuantidadeTributavel(),
                legacy.getValorUnitarioTributavel())));
        item.setTotalAmount(legacy.getValorTotal());
        item.setIcms(new ItemIcms(
                detail == null ? null : detail.origemMercadoria(), legacy.getSituacaoTributariaIcms(),
                detail == null ? null : detail.csosn(), detail == null ? null : detail.modalidadeBcIcms(),
                legacy.getBaseCalculoIcms(), legacy.getAliquotaIcms(),
                detail == null ? null : detail.reducaoBcIcms(), legacy.getValorIcms(),
                detail == null ? null : detail.valorIcmsDesonerado()));
        if (detail != null) {
            item.setIcmsSt(new ItemIcmsSt(detail.modalidadeBcIcmsSt(), detail.mvaIcmsSt(), detail.baseCalculoIcmsSt(),
                    detail.aliquotaIcmsSt(), detail.valorIcmsSt()));
            item.setSimplesCredit(detail.aliquotaCreditoSn() == null && detail.valorCreditoSn() == null ? null
                    : new SimplesCredit(detail.aliquotaCreditoSn(), detail.valorCreditoSn()));
        }
        item.setIpi(new ItemFederalTax(legacy.getSituacaoTributariaIpi(), detail == null ? null : detail.baseCalculoIpi(),
                detail == null ? null : detail.aliquotaIpi(), legacy.getValorIpi()));
        item.setPis(new ItemFederalTax(legacy.getSituacaoTributariaPis(), detail == null ? null : detail.baseCalculoPis(),
                detail == null ? null : detail.aliquotaPis(), legacy.getValorPis()));
        item.setCofins(new ItemFederalTax(legacy.getSituacaoTributariaCofins(),
                detail == null ? null : detail.baseCalculoCofins(), detail == null ? null : detail.aliquotaCofins(),
                legacy.getValorCofins()));
        item.setIbsCbs(ibsCbsOf(legacy.getIbsCbs()));
        return item;
    }

    private static ItemIbsCbs ibsCbsOf(FiscalItemIbsCbs legacy) {
        if (legacy == null) {
            return null;
        }
        return new ItemIbsCbs(legacy.cst(), legacy.classificacaoTributaria(), legacy.baseCalculo(), legacy.aliquotaIbsUf(),
                legacy.reducaoAliquotaIbsUf(), legacy.valorIbsUf(), legacy.aliquotaIbsMun(), legacy.reducaoAliquotaIbsMun(),
                legacy.valorIbsMun(), legacy.valorIbs(), legacy.aliquotaCbs(), legacy.reducaoAliquotaCbs(),
                legacy.valorCbs(), legacy.diferimentoOuDevolucao());
    }

    // ---------------------------------------------------------------- NFS-e

    /** A {@code service_invoices} row as {@link Nfse}. */
    public static Nfse fromServiceInvoice(ServiceInvoice legacy) {
        Nfse nfse = new Nfse(legacy.getCompany(), sourceFromLegacy(legacy.getSourceSystem()), layoutOf(legacy.getLayout()));
        LegacyNfseDirection direction = LegacyNfseDirection.of(legacy.getDirection());
        nfse.setId(legacy.getId());
        nfse.setAccessKey(legacy.getAccessKey());
        nfse.setNumber(legacy.getNumero());
        nfse.setIssueDate(legacy.getIssueDate());
        nfse.assignRole(direction.role, direction.direction);
        if (legacy.isCanceled()) {
            nfse.cancelByLayout();
        }
        nfse.setIssuer(brazilianPartyOrNull(legacy.getPrestadorDocumento(), legacy.getPrestadorNome(),
                legacy.getPrestadorMunicipio()));
        nfse.setRecipient(brazilianPartyOrNull(legacy.getTomadorDocumento(), legacy.getTomadorNome(), null));
        nfse.setTotalAmount(legacy.getValorServico());
        nfse.setWithholdings(withholdingsOrNull(new Withholdings(null, null, legacy.getValorCsll(), legacy.getValorIrrf(),
                legacy.getValorInss())));
        nfse.setVerificationCode(legacy.getCodigoVerificacao());
        nfse.setCompetence(legacy.getCompetence());
        nfse.setIncidenceCityCode(legacy.getMunicipioIncidencia());
        nfse.setServiceCode(legacy.getCodigoServico());
        nfse.setMunicipalTaxCode(legacy.getCodigoTributacaoMunicipal());
        nfse.setServiceDescription(legacy.getDescricaoServico());
        nfse.setDeductionsAmount(legacy.getValorDeducoes());
        nfse.setIss(new IssTax(legacy.getBaseCalculo(), legacy.getAliquotaIss(), legacy.getValorIss(), legacy.isIssRetido(),
                legacy.getValorIssRetido()));
        nfse.setPisAmount(legacy.getValorPis());
        nfse.setCofinsAmount(legacy.getValorCofins());
        nfse.setPisCofinsWithheld(legacy.isPisCofinsRetidos());
        nfse.setNetAmount(legacy.getValorLiquido());
        nfse.setXmlFilePath(legacy.getXmlFilePath());
        nfse.setMetadataVersion(0);
        nfse.copyAuditingFrom(legacy);
        copySources(nfse, legacy.getFoundInSiegAt(), DocumentSourceKey.SIEG);
        copySources(nfse, legacy.getFoundInAdnAt(), DocumentSourceKey.NFSE_ADN);
        copyUploadSource(nfse, legacy.getCreatedDate());
        return nfse;
    }

    /** {@code NACIONAL} = NATIONAL, {@code ABRASF} = ABRASF. */
    public static NfseLayout layoutOf(String legacyLayout) {
        if (LEGACY_NATIONAL_LAYOUT.equals(legacyLayout)) {
            return NfseLayout.NATIONAL;
        }
        if (LEGACY_ABRASF_LAYOUT.equals(legacyLayout)) {
            return NfseLayout.ABRASF;
        }
        throw new IllegalArgumentException("unknown legacy NFS-e layout");
    }

    /** FD-RN-08 for the NFS-e: the old direction as (direction, role) - and back, for the API (FD-RN-23). */
    public enum LegacyNfseDirection {
        PRESTADA(FiscalDirection.OUTBOUND, CompanyDocumentRole.ISSUER),
        TOMADA(FiscalDirection.INBOUND, CompanyDocumentRole.TAKER),
        TERCEIRO(null, CompanyDocumentRole.THIRD_PARTY);

        private final FiscalDirection direction;
        private final CompanyDocumentRole role;

        LegacyNfseDirection(FiscalDirection direction, CompanyDocumentRole role) {
            this.direction = direction;
            this.role = role;
        }

        public FiscalDirection direction() {
            return direction;
        }

        public CompanyDocumentRole role() {
            return role;
        }

        public static LegacyNfseDirection of(String legacyDirection) {
            return Arrays.stream(values())
                    .filter(value -> value.name().equals(legacyDirection))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown legacy NFS-e direction"));
        }

        /** The old direction an NFS-e role shows in the API ({@code direction = "TOMADA"}...). */
        public static LegacyNfseDirection ofRole(CompanyDocumentRole role) {
            return Arrays.stream(values())
                    .filter(value -> value.role == role)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("role without legacy NFS-e direction"));
        }
    }

    // ---------------------------------------------------------------- events

    /**
     * A {@code fiscal_xml_events} row: same id and link, copied as it is (its effect on the document was applied when
     * it arrived). {@code linkedDocument} is the translated document of {@code legacy.getDocument()}, or null.
     */
    public static FiscalDocumentEvent fromEvent(FiscalXmlEvent legacy, FiscalDocument linkedDocument) {
        UUID legacyDocumentId = legacy.getDocument() == null ? null : legacy.getDocument().getId();
        UUID linkedId = linkedDocument == null ? null : linkedDocument.getId();
        if (!Objects.equals(legacyDocumentId, linkedId)) {
            throw new IllegalArgumentException("linked document is not the one of the legacy event");
        }
        FiscalDocumentEvent event = new FiscalDocumentEvent(legacy.getCompany(), legacy.getAccessKey(), legacy.getEventType(),
                legacy.getSequenceNumber(), legacy.getDescription(), legacy.getEventAt(), legacy.getProtocol(),
                legacy.getText(), legacy.isRegistered());
        event.setId(legacy.getId());
        event.restoreLink(linkedDocument);
        event.copyAuditingFrom(legacy);
        return event;
    }

    // ---------------------------------------------------------------- capture counts (3.11)

    /**
     * The counters of a {@code fiscal_xml_sync_control} row as {@code capture_period_counts} rows (channel SIEG, type
     * NFE): received and issued of the month of {@code data_emissao_inicio}, and the previous months of V79, closed at
     * {@code copiedAt}. A previous month equal to the current one is not copied twice (the current row wins). The six
     * columns of V85 ({@code fix/sieg-call-savings}) are not in this branch yet: they will be mapped here when it is
     * merged ({@code stored_any_source_total}, {@code known_gap}).
     */
    public static List<CapturePeriodCount> fromSyncControl(FiscalXmlSyncControl control, LocalDateTime copiedAt) {
        Objects.requireNonNull(copiedAt, "copiedAt");
        List<CapturePeriodCount> counts = new ArrayList<>();
        YearMonth current = control.getDataEmissaoInicio() == null ? null : YearMonth.from(control.getDataEmissaoInicio());
        if (current != null) {
            CapturePeriodCount received = count(control, CaptureSide.RECEIVED, current);
            received.askedWindow(control.getDataEmissaoInicio(), control.getDataEmissaoFim());
            received.recordProviderCount(control.getTotalSieg(), control.getUltimaVerificacaoEm());
            received.recordImported(control.getTotalImportado(), control.getUltimaVerificacaoEm());
            counts.add(received);
            if (control.getIssuedTotalSieg() != null || control.getIssuedTotalImported() != null
                    || control.getIssuedCheckedAt() != null) {
                CapturePeriodCount issued = count(control, CaptureSide.ISSUED, current);
                issued.askedWindow(control.getDataEmissaoInicio(), control.getDataEmissaoFim());
                issued.recordProviderCount(control.getIssuedTotalSieg(), control.getIssuedCheckedAt());
                issued.recordImported(orZero(control.getIssuedTotalImported()), control.getIssuedCheckedAt());
                counts.add(issued);
            }
        }
        addClosedMonth(counts, control, CaptureSide.RECEIVED, control.getReceivedPrevMonth(),
                control.getReceivedPrevTotalSieg(), control.getReceivedPrevImported(), current, copiedAt);
        addClosedMonth(counts, control, CaptureSide.ISSUED, control.getIssuedPrevMonth(),
                control.getIssuedPrevTotalSieg(), control.getIssuedPrevImported(), current, copiedAt);
        return counts;
    }

    private static void addClosedMonth(List<CapturePeriodCount> counts, FiscalXmlSyncControl control, CaptureSide side,
                                       String month, Integer providerTotal, Integer imported, YearMonth current,
                                       LocalDateTime copiedAt) {
        if (!hasText(month)) {
            return;
        }
        YearMonth period = YearMonth.parse(month.strip());
        if (period.equals(current)) {
            return;
        }
        CapturePeriodCount closed = count(control, side, period);
        closed.recordProviderCount(providerTotal, null);
        closed.recordImported(orZero(imported), null);
        closed.close(copiedAt);
        counts.add(closed);
    }

    private static CapturePeriodCount count(FiscalXmlSyncControl control, CaptureSide side, YearMonth month) {
        return new CapturePeriodCount(control.getCompany(), ProviderKey.SIEG, FiscalDocumentType.NFE, side, month);
    }

    // ---------------------------------------------------------------- helpers

    private record TypedDocument(String document, TaxpayerDocumentType type) {
    }

    private static TypedDocument firstDocument(TypedDocument... candidates) {
        for (TypedDocument candidate : candidates) {
            if (hasText(candidate.document())) {
                return candidate;
            }
        }
        return null;
    }

    private static Party partyOrNull(TypedDocument document, String name, String uf, String cityCode) {
        String number = document == null ? null : document.document();
        TaxpayerDocumentType type = document == null ? null : document.type();
        return allNull(number, name, uf, cityCode) ? null : new Party(number, type, name, uf, cityCode);
    }

    private static Party brazilianPartyOrNull(String document, String name, String cityCode) {
        String number = hasText(document) ? document : null;
        return allNull(number, name, cityCode) ? null : Party.brazilian(number, name, null, cityCode);
    }

    private static void copySources(FiscalDocument document, LocalDateTime seenAt, DocumentSourceKey source) {
        if (seenAt != null) {
            document.markSeenIn(source, seenAt);
        }
    }

    /** Spec 3.10: an uploaded document gets the origin UPLOAD at its {@code created_date}. */
    private static void copyUploadSource(FiscalDocument document, LocalDateTime createdDate) {
        if (document.getSourceSystem().isUpload() && createdDate != null) {
            document.markSeenIn(DocumentSourceKey.UPLOAD, createdDate);
        }
    }

    private static Withholdings withholdingsOrNull(Withholdings withholdings) {
        return allNull(withholdings.pis(), withholdings.cofins(), withholdings.csll(), withholdings.irrf(),
                withholdings.inss()) ? null : withholdings;
    }

    private static PartyAddress addressOrNull(PartyAddress address) {
        return allNull(address.street(), address.number(), address.complement(), address.district(), address.cityName(),
                address.zipCode(), address.phone(), address.countryCode(), address.countryName()) ? null : address;
    }

    private static TradeUnit tradeUnitOrNull(TradeUnit unit) {
        return allNull(unit.unit(), unit.quantity(), unit.unitPrice()) ? null : unit;
    }

    private static boolean allNull(Object... values) {
        return Arrays.stream(values).allMatch(Objects::isNull);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
