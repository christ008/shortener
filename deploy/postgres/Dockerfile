# Postgres with pgBackRest and the replica and restore-test scripts (deploy/stack/overlays/compose.postgres-ha.yaml).
#   docker build -t shortener-postgres:18.6 deploy/postgres
FROM postgres:18.6
RUN apt-get update \
    && apt-get install -y --no-install-recommends pgbackrest \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=0555 entrypoint.sh /usr/local/bin/shortener-postgres-entrypoint.sh
COPY --chmod=0555 replica-entrypoint.sh /usr/local/bin/shortener-replica-entrypoint.sh
COPY --chmod=0555 restore-test.sh /usr/local/bin/shortener-restore-test
ENTRYPOINT ["/usr/local/bin/shortener-postgres-entrypoint.sh"]
CMD ["postgres"]
