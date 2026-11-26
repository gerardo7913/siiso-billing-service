package com.siiso.billing.infrastructure.web;

import com.siiso.billing.application.OrderPricingService;
import com.siiso.billing.application.OrderPricingService.Line;
import com.siiso.billing.application.StaffIdentity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/billing/order-prices}: the prices of the day for the lines of an order and the
 * moderator fee / copay rule of its payer (siiso-docs ADR 0027). A read: POST only because the body
 * carries a list.
 */
@RestController
@RequestMapping("/api/v1/billing")
class OrderPricingController {

    private final OrderPricingService pricing;

    OrderPricingController(OrderPricingService pricing) {
        this.pricing = pricing;
    }

    @PostMapping("/order-prices")
    QuoteResponse prices(StaffIdentity staff, @RequestBody QuoteRequest request) {
        var quote = pricing.prices(staff, request.date(), request.organizationId(), request.costSharingLevel(),
                request.lines() == null ? List.of()
                        : request.lines().stream().map(l -> new Line(l.payerOrganizationId(), l.serviceCode())).toList());
        return new QuoteResponse(
                quote.prices().stream().map(p -> new PriceResponse(p.payerOrganizationId(), p.serviceCode(),
                        p.price().price(), p.price().source().name(), p.price().planCode())).toList(),
                quote.unpriced().stream().map(l -> new LineRequest(l.payerOrganizationId(), l.serviceCode())).toList(),
                quote.costSharing() == null ? null : new CostSharingResponse(quote.costSharing().moderatorFee(),
                        quote.costSharing().copayPercent(), quote.costSharing().copayCapPerEvent()));
    }

    /**
     * {@code GET /api/v1/billing/order-prices/services?organization_id=&search=}: what the company can be
     * charged for today (price above zero), for reception to pick from.
     */
    @org.springframework.web.bind.annotation.GetMapping("/order-prices/services")
    List<OfferedService> offered(StaffIdentity staff,
                                 @org.springframework.web.bind.annotation.RequestParam(name = "organization_id") long organizationId,
                                 @org.springframework.web.bind.annotation.RequestParam(required = false) String search,
                                 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate date,
                                 @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int limit) {
        return pricing.offered(staff, organizationId, search, date, limit).stream()
                .map(p -> new OfferedService(p.serviceCode(), p.serviceName(), p.categoryName(), p.price())).toList();
    }

    record OfferedService(String code, String name, String categoryName, BigDecimal price) {
    }

    record LineRequest(long payerOrganizationId, String serviceCode) {
    }

    record QuoteRequest(LocalDate date, Long organizationId, Integer costSharingLevel, List<LineRequest> lines) {
    }

    record PriceResponse(long payerOrganizationId, String serviceCode, BigDecimal price, String source,
                         String planCode) {
    }

    record CostSharingResponse(BigDecimal moderatorFee, BigDecimal copayPercent, BigDecimal copayCapPerEvent) {
    }

    record QuoteResponse(List<PriceResponse> prices, List<LineRequest> unpriced, CostSharingResponse costSharing) {
    }
}
