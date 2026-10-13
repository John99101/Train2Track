/**
 * Wipe the persistent control-analytics log (`control_log` collection).
 *
 * For the testing phase: run locally to delete everything the app has logged so
 * far. Requires the Admin service-account key (functions/serviceAccountKey.json,
 * git-ignored) - the same one used by setAdmin.js.
 *
 * Usage:
 *   cd functions
 *   node clearAnalytics.js            # deletes ALL control_log docs
 *   node clearAnalytics.js --count    # just prints how many are there
 */
const admin = require("firebase-admin");
const serviceAccount = require("./serviceAccountKey.json");

admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
const db = admin.firestore();

const COLLECTION = "control_log";
const BATCH = 400;

async function count() {
  const snap = await db.collection(COLLECTION).count().get();
  return snap.data().count;
}

async function deleteAll() {
  let total = 0;
  while (true) {
    const snap = await db.collection(COLLECTION).limit(BATCH).get();
    if (snap.empty) break;
    const batch = db.batch();
    snap.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit();
    total += snap.size;
    process.stdout.write(`deleted ${total}\r`);
  }
  return total;
}

(async () => {
  try {
    if (process.argv.includes("--count")) {
      console.log(`${COLLECTION}: ${await count()} entries`);
    } else {
      const n = await deleteAll();
      console.log(`\nDone. Deleted ${n} entries from ${COLLECTION}.`);
    }
    process.exit(0);
  } catch (e) {
    console.error(e);
    process.exit(1);
  }
})();
