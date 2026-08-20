import Foundation

/// One row of the backend's `health_tips` table.
///
/// Every field is decoded as OPTIONAL even though the column is populated in practice. Swift's
/// `Codable` fails the whole array if a single row is missing a non-optional field, so decoding
/// strictly would let one bad row silently wipe out all fifteen tips. Android hit the mirror of
/// this problem from the other side — Gson happily wrote `null` into non-null Kotlin fields, and
/// the later `status.lowercase()` crashed. Optional-then-sanitize is the shape that survives both.
private struct RemoteHealthTipRow: Decodable {
    let canonicalParam: String?
    let status: String?
    let headline: String?
    let detail: String?

    enum CodingKeys: String, CodingKey {
        case canonicalParam = "canonical_param"
        case status, headline, detail
    }
}

/// On-device cache of personalized health tips fetched from the backend's `health_tips` table —
/// the DB is the source of truth, so a tip added there reaches every install without an app
/// release. Port of `local/RemoteHealthTips.kt`.
///
/// Fetched once per install, retried on a later launch if it never succeeded (first launch
/// offline). `PersonalizedTips` checks this cache first and falls back to its bundled seed for
/// any test not covered here.
enum RemoteHealthTips {
    private static let keyFetchedOnce = "remote_health_tips_fetched_once"
    private static let keyTipsJson = "remote_health_tips_json"

    private static let defaults = UserDefaults.standard
    private static let lock = NSLock()

    /// key = "canonicalParam|status" (status lowercased). Parsed once per process.
    private static var memoryCache: [String: HealthTip]?

    private static func cacheKey(_ param: String, _ status: String) -> String {
        "\(param)|\(status.lowercased())"
    }

    /// Decodes and sanitises a `health_tips` payload. Internal rather than private so the tests
    /// exercise the exact path production uses, instead of a reimplementation of it.
    static func parse(_ data: Data) -> [String: HealthTip] {
        guard let rows = try? JSONDecoder().decode([RemoteHealthTipRow].self, from: data) else { return [:] }
        return index(rows)
    }

    private static func loadCache() -> [String: HealthTip] {
        lock.lock()
        defer { lock.unlock() }
        if let cached = memoryCache { return cached }

        guard
            let raw = defaults.string(forKey: keyTipsJson),
            let data = raw.data(using: .utf8)
        else {
            memoryCache = [:]
            return [:]
        }
        let parsed = parse(data)
        memoryCache = parsed
        return parsed
    }

    /// Drops a row with no param or status, because those two ARE the cache key: without them it
    /// can never be looked up, so keeping it risks a bad lookup for no benefit.
    private static func index(_ rows: [RemoteHealthTipRow]) -> [String: HealthTip] {
        var out: [String: HealthTip] = [:]
        for row in rows {
            let param = (row.canonicalParam ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let status = (row.status ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !param.isEmpty, !status.isEmpty else { continue }
            out[cacheKey(param, status)] = HealthTip(
                headline: row.headline ?? "",
                detail: row.detail ?? ""
            )
        }
        return out
    }

    /// The remote tip for this canonical test + status ("low"/"high"), or nil if not cached yet
    /// or not covered — the caller falls back to its own bundled seed in that case.
    static func get(canonicalParam: String, status: String) -> HealthTip? {
        loadCache()[cacheKey(canonicalParam, status)]
    }

    /// Fetches every tip once ever; retries on a later launch if it never succeeded.
    ///
    /// Deliberately silent on failure: this is a content refresh, not a feature the user is
    /// waiting on, and `PersonalizedTips` already has a complete bundled fallback.
    static func fetchIfNeverFetched() async {
        guard !defaults.bool(forKey: keyFetchedOnce) else { return }
        guard let rows = try? await APIClient.shared.healthTips() else { return }

        let parsed = index(rows)
        // Don't mark it fetched on an empty result — an empty table (or a shape change that
        // sanitises every row away) should be retried on a later launch, not cached forever.
        guard !parsed.isEmpty else { return }

        lock.lock()
        memoryCache = parsed
        lock.unlock()

        if let data = try? JSONEncoder().encode(rows.map(EncodableRow.init)),
           let json = String(data: data, encoding: .utf8) {
            defaults.set(json, forKey: keyTipsJson)
            defaults.set(true, forKey: keyFetchedOnce)
        }
    }

    /// Re-encodes a decoded row back to the backend's snake_case shape, so the cached JSON can be
    /// read by the same decoder on the next launch.
    private struct EncodableRow: Encodable {
        let canonicalParam: String?
        let status: String?
        let headline: String?
        let detail: String?

        init(_ row: RemoteHealthTipRow) {
            canonicalParam = row.canonicalParam
            status = row.status
            headline = row.headline
            detail = row.detail
        }

        enum CodingKeys: String, CodingKey {
            case canonicalParam = "canonical_param"
            case status, headline, detail
        }
    }
}

/// Internal seam so `RemoteHealthTips` can decode rows without exposing the row type.
extension APIClient {
    fileprivate func healthTips() async throws -> [RemoteHealthTipRow] {
        try await unauthenticatedGet("api/health-tips")
    }
}
