package br.com.cshub.taxify.config;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.service.FiscalDirections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Classifies notes imported before entries/exits existed (direction null): own issue when
 * the company is the issuer, direction from that plus tpNF - same rule as a new import (see
 * FiscalDirections). Runs on startup in batches of 500, each in its own transaction, and
 * does nothing once every row is classified.
 */
@Component
public class FiscalDirectionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FiscalDirectionBackfill.class);

    private final FiscalXmlDocumentRepository repository;
    private final TransactionTemplate transactionTemplate;

    public FiscalDirectionBackfill(FiscalXmlDocumentRepository repository, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        while (true) {
            Integer done = transactionTemplate.execute(status -> classifyBatch());
            if (done == null || done == 0) {
                break;
            }
            total += done;
        }
        if (total > 0) {
            log.info("Classified {} existing fiscal notes as entries/exits", total);
        }
    }

    private int classifyBatch() {
        List<FiscalXmlDocument> batch = repository.findTop500ByDirectionIsNull();
        for (FiscalXmlDocument document : batch) {
            String companyDocument = document.getCompany().getDocument() == null ? null : document.getCompany().getDocument().getNumber();
            boolean ownIssue = FiscalDirections.isOwnIssue(companyDocument, document.getIssuerCnpj(), document.getEmitenteCpf());
            document.setOwnIssue(ownIssue);
            document.setDirection(FiscalDirections.directionOf(ownIssue, document.getTipoOperacao()));
        }
        repository.saveAll(batch);
        return batch.size();
    }
}
