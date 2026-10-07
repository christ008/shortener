# Design

The shape of the code and why it has that shape: the modules, the types, a review against SOLID, and the decisions in one list.
How a request is served is in [INTERNALS.md](INTERNALS.md#request-flows), and the controls that guard it are in
[SECURITY.md](SECURITY.md). Each decision has a record with its cost and alternatives in the [ADRs](adr/README.md).

- [Structure](#structure)
- [Domain types](#domain-types)
- [Design review](#design-review)
  - [Checked by the compiler](#checked-by-the-compiler)
  - [Tell, don't ask](#tell-dont-ask)
  - [SOLID](#solid)
    - [Why the service keeps its observations](#why-the-service-keeps-its-observations)
    - [Why the contract exposes Spring Data's paging types](#why-the-contract-exposes-spring-datas-paging-types)
- [Decisions](#decisions)

## Structure

Two Spring Modulith modules: `shortlink` and `security`. In `shortlink` the public package is the contract and
everything else is an adapter behind it.

```mermaid
flowchart LR
    web["web<br/>controller and DTOs"] -->|calls| service(["ShortLinkService<br/>interface"])
    authorization["authorization<br/>method security, permission evaluator, scopes"] -.->|guards| service
    service ---|implemented by| impl["DefaultShortLinkService"]
    impl --> repository(["ShortLinkRepository<br/>interface"])
    impl --> cache(["RedirectCache<br/>interface"])
    impl --> policy(["TargetUrlPolicy<br/>interface"])
    repository ---|implemented by| persistence["persistence<br/>JdbcClient"]
    cache ---|implemented by| caches["CaffeineRedirectCache<br/>NoRedirectCache"]
    policy ---|implemented by| policies["AnyTarget<br/>AllowedHosts"]
```

ArchUnit tests enforce what Modulith does not check inside a module:

- The public API depends on no JDBC type and no security type. It does use Spring Data's `Page` and `Pageable`, on purpose
  (see [Design review](#design-review)).
- The web adapter talks only to the service interface.
- Nothing depends on the web or persistence adapters.
- JDBC types never leave `persistence`.

## Domain types

Absence and "unknown" are types, not nulls, so callers match on them and the compiler checks every case.

| Type | Replaces | Cases |
|---|---|---|
| `LinkStatus` | nullable `disabledAt` and `disabledBy` | `Active`, `Disabled(at, by)` |
| `Actor` | nullable creator and disabler names | `Client(name)`, `Unknown` for links stored before creators were recorded |
| `CreatedByFilter` | nullable `createdBy` in listings | `Anyone`, `Only(client)` |
| `LinkLookup` | nullable `findByShortCode` and `disable` | `Found(link)`, `Missing` |
| `InsertResult` | nullable `insertIfAbsent` | `Created(link)`, `Taken` |
| `RedirectCache` | a nullable cache field | `CaffeineRedirectCache`, `NoRedirectCache` (does nothing) |
| `RateLimitDecision` | nullable wait | `Allowed`, `Limited(retryAfter)` |
| `RateLimitKey` | nullable key | `Of(value)`, `Unlimited` |
| `AuthScheme` | nullable scheme | `DPOP`, `BEARER`, `NONE` |

Nulls remain only where a framework owns the signature: JDBC columns, Caffeine's loader, the request DTO, Spring
Security callbacks. They are converted at that boundary, and the JSON API still sends `null` for an absent
`createdBy` or `disabledAt` because the contract says so.

## Design review

A pass for what the compiler can check, and against SOLID and Tell, Don't Ask. What changed, and what was left alone on
purpose.

### Checked by the compiler

| Where | Now | Gives |
|---|---|---|
| `ShortLinkException` | `sealed`: the failures are exactly the subclasses in the package | no failure the API contract does not describe can be added from outside; a test lists them |
| `LinkStatus`, `Actor`, `LinkLookup`, `InsertResult`, `CreatedByFilter`, `RateLimitDecision`, `RateLimitKey` | sealed interfaces | a `when` over them needs every case |
| `ShortLinkResponse`, `CaffeineRedirectCache` | exhaustive `when` where `as?` casts stood | a new case is a compile error, not a silent `null` |
| `SecurityProblemResponder` challenge | `when` over `AuthScheme`, not over booleans | each scheme's answer is stated, and a new scheme must be handled |
| `ShortLinkScopes` | immutable, bound through the constructor | the authorization rules cannot change after start |

Left as they are:

- **Kotlin `internal`.** It means module-wide, and this is one module, so it would hide nothing. The boundaries are held
  by Spring Modulith and the ArchUnit tests instead.
- **Value classes** for `ShortCode`. Spring MVC, Jackson and native image support for them is thinner than for data
  classes, and the gain is one allocation.
- **Unchecked cast in `NotFoundWhenDenied`.** The first argument of a denied call is a framework-supplied `Object`.

### Tell, don't ask

Callers were pulling fields out of an object to decide something that the object knows. Each now asks the object to do
it, which puts the rule in one place and lets the type change without its callers.

| Before | Now |
|---|---|
| the evaluator compared `link.createdBy` with the caller | `link.isCreatedBy(client)` |
| the service read `link.isDisabled` and threw | `link.requireActive()` |
| the service checked `shortCode.value in RESERVED_CODES` | `shortCode.requireClaimable()` |
| the controller chose the listing filter from the caller's authority | `CreatedByFilter.of(requested, caller, isAdministrator)`, with tests |
| the service parsed the target and would have had to know which hosts are allowed | a `TargetUrlPolicy` says whether a target is accepted |

### SOLID

| Principle | Finding | Decision |
|---|---|---|
| Single responsibility | `DefaultShortLinkService` also opens the observations around two repository calls | Kept, [see below](#why-the-service-keeps-its-observations) |
| Open/closed | which targets are accepted was going to be an `if` in the service | Done: `TargetUrlPolicy`, with `AnyTarget` and `AllowedHosts`. A new rule is a new implementation |
| Liskov | the two null objects, `NoRedirectCache` and `AnyTarget`, must honor their contracts | Done: each has tests for its contract |
| Interface segregation | the repository has four operations, each used by the service or `ManageableLinks` | Nothing to split |
| Dependency inversion | the service depends on interfaces for the repository, the cache, the generator and the policy. Its public contract exposes Spring Data's `Page` and `Pageable` | Kept, with a cost: [see below](#why-the-contract-exposes-spring-datas-paging-types) |

#### Why the service keeps its observations

The `shortlink.load` observation marks a redirect that missed the cache. A repository decorator would also observe the reads that
decide who may see a link, which is not what the observation is for.

#### Why the contract exposes Spring Data's paging types

- The ArchUnit tests keep JDBC and security types out of the contract, and accept `Page` and `Pageable` on purpose.
- Own paging types would be a copy of them. The web adapter would then parse `page`, `size` and `sort` itself, which
  `Pageable`'s resolver does today, with the defaults and the cap of `spring.data.web.pageable.*`.
- The cost: the contract is tied to `spring-data-commons`, a library with no persistence in it. A different paging library would
  change the contract, not only an adapter.

## Decisions

Each has a record with its problem, cost and alternatives in [adr/](adr/README.md). The list below is the summary.

- **Plain JDBC, not JPA.** Two statements dominate and need SQL features JPA hides. Only the persistence adapter knows SQL.
- **Spring facilities over bespoke code.** Method security with a `PermissionEvaluator`, `Pageable` and `Page`, Spring
  Security's resource server, DPoP and RFC 9728 metadata, MVC's problem details. Custom code is limited to what Spring
  lacks: the Bearer refusal, rate limiting and the native hints.
- **Absence is a type.** Sealed types and a null-object cache instead of nullable returns and fields (see
  [Domain types](#domain-types)).
- **Virtual threads, not coroutines.** Each request does one blocking query, so there is nothing to fan out. The native
  image's problem was never scheduling: it was 150 KB of Tomcat buffers per connection and the collector. Revisit if a
  request ever calls several things in parallel.
- **Ownership is the client id.** There are no end users yet, so the token's `azp` is the owner and scopes are the only
  permission model.
- **Sender-constrained tokens by default.** A leaked bearer token is the main risk of a token API. DPoP removes it at the
  cost of a client that can sign. A reference client is provided: `deploy/keycloak/DpopClient.java`, one file that needs only a JDK 17
  or newer and no build, and that CI compiles for 17 and runs on 17.
- **In-process redirect cache.** Caffeine, active links only, short TTL, local eviction on disable. See
  [Redirect cache](INTERNALS.md#redirect-cache) for what was rejected.
- **Which hosts a link may point to is a policy.** Empty by default, so a private instance accepts anything; a public
  one lists the hosts it accepts, so it cannot be used to redirect to arbitrary sites.
- **Reproducible image.** Everything the build downloads is pinned, by version where one exists and by digest where
  not.
- **Offset pagination with totals, not cursors.** Listings take `page` and `size` and answer with totals, so a client can
  jump to any page and draw a numbered pager. The web UI needs that. Cursor (keyset) paging only moves to the next or
  previous page. Its advantages (constant cost at any depth, stable pages under inserts) do not matter at this size. See
  [Request flows](INTERNALS.md#request-flows).
