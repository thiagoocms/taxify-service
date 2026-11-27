package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fronteira.EfiscoMemory;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.efisco.EfiscoClient;
import br.com.cshub.taxify.integration.efisco.EfiscoException;
import br.com.cshub.taxify.integration.efisco.EfiscoMemoryParser;
import br.com.cshub.taxify.repository.EfiscoMemoryRepository;
import br.com.cshub.taxify.service.FirmCertificateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * e-Fisco's "Memória de Cálculo" of a note, for the accountant to compare with Taxify's and decide: fetched with the
 * firm's accountant's certificate by the extract's recordNumber, kept, and read into steps (EfiscoMemoryParser).
 */
@Service
public class EfiscoMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EfiscoMemoryService.class);

    private final EfiscoMemoryRepository repository;
    private final FirmCertificateService certificateService;

    public EfiscoMemoryService(EfiscoMemoryRepository repository, FirmCertificateService certificateService) {
        this.repository = repository;
        this.certificateService = certificateService;
    }

    /** What the screen shows: e-Fisco's steps per item, the calculation types and the whole text. */
    public record MemoryDTO(String registro, String chaveAcesso, String regras, LocalDateTime buscadaEm,
                            List<EfiscoMemoryParser.CalculationType> tipos, List<EfiscoMemoryParser.Item> itens,
                            BigDecimal icmsTotal, String texto) {
    }

    /** The kept memória, if it was ever fetched. */
    @Transactional(readOnly = true)
    public Optional<MemoryDTO> kept(Company company, String recordNumber) {
        return repository.findByCompany_IdAndRecordNumber(company.getId(), recordNumber.strip()).map(EfiscoMemoryService::toDto);
    }

    /** Fetches it now from e-Fisco (and keeps it, replacing an older one). */
    @Transactional
    public MemoryDTO fetch(Company company, String recordNumber) {
        if (recordNumber == null || recordNumber.isBlank()) {
            throw new BadRequestException("A nota não tem registro no extrato do e-Fisco.");
        }
        if (company.getAccountingFirm() == null) {
            throw new BadRequestException("A empresa não pertence a um escritório.");
        }
        FirmCertificate certificate = certificateService.find(company.getAccountingFirm().getId())
                .orElseThrow(() -> new BadRequestException(
                        "Cadastre o certificado do contador (e-CPF) em Integrações para buscar o cálculo no e-Fisco."));
        if (certificate.getValidUntil().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("O certificado do contador está vencido.");
        }
        EfiscoClient.MemoryPage page;
        try {
            EfiscoClient client = new EfiscoClient(certificateService.open(certificate));
            client.login();
            page = client.calculationMemory(recordNumber.strip());
        } catch (EfiscoException e) {
            throw new BadRequestException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("A busca no e-Fisco foi interrompida.");
        } catch (Exception e) {
            log.warn("e-Fisco memória of registro {} (company {}) failed", recordNumber, company.getId(), e);
            throw new BadRequestException("Falha ao falar com o e-Fisco: " + e.getMessage());
        }
        if (!page.found()) {
            throw new BadRequestException(page.message());
        }
        EfiscoMemoryParser.Memory memory = EfiscoMemoryParser.parseHtml(page.html());
        EfiscoMemory entity = repository.findByCompany_IdAndRecordNumber(company.getId(), recordNumber.strip()).orElseGet(EfiscoMemory::new);
        entity.setCompany(company);
        entity.setRecordNumber(recordNumber.strip());
        entity.setAccessKey(memory.accessKey());
        entity.setText(memory.text());
        entity.setFetchedAt(LocalDateTime.now());
        entity.setDeleted(false);
        repository.save(entity);
        return toDto(entity);
    }

    private static MemoryDTO toDto(EfiscoMemory entity) {
        EfiscoMemoryParser.Memory memory = EfiscoMemoryParser.parse(entity.getText());
        return new MemoryDTO(entity.getRecordNumber(), entity.getAccessKey(), memory.rules(), entity.getFetchedAt(),
                memory.types(), memory.items(), memory.icmsTotal(), memory.text());
    }
}
