// Writes the Content-Security-Policy the edge sends with every page of the app, to dist/csp.conf, as an nginx `add_header`.
//
// The policy trusts scripts and styles by a nonce, not by a hash: nginx gives each response its own nonce (`$request_id`) and
// puts it on every <script> and <style> of the shell (`sub_filter`, deploy/nginx). `strict-dynamic` lets the nonced entry
// script load the rest, and the scripts the app creates at run time, which no hash of the build could name.
// `VITE_ISSUER` is the identity provider the browser calls: its origin is the only one besides the app's own that is allowed.
import { writeFileSync } from 'node:fs';

const issuer = new URL(process.env.VITE_ISSUER ?? 'http://localhost:8180/realms/shortener').origin;
const nonce = "'nonce-$request_id'";

const policy = [
    "default-src 'self'",
    `script-src 'strict-dynamic' ${nonce}`,
    `style-src 'self' ${nonce}`,
    `connect-src 'self' ${issuer}`,
    "img-src 'self' data:",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
].join('; ');

writeFileSync('dist/csp.conf', `add_header Content-Security-Policy "${policy}" always;\n`);
console.log(`csp: nonce per response, strict-dynamic, connect-src ${issuer}`);
