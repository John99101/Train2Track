// Checks the database rules of the collections hidden_names.test.js does not cover:
// the activity signal, statistics, disruptions, the app settings (minimum build),
// the server only bookkeeping and the city ids. With LOCKDOWN=1 it checks the
// emergency rules instead (firestore.lockdown.rules): every write refused, reading
// still possible. Nothing here can reach the live project: it runs under the made
// up project "demo-t2t" on the local emulators.
//
// From the repo root (needs Java 21 or newer for the emulators):
//   firebase emulators:exec --only auth,firestore --project demo-t2t "node functions/test/rules_more.test.js"
//   firebase emulators:exec --only firestore --project demo-t2t --config firebase.lockdown.json "node functions/test/rules_more.test.js lockdown"
const assert = require("assert");

const PROJECT = process.env.GCLOUD_PROJECT || "demo-t2t";
const FIRESTORE = `http://${process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080"}`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
const LOCKDOWN = process.argv[2] === "lockdown";

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
  if (typeof v === "number") return { integerValue: String(v) };
  if (typeof v === "boolean") return { booleanValue: v };
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  throw new Error(`unsupported value ${v}`);
}

/** Writes one document; [stamp] names a field set to the server time. true when allowed. */
async function write(auth, path, data, stamp) {
  const fields = {};
  for (const [k, v] of Object.entries(data)) fields[k] = toValue(v);
  const w = { update: { name: `${DOCS}/${path}`, fields } };
  if (stamp) w.updateTransforms = [{ fieldPath: stamp, setToServerValue: "REQUEST_TIME" }];
  const headers = { "content-type": "application/json" };
  if (auth) headers.authorization = `Bearer ${auth}`;
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}:commit`, { method: "POST", headers, body: JSON.stringify({ writes: [w] }) });
  if (res.ok) return true;
  if (res.status === 403) return false;
  throw new Error(`write ${path}: HTTP ${res.status} ${await res.text()}`);
}
/** Reads one document: "ok", "missing" or "denied". */
async function read(auth, path) {
  const headers = auth ? { authorization: `Bearer ${auth}` } : {};
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}/${path}`, { headers });
  if (res.ok) return "ok";
  if (res.status === 404) return "missing";
  if (res.status === 403) return "denied";
  throw new Error(`read ${path}: HTTP ${res.status}`);
}
/** Lists a collection: "ok" or "denied". */
async function list(auth, collection) {
  const headers = auth ? { authorization: `Bearer ${auth}` } : {};
  const res = await fetch(`${FIRESTORE}/v1/${DOCS}/${collection}?pageSize=5`, { headers });
  if (res.ok) return "ok";
  if (res.status === 403) return "denied";
  throw new Error(`list ${collection}: HTTP ${res.status}`);
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
  const A = `riderA${run}`, B = `riderB${run}`;
  // Documents only the server writes, prepared without the rules.
  for (const [path, data] of [
    ["config/app", { androidMinBuild: 0, iosMinBuild: 0 }],
    [`disruptions/d${run}`, { title: "Test" }],
    ["stats/live", { activeUsers: 3 }],
    ["stats/controls", { total: 1 }],
    [`rate_limits/${A}`, { count: 1 }],
    [`trip_moderation/t${run}`, { tripId: "x" }],
    [`route_cache/muenchen`, { v: 1 }],
    [`users/${A}`, { token: "old" }],
  ]) await write(ADMIN, path, data);

  if (LOCKDOWN) {
    await test("lockdown: an app cannot write a report, a vote, a statistics entry or its activity signal", async () => {
      const t = "20261019_08:10_de-DELFI_x";
      assert.strictEqual(await write(token(A), `reports/r${run}`, { tripId: t, uid: A, city: "muenchen" }, "createdAt"), false);
      assert.strictEqual(await write(token(A), `delay_reports/v${run}`, { tripId: t, uid: A, vote: "confirm" }, "createdAt"), false);
      assert.strictEqual(await write(token(A), `control_log/c${run}`, { tripId: t, city: "muenchen" }, "createdAt"), false);
      assert.strictEqual(await write(token(A), `presence/${A}`, { platform: "android" }, "lastSeen"), false);
    });
    await test("lockdown: the apps can still read reports, disruptions, the live numbers and the settings", async () => {
      assert.strictEqual(await list(token(A), "reports"), "ok");
      assert.strictEqual(await list(token(A), "disruptions"), "ok");
      assert.strictEqual(await read(token(A), "stats/live"), "ok");
      assert.strictEqual(await read(null, "config/app"), "ok");
      assert.strictEqual(await read(token(A), "stats/controls"), "denied");
    });
  } else {
    await test("activity signal: only the own document, server time and a known platform", async () => {
      assert.strictEqual(await write(token(A), `presence/${A}`, { platform: "android" }, "lastSeen"), true);
      assert.strictEqual(await write(token(A), `presence/${B}`, { platform: "android" }, "lastSeen"), false);
      assert.strictEqual(await write(token(A), `presence/${A}`, { platform: "web" }, "lastSeen"), false);
      assert.strictEqual(await write(token(A), `presence/${A}`, { platform: "ios", extra: "x" }, "lastSeen"), false);
      assert.strictEqual(await write(token(A), `presence/${A}`, { platform: "ios", lastSeen: new Date(Date.now() + 3600e3) }), false);
      assert.strictEqual(await read(token(A), `presence/${A}`), "denied");
    });
    await test("statistics: the live numbers for every app, everything else for admins only, writing for nobody", async () => {
      assert.strictEqual(await read(token(A), "stats/live"), "ok");
      assert.strictEqual(await read(null, "stats/live"), "denied");
      assert.strictEqual(await read(token(A), "stats/controls"), "denied");
      assert.strictEqual(await read(token(A, { admin: true }), "stats/controls"), "ok");
      assert.strictEqual(await write(token(A, { admin: true }), "stats/controls", { total: 99 }), false);
    });
    await test("disruptions: readable when signed in, written only by the server", async () => {
      assert.strictEqual(await list(token(A), "disruptions"), "ok");
      assert.strictEqual(await list(null, "disruptions"), "denied");
      assert.strictEqual(await write(token(A), `disruptions/x${run}`, { title: "fake" }), false);
    });
    await test("app settings (minimum build): readable before sign in, written by nobody", async () => {
      assert.strictEqual(await read(null, "config/app"), "ok");
      assert.strictEqual(await write(token(A), "config/app", { androidMinBuild: 99 }), false);
      assert.strictEqual(await write(token(A, { admin: true }), "config/app", { androidMinBuild: 99 }), false);
    });
    await test("server bookkeeping and old notification tokens: no app can read or write them", async () => {
      for (const path of [`rate_limits/${A}`, `trip_moderation/t${run}`, "route_cache/muenchen", `users/${A}`]) {
        assert.strictEqual(await read(token(A), path), "denied", path);
        assert.strictEqual(await write(token(A), path, { x: "y" }), false, path);
      }
    });
    await test("reports: nothing readable without sign in", async () => {
      assert.strictEqual(await list(null, "reports"), "denied");
      assert.strictEqual(await list(null, "delay_reports"), "denied");
    });
    await test("cities: the prepared ones (Leipzig, Hannover) are accepted, unknown ones refused", async () => {
      const entry = (city) => ({ tripId: "20261019_08:10_de-DELFI_x", line: "S1", station: "Hbf", city });
      assert.strictEqual(await write(token(A), `control_log/l${run}`, entry("leipzig"), "createdAt"), true);
      assert.strictEqual(await write(token(A), `control_log/h${run}`, entry("hannover"), "createdAt"), true);
      assert.strictEqual(await write(token(A), `control_log/a${run}`, entry("atlantis"), "createdAt"), false);
    });
    await test("anything not listed in the rules is refused", async () => {
      assert.strictEqual(await read(token(A), `secrets/x${run}`), "denied");
      assert.strictEqual(await write(token(A), `secrets/x${run}`, { x: "y" }), false);
    });
  }

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
