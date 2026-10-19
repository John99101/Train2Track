// Checks the live data per mode group (/api/live/{city}/{group}): every vehicle lands
// in exactly one group with valid indices, the ETag stays the same as long as nothing
// changed, a matching If-None-Match gets "304 Not Modified", and the old path for the
// app versions up to 0.5.1 answers as before.
//
// Nothing here reaches transitous or Firebase: transitous is replaced by a stand-in.
//
// From the repo root:
//   node functions/test/live_groups.test.js
const assert = require("assert");
const { GROUPS, groupOf, splitLive, liveHash } = require("../lib/liveGroups");
const T = require("../lib/transitous");

// ---- stand-in for transitous ------------------------------------------------------
const baseMs = Date.now();
const iso = (offsetS) => new Date(baseMs + offsetS * 1000).toISOString();
const segment = (mode, name, tripId, from, to, delayS = 0) => ({
  mode, trips: [{ tripId, routeShortName: name }],
  departure: iso(-30 + delayS), arrival: iso(90 + delayS), scheduledDeparture: iso(-30), realTime: true,
  from: { name: from, lat: 48.1, lon: 11.5 }, to: { name: to, lat: 48.2, lon: 11.6 },
  routeColor: "0099cc", polyline: `line_${name}`,
});
let running = [];
let liveRequests = 0;
const windows = [];   // [startTime, endTime] of every live request
global.fetch = async (url) => {
  const u = new URL(url);
  if (u.pathname === "/api/v1/map/trips") {
    liveRequests++;
    windows.push([u.searchParams.get("startTime"), u.searchParams.get("endTime")]);
    return { ok: true, status: 200, json: async () => running };
  }
  throw new Error(`unexpected request ${url}`);
};

const { createApi, etagMatches } = require("../lib/liveApi");
const errors = [];
const logger = { info() {}, warn() {}, error: (m) => errors.push(m) };

/** Calls the API like Hosting does (with request headers) and returns { code, headers, body }. */
function call(api, url, headers = {}) {
  return new Promise((resolve) => {
    const out = {};
    const res = {
      set(k, v) { out[k.toLowerCase()] = v; return this; },
      status(c) { this.code = c; return this; },
      send(b) { resolve({ code: this.code || 200, headers: out, body: JSON.parse(b) }); },
      json(o) { resolve({ code: this.code || 200, headers: out, body: o }); },
      end() { resolve({ code: this.code || 200, headers: out, body: null }); },
    };
    const lower = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
    api({ method: "GET", originalUrl: url, path: url.split("?")[0], get: (h) => lower[h.toLowerCase()] || "" }, res);
  });
}

