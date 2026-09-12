package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.dto.plan.PlanDTO;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlanMapperTest {

    private final PlanMapper mapper = new PlanMapper();

    @Test
    void toEntityCopiesAllFieldsIncludingServices() {
        UUID id = UUID.randomUUID();
        PlanDTO dto = new PlanDTO(id.toString(), "Pro", "Full access", Set.of(PlanServiceEnum.FISCAL_XML_IMPORT), true);

        Plan entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getName()).isEqualTo("Pro");
        assertThat(entity.getDescription()).isEqualTo("Full access");
        assertThat(entity.getServices()).containsExactly(PlanServiceEnum.FISCAL_XML_IMPORT);
        assertThat(entity.isDeleted()).isTrue();
    }

    @Test
    void toEntityDefaultsToAnEmptyServiceSetWhenNull() {
        PlanDTO dto = new PlanDTO(null, "Basic", null, null, null);

        Plan entity = mapper.toEntity(dto);

        assertThat(entity.getServices()).isEmpty();
        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void toModelExposesServicesAsSetOfEnum() {
        Plan entity = new Plan(UUID.randomUUID());
        entity.setName("Pro");
        entity.setDescription("desc");
        entity.setServices(Set.of(PlanServiceEnum.REPORTS, PlanServiceEnum.MULTI_USER_ACCESS));

        PlanDTO dto = mapper.toModel(entity);

        assertThat(dto.services()).containsExactlyInAnyOrder(PlanServiceEnum.REPORTS, PlanServiceEnum.MULTI_USER_ACCESS);
    }

    @Test
    void mergeCopiesNameDescriptionServicesAndDeleted() {
        Plan source = new Plan();
        source.setName("Enterprise");
        source.setDescription("everything");
        source.setServices(Set.of(PlanServiceEnum.SIEG_INTEGRATION));
        source.setDeleted(true);

        Plan target = new Plan(UUID.randomUUID());
        target.setName("Basic");

        mapper.merge(source, target);

        assertThat(target.getName()).isEqualTo("Enterprise");
        assertThat(target.getDescription()).isEqualTo("everything");
        assertThat(target.getServices()).containsExactly(PlanServiceEnum.SIEG_INTEGRATION);
        assertThat(target.isDeleted()).isTrue();
    }
}
