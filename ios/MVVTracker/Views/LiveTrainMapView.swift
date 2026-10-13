import SwiftUI
import MapKit
import MVVTrackerCore

/// The live Apple map (MapKit): line routes under gliding train badges. Port of
/// Android's `LiveTrainMap.kt`, keeping Apple Maps:
///  - markers are reused across position ticks; each tick animates every marker's
///    coordinate linearly over one tick (1 s) so trains glide instead of hopping,
///    and snaps instead when a train jumps further than ~2 km (new data);
///  - routes are persistent overlays (added once per shown line, dropped when the
///    line is no longer shown), styled per mode: S-Bahn solid, U-Bahn dashed,
///    Tram dotted, Bus dash-dot;
///  - the visible area is reported (debounced) so only nearby vehicles are built.
struct LiveTrainMapView: UIViewRepresentable {
    let trains: [TrainPosition]
    let routes: [String: [GeoPoint]]
    let flaggedTripIds: Set<String>
    let showUserLocation: Bool
    /// Changes whenever the map should jump to a (new) city centre.
    let center: CLLocationCoordinate2D
    let centerToken: String
    let onBoundsChange: (_ north: Double, _ south: Double, _ east: Double, _ west: Double) -> Void
    let onTrainTap: (TrainPosition) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsCompass = true
        map.pointOfInterestFilter = .excludingAll
        map.register(TrainBadgeAnnotationView.self, forAnnotationViewWithReuseIdentifier: TrainBadgeAnnotationView.reuseId)
        context.coordinator.recenter(map, on: center, token: centerToken, animated: false)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let c = context.coordinator
        c.parent = self
        c.recenter(map, on: center, token: centerToken, animated: true)
        if map.showsUserLocation != showUserLocation { map.showsUserLocation = showUserLocation }
        c.syncRoutes(map)
        c.syncTrains(map)
    }

    // MARK: - Coordinator

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: LiveTrainMapView
        private var centeredToken: String?
        private var annotations: [String: TrainAnnotation] = [:]
        private var routeOverlays: [String: RouteOverlay] = [:]
        private var boundsWork: DispatchWorkItem?

        /// One glide spans one position tick, so consecutive glides join into steady motion.
        private let glideSeconds = Double(LiveMapConfig.tickMillis) / 1000
        /// ~2 km: further than a train moves in a tick, so this is a data jump, not motion.
        private let snapDegrees = 0.02

        init(_ parent: LiveTrainMapView) { self.parent = parent }

        func recenter(_ map: MKMapView, on center: CLLocationCoordinate2D, token: String, animated: Bool) {
            guard centeredToken != token else { return }
            centeredToken = token
            let region = MKCoordinateRegion(center: center, span: MKCoordinateSpan(latitudeDelta: 0.14, longitudeDelta: 0.14))
            map.setRegion(region, animated: animated)
            reportBounds(map, delay: 0)
        }

        // ROUTES (persistent): one full line per shown line that has a route.
        func syncRoutes(_ map: MKMapView) {
            var wanted: [String: TrainPosition] = [:]
            for t in parent.trains {
                let k = routeKey(mode: t.mode, label: t.label)
                if wanted[k] == nil, parent.routes[k] != nil { wanted[k] = t }
            }
            for (k, rep) in wanted where routeOverlays[k] == nil {
                let pts = parent.routes[k]!.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                let overlay = RouteOverlay(coordinates: pts, count: pts.count)
                overlay.color = UIColor(rep.displayColor)
                overlay.mode = rep.mode
                routeOverlays[k] = overlay
                map.addOverlay(overlay, level: .aboveRoads)
            }
            for k in Set(routeOverlays.keys).subtracting(wanted.keys) {
                if let o = routeOverlays.removeValue(forKey: k) { map.removeOverlay(o) }
            }
        }

        // TRAIN MARKERS (reused): move existing, add new, drop gone.
        func syncTrains(_ map: MKMapView) {
            var shown: [String: TrainPosition] = [:]
            for t in parent.trains { shown[t.id] = t }

            var toAdd: [TrainAnnotation] = []
            var glides: [(TrainAnnotation, CLLocationCoordinate2D)] = []
            for (key, train) in shown {
                let flagged = train.tripId.map(parent.flaggedTripIds.contains) ?? false
                let target = CLLocationCoordinate2D(latitude: train.latitude, longitude: train.longitude)
                if let a = annotations[key] {
                    let from = a.coordinate
                    let styleChanged = a.flagged != flagged || a.train.label != train.label
                        || a.train.colorHex != train.colorHex || a.train.isRealtime != train.isRealtime
                    a.train = train
                    a.flagged = flagged
                    if abs(from.latitude - target.latitude) < snapDegrees && abs(from.longitude - target.longitude) < snapDegrees {
                        glides.append((a, target))
                    } else {
                        a.coordinate = target   // big jump: snap instead of sliding across
                    }
                    if styleChanged, let v = map.view(for: a) as? TrainBadgeAnnotationView { v.apply(a) }
                } else {
                    let a = TrainAnnotation(train: train, flagged: flagged)
                    annotations[key] = a
                    toAdd.append(a)
                }
            }
            let gone = Set(annotations.keys).subtracting(shown.keys)
            let toRemove = gone.compactMap { annotations.removeValue(forKey: $0) }
            if !toRemove.isEmpty { map.removeAnnotations(toRemove) }
            if !toAdd.isEmpty { map.addAnnotations(toAdd) }
            if !glides.isEmpty {
                UIView.animate(withDuration: glideSeconds, delay: 0,
                               options: [.curveLinear, .beginFromCurrentState, .allowUserInteraction]) {
                    for (a, target) in glides { a.coordinate = target }
                }
            }
        }

        // MARK: MKMapViewDelegate

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let a = annotation as? TrainAnnotation else { return nil }   // user location: default dot
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: TrainBadgeAnnotationView.reuseId, for: a)
            (v as? TrainBadgeAnnotationView)?.apply(a)
            return v
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            guard let a = view.annotation as? TrainAnnotation else { return }
            mapView.deselectAnnotation(a, animated: false)
            parent.onTrainTap(a.train)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let route = overlay as? RouteOverlay else { return MKOverlayRenderer(overlay: overlay) }
            return RouteRenderer(route: route, dash: Self.dash(for: route.mode))
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            reportBounds(mapView, delay: 0.3)
        }

        private func reportBounds(_ map: MKMapView, delay: TimeInterval) {
            boundsWork?.cancel()
            let work = DispatchWorkItem { [weak self, weak map] in
                guard let self, let map else { return }
                let r = map.region
                self.parent.onBoundsChange(
                    r.center.latitude + r.span.latitudeDelta / 2,
                    r.center.latitude - r.span.latitudeDelta / 2,
                    r.center.longitude + r.span.longitudeDelta / 2,
                    r.center.longitude - r.span.longitudeDelta / 2
                )
            }
            boundsWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        }

        /// Stroke pattern per transport mode (nil = solid), like Android's `dashForMode`.
        static func dash(for mode: String) -> [CGFloat]? {
            switch mode {
            case "SUBWAY": return [11, 7]          // U-Bahn: dashed
            case "TRAM": return [1, 7]             // Tram: dotted (round cap)
            case "BUS": return [11, 6, 1, 6]       // Bus: dash-dot
            default: return nil                    // S-Bahn & other rail: solid
            }
        }
    }
}

