/** Test fixture only: an "orders" module that is not allowed to depend on anything. */
@ApplicationModule(allowedDependencies = {})
package com.xavier.archfixture.modules.orders;

import org.springframework.modulith.ApplicationModule;
