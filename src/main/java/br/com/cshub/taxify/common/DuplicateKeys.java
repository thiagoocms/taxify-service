package br.com.cshub.taxify.common;

import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

/** Tells a unique key violation (MySQL error 1062, "Duplicate entry") apart from any other database failure. */
public final class DuplicateKeys {

    /** MySQL ER_DUP_ENTRY. */
    static final int MYSQL_DUPLICATE_KEY = 1062;

    private DuplicateKeys() {
    }

    /** True when the error, or any of its causes, is a duplicate key - Spring's translation or the MySQL code itself. */
    public static boolean isDuplicateKey(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_KEY) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
