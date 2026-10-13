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
const polyline = require("./polyline");

const BASE = "https://api.transitous.org";
// transitous rejects generic user agents (usage policy), so identify ourselves.
const USER_AGENT = "Train2Track/1.0 (+https://example.invalid; contact@example.invalid)";

// Modes the apps show (same set as LiveMapViewModel.SHOWN_MODES).
const SHOWN_MODES = new Set([
  "METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL",
  "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL",
]);
// Long distance routes cross the whole country and share one label ("ICE"), so
// the per city route set only covers the local networks.
const ROUTE_MODES = new Set([
  "METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "RAIL",
]);

const LIVE_WINDOW = { from: -60, to: 180 };   // seconds around "now"
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
    const color = (seg.routeColor || "").replace(/^#/, "");
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
  const segs = await mapTrips(cityId, LIVE_WINDOW.from, LIVE_WINDOW.to, nowMs);
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

/** Full route of one trip as a simplified precision 5 polyline, or null. */
async function tripRoute(tripId) {
  const resp = await getJson("/api/v1/trip", { tripId }, 20000);
  const all = [];
  for (const leg of resp.legs || []) {
    const g = leg.legGeometry;
    if (!g || !g.points) continue;
    for (const p of polyline.decode(g.points, g.precision || 6)) all.push(p);
  }
  if (all.length < 2) return null;
  return polyline.encode(polyline.simplify(all, ROUTE_TOLERANCE_DEG), 5);
}

/**
 * Full line routes for every local line currently running in the city, merged
 * over [previous] routes so lines that are not running right now (night, weekend
 * only lines) are kept. Returns rows [label, mode, colour, encodedPolyline].
 */
async function buildRoutes(cityId, previous = [], { deadlineMs = Infinity } = {}) {
  const segs = await mapTrips(cityId, LIVE_WINDOW.from, LIVE_WINDOW.to);
  const reps = new Map();   // "mode|label" -> { label, mode, color, tripId }
  for (const seg of segs) {
    if (!ROUTE_MODES.has(seg.mode)) continue;
    const tripId = seg.trips && seg.trips[0] && seg.trips[0].tripId;
    if (!tripId) continue;
    const label = labelOf(seg);
    const key = `${seg.mode}|${label}`;
    if (!reps.has(key)) {
      reps.set(key, { label, mode: seg.mode, color: (seg.routeColor || "").replace(/^#/, ""), tripId });
    }
  }
  const todo = [...reps.values()];
  const polys = await mapLimit(todo, 8, (r) =>
    Date.now() > deadlineMs ? Promise.resolve(null) : tripRoute(r.tripId));

  const merged = new Map(previous.map((row) => [`${row[1]}|${row[0]}`, row]));
  todo.forEach((r, i) => {
    if (polys[i]) merged.set(`${r.mode}|${r.label}`, [r.label, r.mode, r.color, polys[i]]);
  });
  return [...merged.values()];
}

module.exports = {
  CITIES, SHOWN_MODES, USER_AGENT, LIVE_WINDOW,
  shapeKey, compactLive, fetchLive, fetchShapes, buildRoutes, tripRoute, mapTrips,
};