/** What a snapshot says, with names instead of indices and absolute times. */
const readable = (snap) => snap.segs.map((s) => {
  const stop = (i) => (i >= 0 ? snap.stops[i][0] : null);
  return [snap.lines[s[0]][0], snap.lines[s[0]][1], s[1], stop(s[2]), stop(s[3]), s[4] + snap.t, s[5] + snap.t, s[6]].join("|");
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
  running = [
    segment("METRO", "S1", "trip_S1", "Pasing", "Laim"),
    segment("SUBWAY", "U2", "trip_U2", "Laim", "Hbf"),
    segment("REGIONAL_RAIL", "RE 5", "trip_RE5", "Hbf", "Pasing"),
    segment("TRAM", "19", "trip_19", "Hbf", "Stachus"),
    segment("BUS", "100", "trip_100", "Stachus", "Laim"),
    segment("BUS", "53", "trip_53", "Pasing", "Hbf"),
  ];

  await test("every mode the apps show has a group", async () => {
    for (const m of ["METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL"]) {
      assert.strictEqual(groupOf(m), "rail", m);
    }
    assert.strictEqual(groupOf("TRAM"), "tram");
    assert.strictEqual(groupOf("BUS"), "bus");
    assert.strictEqual(groupOf("FERRY"), null);
    assert.deepStrictEqual(GROUPS, ["rail", "tram", "bus"]);
  });

  await test("every segment lands in exactly one group, with valid indices and the same content", async () => {
    const snap = await T.fetchLive("muenchen", baseMs);
    const parts = splitLive(snap);
    const all = [];
    for (const g of GROUPS) {
      const p = parts[g];
      assert.strictEqual(p.t, snap.t);
      assert.strictEqual(p.city, "muenchen");
      for (const s of p.segs) {
        assert.ok(s[0] >= 0 && s[0] < p.lines.length, `line index in ${g}`);
        assert.strictEqual(groupOf(p.lines[s[0]][1]), g);
        for (const i of [s[2], s[3]]) assert.ok(i === -1 || (i >= 0 && i < p.stops.length), `stop index in ${g}`);
      }
      // No line or stop a group does not use.
      assert.strictEqual(new Set(p.segs.map((s) => s[0])).size, p.lines.length);
      all.push(...readable(p));
    }
    assert.deepStrictEqual(all.sort(), readable(snap).sort());
    assert.deepStrictEqual(parts.rail.segs.length, 3);
    assert.deepStrictEqual(parts.tram.segs.length, 1);
    assert.deepStrictEqual(parts.bus.segs.length, 2);
  });

  await test("a missing stop stays missing (-1)", async () => {
    const snap = { v: 1, city: "x", t: 100, stale: false, stops: [["A", 1, 2]], lines: [["S1", "METRO", ""]], segs: [[0, "t", -1, 0, 0, 60, null, 0, ""]] };
    const rail = splitLive(snap).rail;
    assert.deepStrictEqual(rail.segs[0].slice(2, 4), [-1, 0]);
  });

  await test("the hash ignores the base time but sees every change", async () => {
    const a = await T.fetchLive("muenchen", baseMs);
    const b = await T.fetchLive("muenchen", baseMs + 7000);
    assert.notStrictEqual(a.t, b.t);
    for (const g of GROUPS) assert.strictEqual(liveHash(splitLive(a)[g]), liveHash(splitLive(b)[g]), g);
    const delayed = JSON.parse(JSON.stringify(a));
    delayed.segs[0][4] += 120;
    assert.notStrictEqual(liveHash(delayed), liveHash(a));
    assert.notStrictEqual(liveHash({ ...a, stale: true }), liveHash(a));
  });

  await test("the time window moves in 60 second steps", async () => {
    const step = Math.floor(baseMs / T.LIVE_STEP_MS) * T.LIVE_STEP_MS;
    windows.length = 0;
    await T.fetchLive("muenchen", step + 1000);
    await T.fetchLive("muenchen", step + 59000);
    await T.fetchLive("muenchen", step + 61000);
    assert.deepStrictEqual(windows[0], windows[1], "same step, same question");
    assert.notDeepStrictEqual(windows[1], windows[2], "next step, next window");
    const [from, to] = windows[0].map((t) => Date.parse(t));
    assert.ok(from <= step + 1000 - 60000 && to >= step + 59000 + 180000, "reaches at least 1 minute back and 3 minutes ahead");
  });

  await test("line colours: the timetable's own colour wins, the table fills the gaps", async () => {
    const own = { mode: "TRAM", routeColor: "#123456" };
    assert.strictEqual(T.lineColor("berlin", own, "16"), "123456");
    const table = require("../lib/lineColors.json");
    const key = Object.keys(table.berlin).find((k) => k.startsWith("TRAM|"));
    const [mode, label] = key.split("|");
    assert.strictEqual(T.lineColor("berlin", { mode, routeColor: "" }, label), table.berlin[key]);
    assert.strictEqual(T.lineColor("berlin", { mode: "BUS", routeColor: "" }, "no such line"), "");
    assert.strictEqual(T.lineColor(null, { mode, routeColor: "" }, label), "");
  });

  await test("If-None-Match: weak or strong, a list, or *", async () => {
    assert.ok(etagMatches('W/"abc"', "abc"));
    assert.ok(etagMatches('"abc"', "abc"));
    assert.ok(etagMatches('"x", W/"abc"', "abc"));
    assert.ok(etagMatches("*", "abc"));
    assert.ok(!etagMatches('W/"abd"', "abc"));
    assert.ok(!etagMatches("", "abc"));
    assert.ok(!etagMatches(undefined, "abc"));
  });

  const api = createApi({ db: null, logger });
  let railTag;

  await test("a group answers with its own vehicles and an ETag", async () => {
    const r = await call(api, "/api/live/muenchen/rail");
    assert.strictEqual(r.code, 200);
    assert.ok(/^W\/"[A-Za-z0-9_-]{16}"$/.test(r.headers.etag), r.headers.etag);
    assert.strictEqual(r.headers["cache-control"], "public, max-age=10, s-maxage=20");
    assert.deepStrictEqual(r.body.lines.map((l) => l[0]).sort(), ["RE", "S1", "U2"]);
    railTag = r.headers.etag;
    const bus = await call(api, "/api/live/muenchen/bus");
    assert.strictEqual(bus.code, 200);
    assert.strictEqual(bus.body.segs.length, 2);
    assert.notStrictEqual(bus.headers.etag, railTag);
  });

  await test("the same ETag again gets 304 without a body", async () => {
    const r = await call(api, "/api/live/muenchen/rail", { "If-None-Match": railTag });
    assert.strictEqual(r.code, 304);
    assert.strictEqual(r.body, null);
    assert.strictEqual(r.headers.etag, railTag);
    assert.strictEqual(r.headers["cache-control"], "public, max-age=10, s-maxage=20");
  });

  await test("all groups and the old path come from one transitous request", async () => {
    const fresh = createApi({ db: null, logger });
    liveRequests = 0;
    await Promise.all(["/api/live/muenchen", "/api/live/muenchen/rail", "/api/live/muenchen/tram", "/api/live/muenchen/bus"].map((u) => call(fresh, u)));
    assert.strictEqual(liveRequests, 1);
  });

  await test("another server instance with the same trains gives the same ETag", async () => {
    const realNow = Date.now;
    Date.now = () => realNow() + 5000;   // a later base time
    try {
      const r = await call(createApi({ db: null, logger }), "/api/live/muenchen/rail", { "If-None-Match": railTag });
      assert.strictEqual(r.code, 304);
    } finally {
      Date.now = realNow;
    }
  });

  await test("a new delay gives a new ETag and the full answer", async () => {
    running[0] = segment("METRO", "S1", "trip_S1", "Pasing", "Laim", 180);
    const r = await call(createApi({ db: null, logger }), "/api/live/muenchen/rail", { "If-None-Match": railTag });
    assert.strictEqual(r.code, 200);
    assert.notStrictEqual(r.headers.etag, railTag);
    assert.strictEqual(r.body.segs.length, 3);
  });

  await test("the old path answers as before, unknown groups and paths are refused", async () => {
    const live = await call(api, "/api/live/muenchen");
    assert.strictEqual(live.code, 200);
    assert.strictEqual(live.body.segs.length, 6);
    assert.strictEqual(live.headers.etag, undefined);
    assert.strictEqual((await call(api, "/api/live/muenchen/ferry")).code, 404);
    assert.strictEqual((await call(api, "/api/live/muenchen/rail/x")).code, 404);
    assert.strictEqual((await call(api, "/api/live/atlantis/rail")).code, 404);
    assert.strictEqual((await call(api, "/api/live/muenchen/rail?x=1")).code, 400);
  });

  await test("no errors were logged", async () => {
    assert.deepStrictEqual(errors, []);
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