/// One train on the map. `coordinate` is KVO-animatable, so MapKit glides it.
final class TrainAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var train: TrainPosition
    var flagged: Bool

    init(train: TrainPosition, flagged: Bool) {
        self.train = train
        self.flagged = flagged
        self.coordinate = CLLocationCoordinate2D(latitude: train.latitude, longitude: train.longitude)
    }
}

/// A line route with its colour and mode (for the dash pattern).
final class RouteOverlay: MKPolyline {
    var color: UIColor = .gray
    var mode: String = ""
}

/// Draws a route with a fixed width and dash pattern in SCREEN points at every
/// zoom (5 pt, like Android's 5 dp). MKPolylineRenderer scales dashed lines
/// differently from solid ones, so the stroke is set up here explicitly.
final class RouteRenderer: MKOverlayPathRenderer {
    private let route: RouteOverlay
    private let dash: [CGFloat]?
    private static let widthPoints: CGFloat = 5

    init(route: RouteOverlay, dash: [CGFloat]?) {
        self.route = route
        self.dash = dash
        super.init(overlay: route)
    }

    override func createPath() {
        let path = CGMutablePath()
        let pts = route.points()
        for i in 0..<route.pointCount {
            let p = point(for: pts[i])
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        self.path = path
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        guard let path else { return }
        // Renderer units are map points; divide by the zoom scale to get screen points.
        context.addPath(path)
        context.setStrokeColor(route.color.withAlphaComponent(200.0 / 255.0).cgColor)
        context.setLineWidth(Self.widthPoints / zoomScale)
        context.setLineCap(.round)
        context.setLineJoin(.round)
        if let dash {
            context.setLineDash(phase: 0, lengths: dash.map { $0 / zoomScale })
        }
        context.strokePath()
    }
}

/// The badge view: a rounded square for rail (S-Bahn, U-Bahn, other trains) or a
/// pill for bus/tram, in the line colour, with a red "!" corner when reported.
/// Real-time trains are near solid; schedule-only trains are faded.
final class TrainBadgeAnnotationView: MKAnnotationView {
    static let reuseId = "train"

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        canShowCallout = false
        collisionMode = .circle
        displayPriority = .required
    }

