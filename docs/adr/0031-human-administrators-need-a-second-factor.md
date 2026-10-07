# 0031. A human administrator needs a second factor, and the API checks it

- Status: **Proposed**, 2026-10-06. Nothing is built: no user holds `shortlinks:admin` today
- Evidence: [THREAT_MODEL.md](../THREAT_MODEL.md) (risks accepted, E), [0007](0007-owner-claim-and-ownership-rule.md), [0030](0030-web-ui-as-a-static-spa.md), `deploy/keycloak/shortener-realm.production.template.json`

## Problem

`shortlinks:admin` takes down any link. Only `admin-client` holds it: a service account that signs in with an ES256 key and gets a
DPoP-bound token, in a realm with no users. A person cannot be asked for a second factor there, so there is none to ask for.

The web UI ([0030](0030-web-ui-as-a-static-spa.md)) brings users, and an administrator who is a person signs in with a password.
A phished or reused password would then take down any link, with nothing else in the way.

## Decision

Before any user holds `shortlinks:admin`:

- **Keycloak grants it only after a second factor.** The scope is optional on `shortener-ui` and goes only to the `admins` group.
  The browser flow maps authentication levels to `acr`: 1 is a password, 2 is OTP or WebAuthn. The client asks for level 2 when it
  asks for the scope, so a signed-in user steps up to become an administrator.
- **The API checks it too.** A token whose `owner` starts with `user:` and that carries `shortlinks:admin` without `acr` 2 or
  higher does not get the admin authority. Its other scopes stand, and an admin operation is denied as for any caller without the
  scope. A service account is not a person and is not asked.
- **The service account stays as it is.** Its second factor cannot be a prompt, so it is the key. Keep it on the operator's machine,
  encrypted at rest, and never on the VM.

## Cost

- A change to the JWT converter and a test with tokens of each kind: a user with and without level 2, and a service account.
- Keycloak's levels of authentication, with DPoP, a public client and step-up together, are assumed, not verified. It is a task of
  phase 0 of [UI.md](../UI.md). If a token comes out with no `acr`, administrators fail closed and cannot act, which is safe and
  visible.
- Someone enrols the first factor of each administrator.
- **A gap this does not close:** replacing the key of `admin-client` has no procedure yet. A lost or exposed key is handled by
  making a new one and importing the realm again, which is not written down.
- How long a step-up lasts (`max_age`) is settled in the spike.

## Rejected

- Keycloak alone: a flow set up wrong would issue an administrator token with no factor, and nothing in the API would notice.
- No person is ever an administrator: the UI could not take a link down, and the operator would use a command line and a key,
  which is one factor. It is the position until this is built.
- A second factor for `admin-client`: it has no person to ask.
