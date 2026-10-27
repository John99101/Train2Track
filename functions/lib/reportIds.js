/**
 * The names of report and delay vote documents.
 *
 * Every rider has at most one document per journey in `reports`, one in
 * `delay_reports` and one in `occupancy_votes` (hidden names only). Every app that shows the journey loads these documents, so
 * their name and content must not show who wrote them.
 *
 *  - Hidden name (apps from 0.5.0 on): the SHA-256 hash, as lowercase hex, of
 *    "<uid>__<trip key>". It is different on every journey, so two entries of one
 *    rider cannot be linked, and it cannot be turned back into the id. The
 *    document's `uid` field holds the same hash, never the id.
 *    In the same write the app stores an owner note in `doc_owners`, named
 *    "<collection>__<hidden name>" and holding the id. No app can read that
 *    collection; the server needs it for the spam limit, moderation and "delete
 *    my data". The rules refuse a hidden entry without its note.
 *  - Old name (apps up to 0.4.0): "<uid>__<trip key>" with the id in the `uid`
 *    field. The server renames such a document as soon as it arrives and writes
 *    the owner note itself.
 *
 * firestore.rules and both apps build exactly the same names.
 */
const crypto = require("crypto");

/** A trip id made safe for a document name, as every app version does it. */
const tripKey = (tripId) => String(tripId).replace(/[^A-Za-z0-9_-]/g, "-");

const legacyDocId = (uid, tripId) => `${uid}__${tripKey(tripId)}`;

const hiddenDocId = (uid, tripId) =>
  crypto.createHash("sha256").update(legacyDocId(uid, tripId), "utf8").digest("hex");

/** The name of the owner note for the entry [docId] of [collection]. */
const ownerNoteId = (collection, docId) => `${collection}__${docId}`;

/**
 * Who wrote the document [docId] with [data], as far as it can be proven:
 *   { uid, legacy: true }   an old name, which contains the id
 *   { uid, legacy: false }  a hidden name that fits the id in its owner note
 *   null                    neither
 * [noteUid] is the id in the entry's owner note, if there is one.
 */
function ownerOf(docId, data, noteUid) {
  if (!data || typeof data.tripId !== "string" || !data.tripId) return null;
  if (typeof data.uid === "string" && data.uid && docId === legacyDocId(data.uid, data.tripId)) {
    return { uid: data.uid, legacy: true };
  }
  if (typeof noteUid === "string" && noteUid && docId === hiddenDocId(noteUid, data.tripId)) {
    return { uid: noteUid, legacy: false };
  }
  return null;
}

module.exports = { tripKey, legacyDocId, hiddenDocId, ownerNoteId, ownerOf };
