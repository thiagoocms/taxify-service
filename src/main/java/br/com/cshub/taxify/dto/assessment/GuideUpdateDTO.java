package br.com.cshub.taxify.dto.assessment;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The guide (DAS/DARF) of the month and the closing notes - editable whether the month is open or closed. */
public record GuideUpdateDTO(
        boolean guideIssued,
        @PositiveOrZero BigDecimal guideAmount,
        LocalDate guideDueDate,
        @Size(max = 1000) String notes
) {
}
