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
// Stops in München: A - M - B is the main line, M - C a branch to the north west.
const STOP = {
  A: { name: "A", stopId: "de-DELFI_de:09162:1:40:81", lat: 48.1, lon: 11.5 },
  M: { name: "M", stopId: "de-DELFI_de:09162:2:41:82", parentId: "de-DELFI_de:09162:2", lat: 48.15, lon: 11.58 },
  B: { name: "B", stopId: "de-DELFI_de:09162:3:1:1", lat: 48.2, lon: 11.6 },
  C: { name: "C", stopId: "de-DELFI_de:09162:4:2:2", lat: 48.25, lon: 11.45 },
};
const MAIN = [STOP.A, STOP.M, STOP.B];
const BRANCH = [STOP.A, STOP.M, STOP.C];
const pts = (stops) => stops.map((s) => [s.lat, s.lon]);
const tripStops = new Map();   // journey -> its stops (default: the main line)
// A running vehicle: on its journey's hop between [from] and [to] (default: A to M).
const segment = (mode, name, tripId, { from = STOP.A, to = STOP.M, stops = MAIN } = {}) => {
  tripStops.set(tripId, stops);
  return {
    mode, trips: [{ tripId, routeShortName: name }],
    departure: iso(-30), arrival: iso(90), scheduledDeparture: iso(-30), realTime: true,
    from: { name: from.name, lat: from.lat, lon: from.lon }, to: { name: to.name, lat: to.lat, lon: to.lon },
    routeColor: "0099cc", polyline: polyline.encode(pts([from, to]), 5),
  };
};
let running = [];            // what /map/trips answers
global.fetch = async (url, options) => {
  const u = new URL(url);
  asked.push({ path: u.pathname, tripId: u.searchParams.get("tripId"), userAgent: options.headers["User-Agent"] });
  if (u.pathname === "/api/v1/map/trips") return { ok: true, status: 200, json: async () => running };
  if (u.pathname === "/api/v1/trip") {
    const id = u.searchParams.get("tripId");
    if (failTrips.has(id)) return { ok: false, status: 404, json: async () => ({}) };
    const stops = tripStops.get(id) || MAIN;
    const points = polyline.encode(pts(stops), 6);
    const leg = {
      legGeometry: { points, precision: 6 },
      from: stops[0], intermediateStops: stops.slice(1, -1), to: stops[stops.length - 1],
    };
    return { ok: true, status: 200, json: async () => ({ legs: [leg] }) };
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
function call(api, url, headers = {}) {
  return new Promise((resolve) => {
    const res = {
      headers: {},
      set(k, v) { this.headers[k.toLowerCase()] = v; return this; },
      status(c) { this.code = c; return this; },
      send(b) { resolve({ code: this.code || 200, body: JSON.parse(b), headers: this.headers }); },
      json(o) { resolve({ code: this.code || 200, body: o, headers: this.headers }); },
      end() { resolve({ code: this.code || 200, body: null, headers: this.headers }); },
    };
    const get = (h) => headers[h.toLowerCase()] || "";
    api({ method: "GET", originalUrl: url, path: url.split("?")[0], get }, res);
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
  await test("one line on demand: a long distance train gets no route, nothing is asked or stored", async () => {
    const r = await call(api, routeUrl("muenchen", "HIGHSPEED_RAIL", "ICE", "trip_ICE"));
    assert.strictEqual(r.code, 404);
    assert.strictEqual(trips().length, 0);
    assert.ok(!(await loadStoredRoutes(db, "muenchen")).routes.some((row) => row[0] === "ICE"));
  });
  await test("one line on demand: a local line the set lacks is fetched once and stored", async () => {
    running.push(segment("TRAM", "27", "trip_27"));
    const r = await call(api, routeUrl("muenchen", "TRAM", "27", "trip_27"));
    assert.strictEqual(r.code, 200);
    assert.deepStrictEqual(r.body.route.slice(0, 3), ["27", "TRAM", "0099cc"]);
    assert.ok(polyline.decode(r.body.route[3], 5).length >= 2);
    assert.deepStrictEqual(trips().map((a) => a.tripId), ["trip_27"]);
    assert.ok((await loadStoredRoutes(db, "muenchen")).routes.some((row) => row[0] === "27"));
  });
  await test("one line on demand: the next phone gets it without a request to transitous", async () => {
    const r = await call(api, routeUrl("muenchen", "TRAM", "27", "another_trip"));
    assert.strictEqual(r.code, 200);
    assert.strictEqual(asked.length, 0);
  });
  await test("one line on demand: another server instance finds the stored line", async () => {
    const other = createApi({ db, logger });
    const r = await call(other, routeUrl("muenchen", "TRAM", "27", "trip_27"));
    assert.strictEqual(r.code, 200);
    assert.strictEqual(trips().length, 0);
  });
  await test("one line on demand: a journey that is not running is refused without asking for it", async () => {
    const r = await call(api, routeUrl("muenchen", "BUS", "X99", "invented_trip"));
    assert.strictEqual(r.code, 404);
    const wrongLine = await call(api, routeUrl("muenchen", "BUS", "X99", "trip_27"));
    assert.strictEqual(wrongLine.code, 404);
    assert.strictEqual(trips().length, 0);
    assert.ok(!(await loadStoredRoutes(db, "muenchen")).routes.some((row) => row[0] === "X99"));
  });
  await test("one line on demand: many phones at once cause one request", async () => {
    running.push(segment("BUS", "N40", "trip_N40"));
    const fresh = createApi({ db, logger });
    const answers = await Promise.all(
      Array.from({ length: 12 }, () => call(fresh, routeUrl("muenchen", "BUS", "N40", "trip_N40"))));
    assert.ok(answers.every((a) => a.code === 200));
    assert.strictEqual(trips().length, 1);
  });
  await test("one line on demand: a route that cannot be built is not asked for again at once", async () => {
    running.push(segment("REGIONAL_RAIL", "RB 99", "trip_RB99"));
    failTrips = new Set(["trip_RB99"]);
    const fresh = createApi({ db, logger });
    assert.strictEqual((await call(fresh, routeUrl("muenchen", "REGIONAL_RAIL", "RB", "trip_RB99"))).code, 404);
    assert.strictEqual((await call(fresh, routeUrl("muenchen", "REGIONAL_RAIL", "RB", "trip_RB99"))).code, 404);
    assert.strictEqual(trips().length, 1);
    failTrips = new Set();
  });
  await test("long distance lines stored by earlier versions are left out", async () => {
    const poly = polyline.encode([[53.5, 10.0], [53.6, 10.1]], 5);
    db.docs.set("route_cache/hamburg", { v: 1, built: Date.now(), count: 2, chunks: 1, complete: true });
    db.docs.set("route_cache/hamburg/chunks/0", { rows: JSON.stringify([["ICE", "HIGHSPEED_RAIL", "", poly], ["S1", "METRO", "", poly]]) });
    const routes = (await loadStoredRoutes(db, "hamburg")).routes;
    assert.deepStrictEqual(routes.map((r) => r[0]), ["S1"]);
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
  await test("a branch: the vehicle off the drawn line adds only the part the line lacks", async () => {
    const db2 = fakeDb();
    running = [
      segment("METRO", "S2", "s2_main"),
      segment("METRO", "S2", "s2_branch", { from: STOP.M, to: STOP.C, stops: BRANCH }),
    ];
    const first = await rebuildCityRoutes(db2, "muenchen");
    // One journey for the line, then the branch vehicle's journey for the part it lacks.
    assert.deepStrictEqual(trips().map((a) => a.tripId), ["s2_main", "s2_branch"]);
    const row = first.rows.find((r) => r[0] === "S2");
    assert.strictEqual(row.length, 5);   // label, mode, colour, the first route, the branch
    const branch = polyline.decode(row[4], 5);
    assert.ok(branch.some(([la, lo]) => Math.abs(la - 48.25) < 1e-4 && Math.abs(lo - 11.45) < 1e-4));
    assert.ok(!branch.some(([la]) => Math.abs(la - 48.1) < 1e-3), "the shared part is not stored twice");
    reset();
    const again = await rebuildCityRoutes(db2, "muenchen");
    assert.strictEqual(again.asked, 0, "a complete line costs nothing");
  });
  await test("map stations: every journey's stations, with the buttons of their lines", async () => {
    const db3 = fakeDb();
    running = [
      segment("METRO", "S2", "st_s2"),
      segment("BUS", "53", "st_53", { from: STOP.M, to: STOP.B, stops: [STOP.M, STOP.B] }),
    ];
    await rebuildCityRoutes(db3, "muenchen");
    const r = await call(createApi({ db: db3, logger }), "/api/stops/muenchen");
    assert.strictEqual(r.code, 200);
    assert.strictEqual(r.body.complete, true);
    const byId = new Map(r.body.stations.map((s) => [s[4], s]));
    assert.deepStrictEqual([...byId.keys()].sort(), ["de-DELFI_de:09162:1", "de-DELFI_de:09162:2", "de-DELFI_de:09162:3"]);
    assert.strictEqual(byId.get("de-DELFI_de:09162:1")[3], 1);       // S-Bahn only
    assert.strictEqual(byId.get("de-DELFI_de:09162:2")[3], 1 | 8);   // S-Bahn and bus
    assert.deepStrictEqual(byId.get("de-DELFI_de:09162:2").slice(0, 3), ["M", 48.15, 11.58]);
  });
  await test("a set from before the stations gets them in the next run, one journey per line", async () => {
    const db4 = fakeDb();
    const poly = polyline.encode(pts(MAIN), 5);
    db4.docs.set("route_cache/muenchen", { v: 1, built: Date.now(), count: 1, chunks: 1, complete: true });
    db4.docs.set("route_cache/muenchen/chunks/0", { rows: JSON.stringify([["S2", "METRO", "", poly]]) });
    running = [segment("METRO", "S2", "old_s2"), segment("METRO", "S2", "old_s2b")];
    assert.strictEqual((await call(createApi({ db: db4, logger }), "/api/stops/muenchen")).code, 503);
    reset();
    const { asked: count } = await rebuildCityRoutes(db4, "muenchen");
    assert.strictEqual(count, 1);
    const stored = await loadStoredRoutes(db4, "muenchen");
    assert.deepStrictEqual(stored.routes[0], ["S2", "METRO", "", poly], "the route itself is unchanged");
    assert.strictEqual(stored.stations.length, 3);
    assert.strictEqual((await call(createApi({ db: db4, logger }), "/api/stops/muenchen")).code, 200);
  });
  await test("station ids: the whole station, not one platform", async () => {
    const G = require("../lib/lineGeometry");
    assert.strictEqual(G.stationId({ stopId: "de-DELFI_de:09162:2:41:81" }), "de-DELFI_de:09162:2");
    assert.strictEqual(G.stationId({ stopId: "de-DELFI_de:09162:9:3:1_G" }), "de-DELFI_de:09162:9");
    assert.strictEqual(G.stationId({ stopId: "de-DELFI_de:09174:6840_G" }), "de-DELFI_de:09174:6840");
    assert.strictEqual(G.stationId({ stopId: "de-DELFI_de:09162:2" }), "de-DELFI_de:09162:2");
    assert.strictEqual(G.stationId({ stopId: "ch-x_8503000:0:7", parentId: "ch-x_8503000" }), "ch-x_8503000");
    assert.strictEqual(G.stationId({ stopId: "plain123" }), "plain123");
  });
  await test("line set and station list: an app that has them gets 304 without a body", async () => {
    const db5 = fakeDb();
    running = [segment("METRO", "S2", "tag_s2")];
    await rebuildCityRoutes(db5, "muenchen");
    for (const path of ["/api/routes/muenchen", "/api/stops/muenchen"]) {
      const first = await call(createApi({ db: db5, logger }), path);
      assert.strictEqual(first.code, 200);
      const tag = first.headers.etag;
      assert.ok(/^W\/"[\w-]{16}"$/.test(tag), tag);
      const again = await call(createApi({ db: db5, logger }), path, { "if-none-match": tag });
      assert.strictEqual(again.code, 304, path);
      assert.strictEqual(again.body, null);
    }
    // The next daily run changes nothing the apps use: the same tag.
    const tagBefore = (await call(createApi({ db: db5, logger }), "/api/routes/muenchen")).headers.etag;
    await rebuildCityRoutes(db5, "muenchen");
    assert.strictEqual((await call(createApi({ db: db5, logger }), "/api/routes/muenchen")).headers.etag, tagBefore);
  });
  await test("no errors were logged", async () => {
    assert.deepStrictEqual(errors, []);
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
