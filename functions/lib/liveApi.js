/**
 * The apps' live data API, served through Firebase Hosting (rewrite /api/**):
 *
 *   GET /api/live/{city}    compact live snapshot      CDN cached ~20 s (app versions up to 0.5.1)
 *   GET /api/live/{city}/{group}
 *                           the same for one mode group (rail, tram, bus, see
 *                           liveGroups.js), with an ETag: a request with a matching
 *                           If-None-Match gets "304 Not Modified" without a body
 *   GET /api/shapes/{city}  track geometries by key    CDN cached 10 min
 *   GET /api/routes/{city}  full line routes           CDN cached 6 h
 *   GET /api/route/{city}/{mode}/{label}/{tripId}
 *                           one line the set lacks     asked from transitous once, then stored
 *   GET /api/stations/{city}/{text}
 *                           station search             CDN cached 1 day (see stations.js)
 *   GET /api/station/{city}/{stopId}
 *                           departures of a station    CDN cached 30 s
 *
 * The CDN in front means transitous is asked about once per city per ~20 s no
 * matter how many devices are open. The apps never ask transitous for a route
 * themselves: a line the city's set does not have (a long distance train, a night
 * line) is fetched here once and then belongs to the set for everybody. Each instance also keeps the last good live
 * snapshot, so a short transitous outage serves slightly older data (flagged
 * "stale") instead of failing.
 */
const zlib = require("zlib");
const T = require("./transitous");
const { GROUPS, splitLive, liveHash } = require("./liveGroups");
const { CITIES } = require("./cities");
const S = require("./stations");

const LIVE_TTL_MS = 15 * 1000;
const LIVE_STALE_MAX_MS = 10 * 60 * 1000;
const SHAPES_TTL_MS = 10 * 60 * 1000;
const ROUTES_TTL_MS = 30 * 60 * 1000;
// The sets are topped up once a week (refreshRoutes). Only when that has not
// happened for this long does a request top a set up itself.
const ROUTES_MAX_AGE_MS = 21 * 24 * 3600 * 1000;
// A line whose route could not be built is not tried again for this long.
const ROUTE_MISS_TTL_MS = 10 * 60 * 1000;
const ROUTES_BUILD_BUDGET_MS = 40 * 1000;   // Hosting gives a rewrite 60 s
const CHUNK_CHARS = 700000;                  // stays well under Firestore's 1 MiB doc limit

const CC_LIVE = "public, max-age=10, s-maxage=20";
const CC_STALE = "public, max-age=5, s-maxage=5";
const CC_SHAPES = "public, max-age=600, s-maxage=600";
const CC_ROUTES = "public, max-age=3600, s-maxage=21600";
const CC_ROUTE = "public, max-age=3600, s-maxage=3600";
const CC_ROUTE_MISS = "public, max-age=60, s-maxage=60";
const CC_ERROR = "public, max-age=5, s-maxage=5";
const CC_STATIONS = "public, max-age=3600, s-maxage=86400";
const CC_STATION = "public, max-age=20, s-maxage=30";
const CC_STATION_MISS = "public, max-age=60, s-maxage=300";

const STATIONS_TTL_MS = 6 * 3600 * 1000;
const STATION_TTL_MS = 25 * 1000;
// Search texts and stations are many: each cache keeps only the newest this many.
const MAX_CACHED = 2000;

class Entry {
  constructor(obj) {
    this.obj = obj;
    this.json = JSON.stringify(obj);
    this.gz = zlib.gzipSync(this.json);
    this.at = Date.now();
  }
}

/** Per instance cache with in flight de-duplication, keeping at most [max] entries. */
class Cache {
  constructor(max = Infinity) {
    this.entries = new Map();
    this.inflight = new Map();
    this.max = max;
  }
  get(key) { return this.entries.get(key); }
  async load(key, ttlMs, loader) {
    const hit = this.entries.get(key);
    if (hit && Date.now() - hit.at < ttlMs) return hit;
    if (this.inflight.has(key)) return this.inflight.get(key);
    const p = (async () => {
      try {
        const e = new Entry(await loader());
        this.entries.delete(key);
        this.entries.set(key, e);
        // The oldest entry goes first (a Map keeps the order things were added in).
        if (this.entries.size > this.max) this.entries.delete(this.entries.keys().next().value);
        return e;
      } finally {
        this.inflight.delete(key);
      }
    })();
    this.inflight.set(key, p);
    return p;
  }
}

