import Foundation
import Combine
import CoreLocation
import MVVTrackerCore

/// Resolves the user's nearest supported city from a coarse location so the
/// Feed/Map can preselect it and follow the user to another city (until they pick
/// one manually). Port of `LocationCityProvider.kt`, but event driven.
///
/// Location is only ever requested behind the precise-location consent
/// (`requestIfConsented`), and only used to center the map / preselect the Feed —
/// it is never sent anywhere. Callbacks arrive on the main thread.
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    /// The nearest supported city to the latest fix, or nil (no permission / no
    /// fix / not within 60 km of any city).
    @Published private(set) var nearestCity: GermanCity?

    private let manager = CLLocationManager()
    private var wantLocation = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyReduced
        // Android re-checks the nearest city every 8 s while the app is open; here
        // the system reports a new fix only after a real move, which is cheaper.
        manager.distanceFilter = 1_000
    }

    /// Follows the user's coarse location while the precise-location consent is
    /// on (prompts for permission the first time); stops when it is turned off.
    /// Updates pause by themselves while the app is in the background.
    func requestIfConsented(_ consented: Bool) {
        wantLocation = consented
        guard consented else {
            manager.stopUpdatingLocation()
            nearestCity = nil
            return
        }
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        if wantLocation, status == .authorizedWhenInUse || status == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let here = locations.last else { return }
        nearestCity = CityLocator.nearestCity(lat: here.coordinate.latitude,
                                              lon: here.coordinate.longitude)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Keep whatever we had; the Feed just stays on its current city.
    }
}
