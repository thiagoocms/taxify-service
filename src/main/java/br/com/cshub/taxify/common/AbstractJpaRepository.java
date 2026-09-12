package br.com.cshub.taxify.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.io.Serializable;
import java.util.Optional;

@NoRepositoryBean
public interface AbstractJpaRepository<E, ID extends Serializable> extends JpaRepository<E, ID>, JpaSpecificationExecutor<E> {

    Optional<E> findFirstById(ID id);

    Optional<E> findFirstByIdAndDeletedIsFalse(ID id);

    Page<E> findAll(Pageable pageable);
}
