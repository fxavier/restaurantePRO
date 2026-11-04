/**
 * Authorization primitives shared by every module (D13 amendment): {@link com.xavier.backend.access.Permission},
 * {@link com.xavier.backend.access.Actor}, {@link com.xavier.backend.access.AccessPolicy}. These module-root
 * types are its public API. Extracted from identity (S0b) so organization can check permissions without an
 * organization ↔ identity cycle; identity (S2) builds the {@code Actor} principal this module reads.
 */
@ApplicationModule(displayName = "Access", allowedDependencies = {"shared"})
package com.xavier.backend.access;

import org.springframework.modulith.ApplicationModule;
