# The production identity provider: Keycloak built for Postgres, so it starts as it is (`start --optimized`) on a read-only
# filesystem instead of building itself on first run, with an entrypoint that reads its two secrets from files.
#
#   docker build -t shortener-keycloak:26.8.0 deploy/keycloak
FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# The directory the compose files mount a tmpfs on. The tmpfs takes its mode from the mount point, which Docker would make root's, 755; with 1777
# Keycloak (uid 1000) can make the cache of its gzipped static files (without it, every request that accepts gzip is a 404).
RUN mkdir -m 1777 /opt/keycloak/data/tmp
COPY --chmod=0555 entrypoint.sh /opt/keycloak/bin/shortener-entrypoint.sh
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/shortener-entrypoint.sh"]
CMD ["start", "--optimized"]
