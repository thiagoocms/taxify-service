package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacyNfseDirection;
import br.com.cshub.taxify.mapper.fiscaldocument.NfseApiMapper;
import br.com.cshub.taxify.dto.fiscalxml.ServiceInvoiceDTOs;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.NfseRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Reads the stored service invoices (NFS-e) for the screen - list, period totals, one note, its XML. */
@Service
public class ServiceInvoiceQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final NfseRepository repository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final FiscalXmlFileStorage fileStorage;

    public ServiceInvoiceQueryService(NfseRepository repository, CompanyValidation companyValidation,
                                      MessageService messageService, FiscalXmlFileStorage fileStorage) {
        this.repository = repository;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
        this.fileStorage = fileStorage;
    }

    @Transactional(readOnly = true)
    public Page<ServiceInvoiceDTOs.Summary> search(ServiceInvoiceDTOs.Filter filter) {
        Company company = companyValidation.checkExist(RequestIds.parse(filter.companyId(), messageService));
        int size = filter.size() == null ? 50 : Math.min(Math.max(filter.size(), 1), MAX_PAGE_SIZE);
        PageRequest page = PageRequest.of(filter.page() == null ? 0 : Math.max(filter.page(), 0), size,
                Sort.by(Sort.Direction.DESC, "issueDate"));
        CompanyDocumentRole role = roleOf(filter.direction());
        String number = filter.numero() == null || filter.numero().isBlank() ? null : filter.numero().strip();
        if (role == null && filter.direction() != null && !filter.direction().isBlank()) {
            return Page.empty(page); // a direction that never existed matches no note, as before
        }
        return repository.search(company.getId(), IssuePeriod.start(filter.dataEmissaoInicio()),
                        IssuePeriod.end(filter.dataEmissaoFim()), role, participantPattern(filter.participant()), number,
                        Boolean.TRUE.equals(filter.somenteComRetencao()),
                        ServiceInvoiceDTOs.Filter.ONLY_SIEG.equals(filter.fonte()) ? comparisonCutoff() : null,
                        ServiceInvoiceDTOs.Filter.ONLY_ADN.equals(filter.fonte()) ? comparisonCutoff() : null,
                        DocumentSourceKey.SIEG, DocumentSourceKey.NFSE_ADN, page)
                .map(NfseApiMapper::toSummary);
    }

    /** The API's PRESTADA/TOMADA/TERCEIRO as the role of the company (null = any, or a value that never existed). */
    private static CompanyDocumentRole roleOf(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        try {
            return LegacyNfseDirection.of(direction.strip()).role();
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public ServiceInvoiceDTOs.Totals totals(ServiceInvoiceDTOs.Filter filter) {
        Company company = companyValidation.checkExist(RequestIds.parse(filter.companyId(), messageService));
        List<Nfse> notes = repository.findActiveInPeriod(company.getId(), IssuePeriod.start(filter.dataEmissaoInicio()),
                IssuePeriod.end(filter.dataEmissaoFim()));
        LocalDateTime cutoff = comparisonCutoff();
        long onlySieg = notes.stream().filter(s -> seenBefore(s, DocumentSourceKey.SIEG, cutoff)
                && NfseApiMapper.firstSeenIn(s, DocumentSourceKey.NFSE_ADN) == null).count();
        long onlyAdn = notes.stream().filter(s -> seenBefore(s, DocumentSourceKey.NFSE_ADN, cutoff)
                && NfseApiMapper.firstSeenIn(s, DocumentSourceKey.SIEG) == null).count();
        return new ServiceInvoiceDTOs.Totals(totalsOf(notes, CompanyDocumentRole.ISSUER),
                totalsOf(notes, CompanyDocumentRole.TAKER), onlySieg, onlyAdn);
    }

    private static boolean seenBefore(Nfse s, DocumentSourceKey source, LocalDateTime cutoff) {
        LocalDateTime seen = NfseApiMapper.firstSeenIn(s, source);
        return seen != null && seen.isBefore(cutoff);
    }

    @Transactional(readOnly = true)
    public ServiceInvoiceDTOs.Detail detail(UUID id) {
        return NfseApiMapper.toDetail(owned(id));
    }

    @Transactional(readOnly = true)
    public byte[] xml(UUID id) {
        Nfse invoice = owned(id);
        try {
            return fileStorage.read(invoice.getXmlFilePath());
        } catch (IOException e) {
            throw new BadRequestException("error.sieg.xml.storage.failed", messageService.getMessage("error.sieg.xml.storage.failed"));
        }
    }

    /** A note in one source and not in the other for this long counts as a difference. */
    private static LocalDateTime comparisonCutoff() {
        return LocalDateTime.now().minusHours(FiscalXmlQueryService.SOURCE_COMPARISON_HOURS);
    }

    /** An NFS-e by id - any other type, or a deleted note, answers as before (bad request). */
    private Nfse owned(UUID id) {
        Nfse invoice = repository.findFirstByIdAndDeletedIsFalse(id)
                .orElseThrow(() -> new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request")));
        companyValidation.checkExist(invoice.getCompany().getId());
        return invoice;
    }

    private static ServiceInvoiceDTOs.DirectionTotals totalsOf(List<Nfse> notes, CompanyDocumentRole role) {
        long count = 0;
        BigDecimal valor = BigDecimal.ZERO;
        BigDecimal iss = BigDecimal.ZERO;
        BigDecimal issRetido = BigDecimal.ZERO;
        BigDecimal federal = BigDecimal.ZERO;
        for (Nfse s : notes) {
            if (s.getCompanyRole() != role) {
                continue;
            }
            count++;
            valor = valor.add(orZero(s.getTotalAmount()));
            iss = iss.add(orZero(s.getIss().amount()));
            if (s.getIss().isWithheld()) {
                issRetido = issRetido.add(orZero(s.getIss().withheldAmount()));
            }
            federal = federal.add(NfseApiMapper.federalWithheld(s));
        }
        return new ServiceInvoiceDTOs.DirectionTotals(count, valor, iss, issRetido, federal, issRetido.add(federal));
    }

    private static String participantPattern(String participant) {
        if (participant == null || participant.isBlank()) {
            return null;
        }
        String digits = participant.replaceAll("\\D", "");
        boolean looksLikeDocument = !digits.isEmpty() && participant.replaceAll("[\\d./\\-\\s]", "").isEmpty();
        return "%" + (looksLikeDocument ? digits : participant.strip().toLowerCase(Locale.ROOT)) + "%";
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
