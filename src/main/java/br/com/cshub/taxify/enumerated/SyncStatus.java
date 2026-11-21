package br.com.cshub.taxify.enumerated;

/** State of an integration's background sync/import job (see IntegrationJobRunner). */
public enum SyncStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
