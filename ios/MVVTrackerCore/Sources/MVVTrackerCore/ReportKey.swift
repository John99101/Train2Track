import Foundation
import CryptoKit

/// The shared Firestore report contract. These MUST stay identical to Android
/// (`data/repository/ReportIds.kt`, `UserReportBackend.kt`, `EventRepository.kt`),
/// to the server (`functions/lib/reportIds.js`) and to `firestore.rules`: the
/// document names, field names, TTL and corroboration threshold are the
/// cross-platform interface. Changing any of them silently breaks Android⇄iOS
/// report sharing.
///
/// Every app that shows a train loads the reports on it, so a report must not show
/// who wrote it. Its name is a hash of the device's random id and the trip
/// (`hiddenDocumentId`): different on every train, so two entries of one device
/// cannot be linked, and impossible to turn back into the id. The entry's `uid`
/// field holds the same hash, never the id. The id itself goes into an owner note
/// in `ownersCollection`, which no app can read; the server needs it for the spam
/// limit, moderation and "delete my data".
public enum ReportKey {
    public static let collection = "reports"
    public static let ownersCollection = "doc_owners"
    public static let functionsRegion = "europe-west3"

    /// Report lifetime: 90 minutes. Port of `UserReportBackend.REPORT_TTL_MILLIS`.
    public static let reportTtlMillis: Int64 = 90 * 60 * 1000

    /// A train is flagged with "!" once at least this many active reports exist.
    /// Port of Android's `MIN_CORROBORATIONS`.
    public static let minCorroborations = 1

    /// A trip id made safe for a document name: everything outside
    /// `[A-Za-z0-9_-]` becomes "-". Port of `ReportIds.tripKey`.
    public static func tripKey(_ tripId: String) -> String {
        String(tripId.map { ch -> Character in
            let ok = ch.isASCII && (ch.isLetter || ch.isNumber || ch == "_" || ch == "-")
            return ok ? ch : "-"
        })
    }

    /// The document name of app versions up to 0.4.0: `"<uid>__<trip key>"`. It
    /// shows the id, so it is no longer written. It is still the text that
    /// `hiddenDocumentId` hashes.
    public static func documentId(uid: String, tripId: String) -> String {
        "\(uid)__\(tripKey(tripId))"
    }

    /// The name of the device's entry on a train: SHA-256 of `"<uid>__<trip key>"`
    /// as lowercase hex. Port of `ReportIds.hiddenDocId`.
    public static func hiddenDocumentId(uid: String, tripId: String) -> String {
        let digest = SHA256.hash(data: Data(documentId(uid: uid, tripId: tripId).utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// The name of the owner note for the entry `documentId` of `collection`.
    /// Port of `ReportIds.ownerNoteId`.
    public static func ownerNoteId(collection: String, documentId: String) -> String {
        "\(collection)__\(documentId)"
    }
}
