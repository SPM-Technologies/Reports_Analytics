package com.hospital.patientreg.reporting.api;

import com.hospital.patientreg.reporting.custom.CalculatedFieldService;
import com.hospital.patientreg.reporting.query.QueryRequest.CalculatedSpec;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Formulas: checking a formula while typing, and calculated fields shared with a hospital (stage 5b). */
@RestController
@RequestMapping("/api/v1")
public class CustomisationApiController {

    private final CalculatedFieldService calculated;

    public CustomisationApiController(CalculatedFieldService calculated) {
        this.calculated = calculated;
    }

    public record ValidateRequest(String dataset, String formula, List<CalculatedSpec> calculated) {
    }

    public record SharedFieldRequest(String dataset, String label, String formula, String description, Boolean active) {
    }

    /** Checks a formula; answers its kind (column or measure) and type, or a 400 with the mistake. */
    @PostMapping("/formulas/validate")
    public Map<String, Object> validate(@RequestBody ValidateRequest request) {
        return calculated.validate(request.dataset() == null ? "patients" : request.dataset(), request.formula(), request.calculated());
    }

    @GetMapping("/calculated-fields")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "patients") String dataset) {
        return calculated.list(dataset);
    }

    @PostMapping("/calculated-fields")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody SharedFieldRequest request) {
        return calculated.create(request.dataset() == null ? "patients" : request.dataset(), request.label(),
                request.formula(), request.description());
    }

    @PutMapping("/calculated-fields/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody SharedFieldRequest request) {
        return calculated.update(id, request.label(), request.formula(), request.description(),
                request.active() == null || request.active());
    }

    /** Saved reports that use the field (shown before changing it). */
    @GetMapping("/calculated-fields/{id}/usage")
    public List<String> usage(@PathVariable long id) {
        return calculated.usage(id);
    }
}
