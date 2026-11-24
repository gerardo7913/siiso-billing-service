package com.siiso.billing.infrastructure.persistence;

/**
 * The price of every active service for a company ({@code :organization}) on a date ({@code :date}),
 * siiso-docs ADR 0022 and 0023: the campaign running then (its own price, or the discount over the
 * normal price) always wins; otherwise the company's own price; otherwise its shared plan's. Inactive
 * plans and campaigns price nothing. {@link #RESOLVED} has one row per priced service with
 * {@code service_offering_id, code, name, category_code, price, source, plan_code, plan_name,
 * valid_from, valid_until, rate_schedule_code}. The one definition used by the screens and the legacy
 * write-back.
 */
public final class EffectivePrices {

    static final String OWN = """
            SELECT pr.service_offering_id, pr.price, pr.valid_from, pr.valid_until, p.code AS plan_code,
                   p.name AS plan_name, rs.code AS rate_schedule_code
              FROM billing_rate_plan p
              JOIN catalog_rate_schedule rs ON rs.id = p.rate_schedule_id
              JOIN billing_rate_plan_price pr ON pr.rate_plan_id = p.id
             WHERE p.organization_id = :organization AND p.status = 'ACTIVE'
               AND pr.valid_from <= :date AND (pr.valid_until IS NULL OR pr.valid_until >= :date)
            """;

    static final String SHARED = """
            SELECT pr.service_offering_id, pr.price, pr.valid_from, pr.valid_until, p.code AS plan_code,
                   p.name AS plan_name, rs.code AS rate_schedule_code
              FROM billing_rate_plan_assignment a
              JOIN billing_rate_plan p ON p.id = a.rate_plan_id AND p.status = 'ACTIVE'
              JOIN catalog_rate_schedule rs ON rs.id = p.rate_schedule_id
              JOIN billing_rate_plan_price pr ON pr.rate_plan_id = p.id
             WHERE a.organization_id = :organization
               AND a.valid_from <= :date AND (a.valid_until IS NULL OR a.valid_until >= :date)
               AND pr.valid_from <= :date AND (pr.valid_until IS NULL OR pr.valid_until >= :date)
            """;

    /** Campaigns of the company running on the date (at most one: their periods never overlap). */
    private static final String RUNNING_CAMPAIGN = """
              FROM billing_campaign_organization m
              JOIN billing_rate_plan p ON p.id = m.rate_plan_id AND p.plan_type = 'CAMPAIGN' AND p.status = 'ACTIVE'
                   AND p.valid_from <= :date AND p.valid_until >= :date
              JOIN catalog_rate_schedule rs ON rs.id = p.rate_schedule_id
            """;

    static final String CAMPAIGN_PRICES = """
            SELECT pr.service_offering_id, pr.price, p.valid_from, p.valid_until, p.code AS plan_code,
                   p.name AS plan_name, rs.code AS rate_schedule_code
            """ + RUNNING_CAMPAIGN + """
              JOIN billing_rate_plan_price pr ON pr.rate_plan_id = p.id
                   AND pr.valid_from <= :date AND (pr.valid_until IS NULL OR pr.valid_until >= :date)
             WHERE m.organization_id = :organization AND p.pricing_mode = 'FIXED_PRICES'
            """;

    static final String CAMPAIGN_DISCOUNT = """
            SELECT p.code AS plan_code, p.name AS plan_name, p.valid_from, p.valid_until, p.discount_percent,
                   CASE p.discount_rounding WHEN 'HUNDRED' THEN 100 WHEN 'THOUSAND' THEN 1000 ELSE 1 END AS unit,
                   dc.code AS category_code, rs.code AS rate_schedule_code
            """ + RUNNING_CAMPAIGN + """
              LEFT JOIN catalog_procedure_category dc ON dc.id = p.discount_category_id
             WHERE m.organization_id = :organization AND p.pricing_mode = 'DISCOUNT'
            """;

