package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.assessment.FiscalPeriodClosing;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.assessment.GuideUpdateDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.FiscalPeriodClosingRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Fechamento do mês: closing freezes the month's assessment (snapshot), reopening lets it
 * follow the notes again. The guide (issued, amount, due date) and notes are kept apart
 * from the status, so they can be filled before or after closing.
 */
@Service
public class FiscalPeriodClosingService {

    private final FiscalPeriodClosingRepository repository;
    private final TaxAssessmentService assessmentService;
    private final CompanyValidation companyValidation;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;

    public FiscalPeriodClosingService(FiscalPeriodClosingRepository repository, TaxAssessmentService assessmentService,
                                      CompanyValidation companyValidation, CurrentUserService currentUserService,
                                      MessageService messageService) {
        this.repository = repository;
        this.assessmentService = assessmentService;
        this.companyValidation = companyValidation;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
    }

    @Transactional
    public TaxAssessmentDTO close(UUID companyId, String month) {
        Company company = companyValidation.checkExist(companyId);
        YearMonth period = assessmentService.parseMonth(month);
        if (period.isAfter(YearMonth.now())) {
            throw new BadRequestException(messageService.getMessage("error.closing.future"));
        }
        FiscalPeriodClosing closing = findOrCreate(company, period);
        if (closing.isClosed()) {
            throw new BadRequestException(messageService.getMessage("error.closing.already.closed"));
        }
        TaxAssessmentDTO assessment = assessmentService.calculate(company, period);
        closing.close(currentLogin(), LocalDateTime.now(), assessmentService.toJson(assessment), assessment.taxRegime(),
                assessment.revenue().net(), assessment.rbt12(), assessment.totalTaxes());
        repository.save(closing);
        return assessmentService.assess(companyId, period.toString());
    }

    @Transactional
    public TaxAssessmentDTO reopen(UUID companyId, String month) {
        Company company = companyValidation.checkExist(companyId);
        YearMonth period = assessmentService.parseMonth(month);
        repository.findFirstByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), period.atDay(1))
                .filter(FiscalPeriodClosing::isClosed)
                .ifPresent(closing -> {
                    closing.reopen();
                    repository.save(closing);
                });
        return assessmentService.assess(companyId, period.toString());
    }

    @Transactional
    public TaxAssessmentDTO updateGuide(UUID companyId, String month, GuideUpdateDTO guide) {
        Company company = companyValidation.checkExist(companyId);
        YearMonth period = assessmentService.parseMonth(month);
        FiscalPeriodClosing closing = findOrCreate(company, period);
        closing.updateGuide(guide.guideIssued(), guide.guideAmount(), guide.guideDueDate(), guide.notes());
        repository.save(closing);
        return assessmentService.assess(companyId, period.toString());
    }

    private FiscalPeriodClosing findOrCreate(Company company, YearMonth period) {
        return repository.findFirstByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), period.atDay(1))
                .orElseGet(() -> {
                    FiscalPeriodClosing created = new FiscalPeriodClosing();
                    created.setCompany(company);
                    created.setPeriod(period.atDay(1));
                    return created;
                });
    }

    private String currentLogin() {
        return currentUserService.getCurrentUser().map(User::getLogin).orElse("system");
    }
}
