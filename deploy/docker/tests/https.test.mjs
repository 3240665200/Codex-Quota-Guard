import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');
test('optional TLS ingress terminates encryption and overwrites forwarded identity', () => {
  const config = read('nginx.https.conf');
  assert.match(config, /listen 8443 ssl/);
  assert.match(config, /ssl_certificate \/etc\/quota-tls\/fullchain.pem/);
  assert.match(config, /ssl_certificate_key \/etc\/quota-tls\/privkey.pem/);
  assert.match(config, /proxy_set_header Forwarded ""/);
  assert.match(config, /proxy_set_header X-Forwarded-Proto \$scheme/);
  assert.doesNotMatch(config, /proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto/);
  assert.match(config, /return 308 https:\/\/\$host\$request_uri/);
  const compose = read('compose.https.yaml');
  assert.match(compose, /ports: !override/);
  assert.match(compose, /443:8443/);
  assert.match(compose, /nginx\.https\.conf:\/etc\/nginx\/nginx\.conf:ro/);
  assert.match(compose, /TLS_CERT_DIRECTORY.*:\/etc\/quota-tls:ro/);
  assert.match(compose, /APP_REQUIRE_HTTPS: "true"/);
});
