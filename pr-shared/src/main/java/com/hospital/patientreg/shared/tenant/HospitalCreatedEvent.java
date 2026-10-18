package com.hospital.patientreg.shared.tenant;

/**
 * Published (in the creating transaction) when a hospital is added, so other modules can set up their
 * own data for it, e.g. registration's default department list. Modules never call each other directly.
 */
public record HospitalCreatedEvent(int hospitalId) {
}
