package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FiscalXmlDocumentItemRepository extends JpaRepository<FiscalXmlDocumentItem, UUID> {

    List<FiscalXmlDocumentItem> findByDocument_Company_IdAndDocument_IssueDateBetween(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);
}
