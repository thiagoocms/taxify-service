package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubstitutionCheckTest {

    private final SubstitutionCheck check = new SubstitutionCheck();

    private static FiscalXmlDocumentItem item(String cfop, String cst, String csosn, String cest) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setCfop(cfop);
        item.setSituacaoTributariaIcms(csosn != null ? csosn : cst);
        item.setTaxDetail(new FiscalItemTaxDetail(cest, null, "0", csosn, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));
        return item;
    }

    private List<String> codes(FiscalXmlDocumentItem item) {
        return check.check(item, null).stream().map(FiscalDivergence::code).toList();
    }

    @Test
    void anStCfopNeedsAnStCode() {
        assertThat(codes(item("5405", "00", null, null))).containsExactly(SubstitutionCheck.CODE_CFOP);
        assertThat(codes(item("5405", "060", null, "1234567"))).isEmpty(); // origin in front of the CST
        assertThat(codes(item("6403", null, "102", null))).containsExactly(SubstitutionCheck.CODE_CFOP);
        assertThat(codes(item("5102", "00", null, null))).isEmpty();
    }

    @Test
    void anStItemNeedsTheCest() {
        assertThat(codes(item("5405", "60", null, null))).containsExactly(SubstitutionCheck.CODE_CEST);
        assertThat(codes(item("5405", null, "500", " "))).containsExactly(SubstitutionCheck.CODE_CEST);
        assertThat(codes(item("5102", "90", null, null))).isEmpty(); // 90 doesn't say the item is under ST
    }

    @Test
    void itemsWithoutTheTaxDetailAreSkipped() {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setCfop("5405");
        item.setSituacaoTributariaIcms("00");

        assertThat(check.check(item, null)).isEmpty();
    }
}
