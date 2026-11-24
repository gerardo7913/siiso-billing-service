package com.siiso.billing.infrastructure.web;

import com.siiso.billing.application.RateService;
import com.siiso.billing.application.RateService.PlanInput;
import com.siiso.billing.application.RateService.PriceChange;
import com.siiso.billing.application.RateStore.Page;
import com.siiso.billing.application.RateStore.PriceFilter;
import com.siiso.billing.application.StaffIdentity;
import com.siiso.billing.domain.Company;
import com.siiso.billing.domain.EffectivePrice;
import com.siiso.billing.domain.PlanAssignment;
import com.siiso.billing.domain.PlanPrice;
import com.siiso.billing.domain.PriceHistoryEntry;
import com.siiso.billing.domain.PricingMode;
import com.siiso.billing.domain.RatePlan;
import com.siiso.billing.domain.RecordStatus;
import com.siiso.billing.domain.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rate plans ({@code /rate-plans}) and the prices of each company ({@code /companies}), siiso-docs
 * ADR 0022. JSON in snake_case; dates ISO (AAAA-MM-DD); prices in pesos with up to two decimals.
 */
@RestController
@RequestMapping("/api/v1/billing")
class RateController {

    record PlanRequest(String code, String name, String description, String rateScheduleCode, RecordStatus status,
                       Integer version) {
    }

    /** {@code valid_from} optional: the date of the whole change when absent. */
    record PriceItem(String serviceCode, BigDecimal price, LocalDate validFrom) {
    }

    record PriceChanges(LocalDate validFrom, List<PriceItem> changes) {
    }

    record Increase(LocalDate validFrom, BigDecimal percent, Rounding rounding, String categoryCode) {
    }

    record Copy(String sourcePlanCode, LocalDate validFrom) {
    }

    record Assignment(String planCode, LocalDate validFrom) {
    }

    record CampaignRequest(String name, String description, LocalDate validFrom, LocalDate validUntil,
                           PricingMode pricingMode, BigDecimal discountPercent, Rounding discountRounding,
                           String discountCategoryCode, RecordStatus status, Integer version) {

        RateService.CampaignInput input() {
            return new RateService.CampaignInput(name, description, validFrom, validUntil, pricingMode, discountPercent,
                    discountRounding, discountCategoryCode);
        }
    }

    record CampaignCompany(Long organizationId) {
    }

    /** How many services changed. */
    record Changed(int changed) {
    }

    private final RateService rates;

    RateController(RateService rates) {
        this.rates = rates;
    }

    // ---------------------------------------------------------------- plans

    @GetMapping("/rate-plans")
    Page<RatePlan> plans(StaffIdentity staff, @RequestParam(required = false) String search,
                         @RequestParam(required = false) RecordStatus status,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return rates.plans(staff, search, status, page, size);
    }

    @PostMapping("/rate-plans")
    @ResponseStatus(HttpStatus.CREATED)
    RatePlan create(StaffIdentity staff, @RequestBody PlanRequest request) {
        return rates.createPlan(staff, input(request));
    }

    @GetMapping("/rate-plans/{code}")
    RatePlan plan(StaffIdentity staff, @PathVariable String code) {
        return rates.plan(staff, code);
    }

    @PutMapping("/rate-plans/{code}")
    RatePlan update(StaffIdentity staff, @PathVariable String code, @RequestBody PlanRequest request) {
        return rates.updatePlan(staff, code, input(request), request.status(),
                request.version() == null ? -1 : request.version());
    }

    @GetMapping("/rate-plans/{code}/prices")
    Page<PlanPrice> planPrices(StaffIdentity staff, @PathVariable String code,
                               @RequestParam(required = false) LocalDate date,
                               @RequestParam(required = false) String search,
                               @RequestParam(required = false) String category,
                               @RequestParam(name = "in_plan", defaultValue = "false") boolean inPlan,
                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return rates.planPrices(staff, code, new PriceFilter(date, search, category, inPlan, page, size));
    }

    @PutMapping("/rate-plans/{code}/prices")
    Changed changePlanPrices(StaffIdentity staff, @PathVariable String code, @RequestBody PriceChanges request) {
        return new Changed(rates.changePlanPrices(staff, code, request.validFrom(), changes(request)));
    }

    @GetMapping("/rate-plans/{code}/prices/{serviceCode}/history")
    List<PriceHistoryEntry> history(StaffIdentity staff, @PathVariable String code, @PathVariable String serviceCode) {
        return rates.priceHistory(staff, code, serviceCode);
    }

    @PostMapping("/rate-plans/{code}/increase")
    Changed increase(StaffIdentity staff, @PathVariable String code, @RequestBody Increase request) {
        return new Changed(rates.increase(staff, code, request.validFrom(), request.percent(), request.rounding(),
                request.categoryCode()));
    }

    @PostMapping("/rate-plans/{code}/copy")
    Changed copy(StaffIdentity staff, @PathVariable String code, @RequestBody Copy request) {
        return new Changed(rates.copyPrices(staff, code, request.sourcePlanCode(), request.validFrom()));
    }

