import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ISSUER = __ENV.ISSUER || 'http://localhost:8180/realms/shortener';
const TOKEN_URL = __ENV.TOKEN_URL || `${ISSUER}/protocol/openid-connect/token`;
const CLIENT_ID = __ENV.CLIENT_ID || 'demo-client';
const CLIENT_KEY = JSON.parse(open(__ENV.CLIENT_KEY_FILE || '/keys/demo-client.jwk.json'));
const SEEDS = parseInt(__ENV.SEEDS || '500');
const CREATE_SHARE = parseFloat(__ENV.CREATE_SHARE || '0.01');

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

async function proof(dpop, method, url, accessToken) {
  const claims = { jti: jti(), htm: method, htu: url, iat: now() };
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
  const assertion = await sign(clientKey, { alg: 'ES256', kid: CLIENT_KEY.kid }, {
    iss: CLIENT_ID, sub: CLIENT_ID, aud: ISSUER, jti: jti(), iat: now(), exp: now() + 60,
  });
  const response = http.post(TOKEN_URL, {
    grant_type: 'client_credentials',
    client_id: CLIENT_ID,
    client_assertion_type: 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer',
    client_assertion: assertion,
  }, { headers: { DPoP: await proof(dpop, 'POST', TOKEN_URL) } });
  const token = response.json('access_token');

  const codes = [];
  for (let i = 0; i < SEEDS; i++) {
    const url = `${BASE}/api/short-links`;
    const created = http.post(url, JSON.stringify({ targetUrl: `https://example.com/seed/${i}` }), {
      headers: { Authorization: `DPoP ${token}`, DPoP: await proof(dpop, 'POST', url, token), 'Content-Type': 'application/json' },
    });
    if (created.status === 201) codes.push(created.json('shortCode'));
  }
  return { token, dpopJwk, codes };
}

let dpop;

export default async function (data) {
  if (Math.random() < CREATE_SHARE) {
    dpop = dpop || await load(data.dpopJwk);
    const url = `${BASE}/api/short-links`;
    const res = http.post(url, JSON.stringify({ targetUrl: `https://example.com/load/${__VU}-${__ITER}` }), {
      headers: { Authorization: `DPoP ${data.token}`, DPoP: await proof(dpop, 'POST', url, data.token), 'Content-Type': 'application/json' },
      tags: { name: 'create' },
    });
    createLatency.add(res.timings.duration);
    check(res, { created: (r) => r.status === 201 });
  } else {
    const hot = Math.random() < 0.8;
    const pool = hot ? Math.max(1, Math.floor(data.codes.length * 0.2)) : data.codes.length;
    const code = data.codes[Math.floor(Math.random() * pool)];
    const res = http.get(`${BASE}/${code}`, { redirects: 0, tags: { name: 'redirect' } });
    redirectLatency.add(res.timings.duration);
    check(res, { redirected: (r) => r.status === 302 });
  }
}
