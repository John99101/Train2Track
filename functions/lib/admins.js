/**
 * The app managers. These anonymous ids get the `admin` claim, which is what shows
 * the Admin tab in the app and unlocks the admin functions (moderateTrip,
 * controlStats, rebuildRoutes). Nobody logs in: the role belongs to the
 * app installation with that id.
 *
 * The list of the live Train2Track service is not published. For your own
 * service: put the id from Firebase console > Authentication > Users here and
 * deploy the functions. The claim is set within the hour (see `cleanup`), and the
 * app shows the Admin tab at its next start. An id changes when the app is
 * reinstalled or "Delete my data" is used on that phone; then update this list.
 * Removing an id here does not take the claim away: remove the user in the
 * console for that.
 */
const ADMIN_UIDS = [];

/** Makes sure every id in ADMIN_UIDS carries the admin claim. Returns the ids it changed. */
async function syncAdminClaims(auth, logger) {
  const changed = [];
  for (const uid of ADMIN_UIDS) {
    try {
      const user = await auth.getUser(uid);
      if (user.customClaims && user.customClaims.admin === true) continue;
      await auth.setCustomUserClaims(uid, { ...(user.customClaims || {}), admin: true });
      changed.push(uid);
      logger.info(`admin claim set for ${uid}`);
    } catch (e) {
      // e.g. the id no longer exists (app reinstalled): nothing to do until the list is updated.
      logger.warn(`admin ${uid}: ${e.message}`);
    }
  }
  return changed;
}

module.exports = { ADMIN_UIDS, syncAdminClaims };