    public static final String RESOLVED = """
            SELECT q.service_offering_id, q.code, q.name, q.category_code, q.source,
                   CASE q.source WHEN 'CAMPAIGN' THEN COALESCE(q.campaign_price, q.discounted_price)
                                 WHEN 'OWN' THEN q.own_price ELSE q.plan_price END AS price,
                   CASE q.source WHEN 'CAMPAIGN' THEN COALESCE(q.camp_code, q.disc_code)
                                 WHEN 'OWN' THEN q.own_code ELSE q.plan_code END AS plan_code,
                   CASE q.source WHEN 'CAMPAIGN' THEN COALESCE(q.camp_name, q.disc_name)
                                 WHEN 'OWN' THEN q.own_name ELSE q.plan_name END AS plan_name,
                   CASE q.source WHEN 'CAMPAIGN' THEN COALESCE(q.camp_from, q.disc_from)
                                 WHEN 'OWN' THEN q.own_from ELSE q.plan_from END AS valid_from,
                   CASE q.source WHEN 'CAMPAIGN' THEN COALESCE(q.camp_until, q.disc_until)
                                 WHEN 'OWN' THEN q.own_until ELSE q.plan_until END AS valid_until,
                   CASE q.source WHEN 'CAMPAIGN' THEN COALESCE(q.camp_schedule, q.disc_schedule)
                                 WHEN 'OWN' THEN q.own_schedule ELSE q.plan_schedule END AS rate_schedule_code
              FROM (
                SELECT i.*,
                       CASE WHEN i.campaign_price IS NOT NULL OR i.discounted_price IS NOT NULL THEN 'CAMPAIGN'
                            WHEN i.own_price IS NOT NULL THEN 'OWN'
                            WHEN i.plan_price IS NOT NULL THEN 'PLAN' END AS source
                  FROM (
                    SELECT s.id AS service_offering_id, s.code, s.name, s.category_code,
                           own.price AS own_price, own.plan_code AS own_code, own.plan_name AS own_name,
                           own.valid_from AS own_from, own.valid_until AS own_until, own.rate_schedule_code AS own_schedule,
                           plan.price AS plan_price, plan.plan_code, plan.plan_name, plan.valid_from AS plan_from,
                           plan.valid_until AS plan_until, plan.rate_schedule_code AS plan_schedule,
                           camp.price AS campaign_price, camp.plan_code AS camp_code, camp.plan_name AS camp_name,
                           camp.valid_from AS camp_from, camp.valid_until AS camp_until,
                           camp.rate_schedule_code AS camp_schedule,
                           IF(d.plan_code IS NOT NULL AND (d.category_code IS NULL OR d.category_code = s.category_code)
                                  AND COALESCE(own.price, plan.price) IS NOT NULL,
                              ROUND(COALESCE(own.price, plan.price) * (100 - d.discount_percent) / 100 / d.unit) * d.unit,
                              NULL) AS discounted_price,
                           d.plan_code AS disc_code, d.plan_name AS disc_name, d.valid_from AS disc_from,
                           d.valid_until AS disc_until, d.rate_schedule_code AS disc_schedule
                      FROM service_offering_directory_view s
                      LEFT JOIN (""" + OWN + """
                      ) own ON own.service_offering_id = s.id
                      LEFT JOIN (""" + SHARED + """
                      ) plan ON plan.service_offering_id = s.id
                      LEFT JOIN (""" + CAMPAIGN_PRICES + """
                      ) camp ON camp.service_offering_id = s.id
                      LEFT JOIN (""" + CAMPAIGN_DISCOUNT + """
                      ) d ON TRUE
                     WHERE s.status = 'ACTIVE'
                  ) i
              ) q
             WHERE q.source IS NOT NULL
            """;

    /** Code, price and rate schedule of every service the company has a price for on the date. */
    public static final String OF_COMPANY = "SELECT e.code, e.price, e.rate_schedule_code FROM (" + RESOLVED + ") e";

    private EffectivePrices() {
    }
}
