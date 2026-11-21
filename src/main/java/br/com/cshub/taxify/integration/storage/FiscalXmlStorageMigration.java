package br.com.cshub.taxify.integration.storage;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;

/**
 * The move from the Docker volume to S3: with {@code fiscal-xml.storage.type=s3} and
 * {@code fiscal-xml.storage.migrate-local=true}, copies every file still on disk to the
 * bucket (same {companyId}/{file} key) and points the note at it - in the background, in
 * batches, after startup. Disk files are never deleted here; remove the volume once the
 * log says nothing is left. Safe to restart: notes already on S3 are skipped.
 */
@Component
public class FiscalXmlStorageMigration {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlStorageMigration.class);
    private static final int BATCH = 200;

    private final FiscalXmlStorageProperties properties;
    private final FiscalXmlDocumentRepository repository;
    private final FiscalXmlFileStorage storage;

    public FiscalXmlStorageMigration(FiscalXmlStorageProperties properties, FiscalXmlDocumentRepository repository,
                                     FiscalXmlFileStorage storage) {
        this.properties = properties;
        this.repository = repository;
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startInBackground() {
        if (properties.getType() != FiscalXmlStorageProperties.Type.S3 || !properties.isMigrateLocal()) {
            return;
        }
        Thread.ofVirtual().name("fiscal-xml-s3-migration").start(this::migrateAll);
    }

    void migrateAll() {
        log.info("Copying fiscal XML files from disk to S3...");
        int copied = 0;
        int missing = 0;
        int failed = 0;
        String after = "";
        while (true) {
            List<FiscalXmlDocument> batch = repository.findWithFileOutsideS3(after, PageRequest.of(0, BATCH));
            if (batch.isEmpty()) {
                break;
            }
            for (FiscalXmlDocument document : batch) {
                Result result = migrate(document);
                copied += result.copied();
                missing += result.missing();
                failed += result.failed();
            }
            after = batch.getLast().getId().toString();
            log.info("S3 migration: {} files copied so far ({} missing on disk, {} failed)", copied, missing, failed);
        }
        log.info("S3 migration finished: {} files copied, {} missing on disk (already purged), {} failed{}",
                copied, missing, failed, failed > 0 ? " - restart to retry the failed ones" : " - the volume can be removed");
    }

    private record Result(int copied, int missing, int failed) {
    }

    private Result migrate(FiscalXmlDocument document) {
        int copied = 0;
        int missing = 0;
        int failed = 0;
        boolean changed = false;
        for (boolean rectified : new boolean[]{false, true}) {
            String location = rectified ? document.getRectifiedFilePath() : document.getXmlFilePath();
            if (location == null || storage.isOnCurrentStorage(location)) {
                continue;
            }
            try {
                String moved = storage.copyToCurrentStorage(location);
                if (rectified) {
                    document.setRectifiedFilePath(moved);
                } else {
                    document.setXmlFilePath(moved);
                }
                changed = true;
                copied++;
            } catch (NoSuchFileException e) {
                missing++;
            } catch (IOException | RuntimeException e) {
                failed++;
                log.warn("Could not copy {} (note {}) to S3", location, document.getId(), e);
            }
        }
        if (changed) {
            repository.save(document);
        }
        return new Result(copied, missing, failed);
    }
}
