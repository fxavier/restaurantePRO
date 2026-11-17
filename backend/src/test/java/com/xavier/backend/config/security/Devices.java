package com.xavier.backend.config.security;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.xavier.backend.identity.IdentityFixtures;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Store devices as an administrator and the device itself create them over HTTP (D182, D187): a terminal or print
 * bridge registered in a store, its one-time code, and the device's enrolment with a fresh P-256 public key from
 * {@code address}. Needs {@link DeviceHttpTest} (the faked devices realm).
 */
public final class Devices {

    public static final String POS_ENROLMENT = "/api/v1/pos/enrolment";
    public static final String BRIDGE_ENROLMENT = "/api/v1/bridge/enrolment";

    private final MockMvc mvc;

    public Devices(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** An enrolled device: its id, store and Keycloak client. */
    public record Device(UUID id, UUID storeId, String clientId) {

        /** A fresh access token of the device's client (client credentials, private_key_jwt at Keycloak). */
        public RequestPostProcessor bearer() {
            return TestJwtFactory.device(clientId, FakeDeviceProvider.subjectOf(clientId)).bearer();
        }
    }

    /** A registered, not yet enrolled device of {@code kind} ({@code POS} or {@code PRINT_BRIDGE}) in {@code store}. */
    public UUID register(UUID store, String kind) throws Exception {
        return IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/terminals").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("storeId", store, "code", IdentityFixtures.unique("D"),
                                "name", "Device", "kind", kind)))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    /** A new one-time code of {@code device}. */
    public String code(UUID device) throws Exception {
        return IdentityFixtures.JSON.readTree(mvc.perform(post("/api/v1/backoffice/terminals/" + device
                                + "/enrollment-code").with(admin()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("code").asString();
    }

    /** The device's enrolment request, from {@code address}. */
    public ResultActions enrol(String path, String code, Map<String, Object> publicJwk, String address)
            throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(IdentityFixtures.json("code", code, "publicJwk", publicJwk))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    /** A terminal of {@code store}, enrolled with its code. */
    public Device enrolledTerminal(UUID store) throws Exception {
        return enrolled(store, "POS", POS_ENROLMENT);
    }

    /** A print bridge of {@code store}, enrolled with its code. */
    public Device enrolledBridge(UUID store) throws Exception {
        return enrolled(store, "PRINT_BRIDGE", BRIDGE_ENROLMENT);
    }

    private Device enrolled(UUID store, String kind, String path) throws Exception {
        UUID device = register(store, kind);
        JsonNode answer = IdentityFixtures.JSON.readTree(enrol(path, code(device), publicJwk(), randomAddress())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Device(device, store, answer.get("clientId").asString());
    }

    /** A new device key pair's public half, as WebCrypto exports it. */
    public static Map<String, Object> publicJwk() {
        try {
            ECKey key = new ECKeyGenerator(Curve.P_256).generate();
            return key.toPublicJWK().toJSONObject();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A source address no other test uses (the enrolment throttle counts per address). */
    public static String randomAddress() {
        UUID random = UUID.randomUUID();
        return "10." + Math.floorMod(random.getMostSignificantBits(), 250) + "."
                + Math.floorMod(random.getLeastSignificantBits(), 250) + "." + (1 + Math.floorMod(random.hashCode(), 250));
    }
}
