package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlItemDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.service.FiscalXmlRectificationService;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/integrations/xmls")
public class FiscalXmlResource {

    private final FiscalXmlIntegrationService service;
    private final FiscalXmlRectificationService rectificationService;

    @Autowired
    public FiscalXmlResource(FiscalXmlIntegrationService service, FiscalXmlRectificationService rectificationService) {
        this.service = service;
        this.rectificationService = rectificationService;
    }

    @PostMapping
    public ResponseEntity<XmlDownloadResultDTO> buscarXmls(@Valid @RequestBody XmlDownloadFilterDTO filter) {
        return ResponseEntity.ok(service.searchStoredXmls(filter));
    }

    /**
     * Manual counterpart to the scheduled sync (see FiscalXmlSyncScheduler): triggers a
     * live SIEG check/download for an explicit date window, for one company. Exists so a
     * specific period (e.g. "agosto inteiro") can be tested on demand, without editing
     * the scheduler - that one always runs against the current month for every company.
     */
    @PostMapping("/sincronizar")
    public ResponseEntity<XmlDownloadResultDTO> sincronizarComSieg(@Valid @RequestBody XmlDownloadFilterDTO filter) {
        return ResponseEntity.ok(service.syncFromSieg(filter));
    }

    /**
     * Manual import: upload a single .xml or a .zip of several, associated with
     * companyId. Goes through the exact same persistence path as the SIEG sync (parse,
     * dedupe by access key, store the file, save the document+items) - see
     * SiegXmlIntegrationService#importManual.
     */
    @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<XmlDownloadResultDTO> importarManual(
            @RequestParam String companyId,
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(service.importManual(companyId, file.getBytes()));
        } catch (IOException e) {
            throw new BadRequestException();
        }
    }

    /**
     * Rewrites this note's CFOP/NCM per the ProductFiscalRule registered for each of its
     * products and writes the result as a new file (original untouched) - see
     * FiscalXmlRectificationService for why the digital signature isn't handled here.
     */
    @PostMapping("/{documentId}/retificar")
    public ResponseEntity<FiscalXmlRectificationResultDTO> retificar(@PathVariable UUID documentId) {
        return ResponseEntity.ok(rectificationService.rectify(documentId));
    }

    /**
     * Header + items per note, for the company/period given - same shape as
     * {@link #buscarXmls}, but serves each note's rectified version instead of the
     * original whenever one exists (see FiscalXmlIntegrationService#exportXmls).
     */
    @PostMapping("/exportar")
    public ResponseEntity<List<FiscalXmlItemDTO>> exportarXmls(@Valid @RequestBody XmlDownloadFilterDTO filter) {
        return ResponseEntity.ok(service.exportXmls(filter));
    }

    /**
     * Same company/period/rectified-vs-original resolution as {@link #exportarXmls}, but
     * downloads the raw .xml files themselves bundled into a single .zip instead of JSON
     * - see FiscalXmlIntegrationService#exportXmlsZip.
     */
    @PostMapping(value = "/exportar/zip", produces = "application/zip")
    public ResponseEntity<byte[]> exportarXmlsZip(@Valid @RequestBody XmlDownloadFilterDTO filter) {
        byte[] zipBytes = service.exportXmlsZip(filter);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"notas-fiscais.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zipBytes);
    }
}
