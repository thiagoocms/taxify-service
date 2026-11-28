package br.com.cshub.taxify.mapper.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload of a CT-e (spec 3.12 R-b, FD-RN-08, I-04-T, I-15): the company's roles come from the participant groups of
 * the XML, and the direction from the role - issuer = OUTBOUND, taker = INBOUND, any other role or none = no direction.
 * Fictitious CNPJs only.
 */
class FiscalDocumentFactoryCteTest {

    private static final String ISSUER = "11222333000181";
    private static final String SENDER = "11444777000161";
    private static final String TAKER = "45723174000110";
    private static final String KEY = "26261111222333000181570010000000011000000010";

    private final FiscalXmlMetadataParser parser = new FiscalXmlMetadataParser();

    private static Company company(String cnpj) {
        Company company = new Company(UUID.randomUUID());
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        return company;
    }

    /** A CT-e issued by ISSUER, sent by SENDER, taken by {@code toma4} (its own group) or by the sender ({@code toma3} 0). */
    private static String cte(boolean ownTaker) {
        String taker = ownTaker
                ? "<toma4><toma>4</toma><CNPJ>" + TAKER + "</CNPJ><xNome>TOMADORA FICTICIA</xNome></toma4>"
                : "<toma3><toma>0</toma></toma3>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <cteProc xmlns="http://www.portalfiscal.inf.br/cte">
                  <CTe>
                    <infCte Id="CTe%s" versao="4.00">
                      <ide><mod>57</mod><dhEmi>2026-11-10T10:15:00-03:00</dhEmi>%s</ide>
                      <emit><CNPJ>%s</CNPJ><xNome>TRANSPORTADORA FICTICIA</xNome></emit>
                      <rem><CNPJ>%s</CNPJ><xNome>REMETENTE FICTICIO</xNome></rem>
                    </infCte>
                  </CTe>
                </cteProc>
                """.formatted(KEY, taker, ISSUER, SENDER);
    }

    private Cte upload(String xml, Company company) {
        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));
        FiscalDocument document = FiscalDocumentFactory.fromParsed(metadata, company, DocumentSourceKey.UPLOAD, "cte.xml",
                FiscalXmlMetadataParser.CURRENT_VERSION, null).orElseThrow();
        assertThat(document).isInstanceOf(Cte.class);
        return (Cte) document;
    }

    @Test
    void theIssuerIsOutbound() {
        Cte cte = upload(cte(true), company(ISSUER));

        assertThat(cte.getCompanyRole()).isEqualTo(CompanyDocumentRole.ISSUER);
        assertThat(cte.getDirection()).isEqualTo(FiscalDirection.OUTBOUND);
        assertThat(cte.getParticipation().issuer()).isTrue();
        assertThat(cte.getModel()).isEqualTo("57");
    }

    @Test
    void theTakerOfItsOwnGroupIsInbound() {
        Cte cte = upload(cte(true), company(TAKER));

        assertThat(cte.getCompanyRole()).isEqualTo(CompanyDocumentRole.TAKER);
        assertThat(cte.getDirection()).isEqualTo(FiscalDirection.INBOUND);
        assertThat(cte.getParticipation().taker()).isTrue();
    }

    @Test
    void theSenderPointedAtByToma3IsTheTaker() {
        Cte cte = upload(cte(false), company(SENDER));

        assertThat(cte.getParticipation().sender()).isTrue();
        assertThat(cte.getParticipation().taker()).isTrue();
        assertThat(cte.getCompanyRole()).isEqualTo(CompanyDocumentRole.TAKER);
        assertThat(cte.getDirection()).isEqualTo(FiscalDirection.INBOUND);
    }

    @Test
    void aSenderThatIsNotTheTakerHasARoleButNoDirection() {
        Cte cte = upload(cte(true), company(SENDER));

        assertThat(cte.getParticipation().sender()).isTrue();
        assertThat(cte.getParticipation().taker()).isFalse();
        assertThat(cte.getDirection()).isNull();
    }

    @Test
    void aCompanyInNoGroupIsAThirdParty() {
        Cte cte = upload(cte(true), company("60701190000104"));

        assertThat(cte.getCompanyRole()).isEqualTo(CompanyDocumentRole.THIRD_PARTY);
        assertThat(cte.getDirection()).isNull();
        assertThat(cte.getParticipation().hasAnyRole()).isFalse();
    }
}
