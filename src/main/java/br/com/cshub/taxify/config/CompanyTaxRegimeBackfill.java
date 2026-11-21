package br.com.cshub.taxify.config;

import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Structures the regime of companies saved while it was only free text - parses the label
 * ("Simples Nacional - Comércio") into regime + activity - and opens the regime history of
 * companies that have a regime but no history ("since always"). Does nothing once done.
 */
@Component
public class CompanyTaxRegimeBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanyTaxRegimeBackfill.class);

    private final CompanyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final CompanyTaxRegimeService taxRegimeService;

    public CompanyTaxRegimeBackfill(CompanyRepository repository, TransactionTemplate transactionTemplate,
                                    CompanyTaxRegimeService taxRegimeService) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.taxRegimeService = taxRegimeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer total = transactionTemplate.execute(status -> {
            List<Company> companies = repository.findByTaxRegimeIsNotNullAndTaxRegimeTypeIsNull();
            companies.forEach(company -> company.applyTaxRegimeLabel(company.getTaxRegime()));
            repository.saveAll(companies);
            return companies.size();
        });
        if (total != null && total > 0) {
            log.info("Structured the tax regime of {} companies", total);
        }
        Integer opened = transactionTemplate.execute(status -> {
            List<Company> companies = repository.findWithRegimeAndNoHistory();
            companies.forEach(company -> taxRegimeService.recordChange(company, null));
            return companies.size();
        });
        if (opened != null && opened > 0) {
            log.info("Opened the regime history of {} companies", opened);
        }
    }
}
