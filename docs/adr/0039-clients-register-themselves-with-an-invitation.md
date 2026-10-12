# 0039. Clients register themselves, with an invitation

- Status: Accepted, 2026-10-12. Amends the token type check of [0005](0005-oauth2-resource-server-with-scopes.md)
- Evidence: `deploy/keycloak/shortener-realm.production.template.json`, `RealmsTest`, `AccessTokenTypeValidatorTest`, `DpopIntegrationTest`, `DpopClientTest` (`register`), `ComposeStackTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) F-14, [0007](0007-owner-claim-and-ownership-rule.md), [0025](0025-keycloak-in-the-stack.md), [0038](0038-segment-the-stack-networks.md)

## Problem

A client was a service account that the operator made by hand, with a public key in the realm and a mapper of its own that wrote its
`owner`. Nobody else could get one, and the mapper per client made it impossible to automate. People (a UI, where anyone signs up) are
for later; this is for the developers who call the API.

## Decision

- **Invitation, then self-service.** An administrator makes an *initial access token* in the console (one use, a deadline, nothing
  else) and gives it to a person. They run `java DpopClient.java register NAME INVITATION KEY_FILE`: it makes the key, writes its private
  half to a file only they can read, registers the public half and prints the client id. There is no approval step: the invitation is the
  approval, and the realm limits how many clients there may be (50, `Max Clients Limit`).
- **The identity provider decides everything but the key.** The edge offers the OpenID Connect registration endpoint alone, which takes
  metadata and no attributes: not Keycloak's own endpoint, where the registrant chooses the client id (which is the `owner`, and could
  imitate the `user:<sub>` of a future person) and any attribute, such as the lifetime of a token.
  - The realm's default scopes are `shortlinks:create`, `read` and `delete`: never `claim`, which picks a code, nor `admin`. A request for
    any other is refused (`Allowed Client Scopes`).
  - A registrant adds no mapper (`Allowed Protocol Mapper Types` is empty), so the `owner` is written by one mapper of the realm, from the
    client id of the session, which no client chooses. The two clients of the realm lose their mappers of their own and use it too.
  - Without an invitation Keycloak refuses (`Trusted Hosts` trusts nobody). The edge limits the endpoint to six requests a minute per address.
- **The application accepts what such a client gets.** A client that registers itself has no setting for the `at+jwt` header, which is
  per client, and Keycloak has no realm default for it. A token is an access token when its header says `at+jwt`, or says `JWT` and its own
  `typ` claim is `Bearer` or `DPoP`, as Keycloak writes its access tokens. Its ID tokens say `ID` and its refresh tokens `Refresh`, so they are
  still refused. `shortener.security.access-token-claim-types` is the list, and empty returns to the header alone.

## Consequences

- Good: a developer can have a client in a minute, with no operator at the keyboard but for the invitation, and cannot choose a scope, an
  owner or an id. Tested against Keycloak 26.8.0: an invited registration, a second use of an invitation refused, a registration without one
  refused, and each way of asking for more refused.
- Cost: the client id, which is the `owner` shown in `created_by`, is a UUID. The name given at registration is `client_name`, in the console.
- Cost: the type check depends on a claim Keycloak writes. A provider that marks its access tokens neither way is refused, as before.
- Limit: Keycloak accepts a `jwks_uri` of the registrant's choice and fetches it when the client asks for a token, which can make it request
  a URL inside the stack. It has no route out ([0038](0038-segment-the-stack-networks.md)), the request is blind, and only someone invited can
  make it ([THREAT_MODEL.md](../THREAT_MODEL.md) F-14).
- Limit: the cap counts every client of the realm, including the operator's, and an invitation that is not used stays until its deadline.

## Rejected

- Keycloak's own registration endpoint, to set the type attribute: a registrant would choose the client id and any attribute.
- A job that sets the attribute after the fact: a moving part, and up to a minute of `401` after registering.
- Registration by anyone: nobody answers for the clients, and the cap would fill. People signing up belong to the UI.
- Registration inside the application: it would become an authorization server ([0005](0005-oauth2-resource-server-with-scopes.md)).
