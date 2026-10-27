// Checks the Stations tab's server part (lib/stations.js): the station search and
// the departures of a station. Only stations of the city, compact answers, and
// transitous asked as rarely as possible.
//
// Nothing here reaches transitous or Firebase: both are replaced by stand-ins.
//
// From the repo root:
//   node functions/test/stations.test.js
const assert = require("assert");

// ---- stand-in for transitous ------------------------------------------------------------
const asked = [];   // { host, path, params, headers }
const iso = (offsetS) => new Date(Date.now() + offsetS * 1000).toISOString();
const stop = (id, name, lat, lon, modes = ["SUBURBAN", "SUBWAY"]) => ({ type: "STOP", id, name, lat, lon, modes });
let geocode = [];
let stopTimes = {};
global.fetch = async (url, options) => {
  const u = new URL(url);
  asked.push({ host: u.host, path: u.pathname, params: u.searchParams, headers: options.headers });
  const ok = (body) => ({ ok: true, status: 200, json: async () => body });
  if (u.pathname === "/api/v1/geocode") return ok(geocode);
  if (u.pathname === "/api/v1/stoptimes") {
    const body = stopTimes[u.searchParams.get("stopId")];
    return body ? ok(body) : { ok: false, status: 404, json: async () => ({}) };
  }
  throw new Error(`unexpected request ${url}`);
};
const count = (path) => asked.filter((a) => a.path.endsWith(path)).length;
const reset = () => { asked.length = 0; };

// ---- stand-in for Firestore (the station part does not use it) --------------------------
const db = {
  collection: () => ({ doc: () => ({ get: async () => ({ exists: false, get: () => undefined }) }) }),
};

const S = require("../lib/stations");
const { createApi } = require("../lib/liveApi");
const errors = [];
const logger = { info() {}, warn() {}, error: (m) => errors.push(m) };

function call(api, url) {
  return new Promise((resolve) => {
    const res = {
      headers: {},
      set(k, v) { this.headers[k] = v; return this; },
      status(c) { this.code = c; return this; },
      send(b) { resolve({ code: this.code || 200, body: JSON.parse(b), headers: this.headers }); },
      json(o) { resolve({ code: this.code || 200, body: o, headers: this.headers }); },
    };
    api({ method: "GET", originalUrl: url, path: url.split("?")[0], get: () => "" }, res);
  });
}

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

const departure = (route, mode, headsign, depOffset, extra = {}) => ({
  mode, routeShortName: route, headsign, realTime: true, tripId: `20261025_${route}_${depOffset}`,
  routeColor: "16C0E9", cancelled: false,
  place: { departure: iso(depOffset), scheduledDeparture: iso(depOffset - 120), track: "1" },
  ...extra,
});

(async () => {
  const api = createApi({ db, logger });

  await test("search: only stations of the city, each name once, compact", async () => {
    geocode = [
      stop("de:09162:2", "Marienplatz", 48.137, 11.5754),
      stop("de:09162:2:1", "Marienplatz", 48.1371, 11.5755),          // a platform of the same station
      stop("de:08111:6019", "Marienplatz", 48.7642, 9.1681),           // Stuttgart
      { type: "ADDRESS", id: "x", name: "Marienplatz 1", lat: 48.137, lon: 11.575 },
      stop("bad id with spaces", "Odd", 48.1, 11.5),
    ];
    const r = await call(api, "/api/stations/muenchen/marienplatz");
    assert.strictEqual(r.code, 200);
    assert.deepStrictEqual(r.body.stations, [["de:09162:2", "Marienplatz", "SUBURBAN,SUBWAY"]]);
    assert.ok(/s-maxage=86400/.test(r.headers["Cache-Control"]));
    assert.strictEqual(asked[0].params.get("type"), "STOP");
  });
  await test("search: the same text again is answered from the cache", async () => {
    await call(api, "/api/stations/muenchen/Marienplatz%20");   // other case and a space: the same search
    assert.strictEqual(count("/geocode"), 0);
  });
  await test("search: a text that is too short, too long or odd is refused", async () => {
    assert.strictEqual((await call(api, "/api/stations/muenchen/m")).code, 400);
    assert.strictEqual((await call(api, `/api/stations/muenchen/${"x".repeat(41)}`)).code, 400);
    assert.strictEqual((await call(api, "/api/stations/muenchen/%3Cscript%3E")).code, 400);
    assert.strictEqual((await call(api, "/api/stations/muenchen/marienplatz?x=1")).code, 400);
    assert.strictEqual(count("/geocode"), 0);
  });

  stopTimes["de:09162:2"] = {
    place: { name: "Marienplatz", lat: 48.137, lon: 11.5754 },
    stopTimes: [
      departure("S1", "METRO", "Flughafen", 120),
      departure("S1", "METRO", "Flughafen", 120),                              // the same journey at a second platform
      departure("S1", "METRO", "Flughafen", 120, { tripId: "coupled" }),         // a coupled train, same line, place and time
      departure("U6", "SUBWAY", "Garching", 240, { cancelled: true, realTime: false }),
      departure("S8", "METRO", "Pasing", 300, { place: { arrival: iso(300) } }),   // ends here
      departure("X", "FERRY", "Somewhere", 360),                                   // not shown in the apps
    ],
  };
  await test("station: departures compact, times relative, cancelled marked, repeats dropped", async () => {
    const r = await call(api, `/api/station/muenchen/${encodeURIComponent("de:09162:2")}`);
    assert.strictEqual(r.code, 200);
    const { t, name, deps } = r.body;
    assert.strictEqual(name, "Marienplatz");
    assert.ok(!("lifts" in r.body));
    assert.strictEqual(deps.length, 2);
    const [s1, u6] = deps;
    assert.deepStrictEqual(s1.slice(0, 4), ["S1", "METRO", "16c0e9", "Flughafen"]);
    assert.ok(Math.abs(s1[5] - 120) <= 2 && Math.abs(s1[4] - 0) <= 2, `times ${s1[4]} ${s1[5]} (t ${t})`);
    assert.strictEqual(s1[6], "1");
    assert.strictEqual(s1[7], 1);                    // live data
    assert.strictEqual(u6[7], 2);                    // cancelled, timetable only
    assert.ok(s1[8].startsWith("20261025_"));
    assert.ok(/s-maxage=30/.test(r.headers["Cache-Control"]));
  });
  await test("station: asked again within 25 seconds: from the cache", async () => {
    await call(api, `/api/station/muenchen/${encodeURIComponent("de:09162:2")}`);
    assert.strictEqual(count("/stoptimes"), 0);
  });

  await test("station: a station of another city or a bad id is refused", async () => {
    stopTimes["de:08111:6019"] = { place: { name: "Marienplatz", lat: 48.7642, lon: 9.1681 }, stopTimes: [] };
    assert.strictEqual((await call(api, `/api/station/muenchen/${encodeURIComponent("de:08111:6019")}`)).code, 404);
    assert.strictEqual((await call(api, `/api/station/stuttgart/${encodeURIComponent("de:08111:6019")}`)).code, 200);
    assert.strictEqual((await call(api, "/api/station/muenchen/a%20b")).code, 404);
    assert.strictEqual((await call(api, "/api/station/atlantis/x")).code, 404);
  });
  await test("helpers: query normalising", async () => {
    assert.strictEqual(S.normalizeQuery("  Münchner   Freiheit "), "münchner freiheit");
    assert.strictEqual(S.normalizeQuery("x"), null);
  });
  await test("no errors were logged", async () => {
    assert.deepStrictEqual(errors, []);
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
