/**
 * Server side access to transitous (https://transitous.org, open MOTIS) and the
 * compact formats the apps download instead of the raw API responses.
 *
 * Why: a raw /api/v1/map/trips response for one city is ~5 MB (1.2 MB gzip),
 * mostly stop metadata the apps never use, and every device used to fetch it
 * every 30 s. Here the server fetches it once per city for everyone, strips it
 * to what the apps need and replaces each track geometry with a short key; the
 * geometries themselves are served separately and cached on the device.
 */
const crypto = require("crypto");
const { CITIES, cityBox } = require("./cities");
// Line colours where the timetable data has none, from the public domain table of
// Träwelling (built by tools/build_line_colors.js).
const LINE_COLORS = require("./lineColors.json");
const polyline = require("./polyline");
const G = require("./lineGeometry");
const { inRegion } = require("./cities");
const { version } = require("../package.json");

const BASE = "https://api.transitous.org";
// transitous asks every client to say who it is (usage policy): name, version and
// a contact. The version is the one in functions/package.json: raise it there with
// every change to the server.
const USER_AGENT = `Train2Track/${version} (server; +https://example.invalid; contact@example.invalid)`;

// Modes the apps show (same set as LiveMapViewModel.SHOWN_MODES).
const SHOWN_MODES = new Set([
  "METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL",
  "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL",
]);
// Long distance routes cross the whole country and share one label ("ICE"): one
// stored line would be drawn for every ICE, mostly wrong. So route lines exist
// only for the local networks, in the set and on demand; long distance trains
// still move along their own track (shapes).
const ROUTE_MODES = new Set([
  "METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "RAIL",
]);

const LIVE_WINDOW = { from: -60, to: 180 };   // seconds around "now"
// The live window moves in steps of this size instead of with every request: two
// requests in the same step ask transitous the same question, so the answer only
// changes when real time data changes, and the apps get "nothing new" (304) more
// often. A step longer than the 20 second map refresh lets about two of three
// refreshes fall into the same step. The window then reaches 180 to 240 seconds ahead.
const LIVE_STEP_MS = 60 * 1000;
const SHAPE_WINDOWS = [600, 300, 180];        // widest first; transitous caps the trip count
const ROUTE_TOLERANCE_DEG = 0.00003;          // ~3 m, same as the app

