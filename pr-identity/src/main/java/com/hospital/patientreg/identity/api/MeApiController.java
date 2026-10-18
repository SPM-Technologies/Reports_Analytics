package com.hospital.patientreg.identity.api;

import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in, for the React app's header and to decide which actions to offer. */
@RestController
public class MeApiController {

    @GetMapping("/api/v1/me")
    public Map<String, Object> me() {
        Tenant t = TenantContext.require();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", t.username());
        out.put("displayName", t.displayName());
        out.put("activeHospital", hospital(t.active()));
        out.put("analyticsHospitals", t.hospitals().stream().filter(h -> h.allows(Permission.ANALYTICS)).map(MeApiController::hospital).toList());
        out.put("canExport", t.canAnywhere(Permission.EXPORT));
        out.put("permissions", t.hospitals().stream()
                .flatMap(h -> h.permissions().stream()).distinct().map(Enum::name).sorted().toList());
        return out;
    }

    private static Map<String, Object> hospital(HospitalAccess h) {
        return Map.of("id", h.hospitalId(), "code", h.code(), "name", h.name());
    }
}
