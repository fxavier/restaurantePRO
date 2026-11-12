package com.xavier.archfixture.forbidden.shared.ops;

import com.xavier.archfixture.forbidden.shared.Kernel;

/** Test fixture only: shared.ops using the shared kernel is allowed (D137). */
public class KernelAlertSource {

    public long count() {
        return new Kernel().zero();
    }
}
