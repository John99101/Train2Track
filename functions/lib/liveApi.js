/**
 * The apps' live data API, served through Firebase Hosting (rewrite /api/**):
 *
 *   GET /api/live/{city}    compact live snapshot      CDN cached ~20 s
 *   GET /api/shapes/{city}  track geometries by key    CDN cached 10 min
 *   GET /api/routes/{city}  full line routes           CDN cached 6 h
 *
 * The CDN in front means transitous is asked about once per city per ~20 s no
 * matter how many devices are open. Each instance also keeps the last good live
 * snapshot, so a short transitous outage serves slightly older data (flagged
 * "stale") instead of failing.
 */
const zlib = require("zlib");
const T = require("./transitous");
const { CITIES } = require("./cities");

const LIVE_TTL_MS = 15 * 1000;
const LIVE_STALE_MAX_MS = 10 * 60 * 1000;
const SHAPES_TTL_MS = 10 * 60 * 1000;
const ROUTES_TTL_MS = 30 * 60 * 1000;
const ROUTES_MAX_AGE_MS = 7 * 24 * 3600 * 1000;
const ROUTES_BUILD_BUDGET_MS = 40 * 1000;   // Hosting gives a rewrite 60 s
const CHUNK_CHARS = 700000;                  // stays well under Firestore's 1 MiB doc limit

const CC_LIVE = "public, max-age=10, s-maxage=20";
const CC_STALE = "public, max-age=5, s-maxage=5";
const CC_SHAPES = "public, max-age=600, s-maxage=600";
const CC_ROUTES = "public, max-age=3600, s-maxage=21600";
const CC_ERROR = "public, max-age=5, s-maxage=5";

class Entry {
  constructor(obj) {
    this.obj = obj;
    this.json = JSON.stringify(obj);
    this.gz = zlib.gzipSync(this.json);
    this.at = Date.now();
  }
}

/** Per instance cache with in flight de-duplication. */
class Cache {
  constructor() {
    this.entries = new Map();
    this.inflight = new Map();
  }
  get(key) { return this.entries.get(key); }
  async load(key, ttlMs, loader) {
    const hit = this.entries.get(key);
    if (hit && Date.now() - hit.at < ttlMs) return hit;
    if (this.inflight.has(key)) return this.inflight.get(key);
    const p = (async () => {
      try {
        const e = new Entry(await loader());
        this.entries.set(key, e);
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
  return { built: meta.get("built") || 0, complete: meta.get("complete") !== false, routes: rows };
}

async function saveRoutes(db, city, rows, complete) {
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
  batch.set(ref, { v: 1, built: Date.now(), count: rows.length, chunks: chunks.length, complete });
  await batch.commit();
}

/** Builds (or extends) a city's route set and stores it. Returns the rows. */
async function rebuildCityRoutes(db, city, { deadlineMs = Infinity } = {}) {
  const prev = await loadStoredRoutes(db, city);
  const rows = await T.buildRoutes(city, prev ? prev.routes : [], { deadlineMs });
  const complete = Date.now() <= deadlineMs;
  await saveRoutes(db, city, rows, complete);
  return rows;
}

// ---- Handler -----------------------------------------------------------------

function createApi({ db, logger }) {
  const live = new Cache();
  const shapes = new Cache();
  const routes = new Cache();

  async function handleLive(req, res, city) {
    try {
      const e = await live.load(city, LIVE_TTL_MS, () => T.fetchLive(city));
      return send(req, res, e, CC_LIVE);
    } catch (err) {
      const last = live.get(city);
      if (last && Date.now() - last.at < LIVE_STALE_MAX_MS) {
        logger.warn(`live ${city}: serving stale snapshot (${err.message})`);
        return send(req, res, new Entry({ ...last.obj, stale: true }), CC_STALE);
      }
      throw err;
    }
  }

  async function handleShapes(req, res, city) {
    const e = await shapes.load(city, SHAPES_TTL_MS, () => T.fetchShapes(city));
    return send(req, res, e, CC_SHAPES);
  }

  async function handleRoutes(req, res, city) {
    const e = await routes.load(city, ROUTES_TTL_MS, async () => {
      let stored = await loadStoredRoutes(db, city);
      if (!stored || !stored.routes.length || Date.now() - stored.built > ROUTES_MAX_AGE_MS) {
        // First request for this city (or a very old set): build it now.
        const rows = await rebuildCityRoutes(db, city, { deadlineMs: Date.now() + ROUTES_BUILD_BUDGET_MS });
        stored = { built: Date.now(), complete: true, routes: rows };
      }
      return { v: 1, city, built: stored.built, routes: stored.routes };
    });
    if (!e.obj.routes.length) return sendError(res, 503, "routes_unavailable");
    return send(req, res, e, CC_ROUTES);
  }

  return async (req, res) => {
    if (req.method !== "GET") return sendError(res, 405, "method_not_allowed");
    // The CDN caches per full URL. The apps only ever ask for the exact paths
    // below, so anything else (a query string, extra path parts) would only skip
    // the cache and make this function do the full work: refuse it cheaply.
    if (req.originalUrl.includes("?")) return sendError(res, 400, "no_query");
    // Through Hosting the path keeps its /api prefix; called directly it does not.
    const parts = req.path.replace(/^\/api(?=\/|$)/, "").split("/").filter(Boolean);
    if (parts.length !== 2) return sendError(res, 404, "not_found");
    const [kind, city] = parts;
    if (!CITIES[city]) return sendError(res, 404, "unknown_city");
    try {
      if (kind === "live") return await handleLive(req, res, city);
      if (kind === "shapes") return await handleShapes(req, res, city);
      if (kind === "routes") return await handleRoutes(req, res, city);
      return sendError(res, 404, "not_found");
    } catch (err) {
      logger.error(`${kind} ${city} failed: ${err.message}`);
      return sendError(res, 502, "upstream_unavailable");
    }
  };
}

module.exports = { createApi, rebuildCityRoutes, loadStoredRoutes };
