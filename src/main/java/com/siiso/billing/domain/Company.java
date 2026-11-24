package com.siiso.billing.domain;

/** A company as billing sees it (organization_directory_view), with its rate plans of today. */
public record Company(long id, String code, String taxId, String taxIdCheckDigit, String legalName,
                      String municipalityName, String status, String currentPlanCode, String currentPlanName,
                      String ownPlanCode, int ownPricesCount) {
}
