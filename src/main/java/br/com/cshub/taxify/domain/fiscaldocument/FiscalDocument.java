package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A fiscal document of one company - NF-e, NFC-e, NFS-e or CT-e - in the single table {@code fiscal_documents}
 * (FD-RN-01). The columns of a subtype accept null in the database: what is mandatory is guaranteed here, by the
 * invariants of spec 3.3, checked on insert ({@link InvariantScope#CREATE}) and on update
 * ({@link InvariantScope#UPDATE}, only the rules marked "always").
 *
 * <p>Unique per company, not globally (FD-RN-12): a document between two clients of the same firm is the issuer's
 * exit and the recipient's entry, each company keeps its own row.
 */
@Entity
@Table(name = "fiscal_documents",
        uniqueConstraints = @UniqueConstraint(name = "uk_fiscal_documents_company_key", columnNames = {"company_id", "access_key"}),
        indexes = {
                @Index(name = "idx_fd_company_type_issue", columnList = "company_id, document_type, issue_date"),
                @Index(name = "idx_fd_company_type_direction_issue", columnList = "company_id, document_type, direction, issue_date"),
                @Index(name = "idx_fd_company_type_source_created", columnList = "company_id, document_type, source_system, created_date"),
                @Index(name = "idx_fd_purge", columnList = "xml_purged_at, issue_date"),
                @Index(name = "idx_fd_type_metadata_version", columnList = "document_type, metadata_version")
        })
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "document_type", length = 10, discriminatorType = DiscriminatorType.STRING)
public abstract class FiscalDocument extends AbstractAuditingEntity {

    /** Size of the access key of NF-e, NFC-e and CT-e (the model is inside the key). */
    public static final int ACCESS_KEY_44 = 44;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, foreignKey = @ForeignKey(name = "fk_fiscal_documents_company"))
    private Company company;

    /** 44 digits (NF-e, NFC-e, CT-e), 50 (national NFS-e) or the synthetic {@code ABRASF-...} key. */
    @Column(name = "access_key", length = 80)
    private String accessKey;

    @Column(name = "model", length = 2)
    private String model;

    @Column(name = "series", length = 5)
    private String series;

    @Column(name = "number", length = 20)
    private String number;

    @Column(name = "issue_date")
    private LocalDateTime issueDate;

    @Column(name = "operation_nature", length = 60)
    private String operationNature;

    /** Entry or exit for the company; null only when the company books nothing (I-04). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "direction", length = 10)
    private FiscalDirection direction;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "company_role", length = 12, nullable = false)
    private CompanyDocumentRole companyRole;

    /** Never null (FD-RN-10): AUTHORIZED until a registered cancellation or the layout says CANCELED. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 12, nullable = false)
    private FiscalDocumentStatus status = FiscalDocumentStatus.AUTHORIZED;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "document", column = @Column(name = "issuer_document", length = 14)),
            @AttributeOverride(name = "documentType", column = @Column(name = "issuer_document_type", length = 10)),
            @AttributeOverride(name = "name", column = @Column(name = "issuer_name", length = 200)),
            @AttributeOverride(name = "uf", column = @Column(name = "issuer_uf", length = 2, columnDefinition = "char(2)")),
            @AttributeOverride(name = "cityCode", column = @Column(name = "issuer_city_code", length = 10))
    })
    private Party issuer;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "document", column = @Column(name = "recipient_document", length = 20)),
            @AttributeOverride(name = "documentType", column = @Column(name = "recipient_document_type", length = 10)),
            @AttributeOverride(name = "name", column = @Column(name = "recipient_name", length = 200)),
            @AttributeOverride(name = "uf", column = @Column(name = "recipient_uf", length = 2, columnDefinition = "char(2)")),
            @AttributeOverride(name = "cityCode", column = @Column(name = "recipient_city_code", length = 10))
    })
    private Party recipient;

    /** Note total (vNF), service value (NFS-e) or vTPrest (CT-e). */
    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Embedded
    private IbsCbsTotals ibsCbsTotals;

    @Embedded
    private Withholdings withholdings;

    /** The first origin of the document (the capture panel reads it); every origin is in {@link #getSources()}. */
    @Convert(converter = DocumentSourceKeyConverter.class)
    @Column(name = "source_system", length = DocumentSourceKey.MAX_LENGTH, nullable = false)
    private DocumentSourceKey sourceSystem;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<FiscalDocumentSource> sources = new LinkedHashSet<>();

    @Column(name = "xml_file_path", length = 1000)
    private String xmlFilePath;

    /** When the XML file was purged (FD-RN-16) - the document stays, only the file goes. */
    @Column(name = "xml_purged_at")
    private LocalDateTime xmlPurgedAt;

    /** Version of the parser that filled the parsed fields; 0 = never parsed by a versioned parser. */
    @Column(name = "metadata_version", nullable = false)
    private Integer metadataVersion = 0;

    @Embedded
    private DocumentReview review;

    protected FiscalDocument() {
    }

    protected FiscalDocument(Company company, DocumentSourceKey sourceSystem) {
        this.company = company;
        this.sourceSystem = sourceSystem;
    }

    /** The discriminator of the subtype. */
    public abstract FiscalDocumentType getDocumentType();

    /**
     * The text of the type in the natural keys already stored (FD-RN-21): {@code "NFE"} for NF-e and NFC-e,
     * {@code "NFSE"}, {@code "CTE"}. Not the discriminator - an NFC-e is {@code "NFE"} in the keys of today.
     */
    public abstract String legacyKeyLabel();

    // ---------------------------------------------------------------- behaviour

    /** Sets who the company is in the document and how it books it (FD-RN-08); checked by I-04. */
    public void assignRole(CompanyDocumentRole role, FiscalDirection direction) {
        this.companyRole = role;
        this.direction = direction;
    }

    public boolean isOwnIssue() {
        return companyRole == CompanyDocumentRole.ISSUER;
    }

    public boolean isCanceled() {
        return status == FiscalDocumentStatus.CANCELED;
    }

    /**
     * FD-RN-10: CANCELED with the time of the cancellation. A document already canceled stays as it is (the first
     * cancellation wins, as the event service of today). {@code at} may be null only where the layout brings no date
     * (NFS-e, or rows copied from the old model) - a new document of another type is refused by I-03.
     *
     * @return whether the document changed
     */
    public boolean cancel(LocalDateTime at) {
        if (isCanceled()) {
            return false;
        }
        this.status = FiscalDocumentStatus.CANCELED;
        this.canceledAt = at;
        return true;
    }

    /** Records the conferência; back to PENDING clears who and when (same rule as the old model). */
    public void review(ReviewStatus reviewStatus, String by, String note, LocalDateTime at) {
        this.review = DocumentReview.of(reviewStatus, by, note, at);
    }

    /** The review of a stored document, copied as it is (no normalization of the note). */
    void restoreReview(DocumentReview stored) {
        this.review = stored;
    }

    /** PENDING when never reviewed. */
    public ReviewStatus getReviewStatus() {
        return review == null || review.status() == null ? ReviewStatus.PENDING : review.status();
    }

    /**
     * FD-RN-16: the XML file was deleted at {@code at}. Idempotent - the first mark wins.
     *
     * @return whether the mark was written now
     */
    public boolean markXmlPurged(LocalDateTime at) {
        Objects.requireNonNull(at, "at");
        if (xmlPurgedAt != null) {
            return false;
        }
        this.xmlPurgedAt = at;
        return true;
    }

    public boolean isXmlPurged() {
        return xmlPurgedAt != null;
    }

    /**
     * FD-RN-31: {@code source} delivered the document at {@code at} - creates the origin with
     * {@code first_seen_at = last_seen_at = at}, or moves {@code last_seen_at} forward (never back).
     */
    public FiscalDocumentSource markSeenIn(DocumentSourceKey source, LocalDateTime at) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
        Optional<FiscalDocumentSource> existing = sourceOf(source);
        if (existing.isPresent()) {
            existing.get().seenAgain(at);
            return existing.get();
        }
        FiscalDocumentSource created = new FiscalDocumentSource(this, source, at, at);
        sources.add(created);
        return created;
    }

    public Optional<FiscalDocumentSource> sourceOf(DocumentSourceKey source) {
        return sources.stream().filter(s -> s.getSource().equals(source)).findFirst();
    }

    /** When {@code source} first delivered the document - what the API shows as {@code foundIn*At}. */
    public Optional<LocalDateTime> firstSeenIn(DocumentSourceKey source) {
        return sourceOf(source).map(FiscalDocumentSource::getFirstSeenAt);
    }

    /** The access key; a document without one (manual upload of an odd file) is identified by its id. */
    public String accessKeyOrId() {
        return hasText(accessKey) ? accessKey : String.valueOf(id);
    }

    /** Copies the auditing columns of the row this document is a copy of (the old tables), unchanged. */
    public void copyAuditingFrom(AbstractAuditingEntity source) {
        restoreAuditingFrom(source);
    }

    // ---------------------------------------------------------------- invariants

    /** Checks the invariants of {@code scope}; throws {@link FiscalDocumentInvariantException} on the first broken one. */
    public final void verifyInvariants(InvariantScope scope) {
        checkInvariants(scope);
    }

    @PrePersist
    void beforeInsert() {
        checkInvariants(InvariantScope.CREATE);
    }

    @PreUpdate
    void beforeUpdate() {
        checkInvariants(InvariantScope.UPDATE);
    }

    /** The common invariants (I-01 to I-09). Subtypes add theirs and call this first. */
    protected void checkInvariants(InvariantScope scope) {
        require(FiscalDocumentInvariant.I01_MANDATORY_FIELDS, scope, hasMandatoryFields());
        require(FiscalDocumentInvariant.I02_AUTHORIZED_WITHOUT_CANCELED_AT, scope,
                status != FiscalDocumentStatus.AUTHORIZED || canceledAt == null);
        require(FiscalDocumentInvariant.I03_CANCELED_WITH_CANCELED_AT, scope,
                !isCanceled() || canceledAt != null || !cancellationRequiresDate());
        require(FiscalDocumentInvariant.I04_DIRECTION_MATCHES_ROLE, scope, directionMatchesRole());
        require(FiscalDocumentInvariant.I05_ISSUER_ROLE_MATCHES_COMPANY, scope, issuerRoleMatchesCompany());
        require(FiscalDocumentInvariant.I06_PARTY_DOCUMENT_TYPE, scope,
                parties().filter(Objects::nonNull).allMatch(PartyIdentity::hasCoherentDocument));
        require(FiscalDocumentInvariant.I07_ACCESS_KEY, scope, hasText(accessKey) || !requiresAccessKey());
        require(FiscalDocumentInvariant.I08_ISSUE_DATE, scope, issueDate != null);
        require(FiscalDocumentInvariant.I09_XML_FILE_PATH, scope,
                xmlPurgedAt != null || hasText(xmlFilePath) || !requiresXmlFile());
    }

    protected final void require(FiscalDocumentInvariant invariant, InvariantScope scope, boolean holds) {
        if (invariant.appliesTo(scope) && !holds) {
            throw new FiscalDocumentInvariantException(invariant, getDocumentType());
        }
    }

    /** I-03: whether a canceled document needs the time of the cancellation (the NFS-e layout has none). */
    protected boolean cancellationRequiresDate() {
        return true;
    }

    /** I-04: direction null exactly when the role books nothing. */
    protected boolean directionMatchesRole() {
        return (direction == null) == companyRole.isWithoutBooking();
    }

    /** I-07: whether the document needs an access key. */
    protected boolean requiresAccessKey() {
        return true;
    }

    /** I-09: whether the document needs an XML file while it is not purged. */
    protected boolean requiresXmlFile() {
        return true;
    }

    /** The parties checked by I-06. */
    protected Stream<? extends PartyIdentity> parties() {
        return Stream.of(issuer, recipient);
    }

    private boolean hasMandatoryFields() {
        return company != null && getDocumentType() != null && sourceSystem != null && status != null
                && companyRole != null && metadataVersion != null;
    }

    /** I-05: ISSUER iff the issuer document is the company CNPJ (14 digits), when both exist. */
    private boolean issuerRoleMatchesCompany() {
        String companyCnpj = companyCnpj();
        String issuerDocument = issuer == null ? null : issuer.document();
        if (companyCnpj == null || !hasText(issuerDocument)) {
            return true;
        }
        return (companyRole == CompanyDocumentRole.ISSUER) == companyCnpj.equals(digits(issuerDocument));
    }

    private String companyCnpj() {
        if (company == null || company.getDocument() == null || company.getDocument().getNumber() == null) {
            return null;
        }
        String cnpj = digits(company.getDocument().getNumber());
        return cnpj.length() == 14 ? cnpj : null;
    }

    protected static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    protected static boolean isDigitsOfLength(String value, int length) {
        return value != null && value.length() == length && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static String digits(String value) {
        return value.replaceAll("\\D", "");
    }

    // ---------------------------------------------------------------- accessors

    public UUID getId() {
        return id;
    }

    /** Ids are kept when copying from the old tables (FD-RN-20); new documents get a generated one. */
    public void setId(UUID id) {
        this.id = id;
    }

    public Company getCompany() {
        return company;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSeries() {
        return series;
    }

    public void setSeries(String series) {
        this.series = series;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDateTime getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDateTime issueDate) {
        this.issueDate = issueDate;
    }

    public String getOperationNature() {
        return operationNature;
    }

    public void setOperationNature(String operationNature) {
        this.operationNature = operationNature;
    }

    public FiscalDirection getDirection() {
        return direction;
    }

    public CompanyDocumentRole getCompanyRole() {
        return companyRole;
    }

    public FiscalDocumentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCanceledAt() {
        return canceledAt;
    }

    public Party getIssuer() {
        return issuer;
    }

    public void setIssuer(Party issuer) {
        this.issuer = issuer;
    }

    public Party getRecipient() {
        return recipient;
    }

    public void setRecipient(Party recipient) {
        this.recipient = recipient;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = FiscalDecimals.money(totalAmount);
    }

    public IbsCbsTotals getIbsCbsTotals() {
        return ibsCbsTotals;
    }

    public void setIbsCbsTotals(IbsCbsTotals ibsCbsTotals) {
        this.ibsCbsTotals = ibsCbsTotals;
    }

    public Withholdings getWithholdings() {
        return withholdings;
    }

    public void setWithholdings(Withholdings withholdings) {
        this.withholdings = withholdings;
    }

    public DocumentSourceKey getSourceSystem() {
        return sourceSystem;
    }

    public Set<FiscalDocumentSource> getSources() {
        return Collections.unmodifiableSet(sources);
    }

    public String getXmlFilePath() {
        return xmlFilePath;
    }

    public void setXmlFilePath(String xmlFilePath) {
        this.xmlFilePath = xmlFilePath;
    }

    public LocalDateTime getXmlPurgedAt() {
        return xmlPurgedAt;
    }

    public Integer getMetadataVersion() {
        return metadataVersion;
    }

    public void setMetadataVersion(Integer metadataVersion) {
        this.metadataVersion = metadataVersion;
    }

    public DocumentReview getReview() {
        return review;
    }
}
