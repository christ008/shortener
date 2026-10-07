# The production identity provider: Keycloak built for Postgres, so it starts as it is (`start --optimized`) on a read-only
# filesystem instead of building itself on first run, with an entrypoint that reads its two secrets from files.
#
#   docker build -t shortener-keycloak:26.7.5 deploy/keycloak
FROM quay.io/keycloak/keycloak:26.8.0 AS builder
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY --chmod=0555 entrypoint.sh /opt/keycloak/bin/shortener-entrypoint.sh
ENTRYPOINT ["/opt/keycloak/bin/shortener-entrypoint.sh"]
CMD ["start", "--optimized"]
