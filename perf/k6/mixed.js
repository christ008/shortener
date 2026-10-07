import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { open as openFile } from 'k6/experimental/fs';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ISSUER = __ENV.ISSUER || 'http://localhost:8180/realms/shortener';
const TOKEN_URL = __ENV.TOKEN_URL || `${ISSUER}/protocol/openid-connect/token`;
const CLIENT_ID = __ENV.CLIENT_ID || 'demo-client';
const CLIENT_KEY = JSON.parse(open(__ENV.CLIENT_KEY_FILE || '/keys/demo-client.jwk.json'));
const SEEDS = parseInt(__ENV.SEEDS || '500');
const CREATE_SHARE = parseFloat(__ENV.CREATE_SHARE || '0.01');
const DATASET_HOT = parseInt(__ENV.DATASET_HOT || '0');
const DATASET_HOT_SHARE = parseFloat(__ENV.DATASET_HOT_SHARE || '0.8');

// The file `tools/run Dataset --out` writes: a code and a newline, 8 bytes each, so the code at index i is the one at byte 8 * i. It is read
// by position, a code per request, and not loaded: 10M codes are 80 MB, and each VU would have its own copy.
const RECORD = 8;
const dataset = __ENV.DATASET_FILE ? await openFile(__ENV.DATASET_FILE) : null;
const DATASET_COUNT = dataset ? (await dataset.stat()).size / RECORD : 0;

async function codeAt(index) {
  const record = new Uint8Array(RECORD);
  await dataset.seek(index * RECORD, 0);
  await dataset.read(record);
  return String.fromCharCode(...record.subarray(0, RECORD - 1));
}

const randomCode = () => codeAt(Math.floor(Math.random() * DATASET_COUNT));
const hotCode = (size) => codeAt(Math.floor(Math.random() * Math.min(size, DATASET_COUNT)));

const redirectLatency = new Trend('redirect_latency', true);
const createLatency = new Trend('create_latency', true);

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.RATE || '500'),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: parseInt(__ENV.VUS || '200'),
      maxVUs: parseInt(__ENV.MAX_VUS || '1000'),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    redirect_latency: ['p(99)<100'],
    create_latency: ['p(99)<250'],
  },
};

const ES256 = { name: 'ECDSA', namedCurve: 'P-256' };
const text = (value) => encoding.b64encode(value, 'rawurl');
const json = (value) => text(JSON.stringify(value));
const now = () => Math.floor(Date.now() / 1000);
const jti = () => `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;

async function sign(privateKey, header, claims) {
  const input = `${json(header)}.${json(claims)}`;
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, new TextEncoder().encode(input));
  return `${input}.${text(signature)}`;
}

let nonce;

const nonceOf = (res) => res.headers['Dpop-Nonce'];
const asksForNonce = (res) => (res.status === 401 && String(res.headers['Www-Authenticate'] || '').includes('error="use_dpop_nonce"'))
  || (res.status === 400 && String(res.body || '').includes('use_dpop_nonce'));

// Sends a request that `send` makes with the proof it is given, keeps the server's nonce, and repeats it once with a new
// proof when the server asks for one.
async function signed(send, dpop, method, url, accessToken) {
  let res;
  for (let attempt = 0; attempt < 2; attempt++) {
    res = await send(await proof(dpop, method, url, accessToken));
    if (nonceOf(res)) nonce = nonceOf(res);
    if (!asksForNonce(res) || !nonceOf(res)) break;
  }
  return res;
}

async function proof(dpop, method, url, accessToken) {
  const claims = { jti: jti(), htm: method, htu: url, iat: now() };
  if (nonce) claims.nonce = nonce;
  if (accessToken) {
    claims.ath = text(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(accessToken)));
  }
  return sign(dpop.privateKey, { alg: 'ES256', typ: 'dpop+jwt', jwk: dpop.publicJwk }, claims);
}

async function load(privateJwk) {
  return {
    privateKey: await crypto.subtle.importKey('jwk', privateJwk, ES256, false, ['sign']),
    publicJwk: { kty: 'EC', crv: 'P-256', x: privateJwk.x, y: privateJwk.y },
  };
}

export async function setup() {
  const generated = await crypto.subtle.generateKey(ES256, true, ['sign', 'verify']);
  const dpopJwk = await crypto.subtle.exportKey('jwk', generated.privateKey);
  const dpop = await load(dpopJwk);

  const clientKey = await crypto.subtle.importKey('jwk', CLIENT_KEY, ES256, false, ['sign']);
  const response = await signed(async (dpopProof) => http.post(TOKEN_URL, {
    grant_type: 'client_credentials',
    client_id: CLIENT_ID,
    client_assertion_type: 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer',
    client_assertion: await sign(clientKey, { alg: 'ES256', kid: CLIENT_KEY.kid }, {
      iss: CLIENT_ID, sub: CLIENT_ID, aud: ISSUER, jti: jti(), iat: now(), exp: now() + 60,
    }),
  }, { headers: { DPoP: dpopProof } }), dpop, 'POST', TOKEN_URL);
  const token = response.json('access_token');

  const codes = [];
  for (let i = 0; i < SEEDS; i++) {
    const url = `${BASE}/api/short-links`;
    const created = await signed(async (dpopProof) => http.post(url, JSON.stringify({ targetUrl: `https://example.com/seed/${i}` }), {
      headers: { Authorization: `DPoP ${token}`, DPoP: dpopProof, 'Content-Type': 'application/json' },
    }), dpop, 'POST', url, token);
    if (created.status === 201) codes.push(created.json('shortCode'));
  }
  if (dataset) {
    for (const code of [await codeAt(0), await codeAt(DATASET_COUNT - 1)]) {
      const res = http.get(`${BASE}/${code}`, { redirects: 0 });
      if (res.status !== 302) throw new Error(`/${code} answered ${res.status}: the ${DATASET_COUNT} codes of ${__ENV.DATASET_FILE} are not in the database (perf/load-dataset.sh)`);
    }
  }
  return { token, dpopJwk, codes, nonce };
}

let dpop;

export default async function (data) {
  if (Math.random() < CREATE_SHARE) {
    dpop = dpop || await load(data.dpopJwk);
    nonce = nonce || data.nonce;
    const url = `${BASE}/api/short-links`;
    const res = await signed(async (dpopProof) => http.post(url, JSON.stringify({ targetUrl: `https://example.com/load/${__VU}-${__ITER}` }), {
      headers: { Authorization: `DPoP ${data.token}`, DPoP: dpopProof, 'Content-Type': 'application/json' },
      tags: { name: 'create' },
    }), dpop, 'POST', url, data.token);
    createLatency.add(res.timings.duration);
    check(res, { created: (r) => r.status === 201 });
  } else if (dataset) {
    const code = await (DATASET_HOT > 0 && Math.random() < DATASET_HOT_SHARE ? hotCode(DATASET_HOT) : randomCode());
    const res = http.get(`${BASE}/${code}`, { redirects: 0, tags: { name: 'redirect' } });
    redirectLatency.add(res.timings.duration);
    check(res, { redirected: (r) => r.status === 302 });
  } else {
    const hot = Math.random() < 0.8;
    const pool = hot ? Math.max(1, Math.floor(data.codes.length * 0.2)) : data.codes.length;
    const code = data.codes[Math.floor(Math.random() * pool)];
    const res = http.get(`${BASE}/${code}`, { redirects: 0, tags: { name: 'redirect' } });
    redirectLatency.add(res.timings.duration);
    check(res, { redirected: (r) => r.status === 302 });
  }
}
