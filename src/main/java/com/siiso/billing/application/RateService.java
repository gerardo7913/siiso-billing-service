package com.siiso.billing.application;

import com.siiso.billing.application.RateStore.Page;
import com.siiso.billing.application.RateStore.PriceFilter;
import com.siiso.billing.domain.BillingException;
import com.siiso.billing.domain.Company;
import com.siiso.billing.domain.EffectivePrice;
import com.siiso.billing.domain.PeriodPlanner;
import com.siiso.billing.domain.PlanAssignment;
import com.siiso.billing.domain.PlanPrice;
import com.siiso.billing.domain.PriceHistoryEntry;
import com.siiso.billing.domain.PricingMode;
import com.siiso.billing.domain.RatePlan;
import com.siiso.billing.domain.RatePlanType;
import com.siiso.billing.domain.RecordStatus;
import com.siiso.billing.domain.Rounding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate plans and the prices of each company (siiso-docs ADR 0022), for staff with the modules
 * RATE_PLANS and ORGANIZATION_RATES of security-service. Prices are dated: nothing before today
 * changes. Changes that apply today queue the affected companies for the legacy write-back; the
 * daily job queues the ones that start later (ADR 0006).
 */
@Service
public class RateService {

    public static final String PLANS = "RATE_PLANS";
    public static final String COMPANY_RATES = "ORGANIZATION_RATES";
    public static final String CAMPAIGNS = "CAMPAIGNS";
    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_CHANGES = 2000;
    private static final Pattern PLAN_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,19}");
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999999999.99");

    /**
     * One price to set; {@code price} {@code null} takes the service out of the plan from the date.
     * {@code validFrom} {@code null} uses the date of the whole change.
     */
    public record PriceChange(String serviceCode, BigDecimal price, LocalDate validFrom) {

        public PriceChange(String serviceCode, BigDecimal price) {
            this(serviceCode, price, null);
        }
    }

    public record PlanInput(String code, String name, String description, String rateScheduleCode) {
    }

    /** What staff edit of a campaign (ADR 0023); the code is generated. */
    public record CampaignInput(String name, String description, LocalDate validFrom, LocalDate validUntil,
                                PricingMode pricingMode, BigDecimal discountPercent, Rounding discountRounding,
                                String discountCategoryCode) {
    }

    private final RateStore store;
    private final Clock clock;

    public RateService(RateStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- plans

    @Transactional(readOnly = true)
    public Page<RatePlan> plans(StaffIdentity staff, String search, RecordStatus status, int page, int size) {
        require(staff, PLANS, "READ");
        return store.plans(RatePlanType.SHARED, blankToNull(search), status, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                today());
    }

    @Transactional(readOnly = true)
    public RatePlan plan(StaffIdentity staff, String code) {
        require(staff, PLANS, "READ");
        return sharedPlan(code);
    }

    @Transactional
    public RatePlan createPlan(StaffIdentity staff, PlanInput input) {
        require(staff, PLANS, "CREATE");
        Map<String, String> errors = new LinkedHashMap<>();
        String code = input.code() == null ? "" : input.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (!PLAN_CODE.matcher(code).matches()) {
            errors.put("code", "De 2 a 20 letras, números, guion o guion bajo, empezando por letra o número.");
        } else if (code.startsWith("ORG-") || code.startsWith("LEGACY-")) {
            errors.put("code", "Los códigos que empiezan por ORG- o LEGACY- los asigna el sistema.");
        }
        String name = validName(input.name(), errors);
        String schedule = validSchedule(input.rateScheduleCode(), errors);
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        if (store.planCodeExists(code)) {
            throw new BillingException.Conflict("code", "Ya existe un plan con el código " + code + ".");
        }
        store.insertPlan(code, name, blankToNull(input.description()), RatePlanType.SHARED, null, schedule,
                staff.principalId());
        return sharedPlan(code);
    }

    @Transactional
    public RatePlan updatePlan(StaffIdentity staff, String code, PlanInput input, RecordStatus status, int version) {
        require(staff, PLANS, "UPDATE");
        RatePlan plan = sharedPlan(code);
        Map<String, String> errors = new LinkedHashMap<>();
        String name = validName(input.name(), errors);
        String schedule = validSchedule(input.rateScheduleCode(), errors);
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        RecordStatus newStatus = status == null ? plan.status() : status;
        if (!store.updatePlan(plan.id(), name, blankToNull(input.description()), schedule, newStatus, version,
                staff.principalId())) {
            throw new BillingException.Stale();
        }
        if (newStatus != plan.status() || !schedule.equals(plan.rateScheduleCode())) {
            store.queuePlanCompanies(plan.id(), today());
        }
        return sharedPlan(code);
    }

    @Transactional(readOnly = true)
    public Page<PlanPrice> planPrices(StaffIdentity staff, String code, PriceFilter filter) {
        require(staff, PLANS, "READ");
        return store.planPrices(sharedPlan(code).id(), normalized(filter));
    }

    @Transactional(readOnly = true)
    public List<PriceHistoryEntry> priceHistory(StaffIdentity staff, String code, String serviceCode) {
        require(staff, PLANS, "READ");
        return store.priceHistory(sharedPlan(code).id(), serviceId(serviceCode));
    }

    @Transactional(readOnly = true)
    public List<Company> planCompanies(StaffIdentity staff, String code) {
        require(staff, PLANS, "READ");
        return store.companiesOfPlan(sharedPlan(code).id(), today());
    }

    /** Sets or removes prices of a shared plan from {@code validFrom}. */
    @Transactional
    public int changePlanPrices(StaffIdentity staff, String code, LocalDate validFrom, List<PriceChange> changes) {
        require(staff, PLANS, "UPDATE");
        return changePrices(staff, sharedPlan(code), validFrom, changes);
    }

    /** Raises (or lowers) by a percentage every price of the plan valid on {@code validFrom}. */
    @Transactional
    public int increase(StaffIdentity staff, String code, LocalDate validFrom, BigDecimal percent, Rounding rounding,
                        String categoryCode) {
        require(staff, PLANS, "UPDATE");
        RatePlan plan = sharedPlan(code);
        Map<String, String> errors = new LinkedHashMap<>();
        requireDate(validFrom, errors);
        if (percent == null || percent.signum() == 0 || percent.compareTo(BigDecimal.valueOf(-100)) <= 0
                || percent.compareTo(BigDecimal.valueOf(1000)) > 0) {
            errors.put("percent", "Escribe un porcentaje distinto de cero, mayor que -100 y hasta 1000.");
        }
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        Rounding unit = rounding == null ? Rounding.NONE : rounding;
        Map<Long, BigDecimal> current = store.pricesOn(plan.id(), validFrom, blankToNull(categoryCode));
        int changed = 0;
        for (Map.Entry<Long, BigDecimal> entry : current.entrySet()) {
            changed += setPrice(plan, entry.getKey(), validFrom, unit.increase(entry.getValue(), percent), staff);
        }
        afterPriceChange(plan, validFrom, changed, staff);
        return changed;
    }

    /** Copies into the plan the prices another plan has on {@code validFrom}; other services stay. */
    @Transactional
    public int copyPrices(StaffIdentity staff, String code, String sourceCode, LocalDate validFrom) {
        require(staff, PLANS, "UPDATE");
        RatePlan plan = sharedPlan(code);
        Map<String, String> errors = new LinkedHashMap<>();
        requireDate(validFrom, errors);
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        RatePlan source = store.plan(blankToNull(sourceCode) == null ? "" : sourceCode.trim(), today())
                .orElseThrow(() -> new BillingException.Invalid(Map.of("source_plan_code", "Ese plan no existe.")));
        if (source.id() == plan.id()) {
            throw new BillingException.Invalid(Map.of("source_plan_code", "Elige un plan distinto."));
        }
        int changed = 0;
        for (Map.Entry<Long, BigDecimal> entry : store.pricesOn(source.id(), validFrom, null).entrySet()) {
            changed += setPrice(plan, entry.getKey(), validFrom, entry.getValue(), staff);
        }
        afterPriceChange(plan, validFrom, changed, staff);
        return changed;
    }

    // ---------------------------------------------------------------- companies

    @Transactional(readOnly = true)
    public Page<Company> companies(StaffIdentity staff, String search, int page, int size) {
        require(staff, COMPANY_RATES, "READ");
        return store.companies(blankToNull(search), Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), today());
    }

    @Transactional(readOnly = true)
    public Company company(StaffIdentity staff, long id) {
        require(staff, COMPANY_RATES, "READ");
        return company(id);
    }

    @Transactional(readOnly = true)
    public List<PlanAssignment> assignments(StaffIdentity staff, long id) {
        require(staff, COMPANY_RATES, "READ");
        company(id);
        return store.assignments(id);
    }

    /** Gives the company a shared plan from {@code validFrom}; {@code planCode} {@code null} removes it. */
    @Transactional
    public List<PlanAssignment> assignPlan(StaffIdentity staff, long id, String planCode, LocalDate validFrom) {
        require(staff, COMPANY_RATES, "UPDATE");
        company(id);
        Map<String, String> errors = new LinkedHashMap<>();
        requireDate(validFrom, errors);
        Long planId = null;
        if (blankToNull(planCode) != null) {
            RatePlan plan = store.plan(planCode.trim(), today()).filter(p -> p.planType() == RatePlanType.SHARED)
                    .orElse(null);
            if (plan == null) {
                errors.put("plan_code", "Ese plan no existe.");
            } else if (plan.status() != RecordStatus.ACTIVE) {
                errors.put("plan_code", "El plan está inactivo: actívalo antes de asignarlo.");
            } else {
                planId = plan.id();
            }
        }
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        var operations = PeriodPlanner.plan(store.lockAssignmentPeriods(id), validFrom, planId, today());
        store.applyAssignments(id, operations, staff.principalId());
        if (!operations.isEmpty() && validFrom.equals(today())) {
            store.queueCompany(id);
        }
        return store.assignments(id);
    }

    @Transactional(readOnly = true)
    public Page<PlanPrice> ownPrices(StaffIdentity staff, long id, PriceFilter filter) {
        require(staff, COMPANY_RATES, "READ");
        company(id);
        PriceFilter normalized = normalized(filter);
        return store.ownPlan(id, today()).map(plan -> store.planPrices(plan.id(), normalized))
                .orElseGet(() -> store.planPrices(-1, normalized));
    }

    /** Sets or removes own prices of a company; its own plan is created the first time. */
    @Transactional
    public int changeOwnPrices(StaffIdentity staff, long id, LocalDate validFrom, List<PriceChange> changes) {
        require(staff, COMPANY_RATES, "UPDATE");
        Company company = company(id);
        RatePlan plan = store.ownPlan(id, today()).orElseGet(() -> {
            String name = ("Tarifa propia – " + company.legalName().trim());
            store.insertPlan("ORG-" + company.code(), name.length() > 100 ? name.substring(0, 100) : name, null,
                    RatePlanType.ORGANIZATION, id, store.defaultRateSchedule(), staff.principalId());
            return store.ownPlan(id, today()).orElseThrow();
        });
        return changePrices(staff, plan, validFrom, changes);
    }

    @Transactional(readOnly = true)
    public List<EffectivePrice> effectivePrices(StaffIdentity staff, long id, LocalDate date, String search) {
        require(staff, COMPANY_RATES, "READ");
        company(id);
        return store.effectivePrices(id, date == null ? today() : date, blankToNull(search));
    }

    // ---------------------------------------------------------------- campaigns (ADR 0023)

    @Transactional(readOnly = true)
    public Page<RatePlan> campaigns(StaffIdentity staff, String search, RecordStatus status, int page, int size) {
        require(staff, CAMPAIGNS, "READ");
        return store.plans(RatePlanType.CAMPAIGN, blankToNull(search), status, Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE), today());
    }

    @Transactional(readOnly = true)
    public RatePlan campaign(StaffIdentity staff, String code) {
        require(staff, CAMPAIGNS, "READ");
        return campaignPlan(code);
    }

    @Transactional
    public RatePlan createCampaign(StaffIdentity staff, CampaignInput input) {
        require(staff, CAMPAIGNS, "CREATE");
        RateStore.CampaignData data = validCampaign(input, null);
        String code = store.nextCampaignCode();
        store.insertCampaign(code, data, staff.principalId());
        return campaignPlan(code);
    }

    @Transactional
    public RatePlan updateCampaign(StaffIdentity staff, String code, CampaignInput input, RecordStatus status,
                                   int version) {
        require(staff, CAMPAIGNS, "UPDATE");
        RatePlan campaign = campaignPlan(code);
        RateStore.CampaignData data = validCampaign(input, campaign);
        RecordStatus newStatus = status == null ? campaign.status() : status;
        List<Company> companies = store.campaignCompanies(campaign.id(), today());
        if (newStatus == RecordStatus.ACTIVE) {
            for (Company company : companies) {
                checkNoOverlap(company, data.validFrom(), data.validUntil(), campaign.id());
            }
        }
        if (!store.updateCampaign(campaign.id(), data, newStatus, version, staff.principalId())) {
            throw new BillingException.Stale();
        }
        companies.forEach(company -> store.queueCompany(company.id()));
        return campaignPlan(code);
    }

    @Transactional(readOnly = true)
    public Page<PlanPrice> campaignPrices(StaffIdentity staff, String code, PriceFilter filter) {
        require(staff, CAMPAIGNS, "READ");
        return store.planPrices(campaignPlan(code).id(), normalized(filter));
    }

    /** Prices of a campaign with its own prices; without a date they start with the campaign (or today). */
    @Transactional
    public int changeCampaignPrices(StaffIdentity staff, String code, LocalDate validFrom, List<PriceChange> changes) {
        require(staff, CAMPAIGNS, "UPDATE");
        RatePlan campaign = campaignPlan(code);
        if (campaign.pricingMode() != PricingMode.FIXED_PRICES) {
            throw new BillingException.Invalid(Map.of("changes",
                    "Esta campaña es de descuento: no tiene precios propios por servicio."));
        }
        if (campaign.validUntil().isBefore(today())) {
            throw new BillingException.Invalid(Map.of("changes", "La campaña ya terminó: sus precios no cambian."));
        }
        LocalDate from = validFrom != null ? validFrom
                : campaign.validFrom().isAfter(today()) ? campaign.validFrom() : today();
        return changePrices(staff, campaign, from, changes);
    }

    @Transactional(readOnly = true)
    public List<Company> campaignCompanies(StaffIdentity staff, String code) {
        require(staff, CAMPAIGNS, "READ");
        return store.campaignCompanies(campaignPlan(code).id(), today());
    }

    /** Applies the campaign to a company; never two campaigns of a company at the same time. */
    @Transactional
    public List<Company> addCampaignCompany(StaffIdentity staff, String code, long organizationId) {
        require(staff, CAMPAIGNS, "UPDATE");
        RatePlan campaign = runningOrFuture(campaignPlan(code));
        Company company = company(organizationId);
        if (campaign.status() == RecordStatus.ACTIVE) {
            checkNoOverlap(company, campaign.validFrom(), campaign.validUntil(), campaign.id());
        }
        if (store.addCampaignCompany(campaign.id(), organizationId, staff.principalId()) && runsToday(campaign)) {
            store.queueCompany(organizationId);
        }
        return store.campaignCompanies(campaign.id(), today());
    }

    @Transactional
    public List<Company> removeCampaignCompany(StaffIdentity staff, String code, long organizationId) {
        require(staff, CAMPAIGNS, "UPDATE");
        RatePlan campaign = runningOrFuture(campaignPlan(code));
        if (store.removeCampaignCompany(campaign.id(), organizationId) && runsToday(campaign)) {
            store.queueCompany(organizationId);
        }
        return store.campaignCompanies(campaign.id(), today());
    }

    private RateStore.CampaignData validCampaign(CampaignInput input, RatePlan existing) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = validName(input.name(), errors);
        LocalDate from = input.validFrom();
        LocalDate until = input.validUntil();
        boolean started = existing != null && !existing.validFrom().isAfter(today());
        if (from == null) {
            errors.put("valid_from", "Indica el primer día de la campaña.");
        } else if (started && !from.equals(existing.validFrom())) {
            errors.put("valid_from", "La campaña ya empezó: su fecha de inicio no cambia.");
        } else if (!started && from.isBefore(today())) {
            errors.put("valid_from", "La campaña debe empezar hoy o después.");
        }
        if (until == null) {
            errors.put("valid_until", "Indica el último día de la campaña.");
        } else if (from != null && until.isBefore(from)) {
            errors.put("valid_until", "El último día debe ser igual o posterior al primero.");
        } else if (existing != null && existing.validUntil().isBefore(today()) && !until.equals(existing.validUntil())) {
            errors.put("valid_until", "La campaña ya terminó: sus fechas no cambian.");
        } else if (until.isBefore(today()) && (existing == null || !until.equals(existing.validUntil()))) {
            errors.put("valid_until", "El último día no puede ser anterior a hoy.");
        }
        PricingMode mode = input.pricingMode();
        if (mode == null) {
            errors.put("pricing_mode", "Elige si la campaña tiene precios propios o un descuento.");
        } else if (started && mode != existing.pricingMode()) {
            errors.put("pricing_mode", "La campaña ya empezó: no se puede cambiar de precios a descuento.");
        }
        BigDecimal percent = null;
        Rounding rounding = null;
        String category = null;
        if (mode == PricingMode.DISCOUNT) {
            percent = input.discountPercent();
            if (percent == null || percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0
                    || percent.scale() > 2) {
                errors.put("discount_percent", "Escribe un porcentaje mayor que 0 y hasta 100, con máximo dos decimales.");
            }
            rounding = input.discountRounding() == null ? Rounding.NONE : input.discountRounding();
            category = blankToNull(input.discountCategoryCode());
            if (category != null && !store.categoryExists(category)) {
                errors.put("discount_category_code", "Esa categoría no existe.");
            }
        }
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        String schedule = existing == null ? store.defaultRateSchedule() : existing.rateScheduleCode();
        return new RateStore.CampaignData(name, blankToNull(input.description()), schedule, from, until, mode, percent,
                rounding, category);
    }

    private void checkNoOverlap(Company company, LocalDate from, LocalDate until, long campaignId) {
        List<String> others = store.overlappingCampaigns(company.id(), from, until, campaignId);
        if (!others.isEmpty()) {
            throw new BillingException.Conflict("organization_id", company.legalName().trim()
                    + " ya está en la campaña " + others.getFirst() + ": una empresa no puede tener dos campañas al mismo tiempo.");
        }
    }

    private RatePlan runningOrFuture(RatePlan campaign) {
        if (campaign.validUntil().isBefore(today())) {
            throw new BillingException.Invalid(Map.of("organization_id", "La campaña ya terminó: sus empresas no cambian."));
        }
        return campaign;
    }

    private boolean runsToday(RatePlan campaign) {
        return campaign.status() == RecordStatus.ACTIVE && !campaign.validFrom().isAfter(today())
                && !campaign.validUntil().isBefore(today());
    }

    private RatePlan campaignPlan(String code) {
        return store.plan(code == null ? "" : code.trim(), today()).filter(p -> p.planType() == RatePlanType.CAMPAIGN)
                .orElseThrow(() -> new BillingException.NotFound("La campaña " + code + " no existe."));
    }

    // ---------------------------------------------------------------- rules

    private int changePrices(StaffIdentity staff, RatePlan plan, LocalDate validFrom, List<PriceChange> changes) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (changes != null && changes.stream().anyMatch(change -> change.validFrom() == null)) {
            requireDate(validFrom, errors);
        }
        if (changes == null || changes.isEmpty()) {
            errors.put("changes", "No hay cambios para guardar.");
        } else if (changes.size() > MAX_CHANGES) {
            errors.put("changes", "Guarda como máximo " + MAX_CHANGES + " cambios a la vez.");
        }
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        Map<String, Long> ids = store.serviceIds(changes.stream().map(PriceChange::serviceCode).toList());
        Set<Long> active = store.activeServiceIds(ids.values());
        Map<String, PriceChange> prices = new LinkedHashMap<>();
        for (PriceChange change : changes) {
            String code = change.serviceCode();
            Long serviceId = ids.get(code);
            LocalDate from = change.validFrom() == null ? validFrom : change.validFrom();
            if (serviceId == null) {
                errors.put("changes." + code, "El servicio " + code + " no existe.");
            } else if (change.price() != null && change.price().signum() < 0) {
                errors.put("changes." + code, "El precio no puede ser negativo.");
            } else if (change.price() != null && (change.price().scale() > 2 || change.price().compareTo(MAX_PRICE) > 0)) {
                errors.put("changes." + code, "Escribe el precio en pesos, con máximo dos decimales.");
            } else if (change.price() != null && !active.contains(serviceId)) {
                errors.put("changes." + code, "El servicio " + code + " está inactivo: no se le puede poner precio.");
            } else if (prices.containsKey(code)) {
                errors.put("changes." + code, "El servicio " + code + " está repetido.");
            } else if (from != null && from.isBefore(today())) {
                errors.put("changes." + code, "No se pueden cambiar días pasados: la fecha debe ser hoy o posterior.");
            } else if (from != null) {
                prices.put(code, new PriceChange(code,
                        change.price() == null ? null : change.price().setScale(2, RoundingMode.UNNECESSARY), from));
            }
        }
        if (!errors.isEmpty()) {
            throw new BillingException.Invalid(errors);
        }
        int changed = 0;
        boolean changesToday = false;
        for (PriceChange change : prices.values()) {
            int done = setPrice(plan, ids.get(change.serviceCode()), change.validFrom(), change.price(), staff);
            changed += done;
            changesToday |= done > 0 && change.validFrom().equals(today());
        }
        afterPriceChange(plan, changesToday ? today() : null, changed, staff);
        return changed;
    }

    private int setPrice(RatePlan plan, long serviceId, LocalDate validFrom, BigDecimal price, StaffIdentity staff) {
        var operations = PeriodPlanner.plan(store.lockPricePeriods(plan.id(), serviceId), validFrom, price, today());
        store.applyPrices(plan.id(), serviceId, operations, staff.principalId());
        return operations.isEmpty() ? 0 : 1;
    }

    private void afterPriceChange(RatePlan plan, LocalDate validFrom, int changed, StaffIdentity staff) {
        if (changed == 0) {
            return;
        }
        store.touchPlan(plan.id(), staff.principalId());
        if (today().equals(validFrom)) {
            store.queuePlanCompanies(plan.id(), today());
        }
    }

    private RatePlan sharedPlan(String code) {
        return store.plan(code == null ? "" : code.trim(), today()).filter(p -> p.planType() == RatePlanType.SHARED)
                .orElseThrow(() -> new BillingException.NotFound("El plan " + code + " no existe."));
    }

    private Company company(long id) {
        return store.company(id, today()).orElseThrow(() -> new BillingException.NotFound("La empresa no existe."));
    }

    private long serviceId(String code) {
        Long id = store.serviceIds(List.of(code == null ? "" : code)).get(code);
        if (id == null) {
            throw new BillingException.NotFound("El servicio " + code + " no existe.");
        }
        return id;
    }

    private PriceFilter normalized(PriceFilter filter) {
        return new PriceFilter(filter.date() == null ? today() : filter.date(), blankToNull(filter.search()),
                blankToNull(filter.categoryCode()), filter.onlyInPlan(), Math.max(filter.page(), 0),
                Math.clamp(filter.size(), 1, MAX_PAGE_SIZE));
    }

    private void requireDate(LocalDate validFrom, Map<String, String> errors) {
        if (validFrom == null) {
            errors.put("valid_from", "Indica desde qué fecha aplica.");
        } else if (validFrom.isBefore(today())) {
            errors.put("valid_from", "No se pueden cambiar días pasados: la fecha debe ser hoy o posterior.");
        }
    }

    private static String validName(String name, Map<String, String> errors) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 100) {
            errors.put("name", "Escribe un nombre de hasta 100 caracteres.");
        }
        return value;
    }

    private String validSchedule(String code, Map<String, String> errors) {
        String value = code == null ? "" : code.trim();
        if (value.isEmpty() || !store.rateScheduleExists(value)) {
            errors.put("rate_schedule_code", "Elige un manual tarifario.");
        }
        return value;
    }

    private static void require(StaffIdentity staff, String module, String action) {
        if (!staff.can(module, action)) {
            throw new AccessDeniedException("Missing permission " + module + ":" + action);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
