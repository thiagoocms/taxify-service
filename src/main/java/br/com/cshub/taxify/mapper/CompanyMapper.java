package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CompanyMapper extends AbstractMapper<Company, CompanyDTO> {

    private final DocumentMapper documentMapper;

    public CompanyMapper(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Override
    protected Company toEntity(CompanyDTO source) {
        Company entity = new Company();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setName(source.name());
        entity.setDocument(documentMapper.toEntity(source.documentType(), source.documentNumber()));
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected CompanyDTO toModel(Company source) {
        return new CompanyDTO(
                source.getId().toString(),
                source.getDocument().getNumber(),
                source.getDocument().getType().toString(),
                source.getName(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(Company source, Company target) {
        target.setName(source.getName());
        target.setDeleted(source.isDeleted());
    }
}
