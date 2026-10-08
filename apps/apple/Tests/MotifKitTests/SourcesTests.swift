import Foundation
import Testing
@testable import MotifKit

@Suite struct AudiusSourceTests {
    @Test func keepsOnlyDownloadableTracks() throws {
        let json = """
        {"data":[
          {"id":"D7KyD","title":"Original Mix","is_downloadable":true,"orig_filename":"Original Mix.wav",
           "user":{"name":"Some Producer"},
           "artwork":{"150x150":"https://a/150.jpg","480x480":"https://a/480.jpg","1000x1000":null}},
          {"id":"Q2","title":"Gated","is_downloadable":true,"is_download_gated":true,"user":{"name":"X"},"artwork":null},
          {"id":"Q3","title":"Old Field","downloadable":true,"user":{"name":"Y"}},
          {"id":"Q4","title":"Stream Only","is_downloadable":false,"user":{"name":"Z"}}
        ]}
        """
        let results = try AudiusSource.parseSearch(Data(json.utf8))
        #expect(results.map(\.id) == ["D7KyD", "Q3"])
        #expect(results[0].artist == "Some Producer")
        #expect(results[0].format == "wav")
        #expect(results[0].coverURL == URL(string: "https://a/480.jpg"))
        #expect(results[1].format == nil)
    }

    @Test func downloadsTheOriginalFormat() async throws {
        let result = SourceResult(id: "D7KyD", title: "Original Mix", artist: nil, album: nil, licenseURL: nil, format: "wav")
        let files = try await AudiusSource().downloads(for: result)
        let file = try #require(files.first)
        #expect(file.format == "wav")
        #expect(file.url.absoluteString == "https://api.audius.co/v1/tracks/D7KyD/download?app_name=Motif")
    }
}
