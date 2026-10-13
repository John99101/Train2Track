// Checks how the server asks transitous for line routes: only for lines it does
// not have yet, once per line, never for a journey that is not running, and with
// a user agent that names the app, its version and a contact.
//
// Nothing here reaches transitous or Firebase: both are replaced by stand-ins.
//
// From the repo root:
//   node functions/test/routes.test.js
const assert = require("assert");
const polyline = require("../lib/polyline");
const { version } = require("../package.json");

// ---- stand-in for transitous ------------------------------------------------------
const asked = [];            // every request the server made: { path, tripId, userAgent }
let failTrips = new Set();   // journeys whose route transitous cannot give
const iso = (offsetS) => new Date(Date.now() + offsetS * 1000).toISOString();
const segment = (mode, name, tripId) => ({
  mode, trips: [{ tripId, routeShortName: name }],
  departure: iso(-30), arrival: iso(90), scheduledDeparture: iso(-30), realTime: true,
  from: { name: "A", lat: 48.1, lon: 11.5 }, to: { name: "B", lat: 48.2, lon: 11.6 },
  routeColor: "0099cc", polyline: "abc",
});
let running = [];            // what /map/trips answers
global.fetch = async (url, options) => {
  const u = new URL(url);
  asked.push({ path: u.pathname, tripId: u.searchParams.get("tripId"), userAgent: options.headers["User-Agent"] });
  if (u.pathname === "/api/v1/map/trips") return { ok: true, status: 200, json: async () => running };
  if (u.pathname === "/api/v1/trip") {
    if (failTrips.has(u.searchParams.get("tripId"))) return { ok: false, status: 404, json: async () => ({}) };
    const points = polyline.encode([[48.1, 11.5], [48.15, 11.58], [48.2, 11.6]], 6);
    return { ok: true, status: 200, json: async () => ({ legs: [{ legGeometry: { points, precision: 6 } }] }) };
  }
  throw new Error(`unexpected request ${url}`);
};
const trips = () => asked.filter((a) => a.path === "/api/v1/trip");
const reset = () => { asked.length = 0; };

// ---- stand-in for Firestore (only what lib/liveApi.js uses) -------------------------
function fakeDb() {
  const docs = new Map();
  const ref = (p) => ({
    path: p,
    id: p.split("/").pop(),
    get: async () => ({ exists: docs.has(p), get: (f) => (docs.get(p) || {})[f] }),
    set: async (data) => { docs.set(p, data); },
    collection: (name) => ({
      doc: (id) => ref(`${p}/${name}/${id}`),
      get: async () => ({
        docs: [...docs.keys()]
          .filter((k) => k.startsWith(`${p}/${name}/`) && !k.slice(`${p}/${name}/`.length).includes("/"))
          .map((k) => ({ id: k.split("/").pop(), ref: ref(k), get: (f) => docs.get(k)[f] })),
      }),
    }),
  });
  return {
    docs,
    collection: (name) => ({ doc: (id) => ref(`${name}/${id}`) }),
    batch: () => {
      const ops = [];
      return {
        set: (r, data) => ops.push(() => docs.set(r.path, data)),
        delete: (r) => ops.push(() => docs.delete(r.path)),
        commit: async () => ops.forEach((op) => op()),
      };
    },
  };
}

const T = require("../lib/transitous");
const { createApi, rebuildCityRoutes, loadStoredRoutes } = require("../lib/liveApi");

const errors = [];
const logger = { info() {}, warn() {}, error: (m) => errors.push(m) };

/** Calls the API like Hosting does and returns { code, body }. */
function call(api, url) {
  return new Promise((resolve) => {
    const res = {
      set() { return this; },
      status(c) { this.code = c; return this; },
      send(b) { resolve({ code: this.code || 200, body: JSON.parse(b) }); },
      json(o) { resolve({ code: this.code || 200, body: o }); },
    };
    api({ method: "GET", originalUrl: url, path: url.split("?")[0], get: () => "" }, res);
  });
}
const routeUrl = (city, mode, label, trip) =>
  `/api/route/${city}/${mode}/${encodeURIComponent(label)}/${encodeURIComponent(trip)}`;

