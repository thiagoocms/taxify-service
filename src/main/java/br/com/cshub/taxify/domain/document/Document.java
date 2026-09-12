package br.com.cshub.taxify.domain.document;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "number", length = 50, nullable = false)
    private String number;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "type", nullable = false)
    private DocumentTypeEnum type;

    public Document() {
    }

    public Document(String number, DocumentTypeEnum type) {
        this.number = number;
        this.type = type;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public DocumentTypeEnum getType() {
        return this.type;
    }

    public void setType(DocumentTypeEnum type) {
        this.type = type;
    }
}
