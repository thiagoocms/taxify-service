package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.ChangeStamps;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.reform.IbsCbsRatePolicy;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.mapper.IbsCbsRateMapper;
import br.com.cshub.taxify.repository.IbsCbsRateRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The console's IBS/CBS rates per year (spec 2026-11-27, F1a-0): AL-RN-03 (the content, by {@link IbsCbsRatePolicy})
 * and AL-RN-04 (create only a new year, update only with the stored {@code version}), each with its error code.
 */
@Component
public class IbsCbsRateValidation extends AbstractCrudValidation<IbsCbsRate, Integer> {

    public static final String NOT_FOUND = "error.reform.rate.not.found";
    static final String YEAR_INVALID = "error.reform.rate.year.invalid";
    static final String RATE_INVALID = "error.reform.rate.invalid";
    static final String BASIS_REQUIRED = "error.reform.rate.basis.required";
    static final String BASIS_INVALID = "error.reform.rate.basis.invalid";
    static final String STATUS_INVALID = "error.reform.rate.status.invalid";
    public static final String CONFLICT = "error.reform.rate.conflict";

    private final IbsCbsRateRepository rateRepository;
    private final IbsCbsRateMapper mapper;
    private final ChangeStamps changeStamps;

    public IbsCbsRateValidation(IbsCbsRateRepository repository, IbsCbsRateMapper mapper, MessageService messageService,
                                ChangeStamps changeStamps) {
        super(repository, messageService);
        setMessageNotFound(NOT_FOUND);
        this.rateRepository = repository;
        this.mapper = mapper;
        this.changeStamps = changeStamps;
    }

    /** AL-RN-03: 2026 to 2040 - checked before anything else, on create and on update. */
    public void checkYear(Integer year) {
        if (year == null || year < IbsCbsRatePolicy.MIN_YEAR || year > IbsCbsRatePolicy.MAX_YEAR) {
            throw badRequest(YEAR_INVALID, Map.of());
        }
    }

    /**
     * Creation: body without version, and the year must not exist yet (409 with currentVersion). Who created it and
     * when (Recife) are stamped here, before the INSERT, so the new year is written once with version 0.
     */
    @Override
    protected void checkOwnerFieldsToCreate(IbsCbsRate entity) {
        checkYear(entity.getYear());
        entity.setDeleted(false);
        ChangeStamps.Stamp stamp = changeStamps.now();
        entity.markChanged(stamp.login(), stamp.at());
        rateRepository.findFirstById(entity.getYear()).ifPresent(existing -> {
            throw conflict(existing.getVersion());
        });
    }

    /**
     * Update: the stored row with the version the client read. The service locked it ({@code findForUpdate}) before
     * calling the CRUD base, so {@code findById} gets the same managed row without a second query.
     */
    @Override
    protected IbsCbsRate checkUpdateConsistence(Integer year, IbsCbsRate toUpdateEntity) {
        checkYear(year);
        if (!Objects.equals(year, toUpdateEntity.getYear())) {
            throw badRequest(BadRequestException.DEFAULT_CODE, Map.of());
        }
        IbsCbsRate persisted = rateRepository.findById(year).filter(found -> !found.isDeleted()).orElseThrow(this::notFound);
        if (!Objects.equals(toUpdateEntity.getVersion(), persisted.getVersion())) {
            throw conflict(persisted.getVersion());
        }
        mapper.merge(toUpdateEntity, persisted);
        return persisted;
    }

    /** AL-RN-03 on the content: status, rates, legal basis and link. */
    @Override
    protected void checkMandatoryFields(IbsCbsRate entity) {
        IbsCbsRatePolicy.check(entity.getYear(), entity.values()).ifPresent(violation -> {
            throw switch (violation.kind()) {
                case YEAR_INVALID -> badRequest(YEAR_INVALID, Map.of());
                case STATUS_INVALID -> badRequest(STATUS_INVALID, Map.of());
                case RATE_INVALID -> badRequest(RATE_INVALID, Map.of("invalidField", violation.field()));
                case BASIS_REQUIRED -> badRequest(BASIS_REQUIRED, Map.of());
                case BASIS_INVALID -> badRequest(BASIS_INVALID, Map.of());
            };
        });
    }

    /** 409 with the committed version at the top of the body. */
    public ConflictException conflict(Integer currentVersion) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("currentVersion", currentVersion);
        return new ConflictException(CONFLICT, messageService.getMessage(CONFLICT), extras);
    }

    public ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(NOT_FOUND, messageService.getMessage(NOT_FOUND));
    }

    private BadRequestException badRequest(String code, Map<String, Object> extras) {
        return new BadRequestException(code, messageService.getMessage(code), extras);
    }
}
