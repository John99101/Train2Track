/**
 * Train2Track Cloud Functions (region europe-west3, same as Firestore).
 *
 *  Live data      api (HTTPS via Hosting /api/**), refreshRoutes, rebuildRoutes
 *  Reports        onReportWritten / onDelayVoteWritten (spam limit, hidden names), moderateTrip
 *  Disruptions    refreshDisruptions
 *  Statistics     onControlLogCreated (no ID in the log), controlStats (admin), liveStats (active users)
 *  Housekeeping   cleanup, deleteMyData, purgeInactiveUsers
 *
 * Reports themselves still sync purely through Firestore; everything here is
 * additive, so older app versions keep working unchanged.
 *
 * No app can see who wrote a report or a delay vote: see lib/reportIds.js. The
 * link between such a document and its rider is an owner note in `doc_owners`,
 * which no app can read.
 */
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onDocumentWritten, onDocumentCreated } = require("firebase-functions/v2/firestore");
const { setGlobalOptions, logger } = require("firebase-functions/v2");
const crypto = require("crypto");
const admin = require("firebase-admin");
const { FieldValue, Timestamp } = require("firebase-admin/firestore");
const GtfsRealtimeBindings = require("gtfs-realtime-bindings");
const { CITIES } = require("./lib/cities");
const { createApi, rebuildCityRoutes } = require("./lib/liveApi");
const { isStaleAnonymous } = require("./lib/accounts");
const { CITY_UNKNOWN, onlyServerFieldsChanged, sameValue } = require("./lib/reportFields");
const { ADMIN_UIDS, syncAdminClaims } = require("./lib/admins");
const { tripKey, legacyDocId, hiddenDocId, ownerNoteId, ownerOf } = require("./lib/reportIds");
const { ENFORCE_APP_CHECK, LIMITS, DISMISSAL_STICKY_MS } = require("./lib/limits");

admin.initializeApp();
setGlobalOptions({ region: "europe-west3" });
const db = admin.firestore();

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

function requireAdmin(request) {
  if (!request.auth || request.auth.token.admin !== true) {
    throw new HttpsError("permission-denied", "App-manager role required.");
  }
}

/** Deletes (or updates) every doc matching [query], in batches. Returns the count. */
async function forEachBatch(query, apply, max = 5000) {
  let total = 0;
  while (total < max) {
    const snap = await query.limit(400).get();
    if (snap.empty) break;
    const batch = db.batch();
    snap.docs.forEach((d) => apply(batch, d.ref));
    await batch.commit();
    total += snap.size;
    if (snap.size < 400) break;
  }
  return total;
}
const deleteWhere = (query, max) => forEachBatch(query, (b, ref) => b.delete(ref), max);

// App Check for the callables: see ENFORCE_APP_CHECK in lib/limits.js.
const CALLABLE = { enforceAppCheck: ENFORCE_APP_CHECK };

// Caps the report triggers: during a spam wave events just wait a little longer
// instead of starting ever more instances (and costs).
const TRIGGER_MAX_INSTANCES = 10;
const REPORT_TRIGGER = { document: "reports/{reportId}", maxInstances: TRIGGER_MAX_INSTANCES };
const VOTE_TRIGGER = { document: "delay_reports/{voteId}", maxInstances: TRIGGER_MAX_INSTANCES };
const LOG_TRIGGER = { document: "control_log/{entryId}", maxInstances: TRIGGER_MAX_INSTANCES };

// ---- Live data proxy ---------------------------------------------------------

exports.api = onRequest(
  { memory: "512MiB", cpu: 1, concurrency: 40, maxInstances: 5, timeoutSeconds: 60 },
  createApi({ db, logger })
);

/**
 * Brings the line routes of all cities up to date. Normally only the lines a
 * city's set does not have yet are asked from transitous; with full = true every
 * running line is fetched anew. Returns per city how many lines the set has and
 * for how many journeys transitous was asked.
 */
async function refreshAllRoutes({ full }) {
  const result = {};
  for (const city of Object.keys(CITIES)) {
    try {
      const { rows, asked } = await rebuildCityRoutes(db, city, { full });
      result[city] = { lines: rows.length, asked };
    } catch (e) {
      logger.warn(`routes ${city} failed: ${e.message}`);
      result[city] = `error: ${e.message}`;
    }
  }
  logger.info(full ? "routes rebuilt" : "routes topped up", result);
  return result;
}

