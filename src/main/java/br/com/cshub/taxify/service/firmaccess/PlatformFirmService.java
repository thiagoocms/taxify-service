package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionEvent;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionSnapshot;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.firmaccess.FirmAccessActionDTO;
import br.com.cshub.taxify.dto.firmaccess.FirmAdminContactDTO;
import br.com.cshub.taxify.dto.firmaccess.FirmContactUpdateDTO;
import br.com.cshub.taxify.dto.firmaccess.FirmEventDTO;
import br.com.cshub.taxify.dto.firmaccess.PlatformFirmDTO;
import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.enumerated.SubscriptionSource;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.mapper.FirmEventMapper;
import br.com.cshub.taxify.mapper.PlatformFirmMapper;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionEventRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.validation.PlatformFirmValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O console da plataforma (F1-RN-14, F1-RN-15): a lista de escritórios com a situação, as contagens e o contato dos
 * ADMIN, e as ações de acesso e revisão. É o único ponto que cruza escritórios: consultas próprias, agregadas por
 * escritório, fora dos CrudService com escopo de tenant - e nunca leem nome, CNPJ ou dado fiscal das empresas
 * clientes, nem colaboradores que não sejam ADMIN. Só o admin da plataforma chega aqui (@PlatformAdminOnly).
 */
@Service
public class PlatformFirmService {

    private static final Logger log = LoggerFactory.getLogger(PlatformFirmService.class);
    /** users.last_modified_by / firm_subscriptions.reviewed_by. */
    private static final int ACTOR_MAX = 50;

    private final AccountingFirmRepository firmRepository;
    private final FirmSubscriptionRepository subscriptionRepository;
    private final FirmSubscriptionEventRepository eventRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final FirmAccessService accessService;
    private final FirmEventService eventService;
    private final CurrentUserService currentUserService;
    private final SaasProperties properties;
    private final MessageService messageService;
    private final BlockedPeriodRecoveryService recoveryService;
    private final PlatformFirmValidation validation;
    private final PlatformFirmMapper mapper;
    private final FirmEventMapper eventMapper;

    public PlatformFirmService(AccountingFirmRepository firmRepository, FirmSubscriptionRepository subscriptionRepository,
                               FirmSubscriptionEventRepository eventRepository, CompanyRepository companyRepository,
                               UserRepository userRepository, FirmAccessService accessService, FirmEventService eventService,
                               CurrentUserService currentUserService, SaasProperties properties, MessageService messageService,
                               BlockedPeriodRecoveryService recoveryService, PlatformFirmValidation validation,
                               PlatformFirmMapper mapper, FirmEventMapper eventMapper) {
        this.firmRepository = firmRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.eventRepository = eventRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
        this.eventService = eventService;
        this.currentUserService = currentUserService;
        this.properties = properties;
        this.messageService = messageService;
        this.recoveryService = recoveryService;
        this.validation = validation;
        this.mapper = mapper;
        this.eventMapper = eventMapper;
    }

    private record Item(AccountingFirm firm, FirmSubscription subscription, FirmAccess access) {
    }

    /**
     * @param status     situação efetiva ({@code ?status=}, TRIAL, BLOCKED...) - um TRIAL vencido aparece como BLOCKED
     * @param unreviewed só os liberados na migração ainda não revisados
     * @param search     parte do nome ou do CNPJ do escritório
     */
    @Transactional(readOnly = true)
    public Page<PlatformFirmDTO> list(String status, boolean unreviewed, String search, int page, int size) {
        FirmSubscriptionStatus wanted = validation.checkStatus(status);
        int pageSize = Math.min(Math.max(size, 1), 200);
        int pageNumber = Math.max(page, 0);
        String term = null;
        String digits = null;
        if (search != null && !search.isBlank()) {
            String stripped = search.strip();
            term = "%" + escapeLike(stripped.toLowerCase(Locale.ROOT)) + "%";
            String onlyDigits = stripped.replaceAll("\\D", "");
            digits = onlyDigits.isEmpty() ? null : "%" + onlyDigits + "%";
        }
        List<AccountingFirm> firms = firmRepository.searchForConsole(term, digits);

        Map<UUID, FirmSubscription> subscriptions = subscriptionsOf(firms.stream().map(AccountingFirm::getId).toList());
        List<Item> matching = new ArrayList<>();
        for (AccountingFirm firm : firms) {
            FirmSubscription subscription = subscriptions.get(firm.getId());
            FirmAccess access = accessService.evaluate(firm.getId(), subscription);
            if (wanted != null && access.status() != wanted) {
                continue;
            }
            if (unreviewed && (subscription == null || !subscription.isMigrated() || subscription.getReviewedAt() != null)) {
                continue;
            }
            matching.add(new Item(firm, subscription, access));
        }
        int from = Math.min(pageNumber * pageSize, matching.size());
        int to = Math.min(from + pageSize, matching.size());
        List<Item> pageItems = matching.subList(from, to);
        Map<UUID, PlatformFirmMapper.Usage> usage = usageOf(pageItems.stream().map(item -> item.firm().getId()).toList());
        List<PlatformFirmDTO> rows = pageItems.stream()
                .map(item -> mapper.toModel(item.firm(), item.subscription(), item.access(), usageFor(usage, item.firm().getId())))
                .toList();
        return new PageImpl<>(rows, PageRequest.of(pageNumber, pageSize), matching.size());
    }

