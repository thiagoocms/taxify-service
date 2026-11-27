package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.ChangeStamps;
import br.com.cshub.taxify.common.ChangeStamps.Stamp;
import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.reform.IbsCbsRateChange;
import br.com.cshub.taxify.domain.reform.IbsCbsRatePolicy;
import br.com.cshub.taxify.domain.reform.IbsCbsRateValues;
import br.com.cshub.taxify.dto.reform.IbsCbsRateChangeDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRateDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRateReferenceDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRateWarningDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRatesDTO;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.IbsCbsRateMapper;
import br.com.cshub.taxify.repository.IbsCbsRateChangeRepository;
import br.com.cshub.taxify.repository.IbsCbsRateRepository;
import br.com.cshub.taxify.repository.IbsCbsRateVersionRepository;
import br.com.cshub.taxify.validation.IbsCbsRateValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * IBS/CBS reference rates per year (spec 2026-11-27, F1a-0), on the project's CRUD base: the console's upsert
 * ({@link #save}, AL-RN-03 and AL-RN-04) with one history event per real change, the readings of the console and of
 * the firms with the warnings of AL-RN-05 (Recife clock) and the year's history. No cache: the conferência reads the
 * table at every context (AL-RN-08), so a change made on one instance is valid on the other at the next conferência.
 */
@Service
public class IbsCbsRateService extends AbstractCrudService<IbsCbsRate, Integer, IbsCbsRateDTO, Void> {

    private static final Logger log = LoggerFactory.getLogger(IbsCbsRateService.class);
    private static final String WARNING_TEXT = "reform.rate.warning.no.law";

    private final IbsCbsRateRepository rateRepository;
    private final IbsCbsRateChangeRepository changeRepository;
    private final IbsCbsRateMapper rateMapper;
    private final IbsCbsRateValidation rateValidation;
    private final ChangeStamps changeStamps;
    private final IbsCbsRateVersionRepository versionRepository;
    private final Clock clock;

    public IbsCbsRateService(IbsCbsRateRepository repository, IbsCbsRateMapper mapper, IbsCbsRateValidation validation,
                             MessageService messageService, IbsCbsRateChangeRepository changeRepository,
                             ChangeStamps changeStamps, IbsCbsRateVersionRepository versionRepository, Clock clock) {
        super(repository, mapper, validation, messageService);
        setMessageNotFound(IbsCbsRateValidation.NOT_FOUND);
        this.rateRepository = repository;
        this.changeRepository = changeRepository;
        this.rateMapper = mapper;
        this.rateValidation = validation;
        this.changeStamps = changeStamps;
        this.clock = clock;
        this.versionRepository = versionRepository;
    }

    // ------------------------------------------------------------------ console: write

    /**
     * AL-RN-04: creates the year when the body has no version, updates it otherwise. READ COMMITTED: after a duplicate
     * year (two admins creating it at once) the 409's currentVersion is the one the other admin committed.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IbsCbsRateDTO save(Integer year, IbsCbsRateDTO body) {
        rateValidation.checkYear(year);
        IbsCbsRateDTO model = body.withYear(year);
        try {
            return model.version() == null ? create(model) : update(year, model);
        } catch (ConflictException e) {
            log.info("ibs.cbs.rate.conflict year={} userId={}", year, changeStamps.now().userId());
            throw e;
        }
    }

    /** The validation stamps who created it and when (Recife) before the INSERT; the event takes the same stamp. */
    @Override
    @Transactional
    public IbsCbsRateDTO create(IbsCbsRateDTO model) {
        Stamp stamp = changeStamps.now();
        super.create(model);
        return recordChange(model.year(), null, stamp);
    }

    /**
     * The row is locked here (the one {@code FOR UPDATE} of the save) and its values kept as "before"; the validation
     * then finds the same managed row without a second query.
     */
    @Override
    @Transactional
    public IbsCbsRateDTO update(Integer year, IbsCbsRateDTO model) {
        Stamp stamp = changeStamps.now();
        IbsCbsRateValues before = rateRepository.findForUpdate(year).map(IbsCbsRate::values).orElseThrow(rateValidation::notFound);
        super.update(year, model);
        return recordChange(year, before, stamp);
    }

    /**
     * Stamps who/when and writes the event when something changed (nothing at all otherwise: no event, same version),
     * then flushes - one INSERT or UPDATE, so the answer carries the new version. No query runs between the write and
     * the flush. A duplicate year on the INSERT is two admins creating it at once: the other one committed first (409).
     */
    private IbsCbsRateDTO recordChange(Integer year, IbsCbsRateValues before, Stamp stamp) {
        IbsCbsRate saved = rateRepository.findById(year).orElseThrow(rateValidation::notFound);
        // a new year was stamped by the validation before the INSERT: the event takes that one
        String login = before == null ? saved.getChangedBy() : stamp.login();
        LocalDateTime at = before == null ? saved.getChangedAt() : stamp.at();
        IbsCbsRateChange.between(year, before, saved.values(), login, at).ifPresent(change -> {
            if (before != null) {
                saved.markChanged(login, at);
            }
            changeRepository.save(change);
            log.info("ibs.cbs.rate.saved year={} action={} statusBefore={} statusAfter={} userId={}", year, change.getAction(),
                    change.getStatusBefore(), change.getStatusAfter(), stamp.userId());
        });
        try {
            rateRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            throw rateValidation.conflict(versionRepository.committedVersion(year).orElse(null));
        }
        return rateMapper.toModel(saved);
    }

    // ------------------------------------------------------------------ readings

    /** Console: every year with version and audit, and the warnings. */
    @Transactional(readOnly = true)
    public IbsCbsRatesDTO<IbsCbsRateDTO> platformList() {
        return list(rateMapper::toModel);
    }

    /** Firms (AL-RN-06): every year and status, without version and audit, and the warnings. */
    @Transactional(readOnly = true)
    public IbsCbsRatesDTO<IbsCbsRateReferenceDTO> referenceList() {
        return list(rateMapper::toReference);
    }

    /** The year's history, most recent first; 404 when the year doesn't exist. */
    @Transactional(readOnly = true)
    public List<IbsCbsRateChangeDTO> history(Integer year) {
        rateValidation.checkExist(year);
        return changeRepository.history(year).stream().map(rateMapper::toChange).toList();
    }

    /** AL-RN-05 for the current date of Recife. */
    public List<IbsCbsRateWarningDTO> warnings(List<IbsCbsRate> years) {
        List<Integer> lawYears = years.stream().filter(IbsCbsRate::generatesFindings).map(IbsCbsRate::getYear).toList();
        return IbsCbsRatePolicy.yearsWithoutLaw(LocalDate.now(clock), lawYears).stream()
                .map(year -> new IbsCbsRateWarningDTO(year, IbsCbsRateWarningDTO.NO_LAW,
                        messageService.getMessage(WARNING_TEXT, String.valueOf(year))))
                .toList();
    }

    private <T> IbsCbsRatesDTO<T> list(Function<IbsCbsRate, T> row) {
        List<IbsCbsRate> years = rateRepository.findAllYears();
        return new IbsCbsRatesDTO<>(years.stream().map(row).toList(), warnings(years));
    }
}