/**
 * Once a week, on Monday at a busy time of day so (almost) every line is running:
 * add the lines the route sets do not have yet. Routes the server already has
 * are kept and not asked again, so this costs transitous a handful of requests
 * instead of thousands. A line that only runs at other times is added when an
 * app first needs it (GET /api/route/..., see lib/liveApi.js).
 */
exports.refreshRoutes = onSchedule(
  { schedule: "30 8 * * 1", timeZone: "Europe/Berlin", timeoutSeconds: 540, memory: "512MiB" },
  () => refreshAllRoutes({ full: false })
);

/**
 * Admin button in the app: fetch the route of every running line anew, right
 * now. This is the only thing that replaces a stored route (after a timetable
 * change, or when a line is drawn wrongly), and it asks transitous for several
 * thousand journeys: use it rarely.
 */
exports.rebuildRoutes = onCall({ ...CALLABLE, timeoutSeconds: 540, memory: "512MiB" }, async (request) => {
  requireAdmin(request);
  return refreshAllRoutes({ full: true });
});

// ---- Reports: spam limit + moderation -----------------------------------------

/** Records one write for [uid] and says whether it is over the limit (lib/limits.js). */
async function overLimit(uid, kind) {
  const ref = db.collection("rate_limits").doc(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = Date.now();
    const stamps = ((snap.exists && snap.get(kind)) || []).filter((t) => now - t < HOUR);
    stamps.push(now);
    tx.set(ref, { [kind]: stamps, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return LIMITS[kind].some(({ windowMs, max }) => stamps.filter((t) => now - t < windowMs).length > max);
  });
}

/** The owner note of a report or delay vote: which rider it belongs to. */
const ownerRef = (collection, docId) => db.collection("doc_owners").doc(ownerNoteId(collection, docId));

/**
 * Every report or delay vote a rider writes to [collection]:
 *  - count it against their spam limit (over the limit it is marked `throttled`
 *    and the apps ignore it);
 *  - file an entry from an app version that sends no city under "unknown";
 *  - for a report, keep an admin dismissal sticky when the same rider simply
 *    reports the same train again;
 *  - rename an entry that came under an old name and write its owner note (see
 *    lib/reportIds.js), so that it no longer shows the rider's id.
 */
async function onRiderWrite(event, collection, limitKind) {
  const after = event.data && event.data.after;
  if (!after || !after.exists) return;
  const data = after.data();
  const before = event.data.before.exists ? event.data.before.data() : null;
  if (onlyServerFieldsChanged(before, data)) return;
  const note = await ownerRef(collection, after.id).get();
  // The entry the server itself just renamed: already counted under its old name.
  if (note.exists && note.get("renamedAt") && sameValue(note.get("renamedAt"), data.createdAt)) return;
  const owner = ownerOf(after.id, data, note.exists ? note.get("uid") : null);
  if (!owner) return;

  const updates = {};
  if (data.city === undefined) updates.city = CITY_UNKNOWN;
  if (await overLimit(owner.uid, limitKind)) updates.throttled = true;
  if (collection === "reports") {
    const mod = await db.collection("trip_moderation").doc(tripKey(data.tripId)).get();
    const at = mod.exists && mod.get("dismissedAt");
    if (at && Date.now() - at.toMillis() < DISMISSAL_STICKY_MS && (mod.get("dismissedUids") || []).includes(owner.uid)) {
      updates.dismissed = true;
    }
  }

  if (!owner.legacy) {
    if (Object.keys(updates).length) await after.ref.update(updates);
    return;
  }
  // An old name: the same entry moves to its hidden name, without the id. Only if
  // this is still the rider's latest write; a newer one brings its own event.
  const hiddenId = hiddenDocId(owner.uid, data.tripId);
  await db.runTransaction(async (tx) => {
    const current = await tx.get(after.ref);
    if (!current.exists || !sameValue(current.get("createdAt"), data.createdAt)) return;
    tx.set(db.collection(collection).doc(hiddenId), { ...data, ...updates, uid: hiddenId });
    tx.set(ownerRef(collection, hiddenId), {
      uid: owner.uid, collection, doc: hiddenId, tripId: data.tripId,
      expiresAt: data.expiresAt || null, renamedAt: data.createdAt || null,
    });
    tx.delete(after.ref);
  });
}

exports.onReportWritten = onDocumentWritten(
  REPORT_TRIGGER, (event) => onRiderWrite(event, "reports", "report"));

exports.onDelayVoteWritten = onDocumentWritten(
  VOTE_TRIGGER, (event) => onRiderWrite(event, "delay_reports", "vote"));

/** The riders behind [docs] of the `reports` collection (see lib/reportIds.js). */
async function reportOwnerUids(docs) {
  const uids = new Set();
  const hidden = [];
  for (const d of docs) {
    const uid = d.get("uid");
    if (typeof uid === "string" && d.id === legacyDocId(uid, d.get("tripId"))) uids.add(uid);
    else hidden.push(ownerRef("reports", d.id));
  }
  if (hidden.length) {
    (await db.getAll(...hidden)).forEach((o) => { if (o.exists && o.get("uid")) uids.add(o.get("uid")); });
  }
  return [...uids];
}

/**
 * Admin moderation of one train's reports:
 *   verify   mark the reports as checked (the app shows them as verified)
 *   dismiss  hide them for everyone; the same riders stay hidden on this train
 *   restore  undo either
 */
exports.moderateTrip = onCall(CALLABLE, async (request) => {
  requireAdmin(request);
  const { tripId, action } = request.data || {};
  if (typeof tripId !== "string" || !tripId || !["verify", "dismiss", "restore"].includes(action)) {
    throw new HttpsError("invalid-argument", "tripId and action (verify|dismiss|restore) required.");
  }
  const snap = await db.collection("reports").where("tripId", "==", tripId).get();
  const modRef = db.collection("trip_moderation").doc(tripKey(tripId));
  const batch = db.batch();
  const flags =
    action === "verify" ? { verified: true, dismissed: false }
      : action === "dismiss" ? { verified: false, dismissed: true }
        : { verified: false, dismissed: false };
  snap.docs.forEach((d) => batch.update(d.ref, { ...flags, moderatedAt: FieldValue.serverTimestamp() }));
  if (action === "dismiss") {
    const uids = await reportOwnerUids(snap.docs);
    batch.set(modRef, { tripId, dismissedUids: uids, dismissedAt: FieldValue.serverTimestamp() });
  } else if (action === "restore") {
    batch.delete(modRef);
  }
  await batch.commit();
  logger.info(`moderateTrip ${action} ${tripId}: ${snap.size} reports`);
  return { ok: true, reports: snap.size };
});

// ---- Disruptions -------------------------------------------------------------

/**
 * Disruptions proxy (Phase 6 / Option 2b). Every 10 minutes this fetches the free
 * nationwide GTFS-RT feed from gtfs.de ONCE on the server (~35 MB), extracts just
 * the ServiceAlerts, and writes a tiny `disruptions` collection to Firestore. Every
 * device then reads a few KB instead of downloading 35 MB each — the whole point of
 * using Firebase as the middle-man. Reads: firestore.rules allows any signed-in
 * device; only this function (Admin SDK) writes.
 */
const GTFS_DE_FREE_URL = "https://realtime.gtfs.de/realtime-free.pb";
const MAX_DISRUPTIONS = 100;
const ALERT_FIELDS = ["title", "description", "affected", "startEpochMillis", "endEpochMillis", "severe"];

/** Same alert content (updatedAt does not count). */
const sameAlert = (prev, next) =>
  ALERT_FIELDS.every((k) => JSON.stringify(prev[k] ?? null) === JSON.stringify(next[k] ?? null));

exports.refreshDisruptions = onSchedule(
  { schedule: "every 10 minutes", timeoutSeconds: 120, memory: "512MiB" },
  async () => {
    const res = await fetch(GTFS_DE_FREE_URL);
    if (!res.ok) throw new Error(`gtfs.de HTTP ${res.status}`);
    const buffer = new Uint8Array(await res.arrayBuffer());
    const feed = GtfsRealtimeBindings.transit_realtime.FeedMessage.decode(buffer);

    const now = Date.now();
    const best = (ts) => {
      if (!ts || !ts.translation || ts.translation.length === 0) return "";
      const de = ts.translation.find((t) => (t.language || "").toLowerCase().startsWith("de"));
      return ((de || ts.translation[0]).text || "").trim();
    };

    const byId = new Map();
    const hasStart = new Set();
    for (const entity of feed.entity) {
      if (!entity.alert) continue;
      const a = entity.alert;
      const title = best(a.headerText);
      if (!title) continue;
      const period = (a.activePeriod && a.activePeriod[0]) || null;
      const start = period && period.start ? Number(period.start) * 1000 : now;
      const end = period && period.end ? Number(period.end) * 1000 : null;
      if (end !== null && end < now) continue; // already over
      const description = best(a.descriptionText);
      const affected = [...new Set((a.informedEntity || [])
        .map((s) => s.routeId || s.stopId || "")
        .filter(Boolean))].slice(0, 20);
      const effect = a.effect;
      const severe = effect === 1 /* NO_SERVICE */ || effect === 2 /* SIGNIFICANT_DELAYS */;
      // The id comes from the content, not from the feed's entity number: when
      // the feed renumbers an unchanged alert, it stays the same document
      // instead of being deleted and written again (sent to every open app).
      const id = "gtfsde-" + crypto.createHash("sha1")
        .update(JSON.stringify([title, description, [...affected].sort(),
          period && period.start ? String(period.start) : null,
          period && period.end ? String(period.end) : null, severe]))
        .digest("hex").slice(0, 20);
      if (byId.has(id)) continue;
      if (period && period.start) hasStart.add(id);
      byId.set(id, {
        id,
        title,
        description,
        affected,
        startEpochMillis: start,
        endEpochMillis: end,
        severe,
        updatedAt: now,
      });
    }
    // Keep the same alerts from run to run (not "the first 100 in feed order"):
    // severe first, then the ones that started earliest.
    const alerts = [...byId.values()]
      .sort((x, y) => (y.severe - x.severe) || (x.startEpochMillis - y.startEpochMillis) || x.id.localeCompare(y.id))
      .slice(0, MAX_DISRUPTIONS);

    const col = db.collection("disruptions");
    const existing = await col.get();
    const old = new Map(existing.docs.map((d) => [d.id, d.data()]));
    const keep = new Set(alerts.map((a) => a.id));
    const batch = db.batch();
    let pruned = 0;
    let written = 0;
    // Remove alerts no longer active.
    existing.forEach((doc) => {
      if (!keep.has(doc.id)) {
        batch.delete(doc.ref);
        pruned++;
      }
    });
    // Write only new or changed alerts: every write is sent to every open app, so
    // rewriting unchanged alerts cost each device ~100 reads per run for nothing.
    alerts.forEach((a) => {
      const prev = old.get(a.id);
      // An alert without a start time got "now": keep its first value.
      if (prev && !hasStart.has(a.id)) a.startEpochMillis = prev.startEpochMillis;
      if (prev && sameAlert(prev, a)) return;
      batch.set(col.doc(a.id), a);
      written++;
    });
    if (written || pruned) await batch.commit();

    console.log(`disruptions: ${alerts.length} active, wrote ${written}, pruned ${pruned}`);
  }
);

// ---- Statistics ----------------------------------------------------------------

const BERLIN_PARTS = new Intl.DateTimeFormat("en-US", {
  timeZone: "Europe/Berlin", weekday: "short", hour: "2-digit", hourCycle: "h23",
});
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

const topEntries = (map, n) => [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);

/**
 * The inspection statistics need no rider: line, station, journey, city and time
 * are enough. The apps released so far send their id with every entry (the rules
 * used to require it), so it is removed here as soon as the entry arrives. The
 * log then cannot show which journeys one id reported over time.
 */
exports.onControlLogCreated = onDocumentCreated(LOG_TRIGGER, async (event) => {
  const snap = event.data;
  if (!snap || snap.get("uid") === undefined) return;
  await snap.ref.update({ uid: FieldValue.delete() });
});

/**
 * Admin only: which lines get inspected how often, on which weekdays, at which
 * times and at which stations (from the persistent `control_log`).
 */
exports.controlStats = onCall({ ...CALLABLE, timeoutSeconds: 120, memory: "512MiB" }, async (request) => {
  requireAdmin(request);
  const days = Math.min(90, Math.max(1, Number((request.data && request.data.days) || 30)));
  const LIMIT = 50000;
  const since = Timestamp.fromMillis(Date.now() - days * 24 * HOUR);
  const snap = await db.collection("control_log").where("createdAt", ">=", since).limit(LIMIT).get();

  const byLine = new Map();
  const byStation = new Map();
  const byCity = new Map();
  const byWeekday = new Array(7).fill(0);
  const byHour = new Array(24).fill(0);
  const trips = new Set();
  snap.docs.forEach((d) => {
    const line = d.get("line") || "?";
    byLine.set(line, (byLine.get(line) || 0) + 1);
    const station = d.get("station");
    if (station) byStation.set(station, (byStation.get(station) || 0) + 1);
    const city = d.get("city");
    if (city) byCity.set(city, (byCity.get(city) || 0) + 1);
    const tripId = d.get("tripId");
    if (tripId) trips.add(tripId);
    const at = d.get("createdAt");
    if (at) {
      const parts = BERLIN_PARTS.formatToParts(at.toDate());
      const wd = WEEKDAYS.indexOf(parts.find((p) => p.type === "weekday").value);
      const hour = Number(parts.find((p) => p.type === "hour").value) % 24;
      if (wd >= 0) byWeekday[wd]++;
      byHour[hour]++;
    }
  });
  return {
    days,
    total: snap.size,
    trips: trips.size,
    truncated: snap.size >= LIMIT,
    byLine: topEntries(byLine, 20),
    byStation: topEntries(byStation, 15),
    byCity: topEntries(byCity, 12),
    byWeekday,
    byHour,
  };
});

/**
 * Active users: the apps write presence/{uid} = { lastSeen, platform } every few
 * minutes while open. This counts the devices seen in the last 10 minutes into
 * stats/live, which any signed-in app may read (one small doc).
 */
exports.liveStats = onSchedule({ schedule: "every 5 minutes", timeoutSeconds: 60 }, async () => {
  const cutoff = Timestamp.fromMillis(Date.now() - 10 * MINUTE);
  const col = db.collection("presence");
  const recent = col.where("lastSeen", ">", cutoff);
  const [all, android, ios] = await Promise.all([
    recent.count().get(),
    col.where("platform", "==", "android").where("lastSeen", ">", cutoff).count().get(),
    col.where("platform", "==", "ios").where("lastSeen", ">", cutoff).count().get(),
  ]);
  await db.collection("stats").doc("live").set({
    activeUsers: all.data().count,
    android: android.data().count,
    ios: ios.data().count,
    windowMinutes: 10,
    updatedAt: FieldValue.serverTimestamp(),
  });
});

// ---- Housekeeping --------------------------------------------------------------

/**
 * Hourly: really delete what has expired (the apps already hide it), and make sure
 * the app managers listed in lib/admins.js carry the admin claim.
 *
 * `users` held the notification tokens of earlier Android versions. The app sends
 * no notifications any more and the rules refuse new tokens, so whatever is left
 * there is deleted.
 *
 * Statistics entries from before onControlLogCreated existed (or that it missed)
 * lose their id here.
 */
exports.cleanup = onSchedule({ schedule: "every 60 minutes", timeoutSeconds: 300 }, async () => {
  await syncAdminClaims(admin.auth(), logger);
  const ago = (ms) => Timestamp.fromMillis(Date.now() - ms);
  const n = {
    presence: await deleteWhere(db.collection("presence").where("lastSeen", "<", ago(HOUR))),
    rateLimits: await deleteWhere(db.collection("rate_limits").where("updatedAt", "<", ago(2 * HOUR))),
    reports: await deleteWhere(db.collection("reports").where("expiresAt", "<", ago(24 * HOUR))),
    delayVotes: await deleteWhere(db.collection("delay_reports").where("expiresAt", "<", ago(24 * HOUR))),
    owners: await deleteWhere(db.collection("doc_owners").where("expiresAt", "<", ago(24 * HOUR))),
    moderation: await deleteWhere(db.collection("trip_moderation").where("dismissedAt", "<", ago(24 * HOUR))),
    oldPushTokens: await deleteWhere(db.collection("users")),
    logIdsRemoved: await forEachBatch(
      db.collection("control_log").orderBy("uid"),
      (b, ref) => b.update(ref, { uid: FieldValue.delete() })
    ),
  };
  logger.info("cleanup", n);
});

/**
 * Erases everything tied to one pseudonymous id. The inspection statistics hold
 * no id (see onControlLogCreated); an entry that still has one loses it here.
 * Finally the anonymous account itself is deleted. Returns what was removed.
 */
/** Deletes the reports and delay votes of [uid] that are stored under a hidden name. */
async function deleteHiddenEntries(uid) {
  let total = 0;
  for (;;) {
    const snap = await db.collection("doc_owners").where("uid", "==", uid).limit(200).get();
    if (snap.empty) break;
    const batch = db.batch();
    snap.docs.forEach((o) => {
      batch.delete(db.collection(o.get("collection")).doc(o.get("doc")));
      batch.delete(o.ref);
    });
    await batch.commit();
    total += snap.size;
    if (snap.size < 200) break;
  }
  return total;
}

async function eraseUser(uid) {
  const n = {
    // The two queries by id find what is still stored under an old name.
    hiddenEntries: await deleteHiddenEntries(uid),
    reports: await deleteWhere(db.collection("reports").where("uid", "==", uid), 10000),
    delayVotes: await deleteWhere(db.collection("delay_reports").where("uid", "==", uid), 10000),
    controlLog: await forEachBatch(
      db.collection("control_log").where("uid", "==", uid),
      (b, ref) => b.update(ref, { uid: FieldValue.delete() }),
      10000
    ),
  };
  const mods = await db.collection("trip_moderation").where("dismissedUids", "array-contains", uid).get();
  if (!mods.empty) {
    const batch = db.batch();
    mods.docs.forEach((d) => batch.update(d.ref, { dismissedUids: FieldValue.arrayRemove(uid) }));
    await batch.commit();
  }
  await Promise.all(["presence", "rate_limits", "users"].map((c) => db.collection(c).doc(uid).delete()));
  try {
    await admin.auth().deleteUser(uid);
  } catch (e) {
    logger.warn(`eraseUser: could not delete auth user: ${e.message}`);
  }
  return n;
}

/** "Delete my data" in the apps; the app then signs in with a fresh id. */
exports.deleteMyData = onCall({ ...CALLABLE, timeoutSeconds: 120 }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
  const n = await eraseUser(request.auth.uid);
  logger.info("deleteMyData", n);
  return { ok: true, ...n };
});

const INACTIVE_DAYS = 30;
const PURGE_MAX_PER_RUN = 300;

/**
 * Daily: remove anonymous ids nobody has used for 30 days, with everything tied
 * to them (same as "delete my data"). Admins are never touched. If such a phone
 * opens the app again later, it simply gets a new id.
 */
exports.purgeInactiveUsers = onSchedule(
  { schedule: "15 4 * * *", timeZone: "Europe/Berlin", timeoutSeconds: 540 },
  async () => {
    const now = Date.now();
    const stale = [];
    let total = 0;
    let pageToken;
    do {
      const page = await admin.auth().listUsers(1000, pageToken);
      total += page.users.length;
      for (const user of page.users) {
        if (ADMIN_UIDS.includes(user.uid)) continue;
        if (stale.length < PURGE_MAX_PER_RUN && isStaleAnonymous(user, now, INACTIVE_DAYS * 24 * HOUR)) {
          stale.push(user.uid);
        }
      }
      pageToken = page.pageToken;
    } while (pageToken);

    for (const uid of stale) await eraseUser(uid);
    logger.info(`purgeInactiveUsers: ${total} ids, removed ${stale.length} unused for ${INACTIVE_DAYS} days`);
  }
);
