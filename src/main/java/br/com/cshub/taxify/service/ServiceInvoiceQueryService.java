package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.ServiceInvoice;
import br.com.cshub.taxify.dto.fiscalxml.ServiceInvoiceDTOs;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.ServiceInvoiceRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Reads the stored service invoices (NFS-e) for the screen - list, period totals, one note, its XML. */
@Service
public class ServiceInvoiceQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ServiceInvoiceRepository repository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final FiscalXmlFileStorage fileStorage;

    public ServiceInvoiceQueryService(ServiceInvoiceRepository repository, CompanyValidation companyValidation,
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
        String direction = filter.direction() == null || filter.direction().isBlank() ? null : filter.direction().strip();
        String numero = filter.numero() == null || filter.numero().isBlank() ? null : filter.numero().strip();
        return repository.search(company.getId(), IssuePeriod.start(filter.dataEmissaoInicio()),
                        IssuePeriod.end(filter.dataEmissaoFim()), direction, participantPattern(filter.participant()), numero,
                        Boolean.TRUE.equals(filter.somenteComRetencao()), page)
                .map(ServiceInvoiceQueryService::toSummary);
    }

    @Transactional(readOnly = true)
    public ServiceInvoiceDTOs.Totals totals(ServiceInvoiceDTOs.Filter filter) {
        Company company = companyValidation.checkExist(RequestIds.parse(filter.companyId(), messageService));
        List<ServiceInvoice> notes = repository.findActiveInPeriod(company.getId(), IssuePeriod.start(filter.dataEmissaoInicio()),
                IssuePeriod.end(filter.dataEmissaoFim()));
        return new ServiceInvoiceDTOs.Totals(totalsOf(notes, ServiceInvoice.PRESTADA), totalsOf(notes, ServiceInvoice.TOMADA));
    }

    @Transactional(readOnly = true)
    public ServiceInvoiceDTOs.Detail detail(UUID id) {
        ServiceInvoice s = owned(id);
        return new ServiceInvoiceDTOs.Detail(s.getId(), s.getCompany().getId(), s.getAccessKey(), s.getLayout(), s.getNumero(),
                s.getCodigoVerificacao(), s.getIssueDate(), s.getCompetence(), s.getDirection(), s.getPrestadorDocumento(),
                s.getPrestadorNome(), s.getPrestadorMunicipio(), s.getTomadorDocumento(), s.getTomadorNome(),
                s.getMunicipioIncidencia(), s.getCodigoServico(), s.getCodigoTributacaoMunicipal(), s.getDescricaoServico(),
                s.getValorServico(), s.getValorDeducoes(), s.getBaseCalculo(), s.getAliquotaIss(), s.getValorIss(),
                s.isIssRetido(), s.getValorIssRetido(), s.getValorIrrf(), s.getValorPis(), s.getValorCofins(),
                s.isPisCofinsRetidos(), s.getValorCsll(), s.getValorInss(), s.getValorLiquido(), totalRetido(s),
                s.isCanceled(), s.getSourceSystem());
    }

    @Transactional(readOnly = true)
    public byte[] xml(UUID id) {
        ServiceInvoice invoice = owned(id);
        try {
            return fileStorage.read(invoice.getXmlFilePath());
        } catch (IOException e) {
            throw new BadRequestException(messageService.getMessage("error.sieg.xml.storage.failed"));
        }
    }

    /** ISS retained + IRRF + CSLL + INSS + PIS/COFINS when retained. */
    public static BigDecimal totalRetido(ServiceInvoice s) {
        BigDecimal total = BigDecimal.ZERO;
        if (s.isIssRetido()) {
            total = total.add(orZero(s.getValorIssRetido()));
        }
        return total.add(federal(s));
    }

    /** IRRF + CSLL + INSS + PIS/COFINS when retained. */
    public static BigDecimal federal(ServiceInvoice s) {
        BigDecimal total = orZero(s.getValorIrrf()).add(orZero(s.getValorCsll())).add(orZero(s.getValorInss()));
        if (s.isPisCofinsRetidos()) {
            total = total.add(orZero(s.getValorPis())).add(orZero(s.getValorCofins()));
        }
        return total;
    }

    private ServiceInvoice owned(UUID id) {
        ServiceInvoice invoice = repository.findById(id).filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.generic.bad.request")));
        companyValidation.checkExist(invoice.getCompany().getId());
        return invoice;
    }

    private static ServiceInvoiceDTOs.DirectionTotals totalsOf(List<ServiceInvoice> notes, String direction) {
        long count = 0;
        BigDecimal valor = BigDecimal.ZERO;
        BigDecimal iss = BigDecimal.ZERO;
        BigDecimal issRetido = BigDecimal.ZERO;
        BigDecimal federal = BigDecimal.ZERO;
        for (ServiceInvoice s : notes) {
            if (!direction.equals(s.getDirection())) {
                continue;
            }
            count++;
            valor = valor.add(orZero(s.getValorServico()));
            iss = iss.add(orZero(s.getValorIss()));
            if (s.isIssRetido()) {
                issRetido = issRetido.add(orZero(s.getValorIssRetido()));
            }
            federal = federal.add(federal(s));
        }
        return new ServiceInvoiceDTOs.DirectionTotals(count, valor, iss, issRetido, federal, issRetido.add(federal));
    }

    private static ServiceInvoiceDTOs.Summary toSummary(ServiceInvoice s) {
        String description = s.getDescricaoServico();
        if (description != null && description.length() > 140) {
            description = description.substring(0, 140) + "…";
        }
        return new ServiceInvoiceDTOs.Summary(s.getId(), s.getNumero(), s.getLayout(), s.getIssueDate(), s.getCompetence(),
                s.getDirection(), s.getPrestadorNome(), s.getPrestadorDocumento(), s.getTomadorNome(), s.getTomadorDocumento(),
                description, s.getValorServico(), s.getValorIss(), s.isIssRetido(), totalRetido(s), s.isCanceled(),
                s.getSourceSystem());
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
