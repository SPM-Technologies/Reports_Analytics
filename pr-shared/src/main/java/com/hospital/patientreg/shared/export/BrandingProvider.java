package com.hospital.patientreg.shared.export;

/** Looks up a hospital's download settings (implemented by the reporting module). */
public interface BrandingProvider {

    Branding forHospital(int hospitalId);
}
