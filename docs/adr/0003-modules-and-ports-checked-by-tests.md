# 0003. A public contract with adapters behind it, held by tests

- Status: Accepted, 2026-10-03
- Evidence: `ShortLinkArchitectureTest`, `ModularityTests`, `INTERNALS.md#structure`

## Problem

A small service stays small only while its layers stay apart. Without a check, a controller reaches the repository, a
JDBC type appears in the domain, and the rules about who may see a link end up in three places.

## Decision

- Two Spring Modulith modules: `shortlink` and `security`. In `shortlink` the public package is the contract (types,
  service and repository interfaces, exceptions) and everything else is an adapter in `internal`.
- ArchUnit tests enforce what Modulith does not: the contract depends on no JDBC or security type, the web adapter talks
  only to the service interface, nothing depends on the web or persistence adapters, and JDBC types never leave
  persistence.
- Access rules are declared on the service with method security ([0005](0005-oauth2-resource-server-with-scopes.md)),
  so they apply whatever calls it.

## Consequences

- Good: a new storage or a new front end is an adapter, and a violation fails the build.
- Cost: interfaces with one implementation, and two test classes to keep current.

## Rejected

- Kotlin `internal`: it means module-wide, and this is one Gradle module, so it hides nothing.
- Value classes for `ShortCode`: thin support in Spring MVC, Jackson and native image for the gain of one allocation.