async function getJson(path, params, timeoutMs = 25000) {
  const url = `${BASE}${path}?${new URLSearchParams(params)}`;
  const res = await fetch(url, {
    headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) {
    const err = new Error(`transitous ${path} HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

/** Raw trip segments active in the city's region between now+fromS and now+toS. */
async function mapTrips(cityId, fromS, toS, nowMs = Date.now()) {
  const box = cityBox(cityId);
  return getJson("/api/v1/map/trips", {
    min: box.min,
    max: box.max,
    zoom: "14",
    startTime: new Date(nowMs + fromS * 1000).toISOString(),
    endTime: new Date(nowMs + toS * 1000).toISOString(),
  });
}

/** Stable short key for a track geometry (the apps cache geometries by it). */
function shapeKey(encoded) {
  return crypto.createHash("sha1").update(encoded).digest("base64url").slice(0, 12);
}

/** The colour of a line: from the timetable data, else from the line colour table, else "". */
function lineColor(cityId, seg, label) {
  const own = (seg.routeColor || "").replace(/^#/, "");
  if (own) return own;
  const table = cityId && LINE_COLORS[cityId];
  return (table && table[`${seg.mode}|${label}`]) || "";
}

function labelOf(seg) {
  const name = ((seg.trips && seg.trips[0] && seg.trips[0].routeShortName) || "").trim();
  return name.split(" ")[0] || "?";
}

const round5 = (v) => Math.round(v * 1e5) / 1e5;

/**
 * Compact live snapshot (format v1):
 *   t     base time, epoch seconds; segment times are seconds relative to it
 *   stops [name, lat, lon]
 *   lines [label, mode, colour hex or ""]
 *   segs  [lineIdx, tripId, fromStopIdx, toStopIdx, dep, arr, delaySec|null, realtime 0/1, shapeKey]
 */
function compactLive(cityId, segs, nowMs = Date.now()) {
  const t = Math.floor(nowMs / 1000);
  const stops = [];
  const stopIdx = new Map();
  const lines = [];
  const lineIdx = new Map();
  const out = [];

  const stopRef = (p) => {
    if (!p || !p.name) return -1;
    const lat = typeof p.lat === "number" ? round5(p.lat) : null;
    const lon = typeof p.lon === "number" ? round5(p.lon) : null;
    const key = `${p.name}|${lat}|${lon}`;
    let i = stopIdx.get(key);
    if (i === undefined) {
      i = stops.length;
      stops.push([p.name, lat, lon]);
      stopIdx.set(key, i);
    }
    return i;
  };

  for (const seg of segs) {
    if (!SHOWN_MODES.has(seg.mode) || !seg.departure || !seg.arrival) continue;
    const depMs = Date.parse(seg.departure);
    const arrMs = Date.parse(seg.arrival);
    if (!(arrMs > depMs)) continue;
    const label = labelOf(seg);
    const color = lineColor(cityId, seg, label);
    const lk = `${label}|${seg.mode}|${color}`;
    let li = lineIdx.get(lk);
    if (li === undefined) {
      li = lines.length;
      lines.push([label, seg.mode, color]);
      lineIdx.set(lk, li);
    }
    const tripId = (seg.trips && seg.trips[0] && seg.trips[0].tripId) || "";
    const schedDep = seg.scheduledDeparture ? Date.parse(seg.scheduledDeparture) : NaN;
    const delay = seg.realTime && !Number.isNaN(schedDep) ? Math.round((depMs - schedDep) / 1000) : null;
    out.push([
      li,
      tripId,
      stopRef(seg.from),
      stopRef(seg.to),
      Math.round(depMs / 1000) - t,
      Math.round(arrMs / 1000) - t,
      delay,
      seg.realTime ? 1 : 0,
      seg.polyline ? shapeKey(seg.polyline) : "",
    ]);
  }
  return { v: 1, city: cityId, t, stale: false, stops, lines, segs: out };
}

/** Live snapshot for a city, straight from transitous. */
async function fetchLive(cityId, nowMs = Date.now()) {
  const step = Math.floor(nowMs / LIVE_STEP_MS) * LIVE_STEP_MS;
  const segs = await mapTrips(cityId, LIVE_WINDOW.from, LIVE_WINDOW.to + LIVE_STEP_MS / 1000, step);
  return compactLive(cityId, segs, nowMs);
}

/**
 * Track geometries {shapeKey: encodedPolyline} for every trip segment in the
 * city over the next few minutes: a superset of what the live snapshots refer
 * to, so a device rarely meets a key it does not know yet.
 */
async function fetchShapes(cityId, nowMs = Date.now()) {
  let segs = null;
  let lastErr = null;
  for (const ahead of SHAPE_WINDOWS) {
    try {
      segs = await mapTrips(cityId, -60, ahead, nowMs);
      break;
    } catch (e) {
      lastErr = e;
      if (e.status !== 422) throw e;   // 422 = "too many trips": try a shorter window
    }
  }
  if (!segs) throw lastErr;
  const shapes = {};
  for (const seg of segs) {
    if (!SHOWN_MODES.has(seg.mode) || !seg.polyline) continue;
    shapes[shapeKey(seg.polyline)] = seg.polyline;
  }
  return { v: 1, city: cityId, t: Math.floor(nowMs / 1000), shapes };
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      try {
        results[i] = await fn(items[i]);
      } catch (e) {
        results[i] = null;
      }
    }
  });
  await Promise.all(workers);
  return results;
}

/**
 * One journey: its whole route (points, not simplified) and its stops as stations
 * { id, name, lat, lon } (see lineGeometry.stationId). null when it has no route.
 */
async function tripDetails(tripId) {
  const resp = await getJson("/api/v1/trip", { tripId }, 20000);
  const points = [];
  const stops = [];
  for (const leg of resp.legs || []) {
    const g = leg.legGeometry;
    if (g && g.points) for (const p of polyline.decode(g.points, g.precision || 6)) points.push(p);
    for (const s of [leg.from, ...(leg.intermediateStops || []), leg.to]) {
      if (!s || !s.name || typeof s.lat !== "number" || typeof s.lon !== "number") continue;
      const id = G.stationId(s);
      if (id) stops.push({ id, name: s.name, lat: round5(s.lat), lon: round5(s.lon) });
    }
  }
  return points.length >= 2 ? { points, stops } : null;
}

/** Full route of one trip as a simplified precision 5 polyline, or null. */
async function tripRoute(tripId) {
  const d = await tripDetails(tripId);
  return d ? polyline.encode(polyline.simplify(d.points, ROUTE_TOLERANCE_DEG), 5) : null;
}

// Where several lines stop at one station, the name and position come from the
// highest of these (the rail platform rather than the bus stop in front).
const STATION_RANK = { METRO: 3, SUBWAY: 3, REGIONAL_RAIL: 2, REGIONAL_FAST_RAIL: 2, RAIL: 2, TRAM: 1, BUS: 0 };

/**
 * Notes the stations of one journey of the line [key] ("mode|label"): in
 * [stations] (id -> [id, name, lat, lon, rank]) and in [lineStops] (key -> Set of
 * ids). Only stations in the city's region: the apps cannot open the others.
 * A line in [lineStops] has had its stations collected, even if none were in the region.
 */
function addStops(cityId, mode, key, stops, stations, lineStops) {
  const rank = STATION_RANK[mode] || 0;
  let ids = lineStops.get(key);
  if (!ids) {
    ids = new Set();
    lineStops.set(key, ids);
  }
  for (const s of stops) {
    if (!inRegion(cityId, s.lat, s.lon)) continue;
    ids.add(s.id);
    const known = stations.get(s.id);
    if (!known || rank > known[4]) stations.set(s.id, [s.id, s.name, s.lat, s.lon, rank]);
  }
}

/** A stored set as working maps: rows by "mode|label", stations by id, line stations as Sets. */
function routeMaps(set = {}) {
  return {
    rows: new Map((set.routes || []).filter((row) => ROUTE_MODES.has(row[1])).map((row) => [`${row[1]}|${row[0]}`, row.slice()])),
    stations: new Map((set.stations || []).map((s) => [s[0], s.slice()])),
    lineStops: new Map((set.lineStops || []).map(([key, ids]) => [key, new Set(ids)])),
  };
}

/** The working maps back as storable arrays (the lines that have a route). */
function routeArrays(maps) {
  return {
    rows: [...maps.rows.values()].filter((row) => row.length >= 4),
    stations: [...maps.stations.values()],
    lineStops: [...maps.lineStops].map(([key, ids]) => [key, [...ids]]),
  };
}

/** A vehicle's current hop as points: its track, else the straight line between its two stops. */
function hopPoints(seg) {
  const pts = seg.polyline ? polyline.decode(seg.polyline, 5) : [];
  if (pts.length >= 2) return pts;
  const f = seg.from, t = seg.to;
  if (f && t && typeof f.lat === "number" && typeof t.lat === "number") return [[f.lat, f.lon], [t.lat, t.lon]];
  return [];
}

// Per run, a line is asked for at most this many journeys, in at most this many rounds.
const MAX_TRIPS_PER_LINE = 4;
const MAX_PASSES = 4;

/**
 * Brings the line routes of the city's local lines up to date, from the vehicles
 * running right now, over the [prev] set ({ routes, stations, lineStops } as
 * stored), so lines that are not running now (night, weekend lines) are kept.
 *
 * transitous is asked for a journey only when it adds something:
 *  - a line the set does not have: one of its journeys;
 *  - a line whose stations were never collected (sets built before stations
 *    existed): one of its journeys;
 *  - a line with a vehicle on a part the line does not have (a branch, a short
 *    run's other end): that vehicle's journey, which adds only the missing parts.
 * A line that is complete costs nothing, so a daily run asks for a handful of
 * journeys. Pass full = true to fetch every running line anew (the admin button).
 *
 * Returns { rows, stations, lineStops, asked, complete }: rows [label, mode,
 * colour, piece, piece...] (encoded precision 5), how many journeys were asked,
 * and whether everything was done before [deadlineMs].
 */
async function buildRoutes(cityId, prev = {}, { deadlineMs = Infinity, full = false } = {}) {
  const segs = await mapTrips(cityId, LIVE_WINDOW.from, LIVE_WINDOW.to);
  const maps = routeMaps(prev);
  const running = new Map();   // "mode|label" -> { label, mode, color, hops: [{ tripId, seg }] }
  for (const seg of segs) {
    if (!ROUTE_MODES.has(seg.mode)) continue;
    const tripId = seg.trips && seg.trips[0] && seg.trips[0].tripId;
    if (!tripId) continue;
    const label = labelOf(seg);
    const key = `${seg.mode}|${label}`;
    let line = running.get(key);
    if (!line) {
      line = { label, mode: seg.mode, color: lineColor(cityId, seg, label), hops: [] };
      running.set(key, line);
    }
    line.hops.push({ tripId, seg });
  }
  if (full) {
    for (const key of running.keys()) {
      maps.rows.delete(key);
      maps.lineStops.delete(key);
    }
  }

  const tried = new Map();   // key -> journeys asked in this run
  let asked = 0;
  let complete = true;
  for (let pass = 0; pass < MAX_PASSES; pass++) {
    const todo = [];
    for (const [key, line] of running) {
      const done = tried.get(key) || new Set();
      if (done.size >= MAX_TRIPS_PER_LINE) continue;
      const fresh = line.hops.filter((h) => !done.has(h.tripId));
      if (!fresh.length) continue;
      const row = maps.rows.get(key);
      let hop = null;
      if (!row || !maps.lineStops.has(key)) {
        hop = fresh[0];
      } else {
        const index = G.lineIndex(row.slice(3).map((p) => polyline.decode(p, 5)));
        hop = fresh.find((h) => !G.hopOnLine(index, hopPoints(h.seg))) || null;
      }
      if (hop) todo.push({ key, line, tripId: hop.tripId });
    }
    if (!todo.length) break;
    if (Date.now() > deadlineMs) {
      complete = false;
      break;
    }
    // undefined = not asked (the deadline passed), null = transitous had no route.
    const results = await mapLimit(todo, 8, (t) =>
      Date.now() > deadlineMs ? Promise.resolve(undefined) : tripDetails(t.tripId));
    todo.forEach((t, i) => {
      if (results[i] === undefined) {
        complete = false;
        return;
      }
      asked++;
      const done = tried.get(t.key) || new Set();
      done.add(t.tripId);
      tried.set(t.key, done);
      const d = results[i];
      if (!d) return;
      let row = maps.rows.get(t.key);
      if (!row) {
        row = [t.line.label, t.line.mode, t.line.color];
        maps.rows.set(t.key, row);
      }
      G.extendRow(row, d.points);
      addStops(cityId, t.line.mode, t.key, d.stops, maps.stations, maps.lineStops);
    });
  }
  return { ...routeArrays(maps), asked, complete };
}

/** Stops whose name matches [text], the ones near the city first (transitous geocoder). */
async function searchStops(cityId, text) {
  const c = CITIES[cityId];
  return getJson("/api/v1/geocode", { text, type: "STOP", place: `${c.lat},${c.lon}`, language: "de" }, 10000);
}

/** The next departures at the stop [stopId] (and its platforms). */
async function stopTimes(stopId, n = 30) {
  return getJson("/api/v1/stoptimes", { stopId, n: String(n), language: "de" }, 10000);
}

module.exports = {
  CITIES, SHOWN_MODES, ROUTE_MODES, USER_AGENT, LIVE_WINDOW, LIVE_STEP_MS,
  shapeKey, lineColor, compactLive, fetchLive, fetchShapes, buildRoutes, tripRoute, tripDetails, mapTrips,
  searchStops, stopTimes, addStops, routeMaps, routeArrays,
};
