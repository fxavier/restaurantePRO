package com.xavier.backend.config;

/**
 * The outcome of a resubmission request (D88).
 *
 * @param selected how many incomplete publications it selected and handed back to their listeners; the listeners
 *                 run asynchronously, so their outcome shows in the publication list later
 */
public record ResubmissionView(int selected) {
}
