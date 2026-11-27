package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalDateTime;

/** The {@code ide} group of a goods invoice beyond model, series, number, issue date and nature (spec 3.4, block G). */
@Embeddable
public record GoodsInvoiceHeader(
        @Column(name = "header_uf_code", length = 5) String ufCode,
        @Column(name = "header_exit_entry_at") LocalDateTime exitEntryAt,
        @Column(name = "header_operation_type", length = 5) String operationType,
        @Column(name = "header_destination_type", length = 5) String destinationType,
        @Column(name = "header_taxable_event_city_code", length = 10) String taxableEventCityCode,
        @Column(name = "header_environment", length = 5) String environment,
        @Column(name = "header_purpose", length = 5) String purpose,
        @Column(name = "header_final_consumer", length = 5) String finalConsumer,
        @Column(name = "header_presence_indicator", length = 5) String presenceIndicator,
        @Column(name = "header_print_type", length = 5) String printType,
        @Column(name = "header_emission_type", length = 5) String emissionType,
        @Column(name = "header_check_digit", length = 5) String checkDigit,
        @Column(name = "header_emission_process", length = 5) String emissionProcess,
        @Column(name = "header_emission_process_version", length = 30) String emissionProcessVersion
) {
}
