package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.CompanyTaxRegime;
import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Drops a company's cached assessment ({@link TaxAssessmentCache}) whenever something the calculation reads is
 * written through Hibernate - a note, an item, the company or its regime history (soft deletes are updates).
 * Evicts right away and again after the commit, so a calculation running in between can't keep the old numbers.
 */
@Component
public class TaxAssessmentCacheInvalidator implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(TaxAssessmentCacheInvalidator.class);

    private final EntityManagerFactory entityManagerFactory;
    private final TaxAssessmentCache cache;

    public TaxAssessmentCacheInvalidator(EntityManagerFactory entityManagerFactory, TaxAssessmentCache cache) {
        this.entityManagerFactory = entityManagerFactory;
        this.cache = cache;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        changed(event.getEntity());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        changed(event.getEntity());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        changed(event.getEntity());
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    void changed(Object entity) {
        // the goods invoices and CT-e (what the old fiscal XML table held), their items, the company and its regime
        if (!(entity instanceof GoodsInvoice || entity instanceof Cte || entity instanceof FiscalDocumentItem
                || entity instanceof Company || entity instanceof CompanyTaxRegime)) {
            return;
        }
        UUID companyId;
        try {
            companyId = companyOf(entity);
        } catch (RuntimeException e) {
            log.debug("Could not tell the company of a changed {} - dropping the whole assessment cache", entity.getClass(), e);
            companyId = null;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(companyId);
            return;
        }
        // one after-commit pass per transaction (a sync writes thousands of items), with every company it touched
        @SuppressWarnings("unchecked")
        Set<UUID> touched = (Set<UUID>) TransactionSynchronizationManager.getResource(TOUCHED_KEY);
        if (touched == null) {
            Set<UUID> companies = new HashSet<>();
            touched = companies;
            TransactionSynchronizationManager.bindResource(TOUCHED_KEY, companies);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TOUCHED_KEY);
                    companies.forEach(TaxAssessmentCacheInvalidator.this::evict);
                }
            });
        }
        if (touched.add(companyId)) {
            evict(companyId);
        }
    }

    /** Key of the per-transaction set of touched companies ({@code null} inside = "unknown, drop everything"). */
    private final Object TOUCHED_KEY = new Object();

    private void evict(UUID companyId) {
        if (companyId == null) {
            cache.evictAll();
        } else {
            cache.evictCompany(companyId);
        }
    }

    private static UUID companyOf(Object entity) {
        Company company = switch (entity) {
            case FiscalDocument document -> document.getCompany();
            case FiscalDocumentItem item -> item.getDocument() == null ? null : item.getDocument().getCompany();
            case CompanyTaxRegime regime -> regime.getCompany();
            case Company c -> c;
            default -> null;
        };
        return company == null ? null : company.getId();
    }
}
