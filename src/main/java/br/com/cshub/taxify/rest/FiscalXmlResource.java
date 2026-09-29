package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.service.FiscalXmlQueryService;
import br.com.cshub.taxify.service.FiscalXmlRectificationService;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final FiscalXmlQueryService queryService;

    @Autowired
    public FiscalXmlResource(FiscalXmlIntegrationService service, FiscalXmlRectificationService rectificationService,
                             FiscalXmlQueryService queryService) {
        this.service = service;
        this.rectificationService = rectificationService;
        this.queryService = queryService;
    }

    /** List rows only (DB columns, no XML file read) - see FiscalXmlQueryService#search. */
    @PostMapping
    public ResponseEntity<Page<FiscalXmlSummaryDTO>> buscarXmls(@Valid @RequestBody XmlSearchFilterDTO filter) {
        return ResponseEntity.ok(queryService.search(filter));
    }

    /** Header of one note (identification, parties, totals) - no items, no raw XML. */
    @GetMapping("/{documentId}")
    public ResponseEntity<FiscalXmlDetailDTO> buscarDetalhe(@PathVariable UUID documentId) {
        return ResponseEntity.ok(queryService.findDetail(documentId));
    }

    @GetMapping("/{documentId}/produtos")
    public ResponseEntity<List<FiscalXmlProdutoDTO>> buscarProdutos(@PathVariable UUID documentId) {
        return ResponseEntity.ok(queryService.findProdutos(documentId));
    }

    /**
     * Raw XML file of one note. {@code retificado=true} serves the rectified version when
     * there is one (falls back to the original otherwise).
     */
    @GetMapping(value = "/{documentId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> baixarXml(@PathVariable UUID documentId,
                                            @RequestParam(defaultValue = "false") boolean retificado) {
        FiscalXmlQueryService.StoredXml xml = queryService.findXml(documentId, retificado);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + xml.fileName() + "\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.content());
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
