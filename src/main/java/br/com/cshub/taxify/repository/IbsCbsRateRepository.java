package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IbsCbsRateRepository extends JpaRepository<IbsCbsRate, Integer> {
}
