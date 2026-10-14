# Browser authentication, MFA and sessions

The backend implements authentication, optional Google OpenID Connect, and
household/account APIs. The frontend, local-signup email verification,
invitations, email/password recovery delivery, passkeys,
uploads, reporting, and complete ledger deletion remain pending. These APIs
are a backend slice, not a finished expense tracker.

## Run and key configuration

Use Java 21, a dedicated PostgreSQL application database, and the process
environment described in [backend setup](../backend/README.md).
`MFA_ENCRYPTION_KEY` is required at startup, even before anyone enables MFA.
It must be canonical Base64 encoding of 32 cryptographically random bytes.
Generate it once for the database, store it in private secret storage, and
reuse it across restarts and all application instances. For example, in PowerShell:

```powershell
$mfaKeyBytes = New-Object byte[] 32
$mfaKeyGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$mfaKeyGenerator.GetBytes($mfaKeyBytes)
$env:MFA_ENCRYPTION_KEY = [Convert]::ToBase64String($mfaKeyBytes)
$mfaKeyGenerator.Dispose()
```

This sets the current process environment; it does not persist the key for
the next shell. Losing or replacing the key makes stored TOTP secrets
undecryptable. No key-rotation workflow is implemented. TOTP secrets use
AES-256-GCM with a fresh nonce and user identity bound to the ciphertext.
Recovery codes are stored as user-bound SHA-256 hashes.

Production requires TLS. The `SESSION` cookie defaults to Secure, HttpOnly
and SameSite=Lax, with path `/`. For local HTTP only, explicitly set
`SESSION_COOKIE_SECURE=false`. Keep client and API on the same origin; no
cross-origin authentication flow is configured. `.env.example` is documentation,
not an automatically loaded environment file.

## Client flow

Client API paths use `/api/v1/`; Google browser redirects use the explicit
absolute paths in their section below. Preserve cookies between requests.
All mutations, including signup, login, MFA completion and logout, require
the CSRF token; the default request header is `X-CSRF-TOKEN`.

1. `GET auth/csrf/` returns `token`, `headerName` and `parameterName`, and
   establishes the anonymous session. Send that token with every POST, PUT,
   PATCH or DELETE.
2. `POST auth/signup/` accepts `email`, `displayName` and `password`, returns
   201 and the created user, and leaves the browser unauthenticated. Passwords
   require at least 12 Unicode code points and at most 72 UTF-8 bytes; they
   are never trimmed or truncated before BCrypt hashing.
3. `POST auth/login/` accepts `email` and `password`. Without MFA it returns
   200 with `mfaRequired:false` and `user`. With MFA it returns 202 with
   `mfaRequired:true` and `user:null`; a password-verified challenge lasts
   five minutes and grants no authenticated access.
4. Fetch a fresh CSRF token after either login response. For a challenge,
   `POST auth/login/mfa/` accepts `code`: a TOTP or an unused recovery code.
   Successful completion returns 200 and establishes authentication. Fetch
   the CSRF token again because authentication rotates both session ID and
   CSRF state.
5. `GET auth/me/` returns the current user, including `passwordSet`, which
   indicates whether local password login is available. `POST auth/logout/` returns 204,
   clears authentication and invalidates the session. Bootstrap a fresh CSRF
   token before another login.

`POST auth/password/change/` accepts `currentPassword`, `newPassword`, and
`code` when MFA is enabled. Successful password/MFA changes refresh the current
authentication and CSRF state; fetch a new token before the next mutation.
Validation failures return bounded error codes. Invalid login credentials use
a generic response, and private responses use `Cache-Control: private, no-store`.

## Optional Google login and explicit linking

Google login/linking is implemented but disabled by default with
`GOOGLE_OAUTH_ENABLED=false`. To enable it,
configure `GOOGLE_OAUTH_ENABLED=true`, `GOOGLE_CLIENT_ID` and
`GOOGLE_CLIENT_SECRET`. Register the exact callback
`{baseUrl}/login/oauth2/code/google` in the Google OAuth client configuration,
where `baseUrl` is the application's externally visible origin. Production
callbacks require HTTPS. Keep the client secret in private environment/secret
storage. Google tests use a mocked provider, never real Google credentials.

