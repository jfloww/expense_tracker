# Shared utilities - planned

Reserved for pure TypeScript helpers used by web and future mobile. No runnable
package or formatter implementation exists yet. Implement it with the first
Next.js workflow, following [engineering conventions](../../docs/engineering-conventions.md).

Planned named modules:

- `dates.ts`: validate date-only/month/UTC instant values; expose
  `formatPostedDate`, `formatMonth`, and `formatTimestamp` separately.
- `money.ts`: validate canonical minor-unit strings and expose exact
  `formatMoney` with an explicit currency and locale.
- `api-errors.ts`: shared error value types and safe normalization; HTTP
  transport and UI messages stay in their respective application.

This package has no React, Next.js, browser globals, environment access, network
calls, secrets, or provider SDKs. Generated wire DTOs belong in
[contracts](../contracts/README.md). Financial calculations and household
authorization stay in the Java Spring Boot backend. Java and TypeScript share
API contracts and synthetic behavior fixtures, not one source implementation.
Keep helpers local to a feature until their behavior is actually shared.
