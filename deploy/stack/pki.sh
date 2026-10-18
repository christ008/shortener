#!/bin/sh
# The stack's certificates, for mutual TLS between its services (ADR 0022, docs/DEPLOY.md#mutual-tls-between-the-services). Run it from anywhere: the repository is found
# from the script's own path. What runs where:
#
#   On a machine that is not the node, offline, once and then once a year:
#     pki.sh root DIR                              makes the root: a key (encrypted) and a certificate, in DIR. Keep DIR off the node
#     pki.sh sign-intermediate DIR CSR CERT        signs the request of the node's issuing CA, for a year, and for the stack's names only
#   On the node that runs the CA (as root):
#     pki.sh init                                  makes the issuing CA's key and request, and the CA's configuration and passwords
#     pki.sh request                               makes a new request for the same key, a month before the issuing CA's year is over
#     pki.sh install-intermediate CERT ROOT        checks what the root signed and installs it with the root certificate
#     pki.sh issue ROLE                            makes a key and a request here, has the CA sign the request, and writes the result
#     pki.sh renew [ROLE...]                       issues again the roles named, or what is two thirds through its life, and deploys. The database's goes alone and first
#     pki.sh inspect                               what secrets/ holds: identity, validity, key, and whether each key matches its certificate
#     pki.sh env                                   the variables deploy.sh gives the Compose files: a hash of each secret and the labels of each certificate
#
# ROLE is edge, shortener, migrator or postgres. The private key of a role is made on the node and never reaches the CA, which only sees the request.
# Every key is ECDSA P-256, the root's, the issuing CA's and each role's, and every certificate is signed with SHA-256: one family for the whole stack, which every
# client of it reads. (Ed25519 has no digest, so a client that binds SCRAM to the server's certificate, as libpq does by default, could not use a password.)
# Files, in SECRETS_DIR (mode 0600, owner root, but the public ones):
#   pki_ca_pass, pki_provisioner_pass, pki_ca_json     the CA's passwords and configuration       pki_ca_key       the issuing CA's key, encrypted
#   pki_root, pki_ca_cert, pki_chain                   the root, the issuing CA, and both         pki_ca.csr       the request that the root signs
#   pki_ROLE_cert                                      the certificate followed by the issuing CA, so that a peer that trusts only the root verifies it
#   pki_ROLE_key                                       the key, PKCS#8, not encrypted
#   pki_ROLE_key_der                                   the same key in DER, for the roles that connect to Postgres (pgjdbc does not read PEM)
#
# Settings, all optional:
#   SECRETS_DIR   default secrets/ in the repository
#   STACK         name of the stack, default shortener; the CA is the container of the service ${STACK}_stepca
#   CA_NAME       a filter that finds the CA container instead, for a rehearsal with `docker compose`
#   STEPCA_IMAGE  the CA's image, the one compose.pki-ca.yaml names
#   PKI_DIR       where the CA keeps its database, default /srv/shortener/pki
set -eu

repo=$(cd "$(dirname "$0")/../.." && pwd)
secrets=${SECRETS_DIR:-$repo/secrets}
stack=${STACK:-shortener}
image=${STEPCA_IMAGE:-smallstep/step-ca:0.30.2}
pki_dir=${PKI_DIR:-/srv/shortener/pki}
domain=shortener.internal
environment=prod
roles="edge shortener migrator postgres"

command=${1:?usage: deploy/stack/pki.sh root | sign-intermediate | init | request | install-intermediate | issue | renew | inspect | env}
umask 077

random_secret() { head -c 33 /dev/urandom | base64; }

quietly() { quiet_output=$("$@" 2>&1) || { echo "$quiet_output" >&2; return 1; }; }

key_pair() { # FILE: a P-256 key, PKCS#8, not encrypted
  key_out=$1
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$key_out"
}

encrypted_key() { # FILE PASSWORD_FILE: a P-256 key, PKCS#8, encrypted with the password
  key_out=$1
  key_password_file=$2
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 | openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256 -iter 600000 -passout "file:$key_password_file" -out "$key_out"
}

ca_request() {
  openssl req -new -key "$secrets/pki_ca_key" -passin "file:$secrets/pki_ca_pass" -subj "/O=Shortener/OU=PKI/CN=Shortener Issuing CA" -out "$secrets/pki_ca.csr"
  chmod 0644 "$secrets/pki_ca.csr"
}

