# Postgres for the stack with backups and a replica (compose.prod.postgres-ha.yaml): the official image with pgBackRest, and the
# scripts that start the replica and prove a restore. Everything else is the stock image, so a data directory made by it is read by
# this one and the other way round.
#
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
