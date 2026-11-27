package br.com.cshub.taxify.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateKeysTest {

    @Test
    void mysql1062AnywhereInTheCausesIsADuplicateKey() {
        DataIntegrityViolationException wrapped = new DataIntegrityViolationException("dup",
                new RuntimeException(new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062)));
        assertThat(DuplicateKeys.isDuplicateKey(wrapped)).isTrue();
    }

    @Test
    void springsDuplicateKeyExceptionIsADuplicateKey() {
        assertThat(DuplicateKeys.isDuplicateKey(new DuplicateKeyException("dup"))).isTrue();
    }

    @Test
    void anotherConstraintIsNot() {
        DataIntegrityViolationException fk = new DataIntegrityViolationException("fk",
                new SQLIntegrityConstraintViolationException("Cannot add or update a child row", "23000", 1452));
        assertThat(DuplicateKeys.isDuplicateKey(fk)).isFalse();
        assertThat(DuplicateKeys.isDuplicateKey(null)).isFalse();
    }
}
