#!/bin/sh
# Prepares a fresh Ubuntu 24.04 arm64 VM to run the stack (docs/CLOUD.md): Docker, cosign, certbot, a single-node Swarm, the secrets,
# the images the overlays build on the node and a first `.env`. It deploys nothing and asks for no certificate. Run it on the VM as
# the user that will deploy, from the repository's root, which must already be there (git clone). It can be run again: it keeps what
# exists.
#
#   HOST=shortener.example.com ALLOWED_TARGET_HOSTS=example.com,*.example.org VERSION=0.22.0 deploy/aws/bootstrap.sh
#
# Required:
#   HOST                  the public host name; PUBLIC_URL, the issuer and the key endpoint of .env follow from it
#   ALLOWED_TARGET_HOSTS  hosts a link may point to (docs/CLOUD.md#safety)
#   VERSION               the release to deploy, a published `v*` tag without the v
#
# Settings, all optional:
#   SWAP_GB               swap file, default 2 (4 GB of memory is tight for the whole stack); 0 makes none
#   COSIGN_VERSION        default 3.0.6: 2.x reads the signatures of the release only while they use the old layout (docs/DEPLOY.md#requirements)
#   OVERLAYS              overlay flags written to .env, default "ACME=1 KEYCLOAK=1 POSTGRES_HA=1". Keep ACME: certificate.sh needs it
#   KEYCLOAK_MEMORY       Keycloak's memory limit when it is in OVERLAYS, default 768M, with KEYCLOAK_HEAP_PERCENT 50 (docs/CLOUD.md#shape has the measure)
#
# Swarm listens on the loopback only, and the security group opens 22, 80 and 443.
set -eu

: "${HOST:?set HOST}" "${ALLOWED_TARGET_HOSTS:?set ALLOWED_TARGET_HOSTS}" "${VERSION:?set VERSION}"
[ -f deploy/stack/deploy.sh ] || { echo "run it from the repository's root" >&2; exit 1; }
[ "$(id -u)" -ne 0 ] || { echo "run it as the deploying user, not root; it uses sudo where needed" >&2; exit 1; }
[ "$(dpkg --print-architecture)" = arm64 ] || { echo "this VM is not arm64" >&2; exit 1; }
swap=${SWAP_GB:-2}
cosign=${COSIGN_VERSION:-3.0.6}
overlays=${OVERLAYS:-ACME=1 KEYCLOAK=1 POSTGRES_HA=1}

echo "== packages"
sudo apt-get update -qq
sudo apt-get install -y -qq ca-certificates curl openssl certbot >/dev/null
if ! command -v docker >/dev/null; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  # shellcheck disable=SC1091
  echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin >/dev/null
fi
sudo usermod -aG docker "$USER"

if ! command -v cosign >/dev/null; then
  tmp=$(mktemp -d)
  base=https://github.com/sigstore/cosign/releases/download/v$cosign
  curl -fsSL "$base/cosign-linux-arm64" -o "$tmp/cosign-linux-arm64"
  curl -fsSL "$base/cosign_checksums.txt" -o "$tmp/checksums"
  (cd "$tmp" && grep ' cosign-linux-arm64$' checksums | sha256sum -c -)
  sudo install -m 0755 "$tmp/cosign-linux-arm64" /usr/local/bin/cosign
  rm -rf "$tmp"
fi

if [ "$swap" -gt 0 ] && ! swapon --show --noheadings | grep -q .; then
  echo "== swap ${swap} GB"
  sudo fallocate -l "${swap}G" /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

# From here on docker needs the group, which this shell does not have yet.
d="sudo docker"

echo "== swarm"
if [ "$($d info --format '{{.Swarm.LocalNodeState}}')" != active ]; then
  $d swarm init --advertise-addr 127.0.0.1 --listen-addr 127.0.0.1:2377 >/dev/null
fi
$d node update --label-add shortener.postgres=true "$($d info --format '{{.Swarm.NodeID}}')" >/dev/null
case " $overlays " in *" POSTGRES_HA=1 "*) $d node update --label-add shortener.postgres-replica=true "$($d info --format '{{.Swarm.NodeID}}')" >/dev/null ;; esac
sudo mkdir -p /srv/shortener/acme
sudo chmod 0755 /srv/shortener/acme

echo "== secrets"
mkdir -p secrets
for name in db_postgres_password db_app_password db_migrator_password db_exporter_password dpop_nonce_secret \
  db_keycloak_password keycloak_admin_password db_replicator_password; do
  [ -e "secrets/$name" ] || openssl rand -hex 24 | tr -d '\n' >"secrets/$name"
done
chmod 0444 secrets/*

echo "== images the overlays build here"
case " $overlays " in *" KEYCLOAK=1 "*) $d build -q -t shortener-keycloak:26.8.0 deploy/keycloak >/dev/null ;; esac
case " $overlays " in *" POSTGRES_HA=1 "*) $d build -q -t shortener-postgres:18.6 deploy/postgres >/dev/null ;; esac

echo "== .env"
if [ -e .env ]; then
  echo ".env exists; left alone"
else
  cat >.env <<EOF
SHORTENER_IMAGE=ghcr.io/christ008/shortener
SHORTENER_VERSION=$VERSION
SHORTENER_MIGRATE_VERSION=$VERSION
PUBLIC_URL=https://$HOST
ISSUER_URI=https://$HOST/realms/shortener
JWKS_URI=http://keycloak:8080/realms/shortener/protocol/openid-connect/certs
ALLOWED_TARGET_HOSTS=$ALLOWED_TARGET_HOSTS
EOF
  for flag in $overlays; do echo "$flag" >>.env; done
  case " $overlays " in *" KEYCLOAK=1 "*) printf '%s\n' KEYCLOAK_IMAGE=shortener-keycloak KEYCLOAK_VERSION=26.8.0 KEYCLOAK_DB_URL=jdbc:postgresql://postgres:5432/keycloak "KEYCLOAK_MEMORY=${KEYCLOAK_MEMORY:-768M}" KEYCLOAK_HEAP_PERCENT=50 >>.env ;; esac
  case " $overlays " in *" POSTGRES_HA=1 "*) printf '%s\n' POSTGRES_IMAGE=shortener-postgres POSTGRES_VERSION=18.6 >>.env ;; esac
fi

echo
echo "ready. Missing, in this order:"
[ -f deploy/keycloak/shortener-realm.production.json ] \
  || echo "  - deploy/keycloak/shortener-realm.production.json: make it on your machine (docs/DEPLOY.md#keycloak) and copy it here"
echo "  - DNS: an A record for $HOST to this VM's Elastic IP"
echo "  - sudo deploy/stack/certificate.sh placeholder $HOST"
echo "  - deploy/stack/deploy.sh $VERSION          (log out and in first, or use sudo)"
echo "  - sudo deploy/stack/certificate.sh issue $HOST you@example.com"
echo "  - deploy/postgres/backup init, and the crontab of docs/DEPLOY.md#backups-and-a-replica"
