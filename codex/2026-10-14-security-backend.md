# Authentication and security implementation — 2026-10-14

The backend now has CSRF-protected signup, password login, logout, current-user
lookup, password changes, and PostgreSQL-backed browser sessions. Optional Google
OIDC uses the authorization-code flow with state, nonce and PKCE. TOTP MFA adds
encrypted secrets, expiring enrollment, replay prevention and single-use recovery
codes. Household/account HTTP routes use the existing authorized services.

The source layout is now `backend/`, `frontend/`, `mobile/`, `worker/`,
`packages/`, `codex/`, `claude/`, and `docs/`. The Next.js directory remains a
planned client. The ignored legacy database was moved to private storage with
its checksum unchanged. Dated review snapshots retain their earlier paths.

## Decisions and review findings

- Use Spring Security's session strategy and explicitly save a local
  `SessionPrincipal`. Rotate the cookie identifier and CSRF state on sign-in
  and credential changes. Idle lifetime is 30 minutes; absolute lifetime is
  12 hours. User authentication versions invalidate older sessions.
- Serialize credential/MFA writes on the user row and check the expected
  version after obtaining the lock. Refresh cached entities without discarding
  earlier authorized changes in a surrounding transaction. Household writes
  retain their separate household lock and membership protocol.
- Google identities use the verified issuer/subject. Matching email does not
  automatically link an existing user. Explicit linking requires local
  reauthentication, and Google login still requires an enabled local MFA factor.
- Google tokens and intermediate OIDC principals are never persisted as local
  authentication. Spring's shared explicit-save configuration replaced the
  OAuth filter's fluent repository setting during an integration test; a final
  filter postprocessor applies the null repository after that configuration.
  Failed callbacks preserve an existing local session, preventing logout CSRF.
- Newly created Google users cannot log in with a local password until they
  set one using recent server-issued Google authentication proof. Proof identity,
  version and expiry are checked in the credential mutation transaction.
- The four pre-controller foundation findings are addressed: inaccessible
  households consistently return 404, JSON includes null fields, account updates
  use full PUT with required `active`, and wrapper line endings are specified.

## Verification and remaining work

Verification uses Java 21 and a disposable PostgreSQL 16.4 database. Each test
JVM migrates a fresh schema. Google tests use a signed loopback-only provider;
no Google credentials, live Google calls, private statements or API keys are
required. Tests cover cookie/CSRF flows, identity and household isolation,
disabled users, revocation, MFA replay/concurrency, exact version checks, durable
attempt limits, state/nonce/PKCE and signature/issuer/audience/expiry rejection.
Final test counts are recorded in [backend setup](../backend/README.md).

Deployment requires a stable private `MFA_ENCRYPTION_KEY`; Google remains
disabled until its client credentials and callback are configured. Local-signup
email verification, invitation/recovery delivery, passkeys, frontend workflows,
OpenAPI generation, imports, reporting and complete ledger deletion remain
separate work. See [security setup and API flow](../docs/security.md).
