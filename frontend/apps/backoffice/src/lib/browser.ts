/** Where the BFF starts the Keycloak login (bff/README). A top-level navigation, never a fetch (FC §6). */
export const LOGIN_PATH = '/oauth2/authorization/keycloak';

/** Top-level navigations that leave the SPA; one seam, so tests record them instead of running them. */
export const browser = {
  assign(url: string) {
    window.location.assign(url);
  },
};

export function goToLogin() {
  browser.assign(LOGIN_PATH);
}
