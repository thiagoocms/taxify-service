package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.subscription.SubscriptionDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionMapperTest {

    private final SubscriptionMapper mapper = new SubscriptionMapper();

    @Test
    void toEntityBuildsReferenceOnlyCompanyAndPlan() {
        UUID companyId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        SubscriptionDTO dto = new SubscriptionDTO(null, companyId.toString(), planId.toString(), start, end, false);

        Subscription entity = mapper.toEntity(dto);

        assertThat(entity.getCompany().getId()).isEqualTo(companyId);
        assertThat(entity.getPlan().getId()).isEqualTo(planId);
        assertThat(entity.getStartDate()).isEqualTo(start);
        assertThat(entity.getEndDate()).isEqualTo(end);
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void toModelExposesCompanyAndPlanIdsAsStrings() {
        UUID id = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Subscription entity = new Subscription(id);
        entity.setCompany(new Company(companyId));
        entity.setPlan(new Plan(planId));
        entity.setStartDate(LocalDate.of(2026, 1, 1));
        entity.setEndDate(LocalDate.of(2026, 12, 31));

        SubscriptionDTO dto = mapper.toModel(entity);

        assertThat(dto.id()).isEqualTo(id.toString());
        assertThat(dto.companyId()).isEqualTo(companyId.toString());
        assertThat(dto.planId()).isEqualTo(planId.toString());
    }

    @Test
    void mergeCopiesCompanyPlanDatesAndDeleted() {
        Subscription source = new Subscription();
        Company company = new Company(UUID.randomUUID());
        Plan plan = new Plan(UUID.randomUUID());
        source.setCompany(company);
        source.setPlan(plan);
        source.setStartDate(LocalDate.of(2027, 1, 1));
        source.setEndDate(LocalDate.of(2027, 6, 30));
        source.setDeleted(true);

        Subscription target = new Subscription(UUID.randomUUID());

        mapper.merge(source, target);

        assertThat(target.getCompany()).isEqualTo(company);
        assertThat(target.getPlan()).isEqualTo(plan);
        assertThat(target.getStartDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(target.getEndDate()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(target.isDeleted()).isTrue();
    }
}
