import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// No external jslib imports - this stays runnable offline against just the app under test.

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNT_COUNT = parseInt(__ENV.ACCOUNT_COUNT || '100', 10);
const OPENING_BALANCE_MINOR_UNITS = parseInt(__ENV.OPENING_BALANCE_MINOR_UNITS || '10000000', 10);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const MAX_TRANSFER_AMOUNT = 100; // tiny relative to OPENING_BALANCE_MINOR_UNITS - insufficient-funds should never trigger

export const transferFailures = new Counter('transfer_failures');

export const options = {
  scenarios: {
    transfers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 50 }, // ramp up
        { duration: '90s', target: 50 }, // sustained peak load
        { duration: '20s', target: 0 },  // ramp down
      ],
      exec: 'transfer',
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<2000'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const accounts = [];
  for (let i = 0; i < ACCOUNT_COUNT; i++) {
    const accountNumber = `LOADTEST-${RUN_ID}-${i}`;
    const res = http.post(
      `${BASE_URL}/api/v1/accounts`,
      JSON.stringify({
        accountNumber,
        ownerName: `Load Test Account ${i}`,
        currency: 'NGN',
        openingBalanceMinorUnits: OPENING_BALANCE_MINOR_UNITS,
      }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status !== 201) {
      throw new Error(`Failed to create account ${accountNumber}: ${res.status} ${res.body}`);
    }
    accounts.push(accountNumber);
  }
  console.log(`Seeded ${accounts.length} accounts for run ${RUN_ID}`);
  return { accounts, runId: RUN_ID };
}

export function transfer(data) {
  const accounts = data.accounts;
  const sourceIdx = randomInt(accounts.length);
  let destIdx = randomInt(accounts.length);
  while (destIdx === sourceIdx) {
    destIdx = randomInt(accounts.length);
  }

  const amountMinorUnits = randomInt(MAX_TRANSFER_AMOUNT) + 1;
  const idempotencyKey = `${data.runId}-${__VU}-${__ITER}`;

  const res = http.post(
    `${BASE_URL}/api/v1/transfers`,
    JSON.stringify({
      sourceAccountNumber: accounts[sourceIdx],
      destinationAccountNumber: accounts[destIdx],
      amountMinorUnits,
      currency: 'NGN',
    }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    }
  );

  const ok = check(res, { 'transfer succeeded (201)': (r) => r.status === 201 });
  if (!ok) {
    transferFailures.add(1);
    console.error(`Transfer failed: ${res.status} ${res.body}`);
  }
}

function randomInt(max) {
  return Math.floor(Math.random() * max);
}
