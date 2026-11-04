package com.xavier.backend.organization.repository;

import com.xavier.backend.organization.model.CompanyProfile;
import org.springframework.data.repository.CrudRepository;

public interface CompanyProfileRepository extends CrudRepository<CompanyProfile, Boolean> {
}