const results = [];
async function test(name, fn) {
  reset();
  try {
    await fn();
    results.push(`ok    ${name}`);
  } catch (e) {
    results.push(`FAIL  ${name}: ${e.message}`);
    process.exitCode = 1;
  }
}

(async () => {
  running = [
    segment("METRO", "S1", "trip_S1"),
    segment("SUBWAY", "U2", "trip_U2"),
    segment("BUS", "100", "trip_100"),
    segment("HIGHSPEED_RAIL", "ICE 1605", "trip_ICE"),
  ];

  await test("every request names the app, its version and a contact", async () => {
    await T.fetchLive("muenchen");
    await T.tripRoute("trip_S1");
    assert.strictEqual(asked.length, 2);
    for (const a of asked) {
      assert.ok(a.userAgent.startsWith(`Train2Track/${version} `), a.userAgent);
      assert.ok(/https?:\/\/\S+/.test(a.userAgent) && /\S+@\S+/.test(a.userAgent), a.userAgent);
    }
  });

  const db = fakeDb();
  await test("first build: one journey per local line, none for long distance", async () => {
    const { rows, asked: count } = await rebuildCityRoutes(db, "muenchen");
    assert.strictEqual(count, 3);
    assert.deepStrictEqual(trips().map((a) => a.tripId).sort(), ["trip_100", "trip_S1", "trip_U2"]);
    assert.strictEqual(rows.length, 3);
  });
  await test("weekly top up: lines the set has are not asked again", async () => {
    const { rows, asked: count } = await rebuildCityRoutes(db, "muenchen");
    assert.strictEqual(count, 0);
    assert.strictEqual(trips().length, 0);
    assert.strictEqual(rows.length, 3);
  });
  await test("weekly top up: only a new line is asked", async () => {
    running.push(segment("TRAM", "19", "trip_19"));
    const { rows, asked: count } = await rebuildCityRoutes(db, "muenchen");
    assert.strictEqual(count, 1);
    assert.deepStrictEqual(trips().map((a) => a.tripId), ["trip_19"]);
    assert.strictEqual(rows.length, 4);
  });
  await test("a line that is not running today stays in the set", async () => {
    running = running.filter((s) => s.trips[0].tripId !== "trip_U2");
    const { rows } = await rebuildCityRoutes(db, "muenchen");
    assert.ok(rows.some((r) => r[0] === "U2" && r[1] === "SUBWAY"));
    assert.strictEqual(trips().length, 0);
  });
  await test("admin button (full): every running line is fetched anew", async () => {
    const { asked: count } = await rebuildCityRoutes(db, "muenchen", { full: true });
    assert.strictEqual(count, 3);   // S1, 100, 19 (U2 is not running, ICE is long distance)
  });

  const api = createApi({ db, logger });
  await test("one line on demand: a stored line needs no request to transitous", async () => {
    await call(api, "/api/routes/muenchen");   // the set is in memory now
    reset();
    const r = await call(api, routeUrl("muenchen", "METRO", "S1", "whatever"));
    assert.strictEqual(r.code, 200);
    assert.deepStrictEqual(r.body.route.slice(0, 2), ["S1", "METRO"]);
    assert.strictEqual(asked.length, 0);
  });
  await test("one line on demand: a long distance line is fetched once and stored", async () => {
    const r = await call(api, routeUrl("muenchen", "HIGHSPEED_RAIL", "ICE", "trip_ICE"));
    assert.strictEqual(r.code, 200);
    assert.deepStrictEqual(r.body.route.slice(0, 3), ["ICE", "HIGHSPEED_RAIL", "0099cc"]);
    assert.ok(polyline.decode(r.body.route[3], 5).length >= 2);
    assert.deepStrictEqual(trips().map((a) => a.tripId), ["trip_ICE"]);
    assert.ok((await loadStoredRoutes(db, "muenchen")).routes.some((row) => row[0] === "ICE"));
  });
  await test("one line on demand: the next phone gets it without a request to transitous", async () => {
    const r = await call(api, routeUrl("muenchen", "HIGHSPEED_RAIL", "ICE", "another_trip"));
    assert.strictEqual(r.code, 200);
    assert.strictEqual(asked.length, 0);
  });
  await test("one line on demand: another server instance finds the stored line", async () => {
    const other = createApi({ db, logger });
    const r = await call(other, routeUrl("muenchen", "HIGHSPEED_RAIL", "ICE", "trip_ICE"));
    assert.strictEqual(r.code, 200);
    assert.strictEqual(trips().length, 0);
  });
  await test("one line on demand: a journey that is not running is refused without asking for it", async () => {
    const r = await call(api, routeUrl("muenchen", "BUS", "X99", "invented_trip"));
    assert.strictEqual(r.code, 404);
    const wrongLine = await call(api, routeUrl("muenchen", "BUS", "X99", "trip_ICE"));
    assert.strictEqual(wrongLine.code, 404);
    assert.strictEqual(trips().length, 0);
    assert.ok(!(await loadStoredRoutes(db, "muenchen")).routes.some((row) => row[0] === "X99"));
  });
  await test("one line on demand: many phones at once cause one request", async () => {
    running.push(segment("NIGHT_RAIL", "NJ 420", "trip_NJ"));
    const fresh = createApi({ db, logger });
    const answers = await Promise.all(
      Array.from({ length: 12 }, () => call(fresh, routeUrl("muenchen", "NIGHT_RAIL", "NJ", "trip_NJ"))));
    assert.ok(answers.every((a) => a.code === 200));
    assert.strictEqual(trips().length, 1);
  });
  await test("one line on demand: a route that cannot be built is not asked for again at once", async () => {
    running.push(segment("LONG_DISTANCE", "IC 2", "trip_IC"));
    failTrips = new Set(["trip_IC"]);
    const fresh = createApi({ db, logger });
    assert.strictEqual((await call(fresh, routeUrl("muenchen", "LONG_DISTANCE", "IC", "trip_IC"))).code, 404);
    assert.strictEqual((await call(fresh, routeUrl("muenchen", "LONG_DISTANCE", "IC", "trip_IC"))).code, 404);
    assert.strictEqual(trips().length, 1);
    failTrips = new Set();
  });
  await test("one line on demand: unknown city, unknown mode and overlong values are refused", async () => {
    assert.strictEqual((await call(api, routeUrl("atlantis", "BUS", "100", "trip_100"))).code, 404);
    assert.strictEqual((await call(api, routeUrl("muenchen", "ROCKET", "100", "trip_100"))).code, 404);
    assert.strictEqual((await call(api, routeUrl("muenchen", "BUS", "x".repeat(41), "trip_100"))).code, 404);
    assert.strictEqual((await call(api, routeUrl("muenchen", "BUS", "100", "t".repeat(301)))).code, 404);
    assert.strictEqual((await call(api, "/api/route/muenchen/BUS/100")).code, 404);
    assert.strictEqual(trips().length, 0);
  });
  await test("the other paths answer as before", async () => {
    const live = await call(api, "/api/live/muenchen");
    assert.strictEqual(live.code, 200);
    assert.ok(live.body.segs.length >= 4);
    assert.strictEqual((await call(api, "/api/routes/muenchen")).code, 200);
    assert.strictEqual((await call(api, "/api/live/muenchen?x=1")).code, 400);
    assert.strictEqual((await call(api, "/api/live/muenchen/extra")).code, 404);
  });
  await test("no errors were logged", async () => {
    assert.deepStrictEqual(errors, []);
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
