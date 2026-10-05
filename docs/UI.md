# Web UI plan

A plan for a browser front end for the shortener, so that the work can be started in small, verifiable steps. Nothing
here is built yet.

- [Goal and scope](#goal-and-scope)
- [Decisions](#decisions)
- [Agent skills and docs](#agent-skills-and-docs)
- [Work outside the UI](#work-outside-the-ui)
- [Architecture](#architecture)
- [Screens](#screens)
- [Testing](#testing)
- [Security](#security)
- [Delivery](#delivery)
- [Phases](#phases)
- [Risks and open questions](#risks-and-open-questions)

## Goal and scope

People sign in, create short links (optionally with a custom code), see their links, and disable them. An administrator
sees every user's links. The UI is a client of the existing API and adds no server-side logic of its own.

In scope: sign-in and sign-out, create, list with paging and sorting, copy and QR code, disable, error and rate-limit
handling, light and dark themes, a keyboard-usable and responsive layout. Out of scope for now: analytics (the service
records none), link editing or expiry (the service has neither), teams or shared ownership.

## Decisions

| Area | Choice | Why |
|---|---|---|
| Framework | TanStack Start in SPA mode, with TanStack Router and Query | file-based type-safe routing and cached server state; SPA mode builds a static shell, so no Node server runs in production |
| Components | Mantine | a complete set of accessible components, hooks and a form library, with theming and dark mode built in |
| Language and tooling | TypeScript in strict mode, Vite, pnpm, Vitest, Playwright | |
| Browser sign-in | OAuth 2.0 authorization code with PKCE, as a public client whose tokens are DPoP-bound | no client secret in the browser, and a stolen token is useless without the key; it is what DPoP was designed for |
| OAuth library | `oauth4webapi` | small, framework-agnostic, and implements DPoP including server nonces; the alternative `oidc-client-ts` is heavier |
| API client | types generated from `docs/openapi.yaml` with `openapi-typescript`, called through `openapi-fetch` | the contract is already tested against the code, so the UI inherits it |
| Hosting | static files in an unprivileged nginx container, behind the existing gateway under `/app` | no CORS, one origin, the same TLS and policies as the API |

Authentication stays in a small module with no UI imports (`src/auth`), so it is testable alone and could be reused by
another front end.

### Ownership of links

Today a link's owner is the client id in the token's `azp` claim, which the service reads through
`shortener.security.client-id-claim`. A user token has `azp` set to the UI's own client, so every user would own
everything under one name. The fix needs no code change in the service:

- Keycloak adds an `owner` claim to every token: for service accounts the client id, for users `user:<sub>`.
- The service is configured with `shortener.security.client-id-claim=owner`.
- `created_by` is already free text, so there is no migration. Clients and users cannot collide because of the prefix.

The assumption is that each user owns only their own links, and that admins are users with the `shortlinks:admin`
scope. Teams are a separate, later decision.

## Agent skills and docs

Both libraries publish material for coding agents. They are installed when the project is scaffolded, not before:

- **Mantine.** `mantinedev/skills` has three skills: `mantine-form` (forms, validation, nested fields), `mantine-combobox`
  (custom selects and autocompletes) and `mantine-custom-components` (components that use the theme and the Styles API).
  Install each with `npx skills add https://github.com/mantinedev/skills --skill <name>`. Mantine also publishes
  `https://mantine.dev/llms.txt`, refreshed with each release, and an `@mantine/mcp-server`.
- **TanStack.** `@tanstack/intent` ships skills inside the npm packages that provide them, so the guidance matches the
  installed version. After `pnpm install`: `npx @tanstack/intent@latest list`, then `install` (it writes the task to skill
  mappings into `CLAUDE.md` or `AGENTS.md`) and `hooks install`. Which TanStack packages ship skills today was not
  confirmed, and `list` shows exactly that.

## Work outside the UI

Done first, because each item can be tested without any front end.

1. **Keycloak.**
   - Users: two regular users and one administrator.
   - A public client `shortener-ui`: authorization code with PKCE (S256), DPoP-bound access tokens, exact redirect URIs,
     web origins for the UI, refresh token rotation, short access token lifetime.
   - Scopes: `create`, `claim`, `read` and `delete` for users; `admin` only for the administrator.
   - The `owner` claim mapper described above, on both the UI client and the service-account clients.
2. **Service.** Set `client-id-claim` to `owner`; the `ClientJwtAuthenticationConverter` and its tests already take the
   claim name from configuration. Add a test with a user token.
3. **Gateway.** Route `/app/*` to the UI service, `/api/*` and single-segment short codes to the API, and `/realms/*` to
   Keycloak. The short-code route must not swallow `/app`, which is a reserved word.
4. **Reserved code.** Add `app` to the reserved short codes so nobody can claim it.

The first item holds the largest unknown: that Keycloak issues DPoP-bound tokens to a public client through the browser
flow. The first phase is a spike that proves it before anything else is built.

## Architecture

```
ui/
  src/
    auth/            no UI imports: PKCE and login redirect, DPoP key, token store, refresh, logout, fetch with proofs
    api/             generated types from docs/openapi.yaml, and a client that uses auth's fetch
    routes/          TanStack Router file routes: __root, index, links/index, auth/callback
    components/      LinkTable, CreateLinkModal, DisableConfirm, QrCode, ProblemAlert
    theme.ts         Mantine theme
  e2e/               Playwright specs against the compose stack
  Dockerfile         node build, then nginx-unprivileged
  nginx.conf         static files, SPA fallback to the shell, security headers
```

### Sign-in and requests

```
Browser                                   Keycloak                     Envoy -> API
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

- The key pair is generated with WebCrypto as non-extractable and kept in IndexedDB, which can store a `CryptoKey`
  without exposing its bytes. Page scripts can use the key but cannot copy it out.
- The access token stays in memory. The refresh token is bound to the same key, so it is useless elsewhere.
- Every call signs a fresh proof (method, URL without the query, token hash, unique id, time). If the server answers with a
  DPoP nonce, the call is repeated once with it.
- A `401` triggers one refresh and one retry, then a return to sign-in.
- The k6 script already signs proofs the same way in JavaScript, which is a working reference.

## Screens

- **Shell**: header with the signed-in user, theme switch and sign-out; routes under `/app`.
- **Links** (`/app/links`): a table of short code, target, created, status; paging from `page`, `size`, `hasNext`; sort by
  created or code; an owner filter for administrators; empty and loading states.
- **Create**: a modal with a form (target URL, optional custom code shown only when the user holds the `claim` scope),
  validated with the same rules as the API, showing the new short URL with a copy button and a QR code.
- **Disable**: a confirmation, then the row shows the disabled state; the same action repeated is harmless.
- **Errors**: every problem detail becomes a readable message; a `409` on a custom code marks that field; a `429` shows
  the `Retry-After` countdown; a `503` shows a retry banner; a `403` explains the missing permission.

## Testing

- **Unit** (Vitest, Testing Library): the auth module with a fake token endpoint (PKCE values, proof contents, nonce retry,
  refresh and retry), the problem-detail mapping, and each component.
- **End to end** (Playwright): the real compose stack with Postgres, Keycloak, the API and the built UI. Sign in as each
  user and check the full path through the gateway: create, list, copy, disable, the admin view, another user's link being
  invisible, an expired session, and a `429`.
- **Contract**: the generated types fail the build when `openapi.yaml` and the UI disagree; the existing contract test
  keeps the file and the service in step.
- **Accessibility**: axe checks inside the Playwright specs, and a keyboard-only pass of the main flow.

## Security

- Content-Security-Policy from nginx: `default-src 'self'`, `connect-src` limited to the origin and the identity
  provider, no inline scripts, `frame-ancestors 'none'`. Mantine's styles must work under this policy; that is checked in
  the first scaffold.
- No cookies are used, so cross-site request forgery does not apply.
- Tokens are never written to storage; only the non-extractable key and the opaque refresh token persist.
- Sign-out ends the Keycloak session as well as the local one.
- Dependencies are pinned by the lockfile and checked in CI.

## Delivery

- A multi-stage `Dockerfile` builds with Node and serves with an unprivileged nginx; the shell fallback is
  `/app/_shell.html`, and `/app/assets/*` is cached for a long time because file names carry hashes.
- Kubernetes: a `ui` Deployment and Service in the base, with the same pod hardening as the API (non-root, read-only root
  filesystem, no capabilities), and an `HTTPRoute` for `/app` in each overlay.
- Compose: a `ui` service so that `docker compose up` gives the whole system; the Vite dev server proxies `/api` and
  `/realms` for hot reloading.
- CI: install, type-check, lint, unit tests and build on every push; the end-to-end suite in its own job.

## Phases

| # | Phase | Done when |
|---|---|---|
| 0 | **Spike**: Keycloak public client, a throwaway page that completes code, PKCE and DPoP and calls the API | the API returns `200` to a call signed by a browser key; if it cannot be done, the plan changes to a backend-for-frontend |
| 1 | **Identity and service**: users, scopes, the `owner` claim, the gateway routes, the reserved `app` code | tests prove user and client tokens get distinct owners |
| 2 | **Scaffold**: TanStack Start in SPA mode, Mantine, tooling, the agent skills, the generated client | `pnpm build` produces the shell and CI is green |
| 3 | **Auth module**: everything in `src/auth` with unit tests | a signed-in page can list links through the gateway |
| 4 | **Features**: list, create, disable, errors, admin view, themes | the Playwright suite passes |
| 5 | **Delivery**: container, manifests, compose, CI job, screenshots, documentation | one command starts the system and the README shows it |

## Risks and open questions

- Keycloak's DPoP support for public clients in the browser flow is assumed, not verified; phase 0 settles it. A fallback is
  a small backend-for-frontend that holds the tokens, at the cost of a server component and no browser-side DPoP.
- TanStack Start was a release candidate when this was written. SPA mode is documented as supported, but the shell file
  name and base path handling under `/app` need checking in phase 2.
- Mantine's CSS has to be loaded before the app's own, and its color scheme script must work in a static shell without a
  flash of the wrong theme.
- A user whose refresh token expires loses unsaved form input; the create form is short, so this is acceptable.
- Open: whether links should ever be shared between users, whether to show per-link analytics (it needs backend support),
  and whether the short domain should differ from the UI's.
