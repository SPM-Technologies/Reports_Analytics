package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Audit log in identity.audit_log. Never breaks the user's action: a failed audit write is logged instead. */
@Service
public class JdbcAuditTrail implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditTrail.class);

    private final IdentityDao dao;

    public JdbcAuditTrail(IdentityDao dao) {
        this.dao = dao;
    }

    @Override
    public void record(String action, Integer hospitalId, String detail) {
        Tenant t = TenantContext.current().orElse(null);
        write(t == null ? null : t.userId(), t == null ? null : t.username(), hospitalId, action, detail,
                t == null ? null : t.clientAddress());
    }

    /** For events before a user is known (login, failed login, logout). */
    public void recordFor(Long userId, String username, String action, String detail, String clientAddress) {
        write(userId, username, null, action, detail, clientAddress);
    }

    private void write(Long userId, String username, Integer hospitalId, String action, String detail, String ip) {
        try {
            dao.audit(userId, username, hospitalId, action, detail, ip);
        } catch (RuntimeException e) {
            log.error("Audit entry could not be written: {} {} {}", action, username, detail, e);
        }
    }
}
