# CLAUDE.md

Guidance for Claude Code in this repository. Code, identifiers and comments in English; user-facing text in Spanish.

## What this is

`siiso-billing-service` owns **billing** in SIISO. Today: rate plans and the price each company pays for each service of the portfolio (siiso-docs ADR 0022, redesign of legacy `tarifa`). Later: invoices, RIPS, receivables. Spring Boot 4, Java 26, `JdbcClient`, port 8085. Same structure and conventions as `../siiso-service-offering-service`.

## Model (ADR 0022)

- `billing_rate_plan`: SHARED plans (Diamante, Oro…, assigned to many companies) or ORGANIZATION plans (own prices of one company, code `ORG-<company code>`, created on the first own price). `status` ACTIVE / INACTIVE: an inactive plan prices nothing; plans are never deleted. Imported plans are `LEGACY-<company code>`.
- `billing_rate_plan_price` and `billing_rate_plan_assignment` are **dated** (`valid_from`, `valid_until` NULL = open). `domain/PeriodPlanner` is the one rule (pure, unit-tested): a change from a date closes the covering period the day before and opens one until the next scheduled period; a period starting on that date is corrected; ending from a date closes it, or cancels (deletes) one that starts that day. Nothing before today changes.
- Price of a company on a date = running campaign, else own plan price, else its shared plan's (`infrastructure/persistence/EffectivePrices.RESOLVED`, used by the screens and the write-back). Inactive services are never priced.
- **Orders** (ADR 0024, 0027, business-db V013): `billing_cost_sharing_rule` (moderator fee, copay percent and cap per payer company and income level, dated; imported from legacy `descuento` only where a company and level have none). `POST /api/v1/billing/order-prices` (`OrderPricingService`, ORDERS:READ or CREATE): price of the day of each line for its payer (the same `EffectivePrices.RESOLVED`) plus the payer's rule; `GET /api/v1/billing/order-prices/services?organization_id=&search=`: the services a company can be charged for today (price above zero), for reception. admission-service calls both with the user's token.
- Bulk increase: percentage with `Rounding` NONE / HUNDRED / THOUSAND (as the desktop app), from a date. Replaces legacy `tarifaprovi`. A price change item may carry its own `valid_from` (the request's is the default).
- **Campaigns** (ADR 0023, business-db V008): plan type CAMPAIGN with its own period, `pricing_mode` FIXED_PRICES (own prices per service) or DISCOUNT (`discount_percent` over the normal price, all services or one category, rounding), applied to chosen companies (`billing_campaign_organization`); code generated `CAMP-n`. While running, the campaign **always wins** (user decision). A company is never in two overlapping active campaigns (checked when adding a company, changing dates or reactivating). Started campaigns keep start date and mode; ended ones are frozen. Endpoints `/campaigns…`, module CAMPAIGNS (security V031).

## Rules

- Tables live in `siiso_business` (`../siiso-business-db` V007, prefix `billing_*`, role `siiso_billing_service`, MySQL user `siiso_billing`). Companies and services are read through `organization_directory_view` and `service_offering_directory_view`; catalogs `catalog_rate_schedule`, `catalog_procedure_category`. Flyway is disabled here; `BusinessSchemaVersionCheck` requires version 13.
- Permissions from the token: `RATE_PLANS:READ|CREATE|UPDATE` (plans), `ORGANIZATION_RATES:READ|UPDATE` (companies), security-service V030; `CAMPAIGNS:READ|CREATE|UPDATE`, V031.
- JSON snake_case, pages `{items, page, size, total_items}`, dates ISO, prices `DECIMAL(14,2)` pesos.

## Legacy (ADR 0006)

- `LegacyRateImport` (startup, `LEGACY_IMPORT_ENABLED=true`): each group of companies with the same legacy DEPENDENCIA_EMPRESA becomes `LEGACY-<owner>` with the owner's enabled `tarifa` rows from today, assigned to the group. Companies with an own plan or a plan assigned by staff, and plans edited here, are kept. Child companies' own rows and codes that are not companies (9999, 9000) are only reported. Queues nothing.
- `LegacyRateWriteBack` (outbox `billing_legacy_outbox`, per company): writes the company's prices of today to `tarifa` (own code, HABI 1; its other rows HABI 0) and sets its DEPENDENCIA_EMPRESA to itself; legacy companies that depended on it get the same. Writer user `siiso_bill_rw`: SELECT/INSERT/UPDATE on `tarifa`, SELECT (CDGEMPRESA, DEPENDENCIA_EMPRESA) and UPDATE (DEPENDENCIA_EMPRESA) on `empresa`.
- `LegacyDailyRateSync`: the legacy DB has no dates, so every 5 minutes it queues the companies whose prices or plan start or end on each day since `billing_legacy_daily_sync.last_run_date`.

## Tests

`./mvnw test` (Docker): `PeriodPlannerTest`, `RoundingTest`, `RateApiIntegrationTest` (whole service, MySQL 9.7, business schema + `db/test-data`, today fixed by `support/FixedClock`), `LegacyRateSyncTest` (real `tarifa` / `empresa` structure on MySQL 5.7: import groups, idempotence, write-back with dependents, import keeps staff changes). Run `./mvnw install` in `../siiso-business-db` after changing its migrations.
