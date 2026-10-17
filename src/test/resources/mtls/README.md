Public certificates for `PeerIdentityFilterTest`, with no key:

- `edge.pem` and `migrator.pem` are identities of the stack's own CA (step-ca): `spiffe://shortener.internal/prod/edge` and `.../migrator`. Expired: the filter reads names, and TLS has verified the chain by then.
- `dns-only.pem` has a DNS name and no URI name. `two-uris.pem` has two URI names, the edge's second. Both are self-signed and good for a hundred years.
