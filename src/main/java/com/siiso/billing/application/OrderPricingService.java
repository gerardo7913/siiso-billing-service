package com.siiso.billing.application;

import com.siiso.billing.domain.CostSharingRule;
import com.siiso.billing.domain.EffectivePrice;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices for a service order (siiso-docs ADR 0024 and 0027): the price of the day of each service for the
 * company that pays its line (the same rule as everywhere: campaign, own price, plan) and the moderator
 * fee and copay rule of the order's payer. admission-service calls it with the user's token; the
 * liquidation itself is computed there.
 */
@Service
public class OrderPricingService {

    /** Module of the orders (security-service V034). */
    public static final String ORDERS = "ORDERS";

    public record Line(long payerOrganizationId, String serviceCode) {
    }

    public record LinePrice(long payerOrganizationId, String serviceCode, EffectivePrice price) {
    }

    public record Quote(List<LinePrice> prices, List<Line> unpriced, CostSharingRule costSharing) {
    }

    private final RateStore store;
    private final Clock clock;

    public OrderPricingService(RateStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Quote prices(StaffIdentity staff, LocalDate date, Long orderPayerId, Integer costSharingLevel,
                        List<Line> lines) {
        if (!staff.can(ORDERS, "CREATE") && !staff.can(ORDERS, "READ")) {
            throw new AccessDeniedException("Missing permission " + ORDERS + ":READ");
        }
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        Map<Long, Map<String, EffectivePrice>> byPayer = new HashMap<>();
        List<LinePrice> prices = new java.util.ArrayList<>();
        List<Line> unpriced = new java.util.ArrayList<>();
        for (Line line : lines) {
            Map<String, EffectivePrice> companyPrices = byPayer.computeIfAbsent(line.payerOrganizationId(),
                    id -> store.effectivePrices(id, day, null).stream()
                            .collect(Collectors.toMap(EffectivePrice::serviceCode, Function.identity(), (a, b) -> a)));
            EffectivePrice price = companyPrices.get(line.serviceCode());
            if (price == null) {
                unpriced.add(line);
            } else {
                prices.add(new LinePrice(line.payerOrganizationId(), line.serviceCode(), price));
            }
        }
        CostSharingRule rule = orderPayerId == null || costSharingLevel == null ? null
                : store.costSharingRule(orderPayerId, costSharingLevel, day).orElse(null);
        return new Quote(prices, unpriced, rule);
    }

    /**
     * The services a company can be charged for on a date (price above zero), by code or name, as
     * reception picks them for an order; at most {@code limit}, by name.
     */
    @Transactional(readOnly = true)
    public List<EffectivePrice> offered(StaffIdentity staff, long organizationId, String search, LocalDate date,
                                        int limit) {
        if (!staff.can(ORDERS, "CREATE") && !staff.can(ORDERS, "READ")) {
            throw new AccessDeniedException("Missing permission " + ORDERS + ":READ");
        }
        String text = search == null || search.isBlank() ? null : search.trim();
        return store.effectivePrices(organizationId, date == null ? LocalDate.now(clock) : date, text).stream()
                .filter(price -> price.price() != null && price.price().signum() > 0)
                .sorted(java.util.Comparator.comparing(EffectivePrice::serviceName))
                .limit(Math.clamp(limit, 1, 100))
                .toList();
    }

    public Optional<CostSharingRule> rule(long organizationId, int level, LocalDate date) {
        return store.costSharingRule(organizationId, level, date);
    }
}
