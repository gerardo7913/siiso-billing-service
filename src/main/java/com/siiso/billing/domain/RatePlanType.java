package com.siiso.billing.domain;

/**
 * SHARED: a named plan (Diamante, Oro…) assigned to many companies; ORGANIZATION: own prices of one
 * company; CAMPAIGN: a promotion for a period, for the companies chosen for it (ADR 0023).
 */
public enum RatePlanType {
    SHARED, ORGANIZATION, CAMPAIGN
}
