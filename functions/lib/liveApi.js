/**
 * The apps' live data API, served through Firebase Hosting (rewrite /api/**):
 *
 *   GET /api/live/{city}    compact live snapshot      CDN cached ~20 s (app versions up to 0.5.1)
 *   GET /api/live/{city}/{group}
 *                           the same for one mode group (rail, tram, bus, see
 *                           liveGroups.js), with an ETag: a request with a matching
 *                           If-None-Match gets "304 Not Modified" without a body
 *   GET /api/shapes/{city}  track geometries by key    CDN cached 10 min
 *   GET /api/routes/{city}  full line routes           CDN cached 6 h, with an ETag (304)
 *   GET /api/route/{city}/{mode}/{label}/{tripId}
 *                           one line the set lacks     asked from transitous once, then stored
 *   GET /api/stations/{city}/{text}
 *                           station search             CDN cached 1 day (see stations.js)
 *   GET /api/station/{city}/{stopId}
 *                           departures of a station    CDN cached 30 s
 *   GET /api/stops/{city}   the stations for the map   CDN cached 6 h, with an ETag (304)
 *
 * A line route is a list of pieces: the whole route of one journey, then only
 * the parts other journeys add (branches, the other end of short runs), see
 * lineGeometry.js. App versions up to 0.6.0 draw the first piece only.
 *
 * The CDN in front means transitous is asked about once per city per ~20 s no
 * matter how many devices are open. The apps never ask transitous for a route
 * themselves: a line the city's set does not have (a long distance train, a night
 * line) is fetched here once and then belongs to the set for everybody. Each instance also keeps the last good live
 * snapshot, so a short transitous outage serves slightly older data (flagged
 * "stale") instead of failing.
 */
const crypto = require("crypto");
const zlib = require("zlib");
const T = require("./transitous");
const G = require("./lineGeometry");
const { GROUPS, splitLive, liveHash } = require("./liveGroups");
const { CITIES } = require("./cities");
const S = require("./stations");

const LIVE_TTL_MS = 15 * 1000;
const LIVE_STALE_MAX_MS = 10 * 60 * 1000;
const SHAPES_TTL_MS = 10 * 60 * 1000;
const ROUTES_TTL_MS = 30 * 60 * 1000;
// The sets are topped up every day (refreshRoutes). Only when that has not
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
const CC_STOPS = "public, max-age=3600, s-maxage=21600";

const STATIONS_TTL_MS = 6 * 3600 * 1000;
const STATION_TTL_MS = 25 * 1000;
// Search texts and stations are many: each cache keeps only the newest this many.
const MAX_CACHED = 2000;

// Questions to transitous that no cache answers (station search and departures):
// per server instance at most UPSTREAM_PER_SECOND on average and UPSTREAM_BURST at
// once. Beyond that the app gets 429 and tries again later, so a script cannot
// make this server flood transitous, which could block the app's name for everyone.
const UPSTREAM_PER_SECOND = 5;
const UPSTREAM_BURST = 20;
// A station or search transitous could not answer is not asked again this soon.
const FAILED_TTL_MS = 60 * 1000;
const CC_BUSY = "public, max-age=5, s-maxage=5";
// A search that found nothing: kept only briefly, it may have been a hiccup.
const CC_STATIONS_EMPTY = "public, max-age=60, s-maxage=300";

/** Thrown when the budget for transitous is used up: the request gets 429. */
class Busy extends Error {}

/** A token bucket: take(n) says whether n more questions fit right now. */
function budget(perSecond, burst, now = () => Date.now()) {
  let tokens = burst;
  let at = now();
  return (n = 1) => {
    const t = now();
    tokens = Math.min(burst, tokens + ((t - at) * perSecond) / 1000);
    at = t;
    if (tokens < n) return false;
    tokens -= n;
    return true;
  };
}

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

/**
 * send() with an ETag made from what the apps use of the answer ([part] of it, not
 * when it was built): an app that already has it gets "304 Not Modified" without
 * a body. The route set and the station list rarely change, so the phone's daily
 * check costs almost nothing.
 */
