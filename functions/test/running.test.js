// "An Bord" reports only count on journeys that are running now (lib/runningTrips.js
// and tripTimes in lib/transitous.js): the start and end of the reported journey with
// 15 minutes either side, one question per journey for many reports, "not found"
// means not running, and a timetable service that does not answer throws (the report
// then counts, see onRiderWrite in index.js).
//
// Nothing here reaches transitous: fetch is replaced by a stand-in.
//
// From the repo root:
//   node functions/test/running.test.js
const assert = require("assert");
const T = require("../lib/transitous");
const { createRunningCheck } = require("../lib/runningTrips");

const MINUTE = 60 * 1000;
const results = [];
async function test(name, fn) {
  try {
    await fn();
    results.push(`ok    ${name}`);
  } catch (e) {
    results.push(`FAIL  ${name}: ${e.message}`);
    process.exitCode = 1;
  }
}

// ---- stand-in for transitous ------------------------------------------------------
const now = Date.parse("2026-11-05T09:00:00Z");
let clock = now;
let journeys = {};     // tripId -> [startMs, endMs]
let status = null;     // when set, every answer has this HTTP status
let asked = [];
global.fetch = async (url) => {
  const u = new URL(url);
  const tripId = u.searchParams.get("tripId");
  asked.push(tripId);
  const answer = (code, body) => ({ ok: code === 200, status: code, json: async () => body });
  if (status) return answer(status, {});
  if (u.pathname !== "/api/v1/trip") return answer(404, {});
  if (tripId === "20261105_bad") return answer(400, { error: "invalid tripId" });
  const j = journeys[tripId];
  if (!j) return answer(404, { error: `trip not found: tripId=${tripId}` });
  return answer(200, { startTime: new Date(j[0]).toISOString(), endTime: new Date(j[1]).toISOString(), legs: [] });
};
function reset() {
  clock = now;
  status = null;
  asked = [];
  journeys = {
    "20261105_running": [now - 20 * MINUTE, now + 20 * MINUTE],
    "20261105_ended_just_now": [now - 60 * MINUTE, now - 10 * MINUTE],
    "20261105_ended_long_ago": [now - 120 * MINUTE, now - 16 * MINUTE],
    "20261105_starts_soon": [now + 10 * MINUTE, now + 60 * MINUTE],
    "20261105_starts_later": [now + 16 * MINUTE, now + 60 * MINUTE],
  };
}
const check = (options = {}) => createRunningCheck(T.tripTimes, { now: () => clock, ...options });

(async () => {
  await test("tripTimes: start and end of a journey, null when it does not exist", async () => {
    reset();
    assert.deepStrictEqual(await T.tripTimes("20261105_running"), { startMs: now - 20 * MINUTE, endMs: now + 20 * MINUTE });
    assert.strictEqual(await T.tripTimes("20261105_made_up"), null);
    assert.strictEqual(await T.tripTimes("20261105_bad"), null);
    assert.deepStrictEqual(asked, ["20261105_running", "20261105_made_up", "20261105_bad"]);
  });
  await test("tripTimes: a timetable service that does not answer throws", async () => {
    reset();
    status = 500;
    await assert.rejects(T.tripTimes("20261105_running"));
  });
  await test("running now, with 15 minutes before the start and after the end", async () => {
    reset();
    const runningNow = check();
    assert.strictEqual(await runningNow("20261105_running"), true);
    assert.strictEqual(await runningNow("20261105_ended_just_now"), true);
    assert.strictEqual(await runningNow("20261105_starts_soon"), true);
    assert.strictEqual(await runningNow("20261105_ended_long_ago"), false);
    assert.strictEqual(await runningNow("20261105_starts_later"), false);
    assert.strictEqual(await runningNow("20261105_made_up"), false);
  });
  await test("many reports on one journey ask once; after 10 minutes it is asked again", async () => {
    reset();
    const runningNow = check();
    for (let i = 0; i < 5; i++) assert.strictEqual(await runningNow("20261105_running"), true);
    assert.strictEqual(asked.length, 1);
    clock = now + 11 * MINUTE;
    await runningNow("20261105_running");
    assert.strictEqual(asked.length, 2);
  });
  await test("riders reporting one journey at the same moment share one question", async () => {
    reset();
    const runningNow = check();
    const answers = await Promise.all([1, 2, 3, 4, 5].map(() => runningNow("20261105_running")));
    assert.deepStrictEqual(answers, [true, true, true, true, true]);
    assert.strictEqual(asked.length, 1);
  });
  await test("a journey that has ended counts no more once it is asked again", async () => {
    reset();
    const runningNow = check();
    assert.strictEqual(await runningNow("20261105_running"), true);
    clock = now + 36 * MINUTE;   // 16 minutes after its end
    assert.strictEqual(await runningNow("20261105_running"), false);
  });
  await test("no answer throws and is not kept: the next report asks again", async () => {
    reset();
    const runningNow = check();
    status = 503;
    await assert.rejects(runningNow("20261105_running"));
    status = null;
    assert.strictEqual(await runningNow("20261105_running"), true);
    assert.strictEqual(asked.length, 2);
  });
  await test("a full memory starts over instead of growing", async () => {
    reset();
    const runningNow = check({ max: 2 });
    for (const id of ["20261105_running", "20261105_starts_soon", "20261105_ended_just_now", "20261105_running"]) {
      assert.strictEqual(await runningNow(id), true);
    }
    assert.strictEqual(asked.length, 4);
  });

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nFAILED" : "\nall passed");
})();
