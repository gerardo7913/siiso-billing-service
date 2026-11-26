package com.siiso.billing.application;

import com.siiso.billing.domain.Company;
import com.siiso.billing.domain.EffectivePrice;
import com.siiso.billing.domain.PeriodPlanner.Operation;
import com.siiso.billing.domain.PeriodPlanner.Period;
import com.siiso.billing.domain.PlanAssignment;
import com.siiso.billing.domain.PlanPrice;
import com.siiso.billing.domain.PriceHistoryEntry;
import com.siiso.billing.domain.PricingMode;
import com.siiso.billing.domain.Rounding;
import com.siiso.billing.domain.RatePlan;
import com.siiso.billing.domain.RatePlanType;
import com.siiso.billing.domain.RecordStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port: rate plans, prices and assignments in the business database (billing_* tables). */
public interface RateStore {

    record Page<T>(List<T> items, int page, int size, long totalItems) {
    }

    /** Filters of a price grid. {@code onlyInPlan}: services with a price on the date or scheduled. */
    record PriceFilter(LocalDate date, String search, String categoryCode, boolean onlyInPlan, int page, int size) {
    }

    // ---------------------------------------------------------------- plans

    Page<RatePlan> plans(RatePlanType type, String search, RecordStatus status, int page, int size, LocalDate today);

    Optional<RatePlan> plan(String code, LocalDate today);

    Optional<RatePlan> ownPlan(long organizationId, LocalDate today);

    boolean planCodeExists(String code);

    boolean rateScheduleExists(String code);

    boolean categoryExists(String code);

    /** The rate schedule new own plans follow: the first active one (legacy TIPOTARIFA 1). */
    String defaultRateSchedule();

    long insertPlan(String code, String name, String description, RatePlanType type, Long organizationId,
                    String rateScheduleCode, UUID actor);

    /** @return {@code false} when the plan changed since {@code version} */
    boolean updatePlan(long id, String name, String description, String rateScheduleCode, RecordStatus status,
                       int version, UUID actor);

    /** Marks the plan as edited here (its prices changed). */
    void touchPlan(long id, UUID actor);

    // ---------------------------------------------------------------- prices

    /** Ids of the services with these codes (any status). */
    Map<String, Long> serviceIds(Collection<String> codes);

    /** Ids of the active services. */
    java.util.Set<Long> activeServiceIds(Collection<Long> ids);

    /** Price periods of a service in a plan, locked until the transaction ends. */
    List<Period<BigDecimal>> lockPricePeriods(long planId, long serviceId);

    void applyPrices(long planId, long serviceId, List<Operation<BigDecimal>> operations, UUID actor);

    Page<PlanPrice> planPrices(long planId, PriceFilter filter);

    /** Prices of a plan valid on a date, by service id (optionally only of one category). */
    Map<Long, BigDecimal> pricesOn(long planId, LocalDate date, String categoryCode);

    List<PriceHistoryEntry> priceHistory(long planId, long serviceId);

    // ---------------------------------------------------------------- companies and assignments

    Page<Company> companies(String search, int page, int size, LocalDate today);

    Optional<Company> company(long id, LocalDate today);

    List<Company> companiesOfPlan(long planId, LocalDate date);

    /** Shared plan periods of a company, locked until the transaction ends. */
    List<Period<Long>> lockAssignmentPeriods(long organizationId);

    void applyAssignments(long organizationId, List<Operation<Long>> operations, UUID actor);

    List<PlanAssignment> assignments(long organizationId);

    List<EffectivePrice> effectivePrices(long organizationId, LocalDate date, String search);

    /** The moderator fee and copay rule of a payer for an income level on a date (siiso-docs ADR 0024). */
    java.util.Optional<com.siiso.billing.domain.CostSharingRule> costSharingRule(long organizationId, int level,
                                                                                LocalDate date);

    // ---------------------------------------------------------------- campaigns (ADR 0023)

    /** What staff edit of a campaign. */
    record CampaignData(String name, String description, String rateScheduleCode, LocalDate validFrom,
                        LocalDate validUntil, PricingMode pricingMode, BigDecimal discountPercent,
                        Rounding discountRounding, String discountCategoryCode) {
    }

    /** The next generated code: CAMP-n (n = the highest + 1). */
    String nextCampaignCode();

    void insertCampaign(String code, CampaignData data, UUID actor);

    /** @return {@code false} when the campaign changed since {@code version} */
    boolean updateCampaign(long id, CampaignData data, RecordStatus status, int version, UUID actor);

    List<Company> campaignCompanies(long planId, LocalDate today);

    /** @return {@code false} when the company already had it */
    boolean addCampaignCompany(long planId, long organizationId, UUID actor);

    boolean removeCampaignCompany(long planId, long organizationId);

    /** Other active campaigns of the company whose period overlaps, as "name (from – until)". */
    List<String> overlappingCampaigns(long organizationId, LocalDate from, LocalDate until, long excludePlanId);

    // ---------------------------------------------------------------- legacy write-back queue

    /** Queues the companies whose prices come from this plan on {@code date} (its owner, or those assigned). */
    void queuePlanCompanies(long planId, LocalDate date);

    void queueCompany(long organizationId);
}
