package br.com.cshub.taxify.domain.fiscaldocument;

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
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacyNfseDirection;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacySourceNames;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure translation of the old model ({@code fiscal_xml_documents}, {@code fiscal_xml_document_items},
 * {@code service_invoices}, {@code fiscal_xml_events}, the counters of {@code fiscal_xml_sync_control}) into the new
 * one, applying the normalization of spec 3.9. No I/O, no clock (the copy time is a parameter).
 *
 * <p>It lives in {@code src/test} on purpose: it imports the old entities, which leave {@code src/main} with the
 * integration (B-FD-05). It is the oracle of the migration and parity tests; the SQL of V87 follows the same rules
 * (spec 3.11, 3.12, FD-RN-55, FD-RN-56). The API translations it uses ({@link LegacyNfseDirection},
 * {@link LegacySourceNames}) are in the mapper package of {@code src/main} (FD-RN-23).
 *
 * <p>Rules shared with V87:
 * <ul>
 *   <li>"trimmed" and "has text" are the SQL ones - only the ordinary space ({@link FiscalText}: {@code TRIM},
 *       {@code NULLIF(TRIM(x), '')});</li>
 *   <li>a null direction of an old row is derived (FD-RN-56): received = INBOUND; own issue with
 *       {@code ide_tipo_operacao} 0 = INBOUND, 1 = OUTBOUND; anything else stops the copy;</li>
 *   <li>an AUTHORIZED row keeps no {@code canceled_at} (I-02);</li>
 *   <li>a CT-e row keeps, of the goods columns, only the ICMS base and value (shared columns); items under a CT-e row
 *       stop the copy;</li>
 *   <li>origins with their retrieval mode: SIEG = PERIOD, SEFAZ_DFE and NFSE_ADN = CURSOR, UPLOAD = NONE;</li>
 *   <li>the NFS-e document type comes from the size (14 = CNPJ, 11 = CPF, otherwise null);</li>
 *   <li>counts: see {@link #fromSyncControl(FiscalXmlSyncControl, LocalDateTime)}.</li>
 * </ul>
 */
public final class LegacyFiscalDocumentTranslator {

    /** {@code fiscal_xml_documents.document_type} values. */
    public static final String LEGACY_NFE = "NFE";
    public static final String LEGACY_CTE = "CTE";
    /** {@code service_invoices.layout} values. */
    public static final String LEGACY_NATIONAL_LAYOUT = "NACIONAL";
    public static final String LEGACY_ABRASF_LAYOUT = "ABRASF";
    /** {@code ide_tipo_operacao} (tpNF): 0 = entry, 1 = exit. */
    public static final String TP_NF_ENTRY = "0";
    public static final String TP_NF_EXIT = "1";

    private LegacyFiscalDocumentTranslator() {
    }

    // ---------------------------------------------------------------- normalization rules (3.9, 3.10)

    /** FD-RN-14/15: {@code NFE} + model 65 = NFC-e; {@code NFE} = NF-e; {@code CTE} = CT-e. */
    public static FiscalDocumentType typeOf(String legacyDocumentType, String legacyModel) {
        if (LEGACY_CTE.equals(legacyDocumentType)) {
            return FiscalDocumentType.CTE;
        }
        if (LEGACY_NFE.equals(legacyDocumentType)) {
            return Nfce.MODEL.equals(FiscalText.trimSpaces(legacyModel)) ? FiscalDocumentType.NFCE : FiscalDocumentType.NFE;
        }
        throw new IllegalArgumentException("unknown legacy document type");
    }

    /** Spec 3.10: {@code SEFAZ_DIST} becomes {@code SEFAZ_DFE}, {@code ADN} becomes {@code NFSE_ADN}, the rest stays. */
    public static DocumentSourceKey sourceFromLegacy(String legacySource) {
        return LegacySourceNames.toSource(legacySource);
    }

    /** Spec 3.10: how each copied origin was retrieved. */
    public static RetrievalMode retrievalModeOf(DocumentSourceKey source) {
        if (DocumentSourceKey.SIEG.equals(source)) {
            return RetrievalMode.PERIOD;
        }
        if (DocumentSourceKey.SEFAZ_DFE.equals(source) || DocumentSourceKey.NFSE_ADN.equals(source)) {
            return RetrievalMode.CURSOR;
        }
        if (DocumentSourceKey.UPLOAD.equals(source)) {
            return RetrievalMode.NONE;
        }
        throw new IllegalArgumentException("no retrieval mode for the legacy source");
    }

    /** FD-RN-08 for NF-e/NFC-e/CT-e rows: {@code own_issue = true} = ISSUER, anything else (false or null) = RECIPIENT. */
    public static CompanyDocumentRole roleOfGoodsRow(Boolean ownIssue) {
        return Boolean.TRUE.equals(ownIssue) ? CompanyDocumentRole.ISSUER : CompanyDocumentRole.RECIPIENT;
    }

    /**
     * FD-RN-56: the stored direction or, for an old row the backfill did not reach, the rule of
     * {@code FiscalDirectionBackfill}: received = INBOUND; own issue with {@code ide_tipo_operacao} 0 = INBOUND and
     * 1 = OUTBOUND. An own issue without a usable {@code ide_tipo_operacao} cannot be solved and stops the copy.
     */
    public static FiscalDirection directionOfGoodsRow(FiscalXmlDocument legacy) {
        if (legacy.getDirection() != null) {
            return legacy.getDirection();
        }
        if (!Boolean.TRUE.equals(legacy.getOwnIssue())) {
            return FiscalDirection.INBOUND;
        }
        String operationType = FiscalText.trimSpaces(legacy.getTipoOperacao());
        if (TP_NF_ENTRY.equals(operationType)) {
            return FiscalDirection.INBOUND;
        }
        if (TP_NF_EXIT.equals(operationType)) {
            return FiscalDirection.OUTBOUND;
        }
        throw new IllegalStateException("legacy direction cannot be derived");
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
            if (legacy.getItens() != null && !legacy.getItens().isEmpty()) {
                throw new IllegalStateException("legacy CT-e row with items");
            }
            copyCteBlock(legacy, cte);
        }
        copySource(document, legacy.getFoundInSiegAt(), DocumentSourceKey.SIEG);
        copySource(document, legacy.getFoundInSefazAt(), DocumentSourceKey.SEFAZ_DFE);
        copyUploadSource(document, legacy.getCreatedDate());
        return document;
    }

    private static void copyCommon(FiscalXmlDocument legacy, FiscalDocument document) {
        document.setId(legacy.getId());
        document.setAccessKey(legacy.getAccessKey());
        document.setModel(FiscalText.trimSpaces(legacy.getModelo()));
        document.setSeries(legacy.getSerie());
        document.setNumber(legacy.getNumero());
        document.setIssueDate(legacy.getIssueDate());
        document.setOperationNature(legacy.getNaturezaOperacao());
        document.assignRole(roleOfGoodsRow(legacy.getOwnIssue()), directionOfGoodsRow(legacy));
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
        nfse.assignRole(direction.role(), direction.direction());
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
        copySource(nfse, legacy.getFoundInSiegAt(), DocumentSourceKey.SIEG);
        copySource(nfse, legacy.getFoundInAdnAt(), DocumentSourceKey.NFSE_ADN);
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
     * NFE), side by side, in the order of V87:
     * <ol>
     *   <li>the month of {@code data_emissao_inicio} - the issued side only when it was ever checked, with the
     *       received side's window - with {@code *_stored_any_source};</li>
     *   <li>the previous month of V79, closed at {@code copiedAt}; not copied when it is the current month;</li>
     *   <li>the two V85 gap slots ({@code known_gap}, then {@code earlier_gap}): each becomes the {@code known_gap} of
     *       its month's row - the first slot wins on the same month - or a row of its own, closed unless it is the
     *       current month. A slot without month or without value is not copied.</li>
     * </ol>
     * Every row keeps the auditing columns of the control row.
     */
    public static List<CapturePeriodCount> fromSyncControl(FiscalXmlSyncControl control, LocalDateTime copiedAt) {
        Objects.requireNonNull(copiedAt, "copiedAt");
        YearMonth current = control.getIssueDateFrom() == null ? null : YearMonth.from(control.getIssueDateFrom());
        List<CapturePeriodCount> counts = new ArrayList<>(side(control, CaptureSide.RECEIVED, current, copiedAt));
        counts.addAll(side(control, CaptureSide.ISSUED, current, copiedAt));
        return counts;
    }

    private static List<CapturePeriodCount> side(FiscalXmlSyncControl control, CaptureSide side, YearMonth current,
                                                 LocalDateTime copiedAt) {
        boolean issued = side == CaptureSide.ISSUED;
        Map<YearMonth, CapturePeriodCount> rows = new LinkedHashMap<>();
        boolean everChecked = !issued || control.getIssuedTotalSieg() != null || control.getIssuedTotalImported() != null
                || control.getIssuedCheckedAt() != null;
        if (current != null && everChecked) {
            CapturePeriodCount row = count(control, side, current);
            row.askedWindow(control.getIssueDateFrom(), control.getIssueDateTo());
            LocalDateTime checkedAt = issued ? control.getIssuedCheckedAt() : control.getLastCheckedAt();
            row.recordProviderCount(issued ? control.getIssuedTotalSieg() : Integer.valueOf(control.getTotalSieg()), checkedAt);
            row.recordImported(issued ? orZero(control.getIssuedTotalImported()) : control.getTotalImported(), checkedAt);
            row.recordStoredAnySource(issued ? control.getIssuedStoredAnySource() : control.getReceivedStoredAnySource());
            rows.put(current, row);
        }
        String previous = FiscalText.trimSpaces(issued ? control.getIssuedPrevMonth() : control.getReceivedPrevMonth());
        if (hasText(previous) && !YearMonth.parse(previous).equals(current)) {
            CapturePeriodCount row = count(control, side, YearMonth.parse(previous));
            row.recordProviderCount(issued ? control.getIssuedPrevTotalSieg() : control.getReceivedPrevTotalSieg(), null);
            row.recordImported(orZero(issued ? control.getIssuedPrevImported() : control.getReceivedPrevImported()), null);
            row.close(copiedAt);
            rows.put(row.getPeriodMonth(), row);
        }
        String prefix = issued ? "issued" : "received";
        for (String slot : List.of("KnownGap", "EarlierGap")) {
            // the old entity has no getter per slot (only knownGap(side, month)); the oracle reads the fields directly
            String month = FiscalText.trimSpaces((String) ReflectionTestUtils.getField(control, prefix + slot + "Month"));
            Integer gap = (Integer) ReflectionTestUtils.getField(control, prefix + slot);
            if (!hasText(month) || gap == null) {
                continue;
            }
            YearMonth period = YearMonth.parse(month);
            CapturePeriodCount row = rows.get(period);
            if (row == null) {
                row = count(control, side, period);
                if (!period.equals(current)) {
                    row.close(copiedAt);
                }
                rows.put(period, row);
            }
            if (row.getKnownGap() == null) {
                row.recordKnownGap(gap);
            }
        }
        return new ArrayList<>(rows.values());
    }

    private static CapturePeriodCount count(FiscalXmlSyncControl control, CaptureSide side, YearMonth month) {
        CapturePeriodCount count = new CapturePeriodCount(control.getCompany(), ProviderKey.SIEG, FiscalDocumentType.NFE,
                side, month);
        count.copyAuditingFrom(control);
        return count;
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

    private static void copySource(FiscalDocument document, LocalDateTime seenAt, DocumentSourceKey source) {
        if (seenAt != null) {
            document.markSeenIn(source, retrievalModeOf(source), seenAt);
        }
    }

    /** Spec 3.10: an uploaded document gets the origin UPLOAD (mode NONE) at its {@code created_date}. */
    private static void copyUploadSource(FiscalDocument document, LocalDateTime createdDate) {
        if (document.getSourceSystem().isUpload() && createdDate != null) {
            document.markSeenIn(DocumentSourceKey.UPLOAD, RetrievalMode.NONE, createdDate);
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
        return FiscalText.hasText(value);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
