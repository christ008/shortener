# Development client keys

This directory is empty in Git. `deploy/keycloak/dev-setup` writes a private key for each of the four dev clients here
(`demo-client`, `other-client`, `admin-client`, `no-scope-client`), as `<client>.jwk.json`, and puts their public halves in
the dev realm. Git ignores them.

They are throwaway keys for your machine: never reuse them, never import the dev realm into a real Keycloak, and make a
key for a real client with `../dpop keygen CLIENT_ID`.
