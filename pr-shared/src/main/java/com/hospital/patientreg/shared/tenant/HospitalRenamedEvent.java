package com.hospital.patientreg.shared.tenant;

/**
 * Published (in the renaming transaction) when a hospital's name changes, so that the reporting copy of its
 * patients, which carries the hospital name, can be refreshed. Modules never call each other directly.
 */
public record HospitalRenamedEvent(int hospitalId) {
}
