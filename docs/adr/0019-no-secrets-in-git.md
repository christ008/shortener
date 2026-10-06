# 0019. No secrets in Git: generated development keys and a shell client

- Status: Accepted, 2026-10-05. The client part is superseded by [0020](0020-dpop-client-in-java.md); the rest stands
- Evidence: `0f4c18d`, `81a2ec9`, `deploy/keycloak/dev-setup`, `deploy/keycloak/dpop`, `DpopClientTest`

## Problem

The development realm and its client keys were committed so that `bootRun` worked at once. They are throwaway, but they
are real private keys and passwords in the history, and anyone who copied the setup for a real deployment inherited
them. The client that spoke DPoP was a Java program, so using the API meant a JDK and a build.

## Decision

- `deploy/keycloak/dev-setup` generates, per developer, a private key for each dev client, the realm that trusts them and
  the passwords, offering a random value for each. It writes them to `.env` and `dev-keys/`, which Git ignores.
- The realm in Git is a template with placeholders. Compose, CI, the performance scripts and the stack rehearsal read the
  generated `.env`.
- `deploy/keycloak/dpop` does the whole DPoP flow with `openssl` and `curl`, so a client needs nothing to install and
  runs on the user's machine, not in the stack.
- Production secrets are Docker secrets, files outside the repository, created by the operator.

## Consequences

- Good: no secret in the tree, and each developer's keys differ.
- Cost: one setup step, and CI generates its own on every run.
- Known: the earlier committed keys and passwords are in the history. They no longer work against any setup made since,
  and they must not be trusted anywhere.
- Cost: `dev-setup` is a shell script that handles keys and passwords, held to `shellcheck` in CI. The client it first
  shipped was shell too, and is Java since [0020](0020-dpop-client-in-java.md).

## Rejected

- Committed throwaway keys: convenient, and they were copied.
- A Java client: needs a runtime and a build to try the API.
