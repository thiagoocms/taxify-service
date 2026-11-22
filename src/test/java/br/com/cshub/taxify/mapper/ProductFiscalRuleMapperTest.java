package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalRuleDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductFiscalRuleMapperTest {

    private final ProductFiscalRuleMapper mapper = new ProductFiscalRuleMapper();

    @Test
    void blankTaxFieldsMeanNoTaxRule() {
        ProductFiscalRule entity = mapper.toEntity(dto(" ", "", null));

        assertThat(entity.getTaxRule()).isNull();
    }

    @Test
    void taxFieldsRoundTrip() {
        ProductFiscalRule entity = mapper.toEntity(dto(" 0 ", "00", new BigDecimal("18")));
        entity.setId(UUID.randomUUID());
        entity.setCompany(new Company(UUID.randomUUID()));

        assertThat(entity.getTaxRule().origemMercadoria()).isEqualTo("0");
        ProductFiscalRuleDTO back = mapper.toModel(entity);
        assertThat(back.origemMercadoria()).isEqualTo("0");
        assertThat(back.cstIcms()).isEqualTo("00");
        assertThat(back.aliquotaIcms()).isEqualByComparingTo("18");
        assertThat(back.csosn()).isNull();
    }

    private static ProductFiscalRuleDTO dto(String origem, String cstIcms, BigDecimal aliquotaIcms) {
        return new ProductFiscalRuleDTO(null, UUID.randomUUID().toString(), "P1", "Produto", null, "5102", null, false,
                origem, cstIcms, null, aliquotaIcms, null, null, null, null, null, null, null, null, null);
    }
}
