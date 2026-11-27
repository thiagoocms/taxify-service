package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.reform.IbsCbsRateReferenceDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRatesDTO;
import br.com.cshub.taxify.dto.reform.ReformFeaturesDTO;
import br.com.cshub.taxify.service.reform.IbsCbsRateService;
import br.com.cshub.taxify.service.reform.ReformFeaturesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reforma Tributária, what every logged-in firm user reads (any profile; also in trial and in a blocked firm - only
 * GETs): the features that are on (CE-RN-15) and the IBS/CBS reference rates per year with the warnings (F1a-0,
 * AL-RN-06).
 */
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/reform")
public class ReformResource {

    private final IbsCbsRateService rateService;
    private final ReformFeaturesService featuresService;

    public ReformResource(IbsCbsRateService rateService, ReformFeaturesService featuresService) {
        this.rateService = rateService;
        this.featuresService = featuresService;
    }

    /** What the "Reforma Tributária" menu shows. */
    @GetMapping("/features")
    public ResponseEntity<ReformFeaturesDTO> features() {
        return ResponseEntity.ok(featuresService.features());
    }

    /** Every year and status (the reference with its status badge) and the warnings of AL-RN-05. */
    @GetMapping("/ibs-cbs-rates")
    public ResponseEntity<IbsCbsRatesDTO<IbsCbsRateReferenceDTO>> ibsCbsRates() {
        return ResponseEntity.ok(rateService.referenceList());
    }
}