ca_container() {
  id=$(docker ps -q --filter "name=${CA_NAME:-${stack}_stepca}" | head -n 1)
  [ -n "$id" ] || { echo "the CA is not running: deploy the stack with PKI_CA=1" >&2; exit 1; }
  echo "$id"
}

epoch() { epoch_file=$1; epoch_field=$2; date -d "$(openssl x509 -in "$epoch_file" -noout "-$epoch_field" | cut -d= -f2)" +%s; }

end_date() { end_file=$1; openssl x509 -in "$end_file" -noout -enddate | cut -d= -f2; }

days_left() { days_file=$1; echo $(( ( $(epoch "$days_file" enddate) - $(date +%s) ) / 86400 )); }

# The moment two thirds of a certificate's life are over, in epoch seconds: when it is due for renewal.
renew_at() { renew_file=$1; echo $(( $(epoch "$renew_file" startdate) + ( $(epoch "$renew_file" enddate) - $(epoch "$renew_file" startdate) ) * 2 / 3 )); }

hash12() { hash_file=$1; sha256sum "$hash_file" | cut -c1-12; }

# CERT then the arguments of `openssl pkey` that read its key: whether the key is the certificate's.
key_matches() {
  candidate=$1
  shift
  [ "$(openssl x509 -in "$candidate" -noout -pubkey)" = "$(openssl pkey "$@" -pubout)" ]
}

# name of the identity, what it may be used for, who signs it, how long it lives, and its names
role_settings() {
  settings_role=$1
  case "$settings_role" in
    edge)      cn=edge;               eku=client; provisioner=pki;    lifetime=720h;  dns="" ;;
    shortener) cn=shortener-app;      eku=both;   provisioner=pki;    lifetime=720h;  dns="DNS:shortener," ;;
    migrator)  cn=shortener-migrator; eku=client; provisioner=pki;    lifetime=720h;  dns="" ;;
    postgres)  cn=postgres;           eku=server; provisioner=pki-db; lifetime=8760h; dns="DNS:postgres," ;;
    *) echo "unknown role '$settings_role': one of $roles" >&2; exit 1 ;;
  esac
}

issue_role() {
  role=$1
  role_settings "$role"
  id=$(ca_container)
  key=$secrets/pki_${role}_key
  cert=$secrets/pki_${role}_cert
  # Everything is made beside the files in use and moved over them at the end, so that a CA that fails leaves the old ones as they were.
  trap 'rm -f "$key.new" "$cert.new" "${key}_der.new"' EXIT
  key_pair "$key.new"
  openssl req -new -key "$key.new" -subj "/O=Shortener/OU=$role/CN=$cn" \
    -addext "subjectAltName=${dns}URI:spiffe://$domain/$environment/$role" \
    | docker exec -i "$id" sh -c "cat > /tmp/$role.csr"
  signed=$(docker exec "$id" step ca sign "/tmp/$role.csr" "/tmp/$role.crt" --ca-url=https://stepca:9000 --root=/home/step/certs/root_ca.crt \
    --issuer="$provisioner" --provisioner-password-file=/home/step/secrets/provisioner.pass \
    --not-after="$lifetime" --set="role=$role" --set="eku=$eku" --force 2>&1) || { echo "$signed" >&2; exit 1; }
  docker exec "$id" cat "/tmp/$role.crt" > "$cert.new"
  key_matches "$cert.new" -in "$key.new" \
    || { echo "the CA returned a certificate for another key" >&2; exit 1; }
  case "$role" in
    shortener|migrator) openssl pkcs8 -topk8 -nocrypt -in "$key.new" -outform DER -out "${key}_der.new"; mv -f "${key}_der.new" "${key}_der" ;;
    *) ;;
  esac
  mv -f "$key.new" "$key"
  mv -f "$cert.new" "$cert"
  echo "issued $role: $(openssl x509 -in "$cert" -noout -serial) until $(end_date "$cert")"
}

