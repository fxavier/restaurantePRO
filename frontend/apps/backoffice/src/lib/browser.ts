/** Where the BFF starts the Keycloak login (bff/README). A top-level navigation, never a fetch (FC §6). */
export const LOGIN_PATH = '/oauth2/authorization/keycloak';

/** Top-level navigations that leave the SPA; one seam, so tests record them instead of running them. */
export const browser = {
  assign(url: string) {
    window.location.assign(url);
  },
};

/**
 * Loop guard: when the BFF cookie never sticks, every login lands back on an expired session. The time of the last
 * automatic redirect is kept in sessionStorage (a timestamp, no secret); an expiry within LOOP_WINDOW of it, on a later
 * page load, stops and asks the user instead of redirecting again.
 */
const REDIRECT_AT = 'restaurantpro.backoffice.loginRedirectAt';
const LOOP_WINDOW = 30_000;
let redirecting = false;

/** True when this page load already redirected, or did not because the previous redirect was too recent. */
export function loginStuck(): boolean {
  if (redirecting) return false;
  const at = Number(sessionStorage.getItem(REDIRECT_AT));
  return at > 0 && Date.now() - at < LOOP_WINDOW;
}

/** The automatic redirect on an expired session; at most once per page load, and not when looping. */
export function goToLogin() {
  if (redirecting || loginStuck()) return;
  startLogin();
}

/** The user's own "Iniciar sessão": always navigates. */
export function startLogin() {
  redirecting = true;
  sessionStorage.setItem(REDIRECT_AT, String(Date.now()));
  browser.assign(LOGIN_PATH);
}

/** A working session ends the guard. */
export function loginSucceeded() {
  sessionStorage.removeItem(REDIRECT_AT);
}

/** For tests: a new page load. */
export function resetLoginGuard() {
  redirecting = false;
}
