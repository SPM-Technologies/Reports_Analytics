package com.hospital.patientreg.shared.tenant;

/**
 * Records who did what, in which hospital (implemented by the identity module, table identity.audit_log).
 * The user and client address come from the current {@link Tenant}.
 */
public interface AuditTrail {

    void record(String action, Integer hospitalId, String detail);
}