`GET /oauth2/authorization/google` starts the browser authorization flow. The
provider subject is the stable identity, and Google must verify the supplied
email. A matching email alone never links a Google subject to an existing
local account. To link, sign in locally first, then send a CSRF-protected
`POST /api/v1/auth/oauth/google/link/` with `currentPassword` and a factor
`code` when MFA is enabled. It prepares a five-minute link marker and returns
`{"authorizationPath":"/oauth2/authorization/google"}`; navigate to that path.
Successful linking increments the user authentication version and revokes
other sessions. Existing household ownership remains with that user.

A newly created Google account has local-password login disabled. To establish
its initial local password, `POST /api/v1/auth/password/set/` accepts
`newPassword` and `code` if MFA is enabled, and requires a successful Google
authentication within the preceding five minutes. It applies the same password
policy, version revocation and CSRF refresh. Set a local password before using
the password-based MFA management/reauthentication endpoints.

If MFA is enabled, Google login also creates an unauthenticated five-minute
challenge and returns 202. Complete `auth/login/mfa/` with TOTP/recovery code,
then fetch fresh CSRF state. The OAuth callback never bypasses the MFA gate.
Fetch CSRF again after Google sign-in or linking as well.
Failed OAuth callbacks preserve any existing local application session; an
untrusted invalid-state GET cannot act as logout. Provider tokens are never
stored in the local session or returned in the response.

## MFA and session management

| Route | Behavior |
| --- | --- |
| GET auth/mfa/ | Enabled state, enrollment status and remaining recovery-code count |
| POST auth/mfa/setup/ | Require `currentPassword`; return secret, authenticator URI and ten-minute enrollment expiry |
| POST auth/mfa/confirm/ | Accept `code`, enable MFA, return ten recovery codes |
| POST auth/mfa/disable/ | Require `currentPassword` and `code`; return 204 |
| POST auth/mfa/recovery-codes/regenerate/ | Require password and factor; replace all recovery codes |
| GET auth/sessions/ | List the user's valid sessions and mark the current one |
| DELETE auth/sessions/{id}/ | Revoke one own session by its opaque public ID |
| POST auth/sessions/revoke-others/ | Require `currentPassword` and a factor if enabled; preserve the current session |

TOTP uses six digits, a 30-second period and the previous/current/next time
step. An accepted step must be newer than the last accepted step, so an
already-used code cannot be replayed. Recovery codes are ten independently
generated 128-bit values, each rendered as 32 lowercase hexadecimal characters.
Their plaintext is returned only on confirmation/regeneration; store it
privately. Each code is consumed once, including concurrent attempts. TOTP
and recovery codes can complete login or required factor reauthentication.

Spring Session JDBC persists sessions in PostgreSQL. Idle timeout is 30
minutes and absolute lifetime is 12 hours. Password changes, MFA enable/disable,
and recovery-code regeneration increment the user's authentication version;
other sessions fail the version check on their next request. Public session
handles are independent database record UUIDs, never reusable cookie session
IDs. Foreign handles return not-found. User security changes serialize on the
user row and check the expected authentication version after acquiring the lock.

## Durable request limits

| Bucket | Limit |
| --- | --- |
| Signup network address | 10 per hour |
| Login network address | 100 per 15 minutes |
| Login normalized email | 20 per 15 minutes |
| Google authorization/callback network address | 60 per 15 minutes |
| MFA/sensitive-action network address | 60 per 15 minutes |
| MFA/sensitive-action user | 10 per 5 minutes |
| Security-action user | 20 per 15 minutes |

Limits apply to attempts, not just failures. Separate account/network buckets
use atomic PostgreSQL windows starting at the first attempt; denied attempts
do not extend the window. A blocked attempt returns 429 with `Retry-After`
in seconds. Consumption survives authentication transaction rollback, and
expired buckets are cleaned in bounded batches. Only digests are persisted,
without raw email or network addresses. Network keys use the servlet remote
address; untrusted `X-Forwarded-For` is not accepted as client identity.

Authentication never grants household access by itself. Every household/account
operation still checks current membership and related-object scope. See
[implemented routes and planned endpoints](api-plan.md) and
[the next work](next-move.md).

Verification on 2026-10-14: Java 21 Maven `verify` passed 36 unit and 88 real
PostgreSQL integration tests (124 total), with no failures, errors or skips.
The run used an isolated PostgreSQL 16.4 database and signed loopback Google
provider; Docker/Testcontainers and live Google were not exercised. See
[the implementation note](../codex/2026-10-14-security-backend.md).