    private Map<UUID, FirmSubscription> subscriptionsOf(List<UUID> firmIds) {
        if (firmIds.isEmpty()) {
            return Map.of();
        }
        return subscriptionRepository.findByAccountingFirmIdInAndDeletedIsFalse(firmIds).stream()
                .collect(Collectors.toMap(FirmSubscription::getAccountingFirmId, Function.identity(), (a, b) -> a));
    }

    /** As contagens e os contatos só da página, cada um numa consulta agregada. */
    private Map<UUID, PlatformFirmMapper.Usage> usageOf(List<UUID> firmIds) {
        if (firmIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, long[]> companies = new HashMap<>();
        for (Object[] row : companyRepository.countInUseAndInactiveByFirm(firmIds)) {
            companies.put((UUID) row[0], new long[]{toLong(row[1]), toLong(row[2])});
        }
        Map<UUID, Long> users = new HashMap<>();
        for (Object[] row : userRepository.countInUseByFirm(firmIds)) {
            users.put((UUID) row[0], toLong(row[1]));
        }
        Map<UUID, List<FirmAdminContactDTO>> admins = new HashMap<>();
        for (Object[] row : userRepository.findContactsByFirm(firmIds, UserProfileEnum.ADMIN)) {
            admins.computeIfAbsent((UUID) row[0], key -> new ArrayList<>())
                    .add(new FirmAdminContactDTO((String) row[1], (String) row[2]));
        }
        Map<UUID, PlatformFirmMapper.Usage> usage = new HashMap<>();
        for (UUID firmId : firmIds) {
            long[] companyCounts = companies.getOrDefault(firmId, new long[]{0, 0});
            usage.put(firmId, new PlatformFirmMapper.Usage(companyCounts[0], companyCounts[1], users.getOrDefault(firmId, 0L),
                    admins.getOrDefault(firmId, List.of())));
        }
        return usage;
    }

    private static PlatformFirmMapper.Usage usageFor(Map<UUID, PlatformFirmMapper.Usage> usage, UUID firmId) {
        return usage.getOrDefault(firmId, PlatformFirmMapper.Usage.NONE);
    }

    /** Uma ação de acesso ou revisão: vale na próxima requisição do escritório e grava o evento com antes, depois e motivo. */
    @Transactional
    public PlatformFirmDTO apply(UUID firmId, String actionPath, FirmAccessActionDTO request) {
        FirmAccessActions.Action action = validation.checkAction(actionPath);
        AccountingFirm firm = requireFirm(firmId);
        FirmAccessActionDTO body = request == null ? new FirmAccessActionDTO(null, null, null) : request;
        // the CS Hub firm has no trial, block or limit: only the review applies (it is migrated like the others)
        validation.checkNotExempt(accessService.isExempt(firmId), action);
        // trava a linha: duas ações ao mesmo tempo no mesmo escritório não se atropelam
        firmRepository.lock(firmId);
        // locking read: the latest committed row (the daily BLOCKED record may have just written it)
        FirmSubscription subscription = subscriptionRepository.findForUpdate(firmId)
                .orElseGet(() -> missingSubscription(firmId));
        FirmSubscriptionSnapshot before = subscription.snapshot();
        FirmAccess accessBefore = accessService.evaluate(firmId, subscription);

        FirmSubscriptionStatus previousStatus = null;
        BlockedReason previousReason = null;
        if (action == FirmAccessActions.Action.REACTIVATE) {
            Map<String, Object> suspendedFrom = eventRepository
                    .findFirstByAccountingFirmIdAndTypeOrderByCreatedDateDesc(firmId, FirmEventType.SUSPENDED)
                    .map(FirmSubscriptionEvent::getBeforeJson).map(eventMapper::readFields).orElse(null);
            previousStatus = enumOf(FirmSubscriptionStatus.class, suspendedFrom == null ? null : suspendedFrom.get("status"));
            previousReason = enumOf(BlockedReason.class, suspendedFrom == null ? null : suspendedFrom.get("blockedReason"));
        }
        String actor = currentActor();
        try {
            FirmAccessActions.apply(subscription, action, body.trialEndsOn(), body.accessUntil(), body.reason(),
                    accessService.today(), properties.getTrialDays(), previousStatus, previousReason, actor, accessService.now());
        } catch (FirmAccessActions.Refused refused) {
            throw validation.refused(refused);
        }
        subscriptionRepository.save(subscription);
        eventService.record(firmId, action.event(), before, subscription.snapshot(), blankToNull(body.reason()), actor);
        // QA-02: read-only → full access: the months the captures missed are recovered by the schedulers
        FirmAccess accessAfter = accessService.evaluate(firmId, subscription);
        if (accessBefore.readOnly() && !accessAfter.readOnly() && !accessBefore.pendingVerification()
                && accessBefore.blockedSince() != null) {
            recoveryService.schedule(subscription, accessBefore.blockedSince(), actor);
        }
        return mapper.toModel(firm, subscription, accessService.evaluate(firmId, subscription), usageFor(usageOf(List.of(firmId)), firmId));
    }

    /**
     * F1-RN-13: o admin da plataforma preenche o CNPJ, o e-mail financeiro e o telefone do escritório (os migrados não
     * têm). Mesmas validações e códigos do cadastro; o CNPJ é único entre os escritórios (sem contar o próprio). A trilha
     * guarda antes e depois; o log não leva nenhum dos três. Não conta como revisão: a revisão é uma das ações de
     * acesso (F1-RN-14), por exemplo {@code mark-reviewed}.
     */
    @Transactional
    public PlatformFirmDTO updateContact(UUID firmId, FirmContactUpdateDTO request) {
        PlatformFirmValidation.ContactChange change = validation.checkContact(request);

        // lock first and read under the lock (a locking read: the latest committed row) - two edits never lose one another
        List<Object[]> locked = firmRepository.findContactForUpdate(firmId.toString());
        if (locked.isEmpty()) {
            throw new ResourceNotFoundException("error.accountingFirm.not.found", messageService.getMessage("error.accountingFirm.not.found"));
        }
        LockedContact current = LockedContact.of(locked.getFirst());
        FirmSubscription subscription = subscriptionRepository.findForUpdate(firmId).orElse(null);
        validation.checkContactEditable(subscription);

        String cnpj = change.documentNumber() == null ? current.documentNumber() : change.documentNumber();
        String billingEmail = change.billingEmail() == null ? current.billingEmail() : change.billingEmail();
        String phone = change.contactPhone() == null ? current.contactPhone() : change.contactPhone();
        validation.checkDocumentAvailable(cnpj, current.documentNumber(), firmId);
        String actor = currentActor();
        try {
            firmRepository.updateContact(firmId, cnpj, billingEmail, phone, truncate(actor), accessService.now());
        } catch (DataIntegrityViolationException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                // another firm took the CNPJ at the same time - uk_accounting_firms_document_number held
                throw validation.documentTaken();
            }
            throw e;
        }
        eventService.recordFields(firmId, FirmEventType.CONTACT_UPDATED,
                contact(current.documentNumber(), current.billingEmail(), current.contactPhone()),
                contact(cnpj, billingEmail, phone), null, actor);
        log.info("platform.firm.contact.updated firm={}", firmId);
        return mapper.toModel(firmId, current.name(), cnpj, current.createdDate(), phone, billingEmail, subscription,
                accessService.evaluate(firmId, subscription), usageFor(usageOf(List.of(firmId)), firmId));
    }

