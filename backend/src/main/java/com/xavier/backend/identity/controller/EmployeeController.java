package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.AssignmentRequest;
import com.xavier.backend.identity.model.dto.AssignmentSummary;
import com.xavier.backend.identity.model.dto.EmployeeCreateRequest;
import com.xavier.backend.identity.model.dto.EmployeeSummary;
import com.xavier.backend.identity.model.dto.EmployeeUpdateRequest;
import com.xavier.backend.identity.model.dto.PinResetRequest;
import com.xavier.backend.identity.service.EmployeeService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backoffice/employees")
class EmployeeController {

    private final EmployeeService employees;

    EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeSummary hire(@Valid @RequestBody EmployeeCreateRequest request) {
        return employees.hire(request);
    }

    @GetMapping
    PageResponse<EmployeeSummary> list(@RequestParam(required = false) UUID storeId, Pageable pageable) {
        return employees.list(storeId, pageable);
    }

    @GetMapping("/{id}")
    EmployeeSummary get(@PathVariable UUID id) {
        return employees.get(id);
    }

    @PutMapping("/{id}")
    EmployeeSummary update(@PathVariable UUID id, @Valid @RequestBody EmployeeUpdateRequest request) {
        return employees.update(id, request);
    }

    @PostMapping("/{id}/unlock")
    EmployeeSummary unlock(@PathVariable UUID id) {
        return employees.unlock(id);
    }

    @PostMapping("/{id}/reset-pin")
    EmployeeSummary resetPin(@PathVariable UUID id, @Valid @RequestBody PinResetRequest request) {
        return employees.resetPin(id, request.pin());
    }

    @GetMapping("/{id}/assignments")
    List<AssignmentSummary> assignments(@PathVariable UUID id) {
        return employees.assignments(id);
    }

    @PostMapping("/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    AssignmentSummary addAssignment(@PathVariable UUID id, @Valid @RequestBody AssignmentRequest request) {
        return employees.addAssignment(id, request);
    }

    @DeleteMapping("/{id}/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeAssignment(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        employees.removeAssignment(id, assignmentId);
    }
}
