import SwiftUI
import UniformTypeIdentifiers

/// A JSON export of every report on this device, handed to the system file exporter so the user
/// can save it to Files, iCloud Drive, or anywhere else the picker offers.
///
/// This is the iOS answer to Android's `backup/ExportManager.kt`. It is deliberately the plain
/// export, NOT the encrypted password-protected backup Android also has — that format has to
/// round-trip with Android's restore, so it is worth doing properly rather than approximating.
/// Until then, at minimum a user can get their records off the device, which they previously
/// could not do at all.
struct RecordsExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
