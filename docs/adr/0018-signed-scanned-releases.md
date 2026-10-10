# 0018. A release is tested, scanned, signed and accompanied by its bill of materials

- Status: Accepted, 2026-10-05. It has released v0.20.0 and v0.21.1
- Evidence: `32452bb`, `.github/workflows/release.yml`, `ReleaseVersionTest`

## Problem

Production runs an image. Someone who can replace it, or poison what went into it, runs code with the application's
access. The operator needs to know that the image is the one the tests ran on, built from this commit, and what it holds.

## Decision

- A `v*` tag runs the tests, builds the image on an arm64 runner, smoke-tests that exact image against Postgres and Keycloak, scans
  it and only then pushes it. The tag must equal the version in `build.gradle.kts`.
- The scan fails the release on a fixable high or critical vulnerability.
- The image is signed by digest with the workflow's own identity (keyless), and an SPDX bill of materials is attached.
  The job summary prints the digest and the `cosign verify` command.
- Everything the build downloads is pinned ([0036](0036-jvm-image-for-arm64.md)). `deploy.sh` resolves the image
  to its digest.
- Dependabot proposes updates to the actions weekly.

## Consequences

- Good: a signature says which workflow made the image, and a digest cannot be moved.
- Cost: the first releases found what had not been exercised, such as an image that did not start under `production` in the smoke test (0.21.1). It builds only
  arm64 ([0036](0036-jvm-image-for-arm64.md)), and the package is private until made public.
- Gaps, listed in [THREAT_MODEL.md](../THREAT_MODEL.md): `deploy.sh` verifies the signature of the tag and not of the digest it
  then deploys, the `actions/*` actions are pinned by tag and not by commit, and the scan ignores vulnerabilities with no fix.

## Rejected

- Not in the history: pushing from a developer's machine: nothing ties the image to a commit.
- Signing with a long-lived key: a key to guard and rotate, where the workflow's identity needs none (`32452bb`).
