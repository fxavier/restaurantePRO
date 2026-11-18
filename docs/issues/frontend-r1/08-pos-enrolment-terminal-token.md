# 08: POS enrolment and terminal token

**Status:** ready-for-agent
**Blocked by:** 04
**Owner:** `frontend-developer`. Spec: PRD stories 21–22, 30, AC3, AC8, AC9; D182; FC §6.

## What to build

A terminal with no device key opens on the enrolment screen. Entering the one-time code:

1. generates a non-extractable P-256 key;
2. stores the key and the answered client id and store in IndexedDB;
3. completes enrolment.

From then on the app gets its terminal access token from the devices realm with an ES256 client assertion signed in the browser. It keeps the token in memory and renews it before expiry. If Keycloak or the backend is unreachable, the app shows "Sem ligação". A revoked terminal (its token request is refused) returns to enrolment, with an explanation.

## Acceptance criteria

- [ ] Unit tests prove the assertion claims (`iss`, `sub`, `aud`, `jti`, `exp`) and the ES256 signature, verified with the public key. The key is proven non-extractable.
- [ ] App-level tests cover: a wrong, used or expired code; throttling (429); successful enrolment; renewal before expiry; Keycloak unreachable; and a revoked terminal.
- [ ] Nothing about the token is written to `localStorage` or `sessionStorage`.
- [ ] Touch targets follow the DS, and the screens are tested at 1024×768.
