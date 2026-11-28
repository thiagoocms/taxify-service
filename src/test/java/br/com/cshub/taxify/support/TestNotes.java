package br.com.cshub.taxify.support;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.CteParticipation;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
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
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacyNfseDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fictitious fiscal documents of the new model for the unit tests (spec 2026-11-27): builders of NF-e/NFC-e with items,
 * NFS-e and CT-e, with the fields the old tests set one by one. Only fictitious data - CNPJs with valid check digits,
 * no real key. A document built here is not persisted: the tests that need the database insert their rows with SQL.
 */
public final class TestNotes {

    public static final String COMPANY_CNPJ = "11222333000181";
    public static final String SUPPLIER_CNPJ = "98765432000198";
    public static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 15, 10, 30);

    private TestNotes() {
    }

    public static Company company(String cnpj) {
        Company company = new Company(UUID.randomUUID());
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        return company;
    }

    /** A 44-digit fictitious key with {@code model} at positions 21-22 and a serial in the number field. */
    public static String key44(String model, int serial) {
        return "26" + "2610" + SUPPLIER_CNPJ + model + "001" + String.format("%09d", serial) + "1" + "00000000" + "0";
    }

    public static NoteBuilder note(Company company) {
        return new NoteBuilder(company);
    }

    public static ItemBuilder item(int number) {
        return new ItemBuilder(number);
    }

    /** A goods invoice of a company: received from a supplier by default (RECIPIENT, INBOUND). */
    public static final class NoteBuilder {
        private final Company company;
        private UUID id = UUID.randomUUID();
        private DocumentSourceKey source = DocumentSourceKey.SIEG;
        private String model = Nfe.MODEL;
        private String accessKey;
        private boolean withoutKey;
        private String series = "1";
        private String number = "1";
        private LocalDateTime issueDate = ISSUED_AT;
        private String operationNature;
        private CompanyDocumentRole role = CompanyDocumentRole.RECIPIENT;
        private FiscalDirection direction = FiscalDirection.INBOUND;
        private String issuerCnpj = SUPPLIER_CNPJ;
        private String issuerCpf;
        private String issuerName = "Fornecedor Exemplo";
        private String issuerUf;
        private String issuerCity;
        private String recipientCnpj;
        private String recipientCpf;
        private String recipientName;
        private String recipientUf;
        private String operationType;
        private String destinationType;
        private String purpose;
        private String taxRegime;
        private PartyAddress issuerAddress;
        private String issuerStateRegistration;
        private String issuerCnae;
        private String issuerMunicipalRegistration;
        private BigDecimal total;
        private GoodsTotals totals = GoodsTotals.EMPTY;
        private IbsCbsTotals ibsCbsTotals;
        private Withholdings withholdings;
        private boolean canceled;
        private LocalDateTime canceledAt;
        private ReviewStatus reviewStatus;
        private String reviewNote;
        private String xmlFilePath = "xml/test.xml";
        private String rectifiedFilePath;
        private LocalDateTime lastRectificationCheckedAt;
        private Integer metadataVersion = 7;
        private boolean deleted;
        private final List<FiscalDocumentItem> items = new ArrayList<>();

        private NoteBuilder(Company company) {
            this.company = company;
            this.recipientCnpj = company == null || company.getDocument() == null ? null : company.getDocument().getNumber();
        }

        public NoteBuilder id(UUID value) { this.id = value; return this; }
        public NoteBuilder source(DocumentSourceKey value) { this.source = value; return this; }
        public NoteBuilder model(String value) { this.model = value; return this; }
        public NoteBuilder key(String value) { this.accessKey = value; return this; }
        /** No access key - an odd file uploaded by hand (identified by its id). */
        public NoteBuilder withoutKey() { this.withoutKey = true; this.source = DocumentSourceKey.UPLOAD; return this; }
        public NoteBuilder series(String value) { this.series = value; return this; }
        public NoteBuilder number(String value) { this.number = value; return this; }
        public NoteBuilder issuedAt(LocalDateTime value) { this.issueDate = value; return this; }
        public NoteBuilder operationNature(String value) { this.operationNature = value; return this; }
        public NoteBuilder direction(FiscalDirection value) { this.direction = value; return this; }
        public NoteBuilder issuer(String cnpj, String name) { this.issuerCnpj = cnpj; this.issuerCpf = null; this.issuerName = name; return this; }
        public NoteBuilder issuerCpf(String cpf) { this.issuerCnpj = null; this.issuerCpf = cpf; return this; }
        public NoteBuilder issuerUf(String value) { this.issuerUf = value; return this; }
        public NoteBuilder issuerCity(String value) { this.issuerCity = value; return this; }
        public NoteBuilder issuerAddress(PartyAddress value) { this.issuerAddress = value; return this; }
        public NoteBuilder issuerStateRegistration(String value) { this.issuerStateRegistration = value; return this; }
        public NoteBuilder issuerCnae(String value) { this.issuerCnae = value; return this; }
        public NoteBuilder issuerMunicipalRegistration(String value) { this.issuerMunicipalRegistration = value; return this; }
        public NoteBuilder recipient(String cnpj, String name) { this.recipientCnpj = cnpj; this.recipientCpf = null; this.recipientName = name; return this; }
        public NoteBuilder recipientCpf(String cpf) { this.recipientCnpj = null; this.recipientCpf = cpf; return this; }
        public NoteBuilder recipientUf(String value) { this.recipientUf = value; return this; }
        public NoteBuilder operationType(String value) { this.operationType = value; return this; }
        public NoteBuilder destinationType(String value) { this.destinationType = value; return this; }
        public NoteBuilder purpose(String value) { this.purpose = value; return this; }
        public NoteBuilder taxRegime(String crt) { this.taxRegime = crt; return this; }
        public NoteBuilder total(String value) { this.total = decimal(value); return this; }
        public NoteBuilder totals(GoodsTotals value) { this.totals = value; return this; }
        public NoteBuilder ibsCbsTotals(IbsCbsTotals value) { this.ibsCbsTotals = value; return this; }
        public NoteBuilder withholdings(Withholdings value) { this.withholdings = value; return this; }
        public NoteBuilder canceled(LocalDateTime at) { this.canceled = true; this.canceledAt = at; return this; }
        public NoteBuilder review(ReviewStatus status, String note) { this.reviewStatus = status; this.reviewNote = note; return this; }
        public NoteBuilder xml(String path) { this.xmlFilePath = path; return this; }
        public NoteBuilder rectified(String path, LocalDateTime checkedAt) { this.rectifiedFilePath = path; this.lastRectificationCheckedAt = checkedAt; return this; }
        public NoteBuilder metadataVersion(Integer value) { this.metadataVersion = value; return this; }
        public NoteBuilder deleted() { this.deleted = true; return this; }
        public NoteBuilder item(ItemBuilder item) { this.items.add(item.build()); return this; }

        /** The company issued it: ISSUER, OUTBOUND (or INBOUND with tpNF 0); the company is the issuer. */
        public NoteBuilder ownIssue() {
            this.role = CompanyDocumentRole.ISSUER;
            this.direction = "0".equals(operationType) ? FiscalDirection.INBOUND : FiscalDirection.OUTBOUND;
            this.issuerCnpj = company.getDocument().getNumber();
            this.issuerCpf = null;
            if (recipientCnpj == null || recipientCnpj.equals(company.getDocument().getNumber())) {
                this.recipientCnpj = "12345678000195";
            }
            return this;
        }

        public GoodsInvoice build() {
            GoodsInvoice invoice = Nfce.MODEL.equals(model) ? new Nfce(company, source) : new Nfe(company, source);
            invoice.setModel(model);
            invoice.setId(id);
            invoice.setAccessKey(withoutKey ? null : accessKey != null ? accessKey
                    : key44(model == null ? Nfe.MODEL : model, Math.abs(id.hashCode() % 999_999_999)));
            invoice.setSeries(series);
            invoice.setNumber(number);
            invoice.setIssueDate(issueDate);
            invoice.setOperationNature(operationNature);
            invoice.assignRole(role, direction);
            invoice.setIssuer(Party.of(issuerCnpj, issuerCpf, null, issuerName, issuerUf, issuerCity));
            invoice.setRecipient(Party.of(recipientCnpj, recipientCpf, null, recipientName, recipientUf, null));
            invoice.setHeader(new GoodsInvoiceHeader(null, null, operationType, destinationType, null, null, purpose, null, null,
                    null, null, null, null, null));
            invoice.setIssuerDetails(new IssuerDetails(null, issuerStateRegistration, null, issuerMunicipalRegistration,
                    issuerCnae, taxRegime, issuerAddress));
            invoice.setRecipientDetails(RecipientDetails.EMPTY);
            invoice.setTotalAmount(total);
            invoice.setGoodsTotals(totals);
            invoice.setIbsCbsTotals(ibsCbsTotals);
            invoice.setWithholdings(withholdings);
            if (canceled) {
                invoice.cancel(canceledAt);
            }
            if (reviewStatus != null) {
                invoice.review(reviewStatus, "fiscal", reviewNote, ISSUED_AT);
            }
            invoice.setXmlFilePath(xmlFilePath);
            invoice.setRectifiedFilePath(rectifiedFilePath);
            invoice.setLastRectificationCheckedAt(lastRectificationCheckedAt);
            invoice.setMetadataVersion(metadataVersion);
            invoice.setDeleted(deleted);
            items.forEach(invoice::addItem);
            return invoice;
        }
    }

    /** An item ({@code det}) of a goods invoice; without the tax detail unless one of its fields is set. */
    public static final class ItemBuilder {
        private final int number;
        private UUID id = UUID.randomUUID();
        private String code;
        private String description;
        private String ncm;
        private String cest;
        private String cfop;
        private BigDecimal total;
        private ItemIcms icms = ItemIcms.EMPTY;
        private ItemIcmsSt icmsSt;
        private ItemFederalTax ipi;
        private ItemFederalTax pis;
        private ItemFederalTax cofins;
        private ItemIbsCbs ibsCbs;
        private SimplesCredit simplesCredit;
        private TradeUnit commercial;

        private ItemBuilder(int number) {
            this.number = number;
        }

        public ItemBuilder id(UUID value) { this.id = value; return this; }
        public ItemBuilder code(String value) { this.code = value; return this; }
        public ItemBuilder description(String value) { this.description = value; return this; }
        public ItemBuilder ncm(String value) { this.ncm = value; return this; }
        public ItemBuilder cest(String value) { this.cest = value; return this; }
        public ItemBuilder cfop(String value) { this.cfop = value; return this; }
        public ItemBuilder total(String value) { this.total = decimal(value); return this; }
        public ItemBuilder commercial(String unit, String quantity, String unitPrice) {
            this.commercial = new TradeUnit(unit, decimal(quantity), decimal(unitPrice));
            return this;
        }

        /** CST (or CSOSN, as the old column), base, rate and value of the own ICMS. */
        public ItemBuilder icms(String cst, String base, String rate, String amount) {
            this.icms = new ItemIcms(icms.origin(), cst, icms.csosn(), icms.baseModality(), decimal(base), decimal(rate),
                    icms.baseReduction(), decimal(amount), icms.reliefAmount());
            return this;
        }

        /** The ICMS tax detail: origin and CSOSN (null when the note used a CST). */
        public ItemBuilder icmsDetail(String origin, String csosn) {
            this.icms = new ItemIcms(origin, icms.cst(), csosn, icms.baseModality(), icms.base(), icms.rate(),
                    icms.baseReduction(), icms.amount(), icms.reliefAmount());
            return this;
        }

        public ItemBuilder icmsSt(String base, String rate, String amount) {
            this.icmsSt = new ItemIcmsSt(null, null, decimal(base), decimal(rate), decimal(amount));
            return this;
        }

        public ItemBuilder ipi(String cst, String base, String rate, String amount) {
            this.ipi = new ItemFederalTax(cst, decimal(base), decimal(rate), decimal(amount));
            return this;
        }

        public ItemBuilder pis(String cst, String base, String rate, String amount) {
            this.pis = new ItemFederalTax(cst, decimal(base), decimal(rate), decimal(amount));
            return this;
        }

        public ItemBuilder cofins(String cst, String base, String rate, String amount) {
            this.cofins = new ItemFederalTax(cst, decimal(base), decimal(rate), decimal(amount));
            return this;
        }

        public ItemBuilder ibsCbs(ItemIbsCbs value) { this.ibsCbs = value; return this; }

        public ItemBuilder simplesCredit(String rate, String amount) {
            this.simplesCredit = new SimplesCredit(decimal(rate), decimal(amount));
            return this;
        }

        public FiscalDocumentItem build() {
            FiscalDocumentItem item = new FiscalDocumentItem(number, new Product(code, null, description, ncm, cest, null, cfop));
            item.setId(id);
            item.setTotalAmount(total);
            item.setCommercial(commercial);
            item.setIcms(icms);
            item.setIcmsSt(icmsSt);
            item.setIpi(ipi);
            item.setPis(pis);
            item.setCofins(cofins);
            item.setIbsCbs(ibsCbs);
            item.setSimplesCredit(simplesCredit);
            return item;
        }
    }

    /** A national NFS-e of the company in {@code direction} (PRESTADA, TOMADA, TERCEIRO). */
    public static Nfse nfse(Company company, String direction, String serviceValue) {
        Nfse nfse = new Nfse(company, DocumentSourceKey.SIEG, NfseLayout.NATIONAL);
        nfse.setId(UUID.randomUUID());
        nfse.setAccessKey("2611606" + "2" + SUPPLIER_CNPJ + String.format("%028d", Math.abs(nfse.getId().hashCode())));
        nfse.setNumber("1");
        nfse.setIssueDate(ISSUED_AT);
        nfse.setCompetence(ISSUED_AT.toLocalDate());
        LegacyNfseDirection legacy = LegacyNfseDirection.of(direction);
        nfse.assignRole(legacy.role(), legacy.direction());
        String own = company.getDocument().getNumber();
        boolean provided = legacy == LegacyNfseDirection.PRESTADA;
        nfse.setIssuer(Party.brazilian(provided ? own : SUPPLIER_CNPJ, "Prestador Exemplo", null, "2611606"));
        nfse.setRecipient(Party.brazilian(legacy == LegacyNfseDirection.TOMADA ? own : "12345678000195", "Tomador Exemplo",
                null, null));
        nfse.setTotalAmount(decimal(serviceValue));
        nfse.setIss(IssTax.notWithheld());
        nfse.setXmlFilePath("xml/nfse.xml");
        nfse.setMetadataVersion(1);
        nfse.setDeleted(false);
        return nfse;
    }

    /** A CT-e in which the company is the taker. */
    public static Cte takenCte(Company company) {
        Cte cte = new Cte(company, DocumentSourceKey.UPLOAD);
        cte.setId(UUID.randomUUID());
        cte.setAccessKey(key44(Cte.MODEL, 9));
        cte.setIssueDate(ISSUED_AT);
        cte.setIssuer(Party.of(SUPPLIER_CNPJ, null, null, "Transportadora Exemplo", null, null));
        cte.assignParticipation(new CteParticipation(false, false, false, false, false, true));
        cte.setXmlFilePath("xml/cte.xml");
        cte.setMetadataVersion(1);
        cte.setDeleted(false);
        return cte;
    }

    /**
     * The tax detail of an item with the arguments, in order, of the old {@code FiscalItemTaxDetail} record: CEST, cBenef,
     * origin, CSOSN, ICMS base modality, base reduction, relief, ST modality, MVA, ST base, ST rate, ST value, IPI base and
     * rate, PIS base and rate, COFINS base and rate (and the Simples credit rate and value). CST, base, rate and value of
     * ICMS/IPI/PIS/COFINS stay as they are.
     */
    public static void taxDetail(FiscalDocumentItem item, String cest, String benefit, String origin, String csosn,
                                 String baseModality, BigDecimal baseReduction, BigDecimal relief, String stModality,
                                 BigDecimal mva, BigDecimal stBase, BigDecimal stRate, BigDecimal stAmount, BigDecimal ipiBase,
                                 BigDecimal ipiRate, BigDecimal pisBase, BigDecimal pisRate, BigDecimal cofinsBase,
                                 BigDecimal cofinsRate) {
        taxDetail(item, cest, benefit, origin, csosn, baseModality, baseReduction, relief, stModality, mva, stBase, stRate,
                stAmount, ipiBase, ipiRate, pisBase, pisRate, cofinsBase, cofinsRate, null, null);
    }

    public static void taxDetail(FiscalDocumentItem item, String cest, String benefit, String origin, String csosn,
                                 String baseModality, BigDecimal baseReduction, BigDecimal relief, String stModality,
                                 BigDecimal mva, BigDecimal stBase, BigDecimal stRate, BigDecimal stAmount, BigDecimal ipiBase,
                                 BigDecimal ipiRate, BigDecimal pisBase, BigDecimal pisRate, BigDecimal cofinsBase,
                                 BigDecimal cofinsRate, BigDecimal simplesRate, BigDecimal simplesAmount) {
        Product p = item.getProduct();
        item.setProduct(new Product(p.code(), p.barcode(), p.description(), p.ncm(), cest, benefit, p.cfop()));
        ItemIcms icms = item.getIcms();
        item.setIcms(new ItemIcms(origin, icms.cst(), csosn, baseModality, icms.base(), icms.rate(), baseReduction,
                icms.amount(), relief));
        item.setIcmsSt(new ItemIcmsSt(stModality, mva, stBase, stRate, stAmount));
        item.setIpi(new ItemFederalTax(item.getIpi().cst(), ipiBase, ipiRate, item.getIpi().amount()));
        item.setPis(new ItemFederalTax(item.getPis().cst(), pisBase, pisRate, item.getPis().amount()));
        item.setCofins(new ItemFederalTax(item.getCofins().cst(), cofinsBase, cofinsRate, item.getCofins().amount()));
        item.setSimplesCredit(new SimplesCredit(simplesRate, simplesAmount));
    }

    /** The document with the issuer in another UF (the party is a value object). */
    public static void withIssuerUf(FiscalDocument document, String uf) {
        Party p = document.getIssuer();
        document.setIssuer(new Party(p.document(), p.documentType(), p.name(), uf, p.cityCode()));
    }

    /** The goods invoice with another products total (vProd). */
    public static void withProductsAmount(GoodsInvoice invoice, String amount) {
        GoodsTotals t = invoice.getGoodsTotals();
        invoice.setGoodsTotals(new GoodsTotals(t.icmsBase(), t.icmsAmount(), t.icmsReliefAmount(), t.fcpAmount(), t.icmsStBase(),
                t.icmsStAmount(), t.fcpStAmount(), decimal(amount), t.freightAmount(), t.insuranceAmount(), t.discountAmount(),
                t.importTaxAmount(), t.ipiAmount(), t.pisAmount(), t.cofinsAmount(), t.otherExpensesAmount(),
                t.approximateTaxesAmount()));
    }

    /** The goods invoice with another purpose (finNFe; the header is a value object). */
    public static void withPurpose(GoodsInvoice invoice, String purpose) {
        GoodsInvoiceHeader h = invoice.getHeader();
        invoice.setHeader(new GoodsInvoiceHeader(h.ufCode(), h.exitEntryAt(), h.operationType(), h.destinationType(),
                h.taxableEventCityCode(), h.environment(), purpose, h.finalConsumer(), h.presenceIndicator(), h.printType(),
                h.emissionType(), h.checkDigit(), h.emissionProcess(), h.emissionProcessVersion()));
    }

    /** The goods invoice with another destination (idDest; the header is a value object). */
    public static void withDestinationType(GoodsInvoice invoice, String destinationType) {
        GoodsInvoiceHeader h = invoice.getHeader();
        invoice.setHeader(new GoodsInvoiceHeader(h.ufCode(), h.exitEntryAt(), h.operationType(), destinationType,
                h.taxableEventCityCode(), h.environment(), h.purpose(), h.finalConsumer(), h.presenceIndicator(), h.printType(),
                h.emissionType(), h.checkDigit(), h.emissionProcess(), h.emissionProcessVersion()));
    }

    /** The document with another issuer CNPJ (the party is a value object). */
    public static void withIssuerCnpj(FiscalDocument document, String cnpj) {
        Party p = document.getIssuer();
        document.setIssuer(Party.of(cnpj, null, null, p.name(), p.uf(), p.cityCode()));
    }

    /** The company issued the document (ISSUER, OUTBOUND). */
    public static void asOwnIssue(FiscalDocument document) {
        document.assignRole(CompanyDocumentRole.ISSUER, FiscalDirection.OUTBOUND);
    }

    /** The item with another CFOP (the product is a value object). */
    public static void withCfop(FiscalDocumentItem item, String cfop) {
        Product p = item.getProduct();
        item.setProduct(new Product(p.code(), p.barcode(), p.description(), p.ncm(), p.cest(), p.benefitCode(), cfop));
    }

    public static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    public static LocalDate day(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
