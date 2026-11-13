package com.xavier.backend.identity.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code restaurantpro.security.lan-cidrs} (D140; Compose {@code RESTAURANTPRO_LAN_CIDRS}): the address ranges whose
 * requests are "local". The installer narrows it to the store LANs and leaves VPN pools out. Unset, empty or blank
 * (an {@code application.yaml} placeholder with an empty default) means the D140 default list, never "nothing is
 * local", which would ask every login for a second factor.
 */
@ConfigurationProperties("restaurantpro.security")
public record LanProperties(@DefaultValue({"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
        "192.168.0.0/16", "fc00::/7", "fe80::/10"}) List<String> lanCidrs) {

    /** The D140 default list. */
    public static final List<String> DEFAULT_LAN_CIDRS = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8",
            "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fe80::/10");

    public LanProperties {
        List<String> given = lanCidrs == null ? List.of()
                : lanCidrs.stream().map(String::strip).filter(cidr -> !cidr.isEmpty()).toList();
        lanCidrs = given.isEmpty() ? DEFAULT_LAN_CIDRS : given;
    }
}
