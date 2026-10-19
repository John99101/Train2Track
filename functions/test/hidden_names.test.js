// Checks the hidden names of reports and delay votes (lib/reportIds.js) against
// the real rules and functions, on the local emulators. Nothing here can reach
// the live project: it runs under the made up project "demo-t2t".
//
// From the repo root (needs Java 21 or newer for the emulators):
//   firebase emulators:exec --only auth,firestore,functions --project demo-t2t "node functions/test/hidden_names.test.js"
const assert = require("assert");
const { legacyDocId, hiddenDocId, ownerNoteId } = require("../lib/reportIds");
const { LIMITS } = require("../lib/limits");

const PROJECT = process.env.GCLOUD_PROJECT || "demo-t2t";
const FIRESTORE = `http://${process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080"}`;
const FUNCTIONS = `http://${process.env.FUNCTIONS_EMULATOR_HOST || "127.0.0.1:5001"}/${PROJECT}/europe-west3`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
/** An unsigned sign in token, as the emulators accept it. */
function token(uid, claims = {}) {
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: "none", typ: "JWT" })}.${b64({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: "anonymous", identities: {} }, ...claims,
  })}.`;
}
const ADMIN = "owner"; // the emulator's way to skip the rules

function toValue(v) {
  if (v === null) return { nullValue: null };
  if (typeof v === "string") return { stringValue: v };
  if (typeof v === "boolean") return { booleanValue: v };
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  throw new Error(`unsupported value ${v}`);
}
function fromValue(v) {
  if ("stringValue" in v) return v.stringValue;
  if ("booleanValue" in v) return v.booleanValue;
  if ("timestampValue" in v) return v.timestampValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("arrayValue" in v) return (v.arrayValue.values || []).map(fromValue);
  if ("nullValue" in v) return null;
  return v;
}

/**
 * Writes [docs] ({ path, data, stamp }) in one go, as the apps do. With stamp the
 * document gets createdAt = server time. true when the rules allow it.
 */
async function commit(auth, docs) {
  const writes = docs.map(({ path, data, stamp }) => {
    const fields = {};
    for (const [k, v] of Object.entries(data)) fields[k] = toValue(v);
    const w = { update: { name: `${DOCS}/${path}`, fields } };
    if (stamp) w.updateTransforms = [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }];
    return w;
  });
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}:commit`, {
    method: "POST",
    headers: { authorization: `Bearer ${auth}`, "content-type": "application/json" },
    body: JSON.stringify({ writes }),
  });
  if (res.ok) return true;
  if (res.status === 403) return false;
  throw new Error(`write ${docs.map((d) => d.path).join(", ")}: HTTP ${res.status} ${await res.text()}`);
}
/** One document alone (an old app, or an attempt the rules must refuse). */
const write = (auth, path, data) => commit(auth, [{ path, data, stamp: true }]);
/** An entry under its hidden name with its owner note, as apps from 0.5.0 on write it. */
function writeHidden(auth, collection, uid, data, noteUid = uid) {
  const id = hiddenDocId(uid, data.tripId);
  return commit(auth, [
    { path: `${collection}/${id}`, data, stamp: true },
    {
      path: `doc_owners/${ownerNoteId(collection, id)}`,
      data: { uid: noteUid, collection, doc: id, tripId: data.tripId, expiresAt: data.expiresAt },
    },
  ]);
}
async function read(auth, path) {
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}/${path}`, { headers: { authorization: `Bearer ${auth}` } });
  if (res.status === 404) return null;
  if (res.status === 403) return "denied";
  if (!res.ok) throw new Error(`read ${path}: HTTP ${res.status}`);
  const out = {};
  for (const [k, v] of Object.entries((await res.json()).fields || {})) out[k] = fromValue(v);
  return out;
}
async function remove(auth, path) {
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}/${path}`, { method: "DELETE", headers: { authorization: `Bearer ${auth}` } });
  return res.ok;
}
/** Waits until [check] returns something truthy (the triggers run a moment later). */
async function eventually(what, check, timeoutMs = 30000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > end) throw new Error(`timed out: ${what}`);
    await sleep(300);
  }
}
/** An unsigned App Check token, as the emulators accept it (the apps send a real one). */
function appCheckToken() {
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: "none", typ: "JWT" })}.${b64({
    iss: "https://firebaseappcheck.googleapis.com/000000000000", sub: "1:000000000000:android:test",
    aud: [`projects/${PROJECT}`], iat: now, exp: now + 3600,
  })}.`;
}
async function post(auth, name, data = {}, appCheck = true) {
  const headers = { authorization: `Bearer ${auth}`, "content-type": "application/json" };
  if (appCheck) headers["x-firebase-appcheck"] = appCheckToken();
  return fetch(`${FUNCTIONS}/${name}`, { method: "POST", headers, body: JSON.stringify({ data }) });
}
async function call(auth, name, data = {}) {
  const res = await post(auth, name, data);
  const json = await res.json();
  if (!res.ok) throw new Error(`${name}: ${JSON.stringify(json)}`);
  return json.result;
}

const expires = () => new Date(Date.now() + 90 * 60 * 1000);
const hiddenReport = (uid, tripId, state = "entered") => ({
  tripId, uid: hiddenDocId(uid, tripId), state, city: "muenchen", line: "S8", station: "Ostbahnhof",
  description: "Entered at Ostbahnhof", localId: "user-1", expiresAt: expires(),
});
const legacyReport = (uid, tripId, state = "entered") => ({ ...hiddenReport(uid, tripId, state), uid });
const vote = (uid, tripId, hidden) => ({
  tripId, uid: hidden ? hiddenDocId(uid, tripId) : uid, vote: "confirm", city: "berlin",
  expiresAt: new Date(Date.now() + 30 * 60 * 1000),
});

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
  const A = `riderA${run}`, B = `riderB${run}`, C = `riderC${run}`;
  const trip = (n) => `20261013_08:1${n}_de-DELFI_${run}|${n}`; // has characters a name may not contain

  await test("rules: a rider may write a report under its hidden name, with its owner note", async () => {
    assert.strictEqual(await writeHidden(token(A), "reports", A, hiddenReport(A, trip(1))), true);
  });
  await test("rules: a hidden entry without its owner note is refused", async () => {
    assert.strictEqual(await write(token(A), `reports/${hiddenDocId(A, trip(2))}`, hiddenReport(A, trip(2))), false);
  });
  await test("rules: an owner note must carry the own id", async () => {
    assert.strictEqual(await writeHidden(token(A), "reports", A, hiddenReport(A, trip(2)), B), false);
  });
  await test("rules: the hidden entry may not carry the id", async () => {
    assert.strictEqual(await writeHidden(token(A), "reports", A, legacyReport(A, trip(2))), false);
  });
  await test("rules: a rider may not write under the name of another rider", async () => {
    assert.strictEqual(await writeHidden(token(B), "reports", A, hiddenReport(A, trip(1))), false);
    assert.strictEqual(await writeHidden(token(B), "reports", A, hiddenReport(A, trip(1)), B), false);
    assert.strictEqual(await write(token(B), `reports/${hiddenDocId(A, trip(1))}`, hiddenReport(A, trip(1))), false);
  });
  await test("rules: a free name is refused", async () => {
    assert.strictEqual(await write(token(A), `reports/anything${run}`, { ...hiddenReport(A, trip(1)), uid: `anything${run}` }), false);
  });
  await test("rules: a made up journey number is refused", async () => {
    const D = `riderD${run}`;
    const madeUp = `just some text ${run}`;
    assert.strictEqual(await writeHidden(token(D), "reports", D, hiddenReport(D, madeUp)), false);
    assert.strictEqual(await writeHidden(token(D), "delay_reports", D, vote(D, madeUp, true)), false);
    const log = (tripId) => ({ tripId, line: "S8", station: "Ostbahnhof", city: "muenchen" });
    assert.strictEqual(await commit(token(D), [{ path: `control_log/x${run}`, data: log(madeUp), stamp: true }]), false);
    assert.strictEqual(await commit(token(D), [{ path: `control_log/y${run}`, data: log(trip(1)), stamp: true }]), true);
  });
  await test("rules: no app can read or forge the owner notes", async () => {
    assert.strictEqual(await read(token(A), `doc_owners/reports__${hiddenDocId(A, trip(1))}`), "denied");
    assert.strictEqual(await read(token(B), `doc_owners/reports__${hiddenDocId(A, trip(1))}`), "denied");
    assert.strictEqual(await write(token(A), `doc_owners/reports__x${run}`, { uid: A }), false);
  });
  await test("other riders see a hidden report without the id", async () => {
    const id = hiddenDocId(A, trip(1));
    assert.strictEqual((await read(ADMIN, `doc_owners/reports__${id}`)).uid, A);
    const doc = await read(token(B), `reports/${id}`);
    assert.strictEqual(doc.uid, id, "other riders only see the hash");
    assert.ok(!JSON.stringify(doc).includes(A), "the entry must not contain the id");
  });
  await test("old app: a report under the old name is accepted and renamed", async () => {
    const oldPath = `reports/${legacyDocId(B, trip(3))}`;
    const id = hiddenDocId(B, trip(3));
    assert.strictEqual(await write(token(B), oldPath, legacyReport(B, trip(3))), true);
    const doc = await eventually("renamed report", () => read(ADMIN, `reports/${id}`));
    assert.strictEqual(doc.uid, id);
    assert.strictEqual(doc.state, "entered");
    assert.strictEqual(doc.line, "S8");
    assert.ok(!JSON.stringify(doc).includes(B), "the renamed entry must not contain the id");
    await eventually("old name gone", async () => (await read(ADMIN, oldPath)) === null);
    assert.strictEqual((await read(ADMIN, `doc_owners/reports__${id}`)).uid, B);
  });
  await test("old app: has left replaces the renamed entry", async () => {
    const id = hiddenDocId(B, trip(3));
    assert.strictEqual(await write(token(B), `reports/${legacyDocId(B, trip(3))}`, legacyReport(B, trip(3), "exited")), true);
    await eventually("state exited", async () => ((await read(ADMIN, `reports/${id}`)) || {}).state === "exited");
  });
  await test("old app, then the new app on the same journey: still one entry", async () => {
    const id = hiddenDocId(B, trip(3));
    assert.strictEqual(await writeHidden(token(B), "reports", B, hiddenReport(B, trip(3))), true);
    assert.strictEqual((await read(ADMIN, `reports/${id}`)).state, "entered");
    assert.strictEqual((await read(ADMIN, `doc_owners/reports__${id}`)).renamedAt, undefined);
  });
  await test("server: counts each write once (a renamed entry is not counted twice)", async () => {
    await eventually("three counted", async () => ((await read(ADMIN, `rate_limits/${B}`)) || { report: [] }).report.length >= 3);
    await sleep(2500);
    assert.strictEqual((await read(ADMIN, `rate_limits/${B}`)).report.length, 3, "B wrote three reports");
  });
  await test("delay votes: hidden name, old name renamed", async () => {
    const idA = hiddenDocId(A, trip(4)), idB = hiddenDocId(B, trip(4));
    assert.strictEqual(await writeHidden(token(A), "delay_reports", A, vote(A, trip(4), true)), true);
    assert.strictEqual(await writeHidden(token(A), "delay_reports", A, vote(A, trip(4), false)), false);
    assert.strictEqual(await write(token(B), `delay_reports/${legacyDocId(B, trip(4))}`, vote(B, trip(4), false)), true);
    assert.strictEqual((await read(ADMIN, `doc_owners/delay_reports__${idA}`)).uid, A);
    const renamed = await eventually("renamed vote", () => read(ADMIN, `delay_reports/${idB}`));
    assert.strictEqual(renamed.uid, idB);
    assert.strictEqual(renamed.vote, "confirm");
    await eventually("old vote name gone", async () => (await read(ADMIN, `delay_reports/${legacyDocId(B, trip(4))}`)) === null);
  });
  await test("rules: only the owner may delete a hidden entry", async () => {
    const id = hiddenDocId(A, trip(4));
    assert.strictEqual(await remove(token(B), `delay_reports/${id}`), false);
    assert.strictEqual(await remove(token(A), `delay_reports/${id}`), true);
  });
  await test("server: the spam limit still counts per rider", async () => {
    const max = LIMITS.report[0].max;
    for (let i = 0; i <= max; i++) {
      const t = `${trip(5)}_${i}`;
      assert.strictEqual(await writeHidden(token(C), "reports", C, hiddenReport(C, t)), true);
    }
    // The triggers run in no fixed order: exactly one entry must end up marked.
    const marked = async () => {
      let n = 0;
      for (let i = 0; i <= max; i++) {
        if (((await read(ADMIN, `reports/${hiddenDocId(C, `${trip(5)}_${i}`)}`)) || {}).throttled === true) n++;
      }
      return n;
    };
    await eventually("one entry over the limit", async () => (await marked()) === 1);
  });
  await test("moderation: dismissing a journey remembers the riders behind hidden entries", async () => {
    const adminToken = token(`admin${run}`, { admin: true });
    const result = await call(adminToken, "moderateTrip", { tripId: trip(1), action: "dismiss" });
    assert.strictEqual(result.reports, 1);
    assert.strictEqual((await read(ADMIN, `reports/${hiddenDocId(A, trip(1))}`)).dismissed, true);
    assert.deepStrictEqual((await read(ADMIN, `trip_moderation/${trip(1).replace(/[^A-Za-z0-9_-]/g, "-")}`)).dismissedUids, [A]);
    // The same rider reports the same journey again: it stays hidden.
    assert.strictEqual(await writeHidden(token(A), "reports", A, hiddenReport(A, trip(1))), true);
    await eventually("dismissed again", async () => ((await read(ADMIN, `reports/${hiddenDocId(A, trip(1))}`)) || {}).dismissed === true);
  });
  await test("server: a call without App Check is refused", async () => {
    const res = await post(token(`riderE${run}`), "deleteMyData", {}, false);
    assert.strictEqual(res.status, 401, `HTTP ${res.status}`);
  });
  await test("delete my data: removes hidden entries and their notes", async () => {
    await call(token(B), "deleteMyData");
    assert.strictEqual(await read(ADMIN, `reports/${hiddenDocId(B, trip(3))}`), null);
    assert.strictEqual(await read(ADMIN, `doc_owners/reports__${hiddenDocId(B, trip(3))}`), null);
    assert.strictEqual(await read(ADMIN, `delay_reports/${hiddenDocId(B, trip(4))}`), null);
    assert.strictEqual(await read(ADMIN, `doc_owners/delay_reports__${hiddenDocId(B, trip(4))}`), null);
    assert.ok(await read(ADMIN, `reports/${hiddenDocId(A, trip(1))}`), "other riders are not touched");
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})().catch((e) => {
  console.log(results.join("\n"));
  console.error("test run failed:", e.message);
  process.exit(1);
});
