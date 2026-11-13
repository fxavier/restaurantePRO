package com.xavier.backend.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Whether a request is remote (D140): its client address is outside {@code restaurantpro.security.lan-cidrs}. The
 * client address is {@code request.getRemoteAddr()}: in the Compose package Tomcat's RemoteIpValve
 * ({@code server.forward-headers-strategy=native}) has already set it from Caddy's {@code X-Forwarded-For}, trusting
 * only the private-network proxy, and Caddy replaces any client-sent header. A raw header here proves nothing, so it
 * is never read. Addresses are parsed as literals only (no DNS); one that is not a literal counts as remote.
 */
@Component
public class RemoteAddresses {

    private final List<Range> lan;

    public RemoteAddresses(LanProperties properties) {
        lan = properties.lanCidrs().stream().map(Range::parse).toList();
    }

    public boolean isRemote(HttpServletRequest request) {
        InetAddress address = literal(request.getRemoteAddr());
        return address == null || lan.stream().noneMatch(range -> range.contains(address));
    }

    private static InetAddress literal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One CIDR range; an IPv4-mapped IPv6 address is already an {@link Inet4Address} once parsed. */
    private record Range(byte[] network, int prefix) {

        static Range parse(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Not a CIDR range (address/prefix): '" + cidr + "'");
            }
            InetAddress address = literal(cidr.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("Not an IP address literal in '" + cidr + "'");
            }
            int bits = address.getAddress().length * 8;
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a prefix length in '" + cidr + "'");
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Prefix out of 0.." + bits + " in '" + cidr + "'");
            }
            return new Range(address.getAddress(), prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (bytes[full] & mask) == (network[full] & mask);
        }
    }
}