    @GetMapping("/rate-plans/{code}/companies")
    List<Company> planCompanies(StaffIdentity staff, @PathVariable String code) {
        return rates.planCompanies(staff, code);
    }

    // ---------------------------------------------------------------- campaigns (ADR 0023)

    @GetMapping("/campaigns")
    Page<RatePlan> campaigns(StaffIdentity staff, @RequestParam(required = false) String search,
                             @RequestParam(required = false) RecordStatus status,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return rates.campaigns(staff, search, status, page, size);
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    RatePlan createCampaign(StaffIdentity staff, @RequestBody CampaignRequest request) {
        return rates.createCampaign(staff, request.input());
    }

    @GetMapping("/campaigns/{code}")
    RatePlan campaign(StaffIdentity staff, @PathVariable String code) {
        return rates.campaign(staff, code);
    }

    @PutMapping("/campaigns/{code}")
    RatePlan updateCampaign(StaffIdentity staff, @PathVariable String code, @RequestBody CampaignRequest request) {
        return rates.updateCampaign(staff, code, request.input(), request.status(),
                request.version() == null ? -1 : request.version());
    }

    @GetMapping("/campaigns/{code}/prices")
    Page<PlanPrice> campaignPrices(StaffIdentity staff, @PathVariable String code,
                                   @RequestParam(required = false) LocalDate date,
                                   @RequestParam(required = false) String search,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(name = "in_plan", defaultValue = "false") boolean inPlan,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return rates.campaignPrices(staff, code, new PriceFilter(date, search, category, inPlan, page, size));
    }

    @PutMapping("/campaigns/{code}/prices")
    Changed changeCampaignPrices(StaffIdentity staff, @PathVariable String code, @RequestBody PriceChanges request) {
        return new Changed(rates.changeCampaignPrices(staff, code, request.validFrom(), changes(request)));
    }

    @GetMapping("/campaigns/{code}/companies")
    List<Company> campaignCompanies(StaffIdentity staff, @PathVariable String code) {
        return rates.campaignCompanies(staff, code);
    }

    @PostMapping("/campaigns/{code}/companies")
    List<Company> addCampaignCompany(StaffIdentity staff, @PathVariable String code, @RequestBody CampaignCompany request) {
        return rates.addCampaignCompany(staff, code, request.organizationId() == null ? -1 : request.organizationId());
    }

    @DeleteMapping("/campaigns/{code}/companies/{organizationId}")
    List<Company> removeCampaignCompany(StaffIdentity staff, @PathVariable String code, @PathVariable long organizationId) {
        return rates.removeCampaignCompany(staff, code, organizationId);
    }

    // ---------------------------------------------------------------- companies

    @GetMapping("/companies")
    Page<Company> companies(StaffIdentity staff, @RequestParam(required = false) String search,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return rates.companies(staff, search, page, size);
    }

    @GetMapping("/companies/{id}")
    Company company(StaffIdentity staff, @PathVariable long id) {
        return rates.company(staff, id);
    }

    @GetMapping("/companies/{id}/plans")
    List<PlanAssignment> assignments(StaffIdentity staff, @PathVariable long id) {
        return rates.assignments(staff, id);
    }

    @PutMapping("/companies/{id}/plan")
    List<PlanAssignment> assign(StaffIdentity staff, @PathVariable long id, @RequestBody Assignment request) {
        return rates.assignPlan(staff, id, request.planCode(), request.validFrom());
    }

    @GetMapping("/companies/{id}/own-prices")
    Page<PlanPrice> ownPrices(StaffIdentity staff, @PathVariable long id,
                              @RequestParam(required = false) LocalDate date,
                              @RequestParam(required = false) String search,
                              @RequestParam(required = false) String category,
                              @RequestParam(name = "in_plan", defaultValue = "false") boolean inPlan,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return rates.ownPrices(staff, id, new PriceFilter(date, search, category, inPlan, page, size));
    }

    @PutMapping("/companies/{id}/own-prices")
    Changed changeOwnPrices(StaffIdentity staff, @PathVariable long id, @RequestBody PriceChanges request) {
        return new Changed(rates.changeOwnPrices(staff, id, request.validFrom(), changes(request)));
    }

    @GetMapping("/companies/{id}/effective-prices")
    List<EffectivePrice> effectivePrices(StaffIdentity staff, @PathVariable long id,
                                         @RequestParam(required = false) LocalDate date,
                                         @RequestParam(required = false) String search) {
        return rates.effectivePrices(staff, id, date, search);
    }

    private static PlanInput input(PlanRequest request) {
        return new PlanInput(request.code(), request.name(), request.description(), request.rateScheduleCode());
    }

    private static List<PriceChange> changes(PriceChanges request) {
        return request.changes() == null ? List.of()
                : request.changes().stream().map(item -> new PriceChange(item.serviceCode(), item.price(), item.validFrom())).toList();
    }
}
