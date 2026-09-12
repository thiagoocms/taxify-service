package br.com.cshub.taxify.domain.plan;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "plans")
public class Plan extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_services", joinColumns = @JoinColumn(name = "plan_id"))
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "service", nullable = false)
    private Set<PlanServiceEnum> services = new HashSet<>();

    public Plan() {
    }

    public Plan(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<PlanServiceEnum> getServices() {
        return services;
    }

    public void setServices(Set<PlanServiceEnum> services) {
        this.services = services != null ? services : new HashSet<>();
    }
}