function sendTagged(req, res, entry, cacheControl, part) {
  if (!entry.tag) {
    entry.tag = crypto.createHash("sha1").update(JSON.stringify(part(entry.obj))).digest("base64url").slice(0, 16);
  }
  res.set("ETag", `W/"${entry.tag}"`);
  if (etagMatches(req.get("if-none-match"), entry.tag)) {
    res.set("Cache-Control", cacheControl);
    res.set("Vary", "Accept-Encoding");
    return res.status(304).end();
  }
  return send(req, res, entry, cacheControl);
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

// ---- Route storage -----------------------------------------------------------
// Firestore route_cache/{city}, with three lists in numbered chunk documents:
//   chunks/{i}     the line rows [label, mode, colour, piece, piece...]
//   stations/{i}   the stations [id, name, lat, lon, rank]
//   linestops/{i}  which stations each line stops at ["mode|label", [ids]]
// Sets stored before the stations existed have only the first list.

const STORED_LISTS = ["chunks", "stations", "linestops"];

async function loadChunks(ref, name) {
  const snap = await ref.collection(name).get();
  const rows = [];
  snap.docs
    .sort((a, b) => Number(a.id) - Number(b.id))
    .forEach((d) => rows.push(...JSON.parse(d.get("rows") || "[]")));
  return rows;
}

function toChunks(rows) {
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
  return chunks;
}

async function loadStoredRoutes(db, city) {
  const ref = db.collection("route_cache").doc(city);
  const meta = await ref.get();
  if (!meta.exists) return null;
  const [rows, stations, lineStops] = await Promise.all(STORED_LISTS.map((name) => loadChunks(ref, name)));
  // Long distance lines stored by earlier versions are left out (see ROUTE_MODES);
  // the next save of the set drops them for good.
  const local = rows.filter((r) => T.ROUTE_MODES.has(r[1]));
  return {
    built: meta.get("built") || 0,
    complete: meta.get("complete") !== false,
    routes: local,
    stations,
    lineStops,
  };
}

/**
 * Stores a set { rows, stations, lineStops }. [built] = when the set was last
 * built or topped up as a whole (not: one line added).
 */
async function saveRoutes(db, city, set, complete, built = Date.now()) {
  const lists = { chunks: set.rows, stations: set.stations || [], linestops: set.lineStops || [] };
  const ref = db.collection("route_cache").doc(city);
  const batch = db.batch();
  const counts = {};
  for (const name of STORED_LISTS) {
    const chunks = toChunks(lists[name]);
    counts[name] = chunks.length;
    const old = await ref.collection(name).get();
    old.docs.forEach((d) => { if (Number(d.id) >= chunks.length) batch.delete(d.ref); });
    chunks.forEach((c, i) => batch.set(ref.collection(name).doc(String(i)), { rows: JSON.stringify(c) }));
  }
  batch.set(ref, {
    v: 2, built, count: set.rows.length, chunks: counts.chunks, stations: lists.stations.length, complete,
  });
  await batch.commit();
}

/**
 * Brings a city's route set up to date (see transitous.buildRoutes: new lines,
 * missing branches, stations) or, with full = true, fetches every running line
 * anew, and stores it. Returns { rows, asked, complete }: the lines, for how many
 * journeys transitous was asked, and whether all was done before the deadline.
 */
async function rebuildCityRoutes(db, city, { deadlineMs = Infinity, full = false } = {}) {
  const prev = await loadStoredRoutes(db, city);
  const set = await T.buildRoutes(city, prev || {}, { deadlineMs, full });
  await saveRoutes(db, city, set, set.complete);
  return { rows: set.rows, asked: set.asked, complete: set.complete };
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
  const groupHashes = new WeakMap();   // group snapshot -> its liveHash

  async function handleLiveGroup(req, res, city, group) {
    const { entry, cacheControl } = await liveSnapshot(city);
    let groups = groupSplits.get(entry);
    if (!groups) {
      groups = splitLive(entry.obj);
      groupSplits.set(entry, groups);
    }
    const obj = groups[group];
    const key = `${city}|${group}`;
    let hash = groupHashes.get(obj);
    if (hash === undefined) {
      hash = liveHash(obj);
      groupHashes.set(obj, hash);
    }
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
    return sendTagged(req, res, e, CC_ROUTES, (o) => o.routes);
  }

  // ---- One line on demand ----------------------------------------------------
  const lineMisses = new Map();     // "city|mode|label" -> when its route could not be built
  const routeEntries = new WeakMap();   // a stored row -> its packed answer
  const cityChains = new Map();     // city -> the last change of its stored set (one at a time)

  /** Runs [fn] after the city's previous change to its stored set, so two never overwrite each other here. */
  function withCityLock(city, fn) {
    const run = (cityChains.get(city) || Promise.resolve()).then(fn, fn);
    cityChains.set(city, run.catch(() => {}));
    return run;
  }
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
      return withCityLock(city, () => storeLine(city, mode, label, trip, snap, line, key));
    })().finally(() => lineInflight.delete(key));
    lineInflight.set(key, pending);
    return pending;
  }

  /** Adds the line [label] of [mode] to the city's stored set (see lineRoute). */
  async function storeLine(city, mode, label, trip, snap, line, key) {
    {
      const stored = await loadStoredRoutes(db, city);
      let rows = stored ? stored.routes : [];
      let row = findRoute(rows, mode, label);
      if (!row) {
        const d = await T.tripDetails(trip).catch(() => null);
        if (!d) {
          lineMisses.set(key, Date.now());
          return null;
        }
        // The line with its stations, as a build would add it.
        const maps = T.routeMaps(stored || {});
        row = [label, mode, snap.lines[line][2] || ""];
        G.extendRow(row, d.points);
        maps.rows.set(`${mode}|${label}`, row);
        T.addStops(city, mode, `${mode}|${label}`, d.stops, maps.stations, maps.lineStops);
        const set = T.routeArrays(maps);
        rows = set.rows;
        // built stays as it was (0 for a new set): adding one line is not a build.
        await saveRoutes(db, city, set, stored ? stored.complete : false, stored ? stored.built : 0);
        logger.info(`route ${city} ${mode} ${label}: added on demand`);
      }
      // Later requests of this instance find the line in memory. A set that has
      // never been built stays out of memory, so /routes still builds it.
      if (stored && stored.built) {
        routes.entries.set(city, new Entry({ v: 1, city, built: stored.built, routes: rows }));
      }
      return row;
    }
  }

  // ---- Stations (see stations.js) ---------------------------------------------
  const stationSearch = new Cache(MAX_CACHED);
  const stationInfo = new Cache(MAX_CACHED);
  const upstream = budget(UPSTREAM_PER_SECOND, UPSTREAM_BURST);
  const failed = new Map();   // "kind|city|key" -> when transitous could not answer it

  function recentlyFailed(key) {
    const at = failed.get(key);
    return at !== undefined && Date.now() - at < FAILED_TTL_MS;
  }
  function noteFailure(key) {
    failed.delete(key);
    failed.set(key, Date.now());
    if (failed.size > MAX_CACHED) failed.delete(failed.keys().next().value);
  }

  async function handleStations(req, res, city, text) {
    const q = S.normalizeQuery(text);
    if (!q) return sendError(res, 400, "bad_query");
    const key = `${city}|${q}`;
    if (recentlyFailed(`s|${key}`)) return sendError(res, 502, "upstream_unavailable");
    // Asked twice: as typed, and with the city's name in front. The geocoder ranks
    // by name, so "hauptbahnhof" alone finds the Hauptbahnhof of other cities first
    // and only "München hauptbahnhof" finds München Hbf.
    let e;
    try {
      e = await stationSearch.load(key, STATIONS_TTL_MS, async () => {
        const name = CITIES[city].name.toLowerCase();
        const both = !q.includes(name);
        if (!upstream(both ? 2 : 1)) throw new Busy();
        let partial = false;
        const [plain, withCity] = await Promise.all([
          T.searchStops(city, q),
          both ? T.searchStops(city, `${name} ${q}`).catch(() => { partial = true; return []; }) : [],
        ]);
        return { ...S.compactStations(city, [...plain, ...withCity], q), partial };
      });
    } catch (err) {
      if (!(err instanceof Busy)) noteFailure(`s|${key}`);
      throw err;
    }
    // Half an answer (the second search failed) or none at all may be a hiccup:
    // not kept here, and kept only briefly by the CDN.
    if (e.obj.partial || !e.obj.stations.length) {
      stationSearch.entries.delete(key);
      return send(req, res, e, e.obj.partial ? CC_ERROR : CC_STATIONS_EMPTY);
    }
    return send(req, res, e, CC_STATIONS);
  }

  /**
   * The stations for the map's "Stations" button, from the stored route set. Only
   * downloaded by an app that switched the button on, then kept for days.
   */
  const mapStations = new Cache();
  async function handleStops(req, res, city) {
    const e = await mapStations.load(city, ROUTES_TTL_MS, async () => {
      const stored = await loadStoredRoutes(db, city);
      return S.compactMapStations(city, stored || {});
    });
    // Not collected yet (a set from before the stations): the daily run fills it.
    if (!e.obj.stations.length) return sendError(res, 503, "stops_unavailable");
    return sendTagged(req, res, e, CC_STOPS, (o) => [o.complete, o.stations]);
  }

  async function handleStation(req, res, city, stopId) {
    if (!S.validStopId(stopId)) return sendError(res, 404, "unknown_station");
    const key = `${city}|${stopId}`;
    if (recentlyFailed(`d|${key}`)) return sendError(res, 502, "upstream_unavailable");
    let e;
    try {
      e = await stationInfo.load(key, STATION_TTL_MS, async () => {
        if (!upstream(1)) throw new Busy();
        const obj = S.compactDepartures(city, await T.stopTimes(stopId));
        // Only stations of this city: the server is no free timetable service for anywhere.
        if (!S.inRegion(city, obj.lat, obj.lon)) return { v: 1, city, missing: true };
        return obj;
      });
    } catch (err) {
      if (!(err instanceof Busy)) noteFailure(`d|${key}`);
      throw err;
    }
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
    // One packed answer per stored row, not one per request.
    let entry = routeEntries.get(row);
    if (!entry) {
      entry = new Entry({ v: 1, city, route: row });
      routeEntries.set(row, entry);
    }
    return send(req, res, entry, CC_ROUTE);
  }

  return async (req, res) => {
    if (req.method !== "GET") return sendError(res, 405, "method_not_allowed");
    // The CDN caches per full URL. The apps only ever ask for the exact paths
    // below, so anything else (a query string, extra path parts) would only skip
    // the cache and make this function do the full work: refuse it cheaply.
    if (req.originalUrl.includes("?")) return sendError(res, 400, "no_query");
    // Through Hosting the path keeps its /api prefix; called directly it does not.
    // One address per resource: a doubled or trailing slash, or an escaped city or
    // group name, would be another address for the same data that skips the CDN.
    const raw = req.path.replace(/^\/api(?=\/|$)/, "");
    if (raw.includes("//") || (raw.length > 1 && raw.endsWith("/"))) return sendError(res, 404, "not_found");
    let parts;
    try {
      parts = raw.split("/").filter(Boolean).map(decodeURIComponent);
    } catch (_) {
      return sendError(res, 404, "not_found");
    }
    const [kind, city] = parts;
    if (["live", "shapes", "routes", "stops"].includes(kind) && raw.includes("%")) return sendError(res, 404, "not_found");
    const group = kind === "live" && parts.length === 3 ? parts[2] : null;
    const expected = kind === "route" ? 5 : group !== null || kind === "stations" || kind === "station" ? 3 : 2;
    if (parts.length !== expected) return sendError(res, 404, "not_found");
    if (group !== null && !GROUPS.includes(group)) return sendError(res, 404, "not_found");
    // Own keys only: "constructor" or "toString" are no cities.
    if (!Object.hasOwn(CITIES, city)) return sendError(res, 404, "unknown_city");
    try {
      if (kind === "live" && group !== null) return await handleLiveGroup(req, res, city, group);
      if (kind === "live") return await handleLive(req, res, city);
      if (kind === "shapes") return await handleShapes(req, res, city);
      if (kind === "routes") return await handleRoutes(req, res, city);
      if (kind === "route") return await handleRoute(req, res, city, parts[2], parts[3], parts[4]);
      if (kind === "stations") return await handleStations(req, res, city, parts[2]);
      if (kind === "station") return await handleStation(req, res, city, parts[2]);
      if (kind === "stops") return await handleStops(req, res, city);
      return sendError(res, 404, "not_found");
    } catch (err) {
      if (err instanceof Busy) {
        res.set("Retry-After", "5");
        res.set("Cache-Control", CC_BUSY);
        return res.status(429).json({ error: "busy" });
      }
      logger.error(`${kind} ${city} failed: ${err.message}`);
      return sendError(res, 502, "upstream_unavailable");
    }
  };
}

module.exports = { createApi, rebuildCityRoutes, loadStoredRoutes, etagMatches };
