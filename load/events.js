// Event ingest under load: batches of exposures and conversions, as the SDK sends them.
// Throughput in events/sec = http_reqs/s x BATCH.
//
//   k6 run load/events.js
//   k6 run -e BATCH=500 -e VUS=20 load/events.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SDK_KEY = __ENV.SDK_KEY || 'dev-sdk-key';
const BATCH = Number(__ENV.BATCH || 500);
const FLAG = __ENV.FLAG || 'load-test';

const eventsSent = new Counter('events_sent');

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const now = new Date().toISOString();
  const events = [];
  for (let i = 0; i < BATCH; i++) {
    const userId = `load-${__VU}-${__ITER}-${i}`;
    const variant = i % 2 === 0 ? 'control' : 'treatment';
    events.push({ id: uuidv4(), type: 'EXPOSURE', flagKey: FLAG, variant, userId, timestamp: now });
    if (i % 10 === 0) {
      events.push({ id: uuidv4(), type: 'CONVERSION', flagKey: FLAG, variant, userId, eventName: 'applied_to_job', timestamp: now });
    }
    if (events.length >= BATCH) {
      break;
    }
  }
  const res = http.post(`${BASE_URL}/sdk/v1/events`, JSON.stringify({ events }), {
    headers: { 'X-API-Key': SDK_KEY, 'Content-Type': 'application/json' },
  });
  if (check(res, { 'status is 202': (r) => r.status === 202 })) {
    eventsSent.add(events.length);
  }
}
