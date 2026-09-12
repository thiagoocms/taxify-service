package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.dto.plan.PlanDTO;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.UUID;

@Component
public class PlanMapper extends AbstractMapper<Plan, PlanDTO> {

    @Override
    protected Plan toEntity(PlanDTO source) {
        Plan entity = new Plan();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setName(source.name());
        entity.setDescription(source.description());
        entity.setServices(source.services() != null ? new HashSet<>(source.services()) : new HashSet<>());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected PlanDTO toModel(Plan source) {
        return new PlanDTO(
                source.getId().toString(),
                source.getName(),
                source.getDescription(),
                source.getServices(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(Plan source, Plan target) {
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setServices(source.getServices());
        target.setDeleted(source.isDeleted());
    }
}
