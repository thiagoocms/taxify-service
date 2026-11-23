package br.com.cshub.taxify.rest;

import org.springframework.web.bind.annotation.PutMapping;
import br.com.cshub.taxify.service.FiscalXmlReviewService;
import br.com.cshub.taxify.dto.fiscalxml.ReviewRequestDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEventDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotalsDTO;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
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
import br.com.cshub.taxify.service.DanfePdfService;
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
import br.com.cshub.taxify.dto.fiscalxml.ManifestationDTOs;
import br.com.cshub.taxify.service.SefazManifestationScreenService;

@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/integrations/xmls")
public class FiscalXmlResource {

    private final FiscalXmlIntegrationService service;
    private final FiscalXmlRectificationService rectificationService;
    private final FiscalXmlQueryService queryService;
    private final DanfePdfService danfePdfService;
    private final FiscalXmlReviewService reviewService;
    private final SefazManifestationScreenService manifestationService;

    @Autowired
    public FiscalXmlResource(FiscalXmlIntegrationService service, FiscalXmlRectificationService rectificationService,
                             FiscalXmlQueryService queryService, DanfePdfService danfePdfService,
                             FiscalXmlReviewService reviewService, SefazManifestationScreenService manifestationService) {
        this.manifestationService = manifestationService;
        this.reviewService = reviewService;
        this.service = service;
        this.rectificationService = rectificationService;
        this.queryService = queryService;
        this.danfePdfService = danfePdfService;
    }

    /** Entries that still owe a conclusive manifestation (confirmation, unknown or not performed), nearest deadline first. */
    @GetMapping("/manifestacao")
    public ResponseEntity<ManifestationDTOs.Pending> manifestacaoPendente(@RequestParam String companyId) {
        return ResponseEntity.ok(manifestationService.pending(companyId));
    }

    /** Sends one manifestation for several notes (signed with the company's A1) and returns SEFAZ's answer per note. */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/manifestacao")
    public ResponseEntity<ManifestationDTOs.Result> manifestar(@Valid @RequestBody ManifestationDTOs.Request request) {
        return ResponseEntity.ok(manifestationService.send(request));
    }

    /** List rows only (DB columns, no XML file read) - see FiscalXmlQueryService#search. */
    @PostMapping
    public ResponseEntity<Page<FiscalXmlSummaryDTO>> buscarXmls(@Valid @RequestBody XmlSearchFilterDTO filter) {
        return ResponseEntity.ok(queryService.search(filter));
    }

    /** Entries and exits of the period, with the main taxes - the cards above the list. */
    @PostMapping("/totais")
    public ResponseEntity<FiscalXmlTotalsDTO> totais(@Valid @RequestBody XmlSearchFilterDTO filter) {
        return ResponseEntity.ok(queryService.totals(filter));
    }

    /** Header of one note (identification, parties, totals) - no items, no raw XML. */
    @GetMapping("/{documentId}")
    public ResponseEntity<FiscalXmlDetailDTO> buscarDetalhe(@PathVariable UUID documentId) {
        return ResponseEntity.ok(queryService.findDetail(documentId));
    }

    /** Conferência of one note: REVIEWED, DIVERGENT (with a note) or back to PENDING. */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PutMapping("/{documentId}/conferencia")
    public ResponseEntity<FiscalXmlDetailDTO> conferir(@PathVariable UUID documentId, @Valid @RequestBody ReviewRequestDTO request) {
        reviewService.review(List.of(documentId), request.status(), request.note());
        return ResponseEntity.ok(queryService.findDetail(documentId));
    }

    /** Same status for several notes at once (e.g. every entry of the page checked). */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/conferencia")
    public ResponseEntity<Integer> conferirVarias(@Valid @RequestBody ReviewRequestDTO request) {
        return ResponseEntity.ok(reviewService.review(request.documentIds(), request.status(), request.note()));
    }

    /** SEFAZ events of the note (cancelamento, carta de correção, manifestação). */
    @GetMapping("/{documentId}/eventos")
    public ResponseEntity<List<FiscalXmlEventDTO>> buscarEventos(@PathVariable UUID documentId) {
        return ResponseEntity.ok(queryService.findEvents(documentId));
    }

    @GetMapping("/{documentId}/produtos")
    public ResponseEntity<List<FiscalXmlProdutoDTO>> buscarProdutos(@PathVariable UUID documentId) {
        return ResponseEntity.ok(queryService.findProdutosAsRectified(documentId));
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

    /** DANFE (PDF) of one note, rendered from its stored XML - see DanfePdfService. */
    @GetMapping(value = "/{documentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> baixarPdf(@PathVariable UUID documentId) {
        DanfePdfService.DanfePdf pdf = danfePdfService.generate(documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.content());
    }

    /**
     * Manual counterpart to the scheduled sync (see FiscalXmlSyncScheduler): triggers a
     * live SIEG check/download for an explicit date window, for one company. Exists so a
     * specific period (e.g. "agosto inteiro") can be tested on demand, without editing
     * the scheduler - that one always runs against the current month for every company.
     */
    @RequiresPermission(Permission.INVOICES_SYNC)
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
    @RequiresPermission(Permission.INVOICES_SYNC)
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
    @RequiresPermission(Permission.INVOICES_RECTIFY)
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
