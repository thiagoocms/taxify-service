package br.com.cshub.taxify.service.job;

/**
 * How a background job reports progress ("45 of 200 companies") - thread-safe, cheap to
 * call per item; the runner decides how often it actually hits the database.
 */
public interface SyncProgress {

    SyncProgress NONE = new SyncProgress() {
        @Override
        public void total(int total) {
        }

        @Override
        public void advance() {
        }
    };

    void total(int total);

    void advance();
}
