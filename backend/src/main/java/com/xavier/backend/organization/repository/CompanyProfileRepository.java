package com.xavier.backend.organization.repository;

import com.xavier.backend.organization.model.CompanyProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Boolean> {
}
