import XCTest
@testable import MVVTrackerCore

/// The station answers of the server (functions/lib/stations.js) as the app reads them.
final class StationParserTests: XCTestCase {
    func testQueryIsNormalised() {
        XCTAssertEqual(StationParser.normalizeQuery("  Münchner   Freiheit "), "münchner freiheit")
        XCTAssertNil(StationParser.normalizeQuery("x"))
        XCTAssertNil(StationParser.normalizeQuery(String(repeating: "a", count: 41)))
    }

    func testStations() throws {
        let json = #"{"v":1,"city":"muenchen","stations":[["de:09162:2","Marienplatz","SUBURBAN,SUBWAY,BUS"],["","x",""]]}"#
        let list = try StationParser.stations(Data(json.utf8), cityId: "muenchen")
        XCTAssertEqual(list.count, 1)
        XCTAssertEqual(list[0].id, "de:09162:2")
        XCTAssertEqual(list[0].symbolModes, ["METRO", "SUBWAY", "BUS"])
    }

    func testBoard() throws {
        let json = #"""
        {"v":1,"t":1000,"name":"Marienplatz","deps":[
          ["S1","METRO","16c0e9","Flughafen",0,120,"1",1,"trip1"],
          ["S1","METRO","16c0e9","Flughafen",0,120,"1",1,"trip1"],
          ["U6","SUBWAY","0065ae","Garching",240,240,"",2,"trip2"]]}
        """#
        let board = try StationParser.board(Data(json.utf8), nowMs: 5)
        XCTAssertEqual(board.name, "Marienplatz")
        XCTAssertEqual(board.departures.count, 2)          // the repeat is dropped
        XCTAssertEqual(board.departures[0].delayMinutes, 2)
        XCTAssertEqual(board.departures[0].plannedAtMs, 1_000_000)
        XCTAssertTrue(board.departures[0].realtime)
        XCTAssertTrue(board.departures[1].cancelled)
    }

    func testMapStations() throws {
        let json = #"""
        {"v":1,"city":"muenchen","complete":false,"stations":[
          ["Marienplatz",48.13706,11.57539,3,"de-DELFI_de:09162:2"],
          ["Kein Knopf",48.1,11.5,0,"x1"],
          ["",48.1,11.5,8,"x2"]]}
        """#
        let parsed = try StationParser.mapStations(Data(json.utf8))
        XCTAssertFalse(parsed.complete)
        XCTAssertEqual(parsed.stations, [MapStation(id: "de-DELFI_de:09162:2", name: "Marienplatz",
                                                    lat: 48.13706, lon: 11.57539, bits: 3)])
        XCTAssertEqual(MapGroups.modes(3), ["SUBURBAN", "SUBWAY"])
        XCTAssertEqual(StationRef(id: "a", name: "b", cityId: "c", modes: MapGroups.modes(1 | 8 | 16)).symbolModes,
                       ["METRO", "BUS", "REGIONAL_RAIL"])
    }

    func testEmptyBoard() throws {
        let board = try StationParser.board(Data(#"{"t":1,"name":"X","deps":[]}"#.utf8), nowMs: 0)
        XCTAssertEqual(board.name, "X")
        XCTAssertTrue(board.departures.isEmpty)
    }
}
