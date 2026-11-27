package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.NfseLayout;
import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Fictitious data of the spec (section 13): E1 = 11.222.333/0001-81, E2 = 12.345.678/0001-95. */
final class FiscalDocumentFixtures {

    static final String E1_CNPJ = "11222333000181";
    static final String E2_CNPJ = "12345678000195";
    static final String SUPPLIER_CNPJ = "98765432000198";
    static final String PERSON_CPF = "12345678909";
    static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 15, 10, 30);

    private FiscalDocumentFixtures() {
    }

    static Company company(String cnpj) {
        Company company = new Company();
        company.setId(UUID.nameUUIDFromBytes(cnpj.getBytes()));
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        return company;
    }

    static Company e1() {
        return company(E1_CNPJ);
    }

    /** A 44-digit fictitious key with the model at positions 21-22. */
    static String key44(String model, int serial) {
        String head = "26" + "2610" + E1_CNPJ + model + "001";
        String tail = String.format("%09d", serial) + "1" + "00000001";
        String key = head + tail;
        return key.substring(0, 43) + "0";
    }

    /** A 50-digit fictitious national NFS-e key. */
    static String nationalNfseKey(int serial) {
        return "26116062" + SUPPLIER_CNPJ + String.format("%028d", serial);
    }

    static Party party(String document, String name) {
        return new Party(document, TaxpayerDocumentType.bySize(document), name, "PE", "2611606");
    }

    /** A valid NF-e received by E1 from a supplier (passes every CREATE invariant). */
    static Nfe receivedNfe() {
        Nfe nfe = new Nfe(e1(), DocumentSourceKey.SIEG);
        nfe.setAccessKey(key44("55", 1));
        nfe.setSeries("1");
        nfe.setNumber("1");
        nfe.setIssueDate(ISSUED_AT);
        nfe.assignRole(CompanyDocumentRole.RECIPIENT, FiscalDirection.INBOUND);
        nfe.setIssuer(party(SUPPLIER_CNPJ, "Fornecedor Exemplo"));
        nfe.setRecipient(party(E1_CNPJ, "Empresa E1"));
        nfe.setTotalAmount(new BigDecimal("1000.00"));
        nfe.setXmlFilePath("xml/e1/k1.xml");
        nfe.setMetadataVersion(7);
        return nfe;
    }

    /** A valid NFC-e issued by E1. */
    static Nfce issuedNfce() {
        Nfce nfce = new Nfce(e1(), DocumentSourceKey.SIEG);
        nfce.setAccessKey(key44("65", 6));
        nfce.setIssueDate(ISSUED_AT);
        nfce.assignRole(CompanyDocumentRole.ISSUER, FiscalDirection.OUTBOUND);
        nfce.setIssuer(party(E1_CNPJ, "Empresa E1"));
        nfce.setTotalAmount(new BigDecimal("89.90"));
        nfce.setXmlFilePath("xml/e1/k6.xml");
        return nfce;
    }

    /** A valid national NFS-e taken by E1 (S1 of CT-FD-02). */
    static Nfse takenNfse() {
        Nfse nfse = new Nfse(e1(), DocumentSourceKey.NFSE_ADN, NfseLayout.NATIONAL);
        nfse.setAccessKey(nationalNfseKey(1));
        nfse.setNumber("1");
        nfse.setIssueDate(ISSUED_AT);
        nfse.assignRole(CompanyDocumentRole.TAKER, FiscalDirection.INBOUND);
        nfse.setIssuer(party(SUPPLIER_CNPJ, "Prestador Exemplo"));
        nfse.setRecipient(party(E1_CNPJ, "Empresa E1"));
        nfse.setTotalAmount(new BigDecimal("1200.00"));
        nfse.setIss(new IssTax(new BigDecimal("1200.00"), new BigDecimal("2.0000"), new BigDecimal("24.00"), true,
                new BigDecimal("24.00")));
        nfse.setXmlFilePath("xml/e1/s1.xml");
        return nfse;
    }

    /** A valid CT-e in which E1 is the taker. */
    static Cte takenCte() {
        Cte cte = new Cte(e1(), DocumentSourceKey.SIEG);
        cte.setAccessKey(key44("57", 9));
        cte.setIssueDate(ISSUED_AT);
        cte.setIssuer(party(SUPPLIER_CNPJ, "Transportadora Exemplo"));
        cte.assignParticipation(new CteParticipation(false, false, false, false, false, true));
        cte.setTotalAmount(new BigDecimal("350.00"));
        cte.setXmlFilePath("xml/e1/c1.xml");
        cte.setMetadataVersion(1);
        return cte;
    }
}