case "$command" in
  root)
    dir=${2:?usage: deploy/stack/pki.sh root DIR}
    [ ! -e "$dir/root.key" ] || { echo "$dir/root.key exists; nothing to do" >&2; exit 1; }
    mkdir -p "$dir"
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    printf '[v3]\nbasicConstraints=critical,CA:TRUE,pathlen:1\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n' > "$tmp/extensions"
    random_secret > "$dir/root.pass"
    encrypted_key "$dir/root.key" "$dir/root.pass"
    openssl req -new -key "$dir/root.key" -passin "file:$dir/root.pass" -subj "/O=Shortener/OU=PKI/CN=Shortener Root CA" -out "$tmp/root.csr"
    quietly openssl x509 -req -in "$tmp/root.csr" -signkey "$dir/root.key" -passin "file:$dir/root.pass" -set_serial "0x$(openssl rand -hex 15)" -days 3650 \
      -extfile "$tmp/extensions" -extensions v3 -out "$dir/root.crt"
    chmod 0644 "$dir/root.crt"
    echo "root in $dir: root.crt is public, root.key and root.pass stay off the node"
    ;;

  sign-intermediate)
    dir=${2:?usage: deploy/stack/pki.sh sign-intermediate DIR CSR CERT}
    csr=${3:?usage: deploy/stack/pki.sh sign-intermediate DIR CSR CERT}
    out=${4:?usage: deploy/stack/pki.sh sign-intermediate DIR CSR CERT}
    openssl req -in "$csr" -noout -verify 2>/dev/null || { echo "the request is not signed by its own key" >&2; exit 1; }
    echo "signing for a year: $(openssl req -in "$csr" -noout -subject), public key $(openssl req -in "$csr" -noout -text | sed -n 's/.*Public Key Algorithm: //p' | head -n 1)"
    quietly openssl x509 -req -in "$csr" -CA "$dir/root.crt" -CAkey "$dir/root.key" -passin "file:$dir/root.pass" -set_serial "0x$(openssl rand -hex 15)" -days 365 \
      -extfile /dev/stdin -extensions v3 -out "$out" <<EOF
