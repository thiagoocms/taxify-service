package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.support.TestNotes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubstitutionCheckTest {

    private final SubstitutionCheck check = new SubstitutionCheck();

    private static FiscalDocumentItem item(String cfop, String cst, String csosn, String cest) {
        return TestNotes.item(1).cfop(cfop).cest(cest).icms(csosn != null ? csosn : cst, null, null, null)
                .icmsDetail("0", csosn).build();
    }

    private List<String> codes(FiscalDocumentItem item) {
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
        FiscalDocumentItem item = TestNotes.item(1).cfop("5405").icms("00", null, null, null).build();

        assertThat(check.check(item, null)).isEmpty();
    }
}
