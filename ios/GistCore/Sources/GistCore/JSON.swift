import Foundation

/// A JSON-like value: what backup files (YAML) are read into and written from. Objects keep
/// their key order, so files come out in the same order as the Android app writes them.
public enum JSON: Equatable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSON])
    case object(JSONObject)

    public var objectValue: JSONObject? {
        if case .object(let o) = self { return o }
        return nil
    }

    public var arrayValue: [JSON]? {
        if case .array(let a) = self { return a }
        return nil
    }

    /// Like org.json's optString: numbers and booleans as text, null as nil.
    public var stringValue: String? {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        case .double(let d): return JSON.text(d)
        case .bool(let b): return b ? "true" : "false"
        default: return nil
        }
    }

    /// Like org.json's optInt: whole numbers, doubles truncated, numeric text parsed.
    public var intValue: Int? {
        switch self {
        case .int(let i): return i
        case .double(let d): return d.isFinite ? Int(d) : nil
        case .string(let s):
            let t = s.trimmingCharacters(in: .whitespaces)
            if let i = Int(t) { return i }
            if let d = Double(t), d.isFinite { return Int(d) }
            return nil
        default: return nil
        }
    }

    public var doubleValue: Double? {
        switch self {
        case .int(let i): return Double(i)
        case .double(let d): return d
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    /// Like org.json's optBoolean: true / false, or the text "true" / "false" (any case).
    public var boolValue: Bool? {
        switch self {
        case .bool(let b): return b
        case .string(let s):
            switch s.lowercased() {
            case "true": return true
            case "false": return false
            default: return nil
            }
        default: return nil
        }
    }

    /// A double as it's written in files: "40.7128", "3.0".
    static func text(_ d: Double) -> String {
        if d.isNaN { return ".nan" }
        if d.isInfinite { return d > 0 ? ".inf" : "-.inf" }
        return String(d)
    }
}

/// A JSON object that remembers the order its keys were added in.
public struct JSONObject: Equatable {
    public private(set) var keys: [String] = []
    private var values: [String: JSON] = [:]

    public init() {}

    public init(_ pairs: [(String, JSON)]) {
        for (k, v) in pairs { self[k] = v }
    }

    public var count: Int { keys.count }
    public var isEmpty: Bool { keys.isEmpty }

    public func has(_ key: String) -> Bool { values[key] != nil }

    /// Setting nil removes the key; setting an existing key keeps its position.
    public subscript(key: String) -> JSON? {
        get { values[key] }
        set {
            if let v = newValue {
                if values[key] == nil { keys.append(key) }
                values[key] = v
            } else if values[key] != nil {
                values[key] = nil
                keys.removeAll { $0 == key }
            }
        }
    }

    /// A copy with [key] set (for building objects in one expression).
    public func with(_ key: String, _ value: JSON) -> JSONObject {
        var o = self
        o[key] = value
        return o
    }

    public var pairs: [(String, JSON)] { keys.map { ($0, values[$0]!) } }

    // Typed reads with defaults, like org.json's opt… methods.

    public func string(_ key: String, _ fallback: String = "") -> String {
        values[key]?.stringValue ?? fallback
    }

    public func int(_ key: String, _ fallback: Int) -> Int {
        values[key]?.intValue ?? fallback
    }

    public func double(_ key: String) -> Double? {
        values[key]?.doubleValue
    }

    public func bool(_ key: String, _ fallback: Bool) -> Bool {
        values[key]?.boolValue ?? fallback
    }

    public func object(_ key: String) -> JSONObject? {
        values[key]?.objectValue
    }

    public func array(_ key: String) -> [JSON]? {
        values[key]?.arrayValue
    }
}
