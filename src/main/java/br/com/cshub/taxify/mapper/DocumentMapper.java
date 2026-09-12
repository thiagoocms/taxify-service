package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.dto.document.DocumentDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DocumentMapper extends AbstractMapper<Document, DocumentDTO> {

    @Override
    protected Document toEntity(DocumentDTO source) {
        Document entity = new Document();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setType(source.type());
        entity.setNumber(source.number());
        entity.setDeleted(source.deleted());
        return entity;
    }

    @Override
    protected DocumentDTO toModel(Document source) {
        return new DocumentDTO(
                source.getId().toString(),
                source.getNumber(),
                source.getType(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(Document source, Document target) {
        target.setType(source.getType());
        target.setNumber(source.getNumber());
        target.setDeleted(source.isDeleted());
    }

    protected Document toEntity(String type, String number) {
        Document entity = new Document();
        entity.setType(DocumentTypeEnum.valueOf(type));
        entity.setNumber(number);
        return entity;
    }
}
