package com.xavier.backend.production.model;

/**
 * How a production ticket's content is stored (D155): PLAIN text lines (R4 tickets and fiscal printouts, which keep
 * their adapter's layout), STYLED_V1 JSON lines (production's own layouts), or KICK, a drawer kick (D158), whose
 * text only names it: what it sends is {@code ESC p} from the printer's profile.
 */
public enum ContentFormat {
    PLAIN,
    STYLED_V1,
    KICK
}
