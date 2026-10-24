// SDK config polling under load. Most real polls send the current ETag and get a 304, so this
// mixes 90% conditional polls with 10% full fetches.
//
//   k6 run load/config-poll.js
//   k6 run -e BASE_URL=http://localhost:8080 -e VUS=100 load/config-poll.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SDK_KEY = __ENV.SDK_KEY || 'dev-sdk-key';

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/sdk/v1/config`, { headers: { 'X-API-Key': SDK_KEY } });
  return { etag: res.headers['Etag'] || res.headers['ETag'] };
}

export default function (data) {
  const headers = { 'X-API-Key': SDK_KEY };
  const conditional = Math.random() < 0.9;
  if (conditional) {
    headers['If-None-Match'] = data.etag;
  }
  const res = http.get(`${BASE_URL}/sdk/v1/config`, { headers });
  check(res, {
    'status is 200 or 304': (r) => r.status === 200 || r.status === 304,
  });
}
