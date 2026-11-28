package br.com.cshub.taxify.mapper.fiscaldocument;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.CteParticipation;
import br.com.cshub.taxify.domain.fiscaldocument.CteParty;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalText;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoiceHeader;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsTotals;
import br.com.cshub.taxify.domain.fiscaldocument.IbsCbsTotals;
import br.com.cshub.taxify.domain.fiscaldocument.IssTax;
import br.com.cshub.taxify.domain.fiscaldocument.IssuerDetails;
import br.com.cshub.taxify.domain.fiscaldocument.ItemFederalTax;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIbsCbs;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIcms;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIcmsSt;
import br.com.cshub.taxify.domain.fiscaldocument.Nfce;
import br.com.cshub.taxify.domain.fiscaldocument.Nfe;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.domain.fiscaldocument.Party;
import br.com.cshub.taxify.domain.fiscaldocument.PartyAddress;
import br.com.cshub.taxify.domain.fiscaldocument.Product;
import br.com.cshub.taxify.domain.fiscaldocument.RecipientDetails;
import br.com.cshub.taxify.domain.fiscaldocument.SimplesCredit;
import br.com.cshub.taxify.domain.fiscaldocument.TradeUnit;
import br.com.cshub.taxify.domain.fiscaldocument.Withholdings;
import br.com.cshub.taxify.dto.fiscalxml.FiscalIbsCbsTotalsDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalItemIbsCbsDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalItemTaxDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import br.com.cshub.taxify.integration.fiscalxml.NfeRetentions;
import br.com.cshub.taxify.integration.nfse.NfseXml;
import br.com.cshub.taxify.integration.sieg.CteParticipants;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import br.com.cshub.taxify.service.FiscalDirections;

import java.util.Optional;

/**
 * Builds the documents of the new model from what the parsers read (FD-RN-03): the subtype from the document type and
 * the model (FD-RN-14/15), the role and direction of the company (FD-RN-08), the parties with one document and one type
 * each (FD-RN-09). Pure - no I/O. The invariants of spec 3.3 are checked when the document is saved (CREATE); a value
 * with more decimal places than its column is refused here already (I-17). In the mapper package, not in the domain: the
 * domain does not depend on the parser's DTOs.
 */
public final class FiscalDocumentFactory {

    /** What the NFS-e parser of today is, for {@code metadata_version} (0 = copied from the old model). */
    public static final int NFSE_PARSER_VERSION = 1;

    private FiscalDocumentFactory() {
    }

