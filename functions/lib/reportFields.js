/**
 * Which fields of a report / delay vote belong to the server, and how to tell the
 * server's own follow up write from a rider's write (see onReportWritten).
 */

// Fields only the server writes; clients may not set them (see firestore.rules).
const SERVER_FIELDS = new Set(["throttled", "dismissed", "verified", "moderatedAt"]);

/**
 * The apps load reports per city (`city` = a city id or "other", set by the app).
 * App versions from before that send no city: the server files their documents
 * under this value, which every app also listens to, so old and new versions keep
 * seeing each other's reports.
 */
const CITY_UNKNOWN = "unknown";

const sameValue = (x, y) =>
  x && typeof x.isEqual === "function" ? x.isEqual(y) : JSON.stringify(x) === JSON.stringify(y);

/** true when [before] -> [after] only changed what the server itself writes. */
function onlyServerFieldsChanged(before, after) {
  if (!before) return false;
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  for (const k of keys) {
    if (SERVER_FIELDS.has(k)) continue;
    // Our own "no city sent" marker being added is not a rider's write.
    if (k === "city" && before.city === undefined && after.city === CITY_UNKNOWN) continue;
    if (!sameValue(before[k], after[k])) return false;
  }
  return true;
}

module.exports = { SERVER_FIELDS, CITY_UNKNOWN, onlyServerFieldsChanged, sameValue };
