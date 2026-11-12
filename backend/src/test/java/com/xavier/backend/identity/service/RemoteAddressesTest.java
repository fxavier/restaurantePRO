package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;

/** "Remote" (D140): the client address is outside {@code restaurantpro.security.lan-cidrs}; IPv4 and IPv6. */
class RemoteAddressesTest {

    static final RemoteAddresses DEFAULTS = new RemoteAddresses(new LanProperties(LanProperties.DEFAULT_LAN_CIDRS));
    static final RemoteAddresses STORE_LAN = new RemoteAddresses(new LanProperties(List.of("192.168.1.0/24")));

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.0.9", "::1", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.0.5", "fd00::1", "fc00::abcd", "fe80::1", "0:0:0:0:0:0:0:1"})
    void theDefaultListCountsLoopbackAndPrivateAddressesAsLan(String address) {
        assertThat(DEFAULTS.isRemote(from(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.32.0.1", "172.15.255.255", "192.169.0.1", "11.0.0.1", "203.0.113.5",
            "2001:db8::1", "fec0::1", "::2"})
    void theDefaultListCountsPublicAddressesAsRemote(String address) {
        assertThat(DEFAULTS.isRemote(from(address))).isTrue();
    }

    @Test
    void aNarrowedListLeavesTheRestOfThePrivateRangesRemote() {
        assertThat(STORE_LAN.isRemote(from("192.168.1.10"))).isFalse();
        assertThat(STORE_LAN.isRemote(from("192.168.1.255"))).isFalse();
        assertThat(STORE_LAN.isRemote(from("192.168.2.10"))).isTrue();
        assertThat(STORE_LAN.isRemote(from("10.0.0.1"))).isTrue();
        assertThat(STORE_LAN.isRemote(from("203.0.113.5"))).isTrue();
    }

    @Test
    void anIpv4MappedIpv6AddressIsReadAsIpv4() {
        assertThat(STORE_LAN.isRemote(from("::ffff:192.168.1.10"))).isFalse();
        assertThat(STORE_LAN.isRemote(from("::ffff:203.0.113.5"))).isTrue();
    }

    @Test
    void anIpv6PrefixIsMatchedBitByBit() {
        RemoteAddresses lan = new RemoteAddresses(new LanProperties(List.of("2001:db8:abcd:12::/63")));

        assertThat(lan.isRemote(from("2001:db8:abcd:12::1"))).isFalse();
        assertThat(lan.isRemote(from("2001:db8:abcd:13:ffff::1"))).isFalse();
        assertThat(lan.isRemote(from("2001:db8:abcd:14::1"))).isTrue();
    }

    @Test
    void theForwardedAddressIsTheOneTheProxyValveResolved() {
        // Tomcat's RemoteIpValve (server.forward-headers-strategy=native) has already replaced the remote address
        // with the X-Forwarded-For client, from the private-network proxy only; a header that reaches us unresolved
        // came from somewhere else and claims nothing.
        MockHttpServletRequest resolved = from("192.168.1.10");
        resolved.addHeader("X-Forwarded-For", "192.168.1.10");
        MockHttpServletRequest spoofed = from("203.0.113.5");
        spoofed.addHeader("X-Forwarded-For", "192.168.1.10");

        assertThat(STORE_LAN.isRemote(resolved)).isFalse();
        assertThat(STORE_LAN.isRemote(spoofed)).isTrue();
    }

    @Test
    void anAddressThatIsNotALiteralIsRemote() {
        assertThat(DEFAULTS.isRemote(from("unknown"))).isTrue();
        assertThat(DEFAULTS.isRemote(from(""))).isTrue();
        assertThat(DEFAULTS.isRemote(from("localhost"))).isTrue();
    }

    @Test
    void zeroAndFullLengthPrefixes() {
        RemoteAddresses everything = new RemoteAddresses(new LanProperties(List.of("0.0.0.0/0")));
        RemoteAddresses one = new RemoteAddresses(new LanProperties(List.of("192.168.1.7/32")));

        assertThat(everything.isRemote(from("203.0.113.5"))).isFalse();
        assertThat(everything.isRemote(from("2001:db8::1"))).isTrue();
        assertThat(one.isRemote(from("192.168.1.7"))).isFalse();
        assertThat(one.isRemote(from("192.168.1.6"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.0/33", "fe80::/129", "192.168.1.0", "lan.example/24", "192.168.1.0/-1",
            "192.168.1.0/x", ""})
    void aMalformedRangeFailsAtStartup(String cidr) {
        assertThatThrownBy(() -> new RemoteAddresses(new LanProperties(List.of(cidr))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePropertyBindsWithTheD140DefaultList() {
        new ApplicationContextRunner().withUserConfiguration(AddressesOnly.class)
                .run(context -> assertThat(context.getBean(LanProperties.class).lanCidrs())
                        .containsExactly("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                                "fc00::/7", "fe80::/10"));
        new ApplicationContextRunner().withUserConfiguration(AddressesOnly.class)
                .withPropertyValues("restaurantpro.security.lan-cidrs=192.168.1.0/24")
                .run(context -> assertThat(context.getBean(RemoteAddresses.class).isRemote(from("10.0.0.1")))
                        .isTrue());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LanProperties.class)
    @Import(RemoteAddresses.class)
    static class AddressesOnly {
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }
}
