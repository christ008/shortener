# 0031. A human administrator needs a second factor, and the API checks it

- Status: Accepted, 2026-10-06; the API check built 2026-10-12. The realm half waits for the UI: no user holds `shortlinks:admin` today
- Evidence: `AdminAssurance`, `AdminAssuranceTest`, `AdminMfaIntegrationTest`, `SecurityProblemResponderTest`, `deploy/keycloak/realm-step-up.json`, `RealmsTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) (risks accepted, E), [0007](0007-owner-claim-and-ownership-rule.md), [0030](0030-web-ui-as-a-static-spa.md), RFC 9470

## Problem

`shortlinks:admin` takes down any link. Only `admin-client` holds it: a service account that signs in with an ES256 key and gets a
DPoP-bound token, in a realm with no users. A person cannot be asked for a second factor there, so there is none to ask for.

The web UI ([0030](0030-web-ui-as-a-static-spa.md)) brings users, and an administrator who is a person signs in with a password.
A phished or reused password would then take down any link, with nothing else in the way. A second factor in the identity provider
protects the account and not the API, which sees a scope in a token: measured on Keycloak 26.8.0 with its own flows, a person with a
one-time code configured who did not use it and one who did both got `acr: 1`, and no `amr` is written.

## Decision

Before any user holds `shortlinks:admin`:

- **Keycloak grants it only after a second factor.** The scope is optional on `shortener-ui` and goes only to the `admins` group.
  The browser flow maps authentication levels to `acr`: a password is level 1 and a password with a one-time code is level 2, written as
  numbers. The client asks for `acr_values=2` when it asks for the scope, so a signed-in user steps up to become an administrator.
  `deploy/keycloak/realm-step-up.json` is that flow.
- **The API checks it too.** A token that carries `shortlinks:admin` is accepted when its `acr` claim is one of
  a number of at least `shortener.security.admin.min-level` (2) or one of `shortener.security.admin.acr-values` (names a realm gives such
  levels, none by default), or when its client is one of `shortener.security.admin.exempt-clients`. Anything else is a `401` with
  `error="insufficient_user_authentication"` and `acr_values="2"` (RFC 9470, section 3), so a client can sign the person in again asking for
  that level. A token without the scope is not looked at.
- **The service account stays as it is, and is listed.** Its second factor cannot be a prompt, so it is the key. The stack lists it
  (`ADMIN_SERVICE_CLIENTS`, `admin-client` by default) and the application lists none, so a token nobody listed fails closed. Keep the key on
  the operator's machine, encrypted at rest, and never on the VM.

## As built, and where it differs from the proposal

- **A refusal, not a downgrade.** The proposal denied the admin authority and left the token's other scopes standing. A token with the scope
  and no factor is a misconfiguration (the client should have stepped up first), and a `401` that says what level is needed is loud where a
  silent downgrade is not, and it is what RFC 9470 is for.
- **Listed, not recognised.** The proposal asked for the factor when the `owner` starts with `user:`. The development realm writes the bare
  user id, and a missing prefix would then have made an administrator of anyone. Detecting a person by a missing `auth_time` fails the same way:
  the production realm has no built-in scopes, so its user tokens carry none. A list of the service accounts that are exempt fails closed.
- **Numbers, as proposed, and no name for a level.** The realm gives its levels no names, so the claim is `1` or `2`. A name for level 1 (`pwd`)
  would be wrong here: Keycloak writes `acr: 1` on a service account's token too, and `pwd` would label a client that signs in with a signed
  assertion as one that used a password. The API compares the number (`>= 2`, so a level 3 with a hardware key is accepted too), and a realm
  that does name its levels lists the names in `acr-values`.
- **Verified**, which the proposal assumed. Tried on Keycloak 26.8.0: a person with a code who asks for `acr_values=2` is asked for it and gets
  `acr: 2`; one who does not ask gets `1`, and so does a service account; one with no code configured is made to set it up and gets no token without it; a wrong code
  gets none.

## Cost

- Someone enrols the first factor of each administrator, and the UI's client must send `acr_values=2` (or have it as its default).
- `exempt-clients` must name clients that cannot sign a person in (no standard flow), or they are an administrator without a factor.
- The `acr` is what the identity provider says: as good as its flow, and `amr` would say how, which Keycloak does not write.
- **A gap this does not close:** replacing the key of `admin-client` has no procedure yet. A lost or exposed key is handled by
  making a new one and importing the realm again, which is not written down.
- How long a step-up lasts (`max_age`) is settled in the spike. The level-2 step of `realm-step-up.json` asks for the code every time.

## Rejected

- Keycloak alone: a flow set up wrong would issue an administrator token with no factor, and nothing in the API would notice.
- No person is ever an administrator: the UI could not take a link down, and the operator would use a command line and a key,
  which is one factor. It was the position until this was built.
- A second factor for `admin-client`: it has no person to ask.
- A level above 2 for every administrator token (WebAuthn, a hardware key), weighed on 2026-10-12 and not adopted. It resists a phishing site
  that relays a one-time code, at the cost of two keys per administrator, a flow with a level 3 and an enrolment, none of which can be tried
  without a hardware key. It stays a setting: `SHORTENER_SECURITY_ADMIN_MINLEVEL=3` on the service, if administrators grow past a few trusted
  people. `admin-client`, listed and bound to a key, is a way in if a person loses theirs. A level 3 only for taking links down was weighed too,
  and needs the refusal to come from inside the operation, not from the filter.
