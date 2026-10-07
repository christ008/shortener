# Web UI plan

Plan for a browser front end. **Nothing here is built.**

- [Scope](#scope)
- [Choices](#choices)
- [Link ownership](#link-ownership)
- [Agent skills](#agent-skills)
- [Work outside the UI](#work-outside-the-ui)
- [Structure](#structure)
- [Sign-in and requests](#sign-in-and-requests)
- [Screens](#screens)
- [Testing](#testing)
- [Security](#security)
- [Delivery](#delivery)
- [Phases](#phases)
- [Risks and open questions](#risks-and-open-questions)

## Scope

People sign in, create short links (optionally with a custom code), list them, and disable them. An administrator sees every
user's links. The UI is a client of the existing API and adds no server-side logic.

In scope: sign-in and sign-out, create, list with paging and sorting, copy and QR code, disable, error and rate-limit
handling, light and dark themes, keyboard use, responsive layout. Out of scope: analytics, link editing or expiry, teams or
shared ownership.

## Choices

| Area | Choice |
|---|---|
| Framework | TanStack Start in SPA mode, TanStack Router and Query |
| Components | Mantine |
| Tooling | TypeScript (strict), Vite, pnpm, Vitest, Playwright |
| Browser sign-in | OAuth 2.0 authorization code with PKCE, public client, DPoP-bound tokens |
| OAuth library | `oauth4webapi` |
| API client | types generated from `docs/openapi.yaml` with `openapi-typescript`, called through `openapi-fetch` |
| Hosting | static files in an unprivileged nginx container, behind the existing gateway under `/app` |

Authentication lives in a module with no UI imports (`src/auth`).

## Link ownership

A link's owner is the token's `azp` claim, read through `shortener.security.client-id-claim`. For user tokens `azp` is the
UI's client, so users need another claim. No service code changes:

- Keycloak adds an `owner` claim to every token: the client id for service accounts, `user:<sub>` for users.
- The service sets `shortener.security.client-id-claim=owner`.
- `created_by` is free text: no migration.

Each user owns their own links. Admins are users with the `shortlinks:admin` scope.

## Agent skills

Install when the project is scaffolded:

- **Mantine:** `mantinedev/skills` (`mantine-form`, `mantine-combobox`, `mantine-custom-components`), each with
  `npx skills add https://github.com/mantinedev/skills --skill <name>`. Also `https://mantine.dev/llms.txt` and
  `@mantine/mcp-server`.
- **TanStack:** after `pnpm install`, `npx @tanstack/intent@latest list`, then `install` and `hooks install`. Which
  TanStack packages ship skills was not confirmed: `list` shows it.

## Work outside the UI

Done first; each item is testable without a front end.

1. **Keycloak**
   - Users: two regular users, one administrator.
   - Public client `shortener-ui`: authorization code with PKCE (S256), DPoP-bound access tokens, exact redirect URIs, web
     origins, refresh token rotation, short access token lifetime.
   - Scopes: `create`, `claim`, `read`, `delete` for users; `admin` for the administrator only.
   - The `owner` claim mapper on the UI client and the service-account clients.
2. **Service:** set `client-id-claim` to `owner`. `ClientJwtAuthenticationConverter` already takes the claim name from
   configuration. Add a test with a user token.
3. **Gateway:** `/app/*` to the UI, `/api/*` and single-segment short codes to the API, `/realms/*` to Keycloak. The short-code
   route must not match `/app`.
4. **Reserved code:** `app` is among the reserved short codes (done).

## Structure

```
ui/
  src/
    auth/            no UI imports: PKCE and login redirect, DPoP key, token store, refresh, logout, fetch with proofs
    api/             types generated from docs/openapi.yaml, and a client that uses auth's fetch
    routes/          TanStack Router file routes: __root, index, links/index, auth/callback
    components/      LinkTable, CreateLinkModal, DisableConfirm, QrCode, ProblemAlert
    theme.ts         Mantine theme
  e2e/               Playwright specs against the compose stack
  Dockerfile         node build, then nginx-unprivileged
  nginx.conf         static files, SPA fallback to the shell, security headers
```

## Sign-in and requests

```
Browser                                   Keycloak                     nginx -> API
  | generate ES256 key (non-extractable)
  | redirect to /auth?code_challenge&dpop_jkt ->
  |                                         login
  | <- redirect /app/auth/callback?code
  | POST /token  code_verifier + DPoP proof ->
  | <- access token (cnf.jkt) + refresh token
  | GET /api/short-links
  |   Authorization: DPoP <token>, DPoP: <proof for this request> ------------------->
  |                                                                       validates proof
```

- The key pair is non-extractable (WebCrypto), stored in IndexedDB.
- The access token stays in memory. The refresh token is bound to the same key.
- Every call signs a fresh proof (method, URL without query, token hash, unique id, time). On a DPoP nonce from the server,
  the call is repeated once with it.
- A `401` triggers one refresh and one retry, then a return to sign-in.
- The k6 script signs proofs the same way in JavaScript (reference).

## Screens

- **Shell:** header with the signed-in user, theme switch, sign-out. Routes under `/app`.
- **Links** (`/app/links`): table of short code, target, created, status. Numbered pager from `page`, `size`, `totalItems`,
  `totalPages` (first, previous, numbers, next, last, jump to page). A page past the end shows the last one. Sort by created
  or code. Owner filter for administrators. Empty and loading states.
- **Create:** modal with target URL and, when the user has `claim`, an optional custom code: with one it is a `PUT` to
  `/api/short-links/<code>`, without one a `POST`. Same validation as the API. A `200` from the `PUT` means the link was already
  there, which is not an error.
  Shows the new short URL with a copy button and a QR code.
- **Disable:** confirmation, then the row shows the disabled state.
- **Errors:** each problem detail becomes a readable message. `409` on a custom code marks that field, `429` shows the
  `Retry-After` countdown, `503` shows a retry banner, `403` names the missing permission.

## Testing

- **Unit** (Vitest, Testing Library): the auth module against a fake token endpoint (PKCE values, proof contents, nonce retry,
  refresh and retry), the problem-detail mapping, each component.
- **End to end** (Playwright) on the compose stack: sign in as each user; create, list, copy, disable; the admin view; another
  user's link invisible; expired session; `429`.
- **Contract:** generated types fail the build when `openapi.yaml` and the UI disagree.
- **Accessibility:** axe checks in the Playwright specs, and a keyboard-only pass of the main flow.

## Security

- nginx sends a Content-Security-Policy: `default-src 'self'`, `connect-src` limited to the origin and the identity provider,
  no inline scripts, `frame-ancestors 'none'`. Verify Mantine's styles work under it at scaffold.
- No cookies.
- Tokens are never written to storage. Only the non-extractable key and the opaque refresh token persist.
- Sign-out ends the Keycloak session too.
- Dependencies are pinned by the lockfile and checked in CI.

## Delivery

- Multi-stage `Dockerfile` (Node build, unprivileged nginx). Shell fallback `/app/_shell.html`. `/app/assets/*` cached long.
- `deploy/stack/compose.prod.yaml`: a `ui` service with the API's hardening, and a `/app` location in the edge.
- `compose.yaml`: a `ui` service; the Vite dev server proxies `/api` and `/realms`.
- CI: install, type-check, lint, unit tests and build on every push; end-to-end in its own job.

## Phases

| # | Phase | Done when |
|---|---|---|
| 0 | **Spike:** public Keycloak client, a throwaway page that completes code, PKCE and DPoP and calls the API | the API answers `200` to a call signed by a browser key; otherwise switch to a backend-for-frontend |
| 1 | **Identity and service:** users, scopes, `owner` claim, gateway routes, reserved `app` | tests show user and client tokens get distinct owners, and a user token with `shortlinks:admin` and no second factor is not an administrator |
| 2 | **Scaffold:** TanStack Start (SPA), Mantine, tooling, agent skills, generated client | `pnpm build` produces the shell, CI is green |
| 3 | **Auth module:** all of `src/auth`, with unit tests | a signed-in page lists links through the gateway |
| 4 | **Features:** list, create, disable, errors, admin view, themes | the Playwright suite passes |
| 5 | **Delivery:** container, manifests, compose, CI job, screenshots, docs | one command starts the system |

## Risks and open questions

- Keycloak DPoP support for public clients in the browser flow is assumed, not verified (phase 0), and so is a step-up to `acr` 2 for `shortlinks:admin` ([ADR 0031](adr/0031-human-administrators-need-a-second-factor.md)). Fallback: a
  backend-for-frontend that holds the tokens, with no browser-side DPoP.
- TanStack Start was a release candidate when this was written. Check the shell file name and base path under `/app` in phase 2.
- Mantine's CSS must load before the app's, and its color scheme script must work in a static shell without a flash.
- A user whose refresh token expires loses unsaved form input.
- Open: sharing links between users, per-link analytics (needs backend support), a short domain different from the UI's.
