// Load test of the database on the local emulators: many riders report at the same
// moment, many apps read at the same moment. It checks that nothing is lost or
// counted wrong under that load (every report accepted, the spam limit not set
// off for riders who report once, the server triggers keeping up). The emulators
// are much slower than the real database, so the times are only a rough check.
// Nothing here can reach the live project: it runs under the made up project
// "demo-t2t".
//
// From the repo root (needs Java 21 or newer for the emulators):
//   firebase emulators:exec --only auth,firestore,functions --project demo-t2t "node functions/test/load.test.js"
const assert = require("assert");
const { hiddenDocId, ownerNoteId } = require("../lib/reportIds");

const PROJECT = process.env.GCLOUD_PROJECT || "demo-t2t";
const FIRESTORE = `http://${process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080"}`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
const RIDERS = 300;
const TRIPS = 30;
const READERS = 300;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
function token(uid) {
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: "none", typ: "JWT" })}.${b64({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now, firebase: { sign_in_provider: "anonymous", identities: {} },
  })}.`;
}
const toValue = (v) => (v instanceof Date ? { timestampValue: v.toISOString() } : { stringValue: v });

/** A report under its hidden name with its owner note, as the apps write it. */
async function report(uid, tripId) {
  const id = hiddenDocId(uid, tripId);
  const expiresAt = new Date(Date.now() + 90 * 60 * 1000);
  const data = { tripId, uid: id, state: "entered", city: "muenchen", line: "S8", station: "Ostbahnhof", expiresAt };
  const fields = Object.fromEntries(Object.entries(data).map(([k, v]) => [k, toValue(v)]));
  const note = { uid, collection: "reports", doc: id, tripId, expiresAt };
  const noteFields = Object.fromEntries(Object.entries(note).map(([k, v]) => [k, toValue(v)]));
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}:commit`, {
    method: "POST",
    headers: { authorization: `Bearer ${token(uid)}`, "content-type": "application/json" },
    body: JSON.stringify({ writes: [
      { update: { name: `${DOCS}/reports/${id}`, fields }, updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }] },
      { update: { name: `${DOCS}/doc_owners/${ownerNoteId("reports", id)}`, fields: noteFields } },
    ] }),
  });
  return res.ok;
}
/** What the apps ask for: the live reports of a city. */
async function readCity(uid) {
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}:runQuery`, {
    method: "POST",
    headers: { authorization: `Bearer ${token(uid)}`, "content-type": "application/json" },
    body: JSON.stringify({ structuredQuery: {
      from: [{ collectionId: "reports" }],
      where: { compositeFilter: { op: "AND", filters: [
        { fieldFilter: { field: { fieldPath: "city" }, op: "IN", value: { arrayValue: { values: [{ stringValue: "muenchen" }, { stringValue: "unknown" }] } } } },
        { fieldFilter: { field: { fieldPath: "expiresAt" }, op: "GREATER_THAN", value: { timestampValue: new Date().toISOString() } } },
      ] } },
    } }),
  });
  if (!res.ok) return -1;
  return (await res.json()).filter((r) => r.document).length;
}
async function adminList(collection) {
  const out = [];
  let page = "";
  do {
    const res = await fetch(`${FIRESTORE}/v1/${DOCS}/${collection}?pageSize=300${page ? `&pageToken=${page}` : ""}`, { headers: { authorization: "Bearer owner" } });
    const json = await res.json();
    out.push(...(json.documents || []));
    page = json.nextPageToken || "";
  } while (page);
  return out;
}

const results = [];
async function test(name, fn) {
  try {
    await fn();
    results.push(`ok    ${name}`);
  } catch (e) {
    results.push(`FAIL  ${name}: ${e.message}`);
    process.exitCode = 1;
  }
}

(async () => {
  const run = Date.now().toString(36);
  const trip = (n) => `20261019_08:${String(10 + (n % 50)).padStart(2, "0")}_de-DELFI_load${run}_${n}`;

  await test(`${RIDERS} riders report at the same moment (${TRIPS} journeys): every report is accepted`, async () => {
    const t0 = Date.now();
    const ok = await Promise.all(Array.from({ length: RIDERS }, (_, i) => report(`load${run}_${i}`, trip(i % TRIPS))));
    const ms = Date.now() - t0;
    results.push(`      ${RIDERS} reports in ${ms} ms on the emulator`);
    assert.strictEqual(ok.filter(Boolean).length, RIDERS);
  });

  await test(`${READERS} apps load the city's reports at the same moment: all get all of them`, async () => {
    const t0 = Date.now();
    const counts = await Promise.all(Array.from({ length: READERS }, (_, i) => readCity(`reader${run}_${i}`)));
    const ms = Date.now() - t0;
    results.push(`      ${READERS} reads in ${ms} ms on the emulator`);
    assert.ok(counts.every((c) => c >= RIDERS), `smallest answer ${Math.min(...counts)}`);
  });

  await test("the server keeps up: every rider counted once, nobody blocked by the spam limit", async () => {
    // The triggers run a moment later; wait until every rider has its counter.
    const end = Date.now() + 120000;
    let limits = [];
    for (;;) {
      limits = (await adminList("rate_limits")).filter((d) => d.name.includes(`load${run}_`));
      if (limits.length >= RIDERS || Date.now() > end) break;
      await sleep(1000);
    }
    assert.strictEqual(limits.length, RIDERS, "every rider has exactly one counter");
    const ours = (await adminList("reports")).filter((d) => (d.fields.tripId || {}).stringValue?.includes(`load${run}_`));
    assert.strictEqual(ours.length, RIDERS);
    const throttled = ours.filter((d) => d.fields.throttled && d.fields.throttled.booleanValue);
    assert.strictEqual(throttled.length, 0, `${throttled.length} reports wrongly blocked`);
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