    /**
     * An NF-e ({@code mod} 55, or any other model - refused by I-10 on save), an NFC-e ({@code mod} 65) or a CT-e from
     * the parsed XML; empty for a document the parser did not recognize.
     */
    public static Optional<FiscalDocument> fromParsed(FiscalXmlMetadata metadata, Company company, DocumentSourceKey source,
                                                      String xmlFilePath, int metadataVersion, NfeRetentions retentions) {
        if (FiscalXmlMetadata.CTE.equals(metadata.documentType())) {
            return Optional.of(cte(metadata, company, source, xmlFilePath, metadataVersion, retentions));
        }
        if (FiscalXmlMetadata.NFE.equals(metadata.documentType())) {
            return Optional.of(goodsInvoice(metadata, company, source, xmlFilePath, metadataVersion, retentions));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- NF-e / NFC-e

    private static GoodsInvoice goodsInvoice(FiscalXmlMetadata metadata, Company company, DocumentSourceKey source,
                                             String xmlFilePath, int metadataVersion, NfeRetentions retentions) {
        FiscalXmlIdentificacaoDTO ide = metadata.identificacao();
        String model = ide == null ? null : FiscalText.trimSpaces(ide.modelo());
        GoodsInvoice invoice = Nfce.MODEL.equals(model) ? new Nfce(company, source) : new Nfe(company, source);
        invoice.setModel(model);
        fillCommon(invoice, metadata.accessKey(), metadata.issueDate(), xmlFilePath, metadataVersion);
        if (ide != null) {
            invoice.setSeries(ide.serie());
            invoice.setNumber(ide.numero());
            invoice.setOperationNature(ide.naturezaOperacao());
        }

        FiscalXmlEmitenteDTO emit = metadata.emitente();
        FiscalXmlEnderecoDTO emitAddress = emit == null ? null : emit.endereco();
        String issuerCpf = emit == null ? null : emit.cpf();
        invoice.setIssuer(Party.of(metadata.issuerCnpj(), issuerCpf, null, emit == null ? null : emit.nome(),
                emitAddress == null ? null : emitAddress.uf(), emitAddress == null ? null : emitAddress.codigoMunicipio()));
        FiscalXmlDestinatarioDTO dest = metadata.destinatario();
        FiscalXmlEnderecoDTO destAddress = dest == null ? null : dest.endereco();
        invoice.setRecipient(Party.of(metadata.recipientCnpj(), dest == null ? null : dest.cpf(),
                dest == null ? null : dest.idEstrangeiro(), dest == null ? null : dest.nome(),
                destAddress == null ? null : destAddress.uf(), destAddress == null ? null : destAddress.codigoMunicipio()));
        invoice.setHeader(ide == null ? null : header(ide));
        invoice.setIssuerDetails(emit == null ? null : new IssuerDetails(emit.nomeFantasia(), emit.inscricaoEstadual(),
                emit.inscricaoEstadualSubstitutoTributario(), emit.inscricaoMunicipal(), emit.cnae(), emit.regimeTributario(),
                address(emitAddress)));
        invoice.setRecipientDetails(dest == null ? null : new RecipientDetails(dest.inscricaoEstadual(), dest.indicadorIe(),
                dest.email(), address(destAddress)));
        applyTotals(invoice, metadata.totais());
        invoice.setWithholdings(retentions == null ? null : retentions.toWithholdings());

        boolean ownIssue = isOwnIssue(company, metadata.issuerCnpj(), issuerCpf);
        String operationType = ide == null ? null : ide.tipoOperacao();
        invoice.assignRole(ownIssue ? CompanyDocumentRole.ISSUER : CompanyDocumentRole.RECIPIENT,
                directionOf(ownIssue, operationType));

        for (FiscalXmlProdutoDTO produto : metadata.produtos()) {
            invoice.addItem(item(produto));
        }
        return invoice;
    }

    private static GoodsInvoiceHeader header(FiscalXmlIdentificacaoDTO ide) {
        return new GoodsInvoiceHeader(ide.codigoUf(), ide.dataSaidaEntrada(), ide.tipoOperacao(), ide.destinoOperacao(),
                ide.codigoMunicipioFatoGerador(), ide.ambiente(), ide.finalidadeEmissao(), ide.consumidorFinal(),
                ide.indicadorPresenca(), ide.tipoImpressao(), ide.tipoEmissao(), ide.digitoVerificador(), ide.processoEmissao(),
                ide.versaoProcessoEmissao());
    }

    private static PartyAddress address(FiscalXmlEnderecoDTO address) {
        if (address == null) {
            return null;
        }
        return new PartyAddress(address.logradouro(), address.numero(), address.complemento(), address.bairro(),
                address.municipio(), address.cep(), address.telefone(), address.codigoPais(), address.pais());
    }

    private static void applyTotals(GoodsInvoice invoice, FiscalXmlTotaisDTO totals) {
        if (totals == null) {
            return;
        }
        invoice.setTotalAmount(totals.valorTotalNota());
        invoice.setGoodsTotals(new GoodsTotals(totals.baseCalculoIcms(), totals.valorIcms(), totals.valorIcmsDesonerado(),
                totals.valorFcp(), totals.baseCalculoIcmsSt(), totals.valorIcmsSt(), totals.valorFcpSt(), totals.valorProdutos(),
                totals.valorFrete(), totals.valorSeguro(), totals.valorDesconto(), totals.valorImportacao(), totals.valorIpi(),
                totals.valorPis(), totals.valorCofins(), totals.valorOutrasDespesas(), totals.valorAproximadoTributos()));
        invoice.setIbsCbsTotals(ibsCbsTotals(totals.ibsCbs()));
    }

    /** The IBSCBSTot of the note as the domain keeps it; null when the note has none. */
    public static IbsCbsTotals ibsCbsTotals(FiscalIbsCbsTotalsDTO totals) {
        if (totals == null) {
            return null;
        }
        return new IbsCbsTotals(totals.baseCalculo(), totals.valorIbsUf(), totals.valorIbsMun(), totals.valorIbs(),
                totals.valorCbs());
    }

    /** One {@code det} of the note. */
    public static FiscalDocumentItem item(FiscalXmlProdutoDTO produto) {
        FiscalXmlImpostoItemDTO taxes = produto.impostos();
        FiscalItemTaxDetailDTO detail = taxes == null ? null : taxes.detalhe();
        FiscalDocumentItem item = new FiscalDocumentItem(produto.numeroItem(), new Product(produto.codigo(),
                produto.codigoBarras(), produto.descricao(), produto.ncm(), detail == null ? null : detail.cest(),
                detail == null ? null : detail.codigoBeneficio(), produto.cfop()));
        item.setCommercial(new TradeUnit(produto.unidadeComercial(), produto.quantidadeComercial(),
                produto.valorUnitarioComercial()));
        item.setTaxable(new TradeUnit(produto.unidadeTributavel(), produto.quantidadeTributavel(),
                produto.valorUnitarioTributavel()));
        item.setTotalAmount(produto.valorTotal());
        if (taxes != null) {
            applyTaxes(item, taxes, detail);
        }
        return item;
    }

    private static void applyTaxes(FiscalDocumentItem item, FiscalXmlImpostoItemDTO taxes, FiscalItemTaxDetailDTO detail) {
        boolean withDetail = detail != null;
        item.setIcms(new ItemIcms(withDetail ? detail.origemMercadoria() : null, taxes.situacaoTributariaIcms(),
                withDetail ? detail.csosn() : null, withDetail ? detail.modalidadeBcIcms() : null, taxes.baseCalculoIcms(),
                taxes.aliquotaIcms(), withDetail ? detail.reducaoBcIcms() : null, taxes.valorIcms(),
                withDetail ? detail.valorIcmsDesonerado() : null));
        if (withDetail) {
            item.setIcmsSt(new ItemIcmsSt(detail.modalidadeBcIcmsSt(), detail.mvaIcmsSt(), detail.baseCalculoIcmsSt(),
                    detail.aliquotaIcmsSt(), detail.valorIcmsSt()));
            item.setSimplesCredit(new SimplesCredit(detail.aliquotaCreditoSn(), detail.valorCreditoSn()));
        }
        item.setIpi(new ItemFederalTax(taxes.situacaoTributariaIpi(), withDetail ? detail.baseCalculoIpi() : null,
                withDetail ? detail.aliquotaIpi() : null, taxes.valorIpi()));
        item.setPis(new ItemFederalTax(taxes.situacaoTributariaPis(), withDetail ? detail.baseCalculoPis() : null,
                withDetail ? detail.aliquotaPis() : null, taxes.valorPis()));
        item.setCofins(new ItemFederalTax(taxes.situacaoTributariaCofins(), withDetail ? detail.baseCalculoCofins() : null,
                withDetail ? detail.aliquotaCofins() : null, taxes.valorCofins()));
        item.setIbsCbs(ibsCbs(taxes.ibsCbs()));
    }

    /**
     * The item re-read by a newer parser (FiscalXmlTaxDetailBackfill): only what an older version did not extract or
     * got wrong - the tax detail, IBS/CBS and the IPI CST and value; CST, base, rate and value of ICMS/PIS/COFINS stay.
     */
    public static void refreshTaxDetail(FiscalDocumentItem item, FiscalXmlImpostoItemDTO taxes) {
        FiscalItemTaxDetailDTO detail = taxes.detalhe();
        Product product = item.getProduct();
        ItemIcms icms = item.getIcms();
        boolean withDetail = detail != null;
        item.setProduct(new Product(product.code(), product.barcode(), product.description(), product.ncm(),
                withDetail ? detail.cest() : null, withDetail ? detail.codigoBeneficio() : null, product.cfop()));
        item.setIcms(new ItemIcms(withDetail ? detail.origemMercadoria() : null, icms.cst(),
                withDetail ? detail.csosn() : null, withDetail ? detail.modalidadeBcIcms() : null, icms.base(), icms.rate(),
                withDetail ? detail.reducaoBcIcms() : null, icms.amount(), withDetail ? detail.valorIcmsDesonerado() : null));
        item.setIcmsSt(withDetail ? new ItemIcmsSt(detail.modalidadeBcIcmsSt(), detail.mvaIcmsSt(), detail.baseCalculoIcmsSt(),
                detail.aliquotaIcmsSt(), detail.valorIcmsSt()) : null);
        item.setSimplesCredit(withDetail ? new SimplesCredit(detail.aliquotaCreditoSn(), detail.valorCreditoSn()) : null);
        item.setIpi(new ItemFederalTax(taxes.situacaoTributariaIpi(), withDetail ? detail.baseCalculoIpi() : null,
                withDetail ? detail.aliquotaIpi() : null, taxes.valorIpi()));
        item.setPis(new ItemFederalTax(item.getPis().cst(), withDetail ? detail.baseCalculoPis() : null,
                withDetail ? detail.aliquotaPis() : null, item.getPis().amount()));
        item.setCofins(new ItemFederalTax(item.getCofins().cst(), withDetail ? detail.baseCalculoCofins() : null,
                withDetail ? detail.aliquotaCofins() : null, item.getCofins().amount()));
        item.setIbsCbs(ibsCbs(taxes.ibsCbs()));
    }

    /**
     * The header fields a parser before version 4 did not keep (print and emission type, check digit, emission process
     * and its version, country of issuer and recipient) - FiscalXmlTaxDetailBackfill.
     */
    public static void refreshHeaderExtras(GoodsInvoice invoice, FiscalXmlMetadata metadata) {
        FiscalXmlIdentificacaoDTO ide = metadata.identificacao();
        GoodsInvoiceHeader h = invoice.getHeader();
        invoice.setHeader(new GoodsInvoiceHeader(h.ufCode(), h.exitEntryAt(), h.operationType(), h.destinationType(),
                h.taxableEventCityCode(), h.environment(), h.purpose(), h.finalConsumer(), h.presenceIndicator(),
                ide == null ? null : ide.tipoImpressao(), ide == null ? null : ide.tipoEmissao(),
                ide == null ? null : ide.digitoVerificador(), ide == null ? null : ide.processoEmissao(),
                ide == null ? null : ide.versaoProcessoEmissao()));
        FiscalXmlEnderecoDTO emit = metadata.emitente() == null ? null : metadata.emitente().endereco();
        IssuerDetails issuer = invoice.getIssuerDetails();
        invoice.setIssuerDetails(new IssuerDetails(issuer.tradeName(), issuer.stateRegistration(), issuer.stStateRegistration(),
                issuer.municipalRegistration(), issuer.cnae(), issuer.taxRegimeCode(),
                withCountry(issuer.addressOrEmpty(), emit)));
        FiscalXmlEnderecoDTO dest = metadata.destinatario() == null ? null : metadata.destinatario().endereco();
        RecipientDetails recipient = invoice.getRecipientDetails();
        invoice.setRecipientDetails(new RecipientDetails(recipient.stateRegistration(), recipient.stateRegistrationIndicator(),
                recipient.email(), withCountry(recipient.addressOrEmpty(), dest)));
    }

    private static PartyAddress withCountry(PartyAddress address, FiscalXmlEnderecoDTO parsed) {
        return new PartyAddress(address.street(), address.number(), address.complement(), address.district(),
                address.cityName(), address.zipCode(), address.phone(), parsed == null ? null : parsed.codigoPais(),
                parsed == null ? null : parsed.pais());
    }

    /** The IBSCBS group of an item as the domain keeps it; null when the item has none. */
    public static ItemIbsCbs ibsCbs(FiscalItemIbsCbsDTO group) {
        if (group == null) {
            return null;
        }
        return new ItemIbsCbs(group.cst(), group.classificacaoTributaria(), group.baseCalculo(), group.aliquotaIbsUf(),
                group.reducaoAliquotaIbsUf(), group.valorIbsUf(), group.aliquotaIbsMun(), group.reducaoAliquotaIbsMun(),
                group.valorIbsMun(), group.valorIbs(), group.aliquotaCbs(), group.reducaoAliquotaCbs(), group.valorCbs(),
                group.diferimentoOuDevolucao());
    }

    /** The company issued the note: its CNPJ (or CPF) is the issuer's (FD-RN-08, {@link FiscalDirections}). */
    public static boolean isOwnIssue(Company company, String issuerCnpj, String issuerCpf) {
        return FiscalDirections.isOwnIssue(company == null || company.getDocument() == null ? null
                : company.getDocument().getNumber(), issuerCnpj, issuerCpf);
    }

    /** A note not issued by the company is an entry; its own note follows tpNF (0 = entry, 1 = exit). */
    public static FiscalDirection directionOf(boolean ownIssue, String operationType) {
        return FiscalDirections.directionOf(ownIssue, operationType);
    }

    // ---------------------------------------------------------------- CT-e

    /**
     * The CT-e of an upload with what the parser reads (FD-RN-15): key, date, issuer and recipient, and the company's
     * roles from the document of the six participant groups (spec 3.12, R-b): the main role by precedence and the
     * direction derived from it (FD-RN-08, I-04-T). A company in no group is a third party (I-15).
     */
    private static Cte cte(FiscalXmlMetadata metadata, Company company, DocumentSourceKey source, String xmlFilePath,
                           int metadataVersion, NfeRetentions retentions) {
        Cte cte = new Cte(company, source);
        fillCommon(cte, metadata.accessKey(), metadata.issueDate(), xmlFilePath, metadataVersion);
        CteParticipants groups = metadata.cte();
        CteParticipants.Party issuer = groups == null ? null : groups.issuer();
        CteParticipants.Party recipient = groups == null ? null : groups.recipient();
        cte.setIssuer(Party.of(metadata.issuerCnpj(), issuer == null ? null : issuer.cpf(), null,
                issuer == null ? null : issuer.name(), issuer == null ? null : issuer.uf(), null));
        cte.setRecipient(Party.of(metadata.recipientCnpj(), recipient == null ? null : recipient.cpf(), null,
                recipient == null ? null : recipient.name(), recipient == null ? null : recipient.uf(), null));
        cte.setWithholdings(retentions == null ? null : retentions.toWithholdings());
        if (groups != null) {
            cte.setSender(cteParty(groups.sender()));
            cte.setSenderUf(groups.sender() == null ? null : groups.sender().uf());
            cte.setShipper(cteParty(groups.shipper()));
            cte.setReceiver(cteParty(groups.receiver()));
            cte.setTaker(cteParty(groups.taker()));
            cte.setTakerUf(groups.taker() == null ? null : groups.taker().uf());
            cte.setTakerRole(groups.takerRole());
        }
        cte.assignParticipation(participation(company, metadata.issuerCnpj(), groups));
        return cte;
    }

    private static CteParticipation participation(Company company, String issuerCnpj, CteParticipants groups) {
        String own = companyDocument(company);
        boolean issuer = own != null && !own.isEmpty() && (own.equals(DocumentNumbers.onlyDigits(issuerCnpj))
                || (groups != null && is(own, groups.issuer())));
        if (groups == null) {
            return new CteParticipation(issuer, false, false, false, false, false);
        }
        return new CteParticipation(issuer, is(own, groups.sender()), is(own, groups.shipper()), is(own, groups.receiver()),
                is(own, groups.recipient()), is(own, groups.taker()));
    }

    private static boolean is(String own, CteParticipants.Party party) {
        if (own == null || own.isEmpty() || party == null) {
            return false;
        }
        return own.equals(DocumentNumbers.onlyDigits(party.document()));
    }

    private static CteParty cteParty(CteParticipants.Party party) {
        if (party == null) {
            return null;
        }
        if (FiscalText.hasText(party.cnpj())) {
            return new CteParty(party.cnpj(), TaxpayerDocumentType.CNPJ, party.name());
        }
        if (FiscalText.hasText(party.cpf())) {
            return new CteParty(party.cpf(), TaxpayerDocumentType.CPF, party.name());
        }
        return new CteParty(null, null, party.name());
    }

    // ---------------------------------------------------------------- NFS-e

    /**
     * The NFS-e of either layout (FD-RN-08): provided = OUTBOUND + ISSUER, taken = INBOUND + TAKER, neither = no
     * direction + THIRD_PARTY; canceled by the layout without a date (FD-RN-10). The issue date falls back to the
     * competence and the competence to the issue date, as before.
     */
    public static Nfse fromParsed(NfseXml nfse, Company company, DocumentSourceKey source, String xmlFilePath) {
        Nfse invoice = new Nfse(company, source, layoutOf(nfse.layout()));
        fillCommon(invoice, nfse.accessKey(), nfse.issueDate() != null ? nfse.issueDate()
                : nfse.competence() != null ? nfse.competence().atStartOfDay() : null, xmlFilePath, NFSE_PARSER_VERSION);
        invoice.setNumber(nfse.numero());
        invoice.setVerificationCode(nfse.codigoVerificacao());
        invoice.setCompetence(nfse.competence() != null ? nfse.competence()
                : nfse.issueDate() != null ? nfse.issueDate().toLocalDate() : null);
        LegacyNfseDirection direction = nfseDirection(company, nfse);
        invoice.assignRole(direction.role(), direction.direction());
        invoice.setIssuer(Party.brazilian(blankToNull(nfse.prestadorDocumento()), nfse.prestadorNome(), null,
                nfse.prestadorMunicipio()));
        invoice.setRecipient(Party.brazilian(blankToNull(nfse.tomadorDocumento()), nfse.tomadorNome(), null, null));
        invoice.setIncidenceCityCode(nfse.municipioIncidencia());
        invoice.setServiceCode(nfse.codigoServico());
        invoice.setMunicipalTaxCode(nfse.codigoTributacaoMunicipal());
        invoice.setServiceDescription(nfse.descricaoServico());
        invoice.setTotalAmount(nfse.valorServico());
        invoice.setDeductionsAmount(nfse.valorDeducoes());
        invoice.setIss(new IssTax(nfse.baseCalculo(), nfse.aliquotaIss(), nfse.valorIss(), nfse.issRetido(),
                nfse.valorIssRetido()));
        invoice.setPisAmount(nfse.valorPis());
        invoice.setCofinsAmount(nfse.valorCofins());
        invoice.setPisCofinsWithheld(nfse.pisCofinsRetidos());
        invoice.setWithholdings(new Withholdings(null, null, nfse.valorCsll(), nfse.valorIrrf(), nfse.valorInss()));
        invoice.setNetAmount(nfse.valorLiquido());
        if (nfse.canceled()) {
            invoice.cancelByLayout();
        }
        return invoice;
    }

    /** The company is the provider (PRESTADA), the taker (TOMADA) or neither (TERCEIRO). */
    public static LegacyNfseDirection nfseDirection(Company company, NfseXml nfse) {
        String own = companyDocument(company);
        if (own != null && own.equals(nfse.prestadorDocumento())) {
            return LegacyNfseDirection.PRESTADA;
        }
        if (own != null && own.equals(nfse.tomadorDocumento())) {
            return LegacyNfseDirection.TOMADA;
        }
        return LegacyNfseDirection.TERCEIRO;
    }

    /** The layout of {@link NfseXml} ({@code NACIONAL}/{@code ABRASF}). */
    public static NfseLayout layoutOf(String layout) {
        if (NfseXml.NACIONAL.equals(layout)) {
            return NfseLayout.NATIONAL;
        }
        if (NfseXml.ABRASF.equals(layout)) {
            return NfseLayout.ABRASF;
        }
        throw new IllegalArgumentException("unknown NFS-e layout");
    }

    // ---------------------------------------------------------------- common

    private static void fillCommon(FiscalDocument document, String accessKey, java.time.LocalDateTime issueDate,
                                   String xmlFilePath, int metadataVersion) {
        document.setAccessKey(accessKey);
        document.setIssueDate(issueDate);
        document.setXmlFilePath(xmlFilePath);
        document.setMetadataVersion(metadataVersion);
        document.setDeleted(false);
    }

    private static String companyDocument(Company company) {
        if (company == null || company.getDocument() == null) {
            return null;
        }
        return DocumentNumbers.onlyDigits(company.getDocument().getNumber());
    }

    private static String blankToNull(String value) {
        return FiscalText.hasText(value) ? value : null;
    }
}