    required init?(coder: NSCoder) { fatalError() }

    func apply(_ a: TrainAnnotation) {
        let t = a.train
        image = BadgeRenderer.image(label: t.label, color: UIColor(t.displayColor),
                                    flagged: a.flagged, elongated: t.mode == "BUS" || t.mode == "TRAM")
        alpha = t.isRealtime ? 0.85 : 0.4
        accessibilityLabel = t.label
    }
}

/// Renders and caches the badge images (cheap per marker, expensive per render).
enum BadgeRenderer {
    private static var cache: [String: UIImage] = [:]

    static func image(label: String, color: UIColor, flagged: Bool, elongated: Bool) -> UIImage {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, al: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &al)
        let key = "\(Int(r * 255))-\(Int(g * 255))-\(Int(b * 255))|\(label)|\(flagged)|\(elongated)"
        if let img = cache[key] { return img }

        let text = String(label.prefix(elongated ? 5 : 4))
        let font = UIFont.systemFont(ofSize: 9, weight: .bold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
        let textSize = (text as NSString).size(withAttributes: attrs)
        let height: CGFloat = 22
        let padH: CGFloat = elongated ? 8 : 5
        let shapeW = elongated ? max(textSize.width + 2 * padH, height * 1.6) : max(height, textSize.width + 2 * padH)
        let margin: CGFloat = 3   // room for the "!" corner badge to overflow
        let size = CGSize(width: shapeW + 2 * margin, height: height + 2 * margin)

        let img = UIGraphicsImageRenderer(size: size).image { _ in
            let rect = CGRect(x: margin, y: margin, width: shapeW, height: height)
            let corner = elongated ? height / 2 : 6
            let path = UIBezierPath(roundedRect: rect, cornerRadius: corner)
            color.setFill(); path.fill()
            UIColor.white.setStroke(); path.lineWidth = 1.5; path.stroke()
            let luminance = 0.299 * r + 0.587 * g + 0.114 * b
            let textLabelColor: UIColor = luminance > 0.6 ? .black : .white   // same rule as `contrastingLabel`
            let drawAttrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: textLabelColor]
            (text as NSString).draw(at: CGPoint(x: rect.midX - textSize.width / 2, y: rect.midY - textSize.height / 2),
                                    withAttributes: drawAttrs)
            if flagged {
                let br: CGFloat = 7
                let circle = UIBezierPath(ovalIn: CGRect(x: size.width - 2 * br, y: 0, width: 2 * br, height: 2 * br))
                UIColor(red: 220 / 255, green: 40 / 255, blue: 40 / 255, alpha: 1).setFill(); circle.fill()
                UIColor.white.setStroke(); circle.lineWidth = 1.5; circle.stroke()
                let bang = "!" as NSString
                let bangAttrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10, weight: .heavy),
                                                               .foregroundColor: UIColor.white]
                let bs = bang.size(withAttributes: bangAttrs)
                bang.draw(at: CGPoint(x: size.width - br - bs.width / 2, y: br - bs.height / 2), withAttributes: bangAttrs)
            }
        }
        cache[key] = img
        return img
    }
}