[v3]
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
extendedKeyUsage=serverAuth,clientAuth
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid
nameConstraints=critical,permitted;DNS:shortener,permitted;DNS:postgres,permitted;DNS:edge,permitted;DNS:stepca,permitted;URI:$domain,excluded;IP:0.0.0.0/0.0.0.0,excluded;IP:0:0:0:0:0:0:0:0/0:0:0:0:0:0:0:0
EOF
    chmod 0644 "$out"
    echo "wrote $out: $(openssl x509 -in "$out" -noout -serial), until $(end_date "$out")"
    ;;

  init)
    [ ! -e "$secrets/pki_ca_json" ] || { echo "$secrets/pki_ca_json exists: the CA is made already" >&2; exit 1; }
    command -v docker >/dev/null 2>&1 || { echo "docker is needed to make the CA's configuration" >&2; exit 1; }
    mkdir -p "$secrets"
    random_secret > "$secrets/pki_ca_pass"
    random_secret > "$secrets/pki_provisioner_pass"
    encrypted_key "$secrets/pki_ca_key" "$secrets/pki_ca_pass"
    ca_request
    install -d -o 1000 -g 1000 -m 0700 "$pki_dir/db"
    docker run --rm --user 0:0 -v "$secrets:/work" -v "$repo/deploy/pki:/pki:ro" --entrypoint sh "$image" /pki/ca-config
    echo "the CA is configured. Take $secrets/pki_ca.csr to the machine that holds the root:"
    echo "  deploy/stack/pki.sh sign-intermediate ROOT_DIR pki_ca.csr pki_ca_cert.pem"
    echo "and bring back pki_ca_cert.pem and root.crt, for: deploy/stack/pki.sh install-intermediate pki_ca_cert.pem root.crt"
    ;;

  request)
    [ -e "$secrets/pki_ca_key" ] || { echo "$secrets/pki_ca_key does not exist: run init first" >&2; exit 1; }
    ca_request
    echo "wrote $secrets/pki_ca.csr for the same key. The root signs it as before (sign-intermediate), and install-intermediate puts the new certificate in place; then deploy"
    ;;

  install-intermediate)
    cert=${2:?usage: deploy/stack/pki.sh install-intermediate CERT ROOT}
    root=${3:?usage: deploy/stack/pki.sh install-intermediate CERT ROOT}
    openssl verify -x509_strict -CAfile "$root" "$cert" >/dev/null || { echo "$cert is not signed by $root" >&2; exit 1; }
    key_matches "$cert" -in "$secrets/pki_ca_key" -passin "file:$secrets/pki_ca_pass" \
      || { echo "$cert is for another key than the one init made" >&2; exit 1; }
    cp "$cert" "$secrets/pki_ca_cert"
    cp "$root" "$secrets/pki_root"
    cat "$secrets/pki_ca_cert" "$secrets/pki_root" > "$secrets/pki_chain"
    chmod 0644 "$secrets/pki_ca_cert" "$secrets/pki_root" "$secrets/pki_chain"
    echo "installed: the issuing CA until $(end_date "$cert"). Deploy with PKI_CA=1, then issue each role"
    ;;

  issue)
    issue_role "${2:?usage: deploy/stack/pki.sh issue ROLE}"
    ;;

  renew)
    shift
    due=
    if [ "$#" -gt 0 ]; then
      due=$*
    else
      for role in $roles; do
        cert=$secrets/pki_${role}_cert
        [ -e "$cert" ] || continue
        [ "$(date +%s)" -lt "$(renew_at "$cert")" ] || due="$due $role"
      done
    fi
    # A new certificate of the database replaces the database, and the application's new tasks, starting while it restarts, cannot connect and fail, and Swarm rolls the
    # application back. So the database is renewed alone and deployed, and the rest is due still, for the next run, or for the next command.
    case " $due " in *" postgres "*) due=postgres ;; esac
    [ -n "${due# }" ] || { echo "no certificate is two thirds through its life"; exit 0; }
    for role in $due; do issue_role "$role"; done
    version=$(docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${stack}_shortener" 2>/dev/null \
      | sed 's/@.*//; s/.*://' || true)
    [ -n "$version" ] || { echo "the stack $stack is not deployed; the files are in place, deploy.sh will use them" >&2; exit 0; }
    cd "$repo"
    deploy/stack/deploy.sh "$version" "$stack"
    ;;

  inspect)
    [ -e "$secrets/pki_ca_cert" ] && echo "== issuing CA: $(openssl x509 -in "$secrets/pki_ca_cert" -noout -subject), $(days_left "$secrets/pki_ca_cert") days left (renew it with the root, once a year)"
    for cert in "$secrets"/pki_*_cert; do
      [ -e "$cert" ] || { echo "no certificates in $secrets: run issue first" >&2; exit 1; }
      role=$(basename "$cert" _cert); role=${role#pki_}
      [ "$role" != ca ] || continue
      key=$secrets/pki_${role}_key
      echo "== $role"
      openssl x509 -in "$cert" -noout -subject -serial -dates -fingerprint -sha256 \
        -ext subjectAltName,extendedKeyUsage,keyUsage,basicConstraints,certificatePolicies | grep -v '^$' | sed 's/^/   /'
      echo "   public key:  $(openssl x509 -in "$cert" -noout -text | sed -n 's/.*Public Key Algorithm: //p' | head -n 1)"
      if [ -e "$key" ]; then
        match=no
        if key_matches "$cert" -in "$key"; then match=yes; fi
        echo "   key file:    $key, mode $(stat -c %a "$key"), owner $(stat -c %U "$key"), $(head -n 1 "$key" | sed 's/-----//g'), matches the certificate: $match"
      fi
      echo "   days left:   $(days_left "$cert")"
    done
    ;;

  env)
    for file in "$secrets"/pki_*; do
      [ -f "$file" ] || continue
      name=$(basename "$file")
      case "$name" in *[!a-z_]*) continue ;; *) ;; esac
      label=$(echo "${name#pki_}" | tr '[:lower:]' '[:upper:]')
      echo "PKI_${label}_HASH=$(hash12 "$file")"
      case "$name" in
        pki_root|pki_*_cert)
          echo "PKI_${label}_SERIAL=$(openssl x509 -in "$file" -noout -serial | cut -d= -f2)"
          echo "PKI_${label}_SHA256=$(openssl x509 -in "$file" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')"
          echo "PKI_${label}_NOT_AFTER=$(date -u -d "@$(epoch "$file" enddate)" +%Y-%m-%dT%H:%M:%SZ)"
          echo "PKI_${label}_RENEW_AFTER=$(date -u -d "@$(renew_at "$file")" +%Y-%m-%dT%H:%M:%SZ)"
          ;;
        *) ;;
      esac
    done
    echo "PKI_LEAF_TEMPLATE_HASH=$(hash12 "$repo/deploy/pki/leaf.tpl")"
    ;;

  *) echo "usage: deploy/stack/pki.sh root | sign-intermediate | init | request | install-intermediate | issue | renew | inspect | env" >&2; exit 1 ;;
esac
