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
///  - the visible area is reported (debounced) so only nearby vehicles are built;
///  - the badges follow the zoom like on Android: full badge when close, a small
///    badge in between, a dot without a label when far. A reported vehicle keeps the
///    full badge at every zoom, is never faded, has a red ring and a larger "!", and
///    is drawn on top (then S-Bahn and U-Bahn, rail, tram, bus).
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
        context.coordinator.tier = badgeTier(longitudeSpan: map.region.span.longitudeDelta)
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
        /// The badge size for the current zoom (see `badgeTier`).
        var tier: BadgeTier = .mid

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
                    if styleChanged, let v = map.view(for: a) as? TrainBadgeAnnotationView { v.apply(a, tier: tier) }
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
            (v as? TrainBadgeAnnotationView)?.apply(a, tier: tier)
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

        /// Called while the map moves or zooms: the badges change size as soon as the
        /// zoom crosses a step, not only when the gesture ends.
        func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
            let next = badgeTier(longitudeSpan: mapView.region.span.longitudeDelta)
            guard next != tier else { return }
            tier = next
            for a in annotations.values {
                (mapView.view(for: a) as? TrainBadgeAnnotationView)?.apply(a, tier: next)
            }
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
/// pill for bus/tram, in the line colour, in the size the zoom asks for. A reported
/// vehicle has the full size at every zoom, a red ring and a red "!", is never faded
/// and lies on top. Otherwise real-time trains are near solid and schedule-only
/// trains are faded.
final class TrainBadgeAnnotationView: MKAnnotationView {
    static let reuseId = "train"

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        canShowCallout = false
        collisionMode = .circle
        displayPriority = .required
    }

    required init?(coder: NSCoder) { fatalError() }

    func apply(_ a: TrainAnnotation, tier: BadgeTier) {
        let t = a.train
        // A reported vehicle keeps the full badge at every zoom.
        let size: BadgeTier = a.flagged ? .near : tier
        image = BadgeRenderer.image(label: t.label, color: UIColor(t.displayColor),
                                    flagged: a.flagged, elongated: t.mode == "BUS" || t.mode == "TRAM", size: size)
        alpha = a.flagged ? 1 : (t.isRealtime ? 0.85 : 0.4)
        // Higher is drawn on top: bus, tram, rail, S-Bahn and U-Bahn, reported.
        zPriority = MKAnnotationViewZPriority(rawValue: MKAnnotationViewZPriority.defaultUnselected.rawValue
                                              + Float(drawRank(mode: t.mode, flagged: a.flagged)) * 10)
        accessibilityLabel = t.label
    }
}

/// Renders and caches the badge images (cheap per marker, expensive per render).
/// Same shapes and sizes as Android's `createBadgeBitmap` and `createDotBitmap`.
enum BadgeRenderer {
    private static var cache: [String: UIImage] = [:]
    private static let reportRed = UIColor(red: 220 / 255, green: 40 / 255, blue: 40 / 255, alpha: 1)
    /// Room for the red ring and for the "!" that sticks out of the top right corner.
    private static let reportMargin: CGFloat = 13.5
    /// The image of a dot is larger than the dot, so the dot can still be tapped.
    private static let dotSide: CGFloat = 20

