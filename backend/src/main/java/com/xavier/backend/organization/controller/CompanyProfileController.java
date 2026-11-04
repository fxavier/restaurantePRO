package com.xavier.backend.organization.controller;

import com.xavier.backend.organization.model.dto.CompanyProfileUpdateRequest;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import jakarta.validation.Valid;
import com.xavier.backend.organization.service.CompanyProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backoffice/company-profile")
class CompanyProfileController {

    private final CompanyProfileService companyProfiles;

    CompanyProfileController(CompanyProfileService companyProfiles) {
        this.companyProfiles = companyProfiles;
    }

    @GetMapping
    CompanyProfileView get() {
        return companyProfiles.get();
    }

    @PutMapping
    CompanyProfileView update(@Valid @RequestBody CompanyProfileUpdateRequest request) {
        return companyProfiles.update(request);
    }
}
