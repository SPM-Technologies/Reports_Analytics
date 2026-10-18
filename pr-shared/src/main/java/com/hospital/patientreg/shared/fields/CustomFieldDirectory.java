package com.hospital.patientreg.shared.fields;

import java.util.Collection;
import java.util.List;

/** The custom fields of some hospitals that are switched on for reports (active, reportable, not hidden). */
public interface CustomFieldDirectory {

    List<ReportableCustomField> reportable(Collection<Integer> hospitalIds);
}
