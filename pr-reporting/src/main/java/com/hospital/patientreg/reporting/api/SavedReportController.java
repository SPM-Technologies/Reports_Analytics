package com.hospital.patientreg.reporting.api;

import com.hospital.patientreg.reporting.saved.SavedReport;
import com.hospital.patientreg.reporting.saved.SavedReportService;
import com.hospital.patientreg.reporting.saved.SavedReportService.SaveRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Saved reports and built-in templates (templates are read-only). */
@RestController
@RequestMapping("/api/v1/reports")
public class SavedReportController {

    private final SavedReportService service;

    public SavedReportController(SavedReportService service) {
        this.service = service;
    }

    @GetMapping
    public List<SavedReport> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public SavedReport get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedReport create(@RequestBody SaveRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public SavedReport update(@PathVariable long id, @RequestBody SaveRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
