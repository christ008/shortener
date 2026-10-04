# Development client keys

These private keys belong to the four test clients in `../shortener-realm.json` (`demo-client`, `other-client`,
`admin-client`, `no-scope-client`). They are committed on purpose so that `./gradlew bootRun`, the smoke test and the
benchmarks work out of the box, and the realm trusts only their public halves.

They protect nothing. Never reuse them, never import this realm into a real Keycloak, and generate fresh keys for any
real client with `java ../DpopClient.java keygen CLIENT_ID`.