    /// A badge in the size `size` asks for: full (`.near`), small (`.mid`) or a dot
    /// without a label (`.far`). A reported vehicle is always asked for in full size.
    static func image(label: String, color: UIColor, flagged: Bool, elongated: Bool, size: BadgeTier) -> UIImage {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, al: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &al)
        let rgb = "\(Int(r * 255))-\(Int(g * 255))-\(Int(b * 255))"
        // A dot shows no label, so all lines of one colour share one image.
        let key = size == .far ? "dot|\(rgb)|\(elongated)" : "\(rgb)|\(label)|\(flagged)|\(elongated)|\(size.rawValue)"
        if let img = cache[key] { return img }
        let img = size == .far
            ? dot(color: color, elongated: elongated)
            : badge(label: label, color: color, rgb: (r, g, b), flagged: flagged, elongated: elongated, small: size == .mid)
        cache[key] = img
        return img
    }

    private static func badge(label: String, color: UIColor, rgb: (CGFloat, CGFloat, CGFloat),
                              flagged: Bool, elongated: Bool, small: Bool) -> UIImage {
        let text = String(label.prefix(elongated ? 5 : 4))
        let font = UIFont.systemFont(ofSize: small ? 7 : 9, weight: .bold)
        let textSize = (text as NSString).size(withAttributes: [.font: font])
        let height: CGFloat = small ? 16 : 22
        let padH: CGFloat = elongated ? (small ? 5.5 : 8) : (small ? 2.5 : 3.5)
        let shapeW = max(textSize.width + 2 * padH, elongated ? height * 1.6 : height)
        // Room around the shape: for its outline, or for the ring and the "!" of a report.
        let margin: CGFloat = flagged ? reportMargin : 3
        let size = CGSize(width: shapeW + 2 * margin, height: height + 2 * margin)
        let corner: CGFloat = elongated ? height / 2 : (small ? 4.5 : 6)
        let outline: CGFloat = small ? 1.2 : 1.5

        return UIGraphicsImageRenderer(size: size).image { _ in
            let rect = CGRect(x: margin, y: margin, width: shapeW, height: height)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: corner)
            color.setFill(); path.fill()
            UIColor.white.setStroke(); path.lineWidth = outline; path.stroke()
            let luminance = 0.299 * rgb.0 + 0.587 * rgb.1 + 0.114 * rgb.2
            let textLabelColor: UIColor = luminance > 0.6 ? .black : .white   // same rule as `contrastingLabel`
            let drawAttrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: textLabelColor]
            (text as NSString).draw(at: CGPoint(x: rect.midX - textSize.width / 2, y: rect.midY - textSize.height / 2),
                                    withAttributes: drawAttrs)
            guard flagged else { return }

            // A red ring around the whole badge, just outside its white outline.
            let ringWidth: CGFloat = 2.5
            let out = outline / 2 + ringWidth / 2
            let ring = UIBezierPath(roundedRect: rect.insetBy(dx: -out, dy: -out), cornerRadius: corner + out)
            reportRed.setStroke(); ring.lineWidth = ringWidth; ring.stroke()

            // The "!" on the top right corner, pushed outwards so it hides little of the label.
            let br: CGFloat = 9
            let centre = CGPoint(x: rect.maxX + 3, y: rect.minY - 3)
            let circle = UIBezierPath(ovalIn: CGRect(x: centre.x - br, y: centre.y - br, width: 2 * br, height: 2 * br))
            reportRed.setFill(); circle.fill()
            UIColor.white.setStroke(); circle.lineWidth = 1.5; circle.stroke()
            let bang = "!" as NSString
            let bangAttrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 13, weight: .heavy),
                                                           .foregroundColor: UIColor.white]
            let bs = bang.size(withAttributes: bangAttrs)
            bang.draw(at: CGPoint(x: centre.x - bs.width / 2, y: centre.y - bs.height / 2), withAttributes: bangAttrs)
        }
    }

    /// A vehicle on a map that is zoomed far out: a dot in the line's colour, smaller
    /// for bus and tram.
    private static func dot(color: UIColor, elongated: Bool) -> UIImage {
        let radius: CGFloat = elongated ? 3.5 : 5
        return UIGraphicsImageRenderer(size: CGSize(width: dotSide, height: dotSide)).image { _ in
            let rect = CGRect(x: dotSide / 2 - radius, y: dotSide / 2 - radius, width: 2 * radius, height: 2 * radius)
            let path = UIBezierPath(ovalIn: rect)
            color.setFill(); path.fill()
            UIColor.white.setStroke(); path.lineWidth = 1.2; path.stroke()
        }
    }
}
