package com.xavier.backend.identity.oidc;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Calls to the OpenID Provider never wait long (S57 review): an IdP that accepts the connection and never answers
 * (a black hole) must not hold a request thread. Spring's own clients have no timeout at all.
 */
final class OidcHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private OidcHttp() {
    }

    /** For the discovery document. */
    static RestClient discoveryClient() {
        return RestClient.builder().requestFactory(requestFactory()).build();
    }

    /** Spring's authorization-code token client, with its converters and OAuth2 error handler, plus the timeouts. */
    static RestClientAuthorizationCodeTokenResponseClient tokenClient() {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .requestFactory(requestFactory())
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return client;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
