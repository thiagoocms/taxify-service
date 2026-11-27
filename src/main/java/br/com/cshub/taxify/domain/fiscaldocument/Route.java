package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Where the freight of a CT-e starts and ends (IBGE city code and UF). */
@Embeddable
public record Route(
        @Column(name = "cte_origin_city_code", length = 7) String originCityCode,
        @Column(name = "cte_origin_uf", length = 2, columnDefinition = "char(2)") String originUf,
        @Column(name = "cte_destination_city_code", length = 7) String destinationCityCode,
        @Column(name = "cte_destination_uf", length = 2, columnDefinition = "char(2)") String destinationUf
) {
}
