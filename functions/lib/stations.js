/**
 * Stations: search and departures, for the apps' Stations tab.
 *
 *   GET /api/stations/{city}/{text}   stations whose name matches   CDN cached 1 day
 *   GET /api/station/{city}/{stopId}  the next departures there     CDN cached 30 s
 *
 * Stations are only loaded when a rider searches or opens one: nothing is shown
 * on the map and nothing is downloaded in advance. The departures come from
 * transitous (/api/v1/stoptimes, the same journey ids as the live data, so the
 * apps can show reports and occupancy on them).
 */
const T = require("./transitous");
const { CITIES, HALF_LAT, HALF_LON } = require("./cities");

const MAX_STATIONS = 10;
const MAX_DEPARTURES = 30;

/** A search text the apps may send: 2 to 40 letters, digits, spaces and a few signs. */
function normalizeQuery(text) {
  const q = String(text || "").trim().replace(/\s+/g, " ").toLowerCase();
  return /^[\p{L}\p{N} .,'()/&-]{2,40}$/u.test(q) ? q : null;
}

/** A transitous stop id, e.g. "de-DELFI_de:09162:2". */
const validStopId = (id) => typeof id === "string" && /^[A-Za-z0-9_:.|-]{3,100}$/.test(id);

/** True when the point lies in the city's region (a little margin for stations on its edge). */
function inRegion(cityId, lat, lon) {
  const c = CITIES[cityId];
  if (!c || typeof lat !== "number" || typeof lon !== "number") return false;
  return Math.abs(lat - c.lat) <= HALF_LAT + 0.05 && Math.abs(lon - c.lon) <= HALF_LON + 0.05;
}

/**
 * The geocoder's answer as the apps get it: { v, city, stations: [[id, name, modes]] },
 * only stops in the city's region, each name once (the platforms of a station share
 * its name; the first, best match is kept), names containing [query] first, at most MAX_STATIONS.
 */
function compactStations(cityId, results, query = "") {
  const stations = [];
  const seen = new Set();
  const list = Array.isArray(results) ? results : [];
  // Names that contain the search text first; otherwise the geocoder order stays.
  const q = query.toLowerCase();
  const ranked = q ? [...list].sort((a, b) => !String(a.name).toLowerCase().includes(q) - !String(b.name).toLowerCase().includes(q)) : list;
  for (const r of ranked) {
    if (r.type !== "STOP" || !validStopId(r.id) || !r.name) continue;
    if (!inRegion(cityId, r.lat, r.lon)) continue;
    // The same station can come back under two names, and platforms share a name.
    const key = r.name.toLowerCase();
    if (seen.has(key) || seen.has(r.id)) continue;
    seen.add(key);
    seen.add(r.id);
    stations.push([r.id, r.name, (r.modes || []).join(",")]);
    if (stations.length >= MAX_STATIONS) break;
  }
  return { v: 1, city: cityId, stations };
}

/**
 * The next departures as the apps get them:
 *   t     base time, epoch seconds; the times below are seconds relative to it
 *   name  the station
 *   deps  [label, mode, colour hex or "", destination, planned, expected, platform, flags, tripId]
 *         flags: 1 = live data, 2 = cancelled
 * Journeys that end here, modes the apps do not show and repeats are left out.
 */
function compactDepartures(cityId, resp, nowMs = Date.now()) {
  const t = Math.floor(nowMs / 1000);
  const rel = (iso) => {
    const ms = iso ? Date.parse(iso) : NaN;
    return Number.isFinite(ms) ? Math.round(ms / 1000) - t : null;
  };
  const place = (resp && resp.place) || {};
  const deps = [];
  const seen = new Set();
  for (const s of (resp && resp.stopTimes) || []) {
    const p = s.place || {};
    if (!p.departure || !T.SHOWN_MODES.has(s.mode)) continue;
    const label = String(s.routeShortName || s.displayName || s.tripShortName || "").trim().split(" ")[0] || "?";
    // A journey can come twice (two platforms of the station, or two coupled
    // trains with the same line, destination and time): the board shows it once.
    const same = `${label}|${s.headsign || ""}|${p.scheduledDeparture || p.departure}`;
    if ((s.tripId && seen.has(s.tripId)) || seen.has(same)) continue;
    if (s.tripId) seen.add(s.tripId);
    seen.add(same);
    const flags = (s.realTime ? 1 : 0) | (s.cancelled || s.tripCancelled || p.cancelled ? 2 : 0);
    deps.push([
      label,
      s.mode,
      T.lineColor(cityId, { routeColor: s.routeColor, mode: s.mode }, label).toLowerCase(),
      s.headsign || "",
      rel(p.scheduledDeparture || p.departure),
      rel(p.departure),
      p.track || p.scheduledTrack || "",
      flags,
      s.tripId || "",
    ]);
    if (deps.length >= MAX_DEPARTURES) break;
  }
  return { v: 1, city: cityId, t, name: place.name || "", lat: place.lat, lon: place.lon, deps };
}

module.exports = { normalizeQuery, validStopId, inRegion, compactStations, compactDepartures };