function send(req, res, entry, cacheControl, status = 200) {
  res.set("Cache-Control", cacheControl);
  res.set("Vary", "Accept-Encoding");
  res.set("Content-Type", "application/json; charset=utf-8");
  if (/\bgzip\b/.test(req.get("accept-encoding") || "")) {
    res.set("Content-Encoding", "gzip");
    res.status(status).send(entry.gz);
  } else {
    res.status(status).send(entry.json);
  }
}

/** True when an If-None-Match header names the ETag of [hash] (weak or strong, or "*"). */
function etagMatches(header, hash) {
  if (!header) return false;
  return header.split(",").some((tag) => {
    const t = tag.trim();
    return t === "*" || t.replace(/^W\//, "") === `"${hash}"`;
  });
}

function sendError(res, status, error) {
  res.set("Cache-Control", CC_ERROR);
  res.status(status).json({ error });
}

// ---- Route storage (Firestore: route_cache/{city} + chunks/{i}) -------------

async function loadStoredRoutes(db, city) {
  const ref = db.collection("route_cache").doc(city);
  const meta = await ref.get();
  if (!meta.exists) return null;
  const chunks = await ref.collection("chunks").get();
  const rows = [];
  chunks.docs
    .sort((a, b) => Number(a.id) - Number(b.id))
    .forEach((d) => rows.push(...JSON.parse(d.get("rows") || "[]")));
  // Long distance lines stored by earlier versions are left out (see ROUTE_MODES);
  // the next save of the set drops them for good.
  const local = rows.filter((r) => T.ROUTE_MODES.has(r[1]));
  return { built: meta.get("built") || 0, complete: meta.get("complete") !== false, routes: local };
}

/** [built] = when the set was last built or topped up as a whole (not: one line added). */
async function saveRoutes(db, city, rows, complete, built = Date.now()) {
  const chunks = [];
  let cur = [];
  let size = 0;
  for (const r of rows) {
    const s = JSON.stringify(r).length;
    if (size + s > CHUNK_CHARS && cur.length) {
      chunks.push(cur);
      cur = [];
      size = 0;
    }
    cur.push(r);
    size += s;
  }
  if (cur.length) chunks.push(cur);

  const ref = db.collection("route_cache").doc(city);
  const old = await ref.collection("chunks").get();
  const batch = db.batch();
  old.docs.forEach((d) => { if (Number(d.id) >= chunks.length) batch.delete(d.ref); });
  chunks.forEach((c, i) => batch.set(ref.collection("chunks").doc(String(i)), { rows: JSON.stringify(c) }));
  batch.set(ref, { v: 1, built, count: rows.length, chunks: chunks.length, complete });
  await batch.commit();
}

/**
 * Tops up a city's route set with the lines it does not have yet (or, with
 * full = true, fetches every running line anew) and stores it. Returns
 * { rows, asked }: the set, and for how many journeys transitous was asked.
 */
async function rebuildCityRoutes(db, city, { deadlineMs = Infinity, full = false } = {}) {
  const prev = await loadStoredRoutes(db, city);
  const { rows, asked } = await T.buildRoutes(city, prev ? prev.routes : [], { deadlineMs, full });
  const complete = Date.now() <= deadlineMs;
  await saveRoutes(db, city, rows, complete);
  return { rows, asked };
}

const findRoute = (rows, mode, label) => rows.find((r) => r[0] === label && r[1] === mode) || null;

// ---- Handler -----------------------------------------------------------------

function createApi({ db, logger }) {
  const live = new Cache();
  const shapes = new Cache();
  const routes = new Cache();

  /** The city's whole snapshot and its cache time; the last good one, flagged stale, when transitous fails. */
  async function liveSnapshot(city) {
    try {
      return { entry: await live.load(city, LIVE_TTL_MS, () => T.fetchLive(city)), cacheControl: CC_LIVE };
    } catch (err) {
      const last = live.get(city);
      if (last && Date.now() - last.at < LIVE_STALE_MAX_MS) {
        logger.warn(`live ${city}: serving stale snapshot (${err.message})`);
        return { entry: new Entry({ ...last.obj, stale: true }), cacheControl: CC_STALE };
      }
      throw err;
    }
  }

  async function handleLive(req, res, city) {
    const { entry, cacheControl } = await liveSnapshot(city);
    return send(req, res, entry, cacheControl);
  }

  // One snapshot per city and group that only changes when its content changes
  // (same base time, same bytes), so its ETag stays the same and an app that has
  // it gets "not modified".
  const groupSplits = new WeakMap();   // whole snapshot Entry -> its groups
  const groupLatest = new Map();       // "city|group" -> { hash, entry }

  async function handleLiveGroup(req, res, city, group) {
    const { entry, cacheControl } = await liveSnapshot(city);
    let groups = groupSplits.get(entry);
    if (!groups) {
      groups = splitLive(entry.obj);
      groupSplits.set(entry, groups);
    }
    const obj = groups[group];
    const key = `${city}|${group}`;
    const hash = liveHash(obj);
    let latest = groupLatest.get(key);
    if (!latest || latest.hash !== hash) {
      latest = { hash, entry: new Entry(obj) };
      groupLatest.set(key, latest);
    }
    const etag = `W/"${hash}"`;
    res.set("ETag", etag);
    if (etagMatches(req.get("if-none-match"), hash)) {
      res.set("Cache-Control", cacheControl);
      res.set("Vary", "Accept-Encoding");
      return res.status(304).end();
    }
    return send(req, res, latest.entry, cacheControl);
  }

  async function handleShapes(req, res, city) {
    const e = await shapes.load(city, SHAPES_TTL_MS, () => T.fetchShapes(city));
    return send(req, res, e, CC_SHAPES);
  }

  /** The city's route set, from memory or the database (built on the very first request). */
  const routeSet = (city) => routes.load(city, ROUTES_TTL_MS, async () => {
    let stored = await loadStoredRoutes(db, city);
    if (!stored || !stored.routes.length || Date.now() - stored.built > ROUTES_MAX_AGE_MS) {
      // First request for this city (or a very old set): build it now.
      const { rows } = await rebuildCityRoutes(db, city, { deadlineMs: Date.now() + ROUTES_BUILD_BUDGET_MS });
      stored = { built: Date.now(), complete: true, routes: rows };
    }
    return { v: 1, city, built: stored.built, routes: stored.routes };
  });

  async function handleRoutes(req, res, city) {
    const e = await routeSet(city);
    if (!e.obj.routes.length) return sendError(res, 503, "routes_unavailable");
    return send(req, res, e, CC_ROUTES);
  }

  // ---- One line on demand ----------------------------------------------------
  const lineMisses = new Map();     // "city|mode|label" -> when its route could not be built
  const lineInflight = new Map();   // the same key -> the request that is fetching it

  /**
   * The route row of one line: from the city's set, or, when the set does not
   * have the line, built once from the journey [trip] and added to the set.
   * null when there is none.
   */
  async function lineRoute(city, mode, label, trip) {
    // An instance that has just started has no set in memory yet: load it first.
    const cached = routes.get(city) || await routeSet(city).catch(() => null);
    const known = cached && findRoute(cached.obj.routes, mode, label);
    if (known) return known;
    const key = `${city}|${mode}|${label}`;
    if (Date.now() - (lineMisses.get(key) || 0) < ROUTE_MISS_TTL_MS) return null;
    if (lineInflight.has(key)) return lineInflight.get(key);
    const pending = (async () => {
      // Only for a journey that is really running on this line in this city right
      // now. Without this check anybody could make this server ask transitous for
      // any journey, or fill the set with invented lines.
      const snap = (await live.load(city, LIVE_TTL_MS, () => T.fetchLive(city))).obj;
      const line = snap.lines.findIndex((l) => l[0] === label && l[1] === mode);
      if (line < 0 || !snap.segs.some((s) => s[0] === line && s[1] === trip)) return null;

      // Another server instance may have stored the line in the meantime.
      const stored = await loadStoredRoutes(db, city);
      const rows = stored ? stored.routes : [];
      let row = findRoute(rows, mode, label);
      if (!row) {
        const poly = await T.tripRoute(trip).catch(() => null);
        if (!poly) {
          lineMisses.set(key, Date.now());
          return null;
        }
        row = [label, mode, snap.lines[line][2] || "", poly];
        rows.push(row);
        // built stays as it was (0 for a new set): adding one line is not a build.
        await saveRoutes(db, city, rows, stored ? stored.complete : false, stored ? stored.built : 0);
        logger.info(`route ${city} ${mode} ${label}: added on demand`);
      }
      // Later requests of this instance find the line in memory. A set that has
      // never been built stays out of memory, so /routes still builds it.
      if (stored && stored.built) {
        routes.entries.set(city, new Entry({ v: 1, city, built: stored.built, routes: rows }));
      }
      return row;
    })().finally(() => lineInflight.delete(key));
    lineInflight.set(key, pending);
    return pending;
  }

  // ---- Stations (see stations.js) ---------------------------------------------
  const stationSearch = new Cache(MAX_CACHED);
  const stationInfo = new Cache(MAX_CACHED);

  async function handleStations(req, res, city, text) {
    const q = S.normalizeQuery(text);
    if (!q) return sendError(res, 400, "bad_query");
    // Asked twice: as typed, and with the city's name in front. The geocoder ranks
    // by name, so "hauptbahnhof" alone finds the Hauptbahnhof of other cities first
    // and only "München hauptbahnhof" finds München Hbf.
    const e = await stationSearch.load(`${city}|${q}`, STATIONS_TTL_MS, async () => {
      const name = CITIES[city].name.toLowerCase();
      const [plain, withCity] = await Promise.all([
        T.searchStops(city, q),
        q.includes(name) ? [] : T.searchStops(city, `${name} ${q}`).catch(() => []),
      ]);
      return S.compactStations(city, [...plain, ...withCity], q);
    });
    return send(req, res, e, CC_STATIONS);
  }

  async function handleStation(req, res, city, stopId) {
    if (!S.validStopId(stopId)) return sendError(res, 404, "unknown_station");
    const e = await stationInfo.load(`${city}|${stopId}`, STATION_TTL_MS, async () => {
      const obj = S.compactDepartures(city, await T.stopTimes(stopId));
      // Only stations of this city: the server is no free timetable service for anywhere.
      if (!S.inRegion(city, obj.lat, obj.lon)) return { v: 1, city, missing: true };
      return obj;
    });
    if (e.obj.missing) {
      res.set("Cache-Control", CC_STATION_MISS);
      return res.status(404).json({ error: "unknown_station" });
    }
    return send(req, res, e, CC_STATION);
  }

  async function handleRoute(req, res, city, mode, label, trip) {
    // Only local lines have routes (see ROUTE_MODES); a long distance train gets 404.
    const valid = T.ROUTE_MODES.has(mode) && label && label.length <= 40 && trip && trip.length <= 300;
    const row = valid ? await lineRoute(city, mode, label, trip) : null;
    if (!row) {
      res.set("Cache-Control", CC_ROUTE_MISS);
      return res.status(404).json({ error: "route_unavailable" });
    }
    return send(req, res, new Entry({ v: 1, city, route: row }), CC_ROUTE);
  }

  return async (req, res) => {
    if (req.method !== "GET") return sendError(res, 405, "method_not_allowed");
    // The CDN caches per full URL. The apps only ever ask for the exact paths
    // below, so anything else (a query string, extra path parts) would only skip
    // the cache and make this function do the full work: refuse it cheaply.
    if (req.originalUrl.includes("?")) return sendError(res, 400, "no_query");
    // Through Hosting the path keeps its /api prefix; called directly it does not.
    let parts;
    try {
      parts = req.path.replace(/^\/api(?=\/|$)/, "").split("/").filter(Boolean).map(decodeURIComponent);
    } catch (_) {
      return sendError(res, 404, "not_found");
    }
    const [kind, city] = parts;
    const group = kind === "live" && parts.length === 3 ? parts[2] : null;
    const expected = kind === "route" ? 5 : group !== null || kind === "stations" || kind === "station" ? 3 : 2;
    if (parts.length !== expected) return sendError(res, 404, "not_found");
    if (group !== null && !GROUPS.includes(group)) return sendError(res, 404, "not_found");
    if (!CITIES[city]) return sendError(res, 404, "unknown_city");
    try {
      if (kind === "live" && group !== null) return await handleLiveGroup(req, res, city, group);
      if (kind === "live") return await handleLive(req, res, city);
      if (kind === "shapes") return await handleShapes(req, res, city);
      if (kind === "routes") return await handleRoutes(req, res, city);
      if (kind === "route") return await handleRoute(req, res, city, parts[2], parts[3], parts[4]);
      if (kind === "stations") return await handleStations(req, res, city, parts[2]);
      if (kind === "station") return await handleStation(req, res, city, parts[2]);
      return sendError(res, 404, "not_found");
    } catch (err) {
      logger.error(`${kind} ${city} failed: ${err.message}`);
      return sendError(res, 502, "upstream_unavailable");
    }
  };
}

module.exports = { createApi, rebuildCityRoutes, loadStoredRoutes, etagMatches };
