package br.com.cshub.taxify.dto.reform;

import java.util.List;

/** The years in ascending order and the warnings of AL-RN-05 - console ({@link IbsCbsRateDTO}) and firms ({@link IbsCbsRateReferenceDTO}). */
public record IbsCbsRatesDTO<T>(List<T> years, List<IbsCbsRateWarningDTO> warnings) {
}