    /** The row of {@link AccountingFirmRepository#findContactForUpdate}: id, name, document_number, contact_phone, billing_email, created_date. */
    private record LockedContact(String name, String documentNumber, String contactPhone, String billingEmail,
                                 LocalDateTime createdDate) {

        static LockedContact of(Object[] row) {
            return new LockedContact((String) row[1], (String) row[2], (String) row[3], (String) row[4], toLocal(row[5]));
        }

        private static LocalDateTime toLocal(Object value) {
            if (value instanceof Timestamp timestamp) {
                return timestamp.toLocalDateTime();
            }
            return (LocalDateTime) value;
        }
    }

    private static Map<String, Object> contact(String documentNumber, String billingEmail, String contactPhone) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("documentNumber", documentNumber);
        fields.put("billingEmail", billingEmail);
        fields.put("contactPhone", contactPhone);
        return fields;
    }

    /** Escritório sem linha (não deveria existir depois da migração): nasce SUSPENDED, a situação que ele já tinha. */
    private FirmSubscription missingSubscription(UUID firmId) {
        FirmSubscription subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firmId);
        subscription.setStatus(FirmSubscriptionStatus.SUSPENDED);
        subscription.setBlockedReason(BlockedReason.MANUAL);
        subscription.setSource(SubscriptionSource.MANUAL);
        subscription.setDeleted(false);
        return subscription;
    }

    @Transactional(readOnly = true)
    public List<FirmEventDTO> events(UUID firmId) {
        requireFirm(firmId);
        return eventRepository.findByAccountingFirmIdOrderByCreatedDateDesc(firmId).stream().map(eventMapper::toModel).toList();
    }

    private AccountingFirm requireFirm(UUID firmId) {
        return firmRepository.findFirstByIdAndDeletedIsFalse(firmId)
                .orElseThrow(() -> new ResourceNotFoundException("error.accountingFirm.not.found",
                        messageService.getMessage("error.accountingFirm.not.found")));
    }

    private String currentActor() {
        return currentUserService.getCurrentUser().map(User::getLogin).orElse(FirmEventService.SYSTEM);
    }

    private static String truncate(String actor) {
        return actor.length() > ACTOR_MAX ? actor.substring(0, ACTOR_MAX) : actor;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long toLong(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** {@code !} is the escape character of {@link AccountingFirmRepository#searchForConsole}. */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
