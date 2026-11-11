import Foundation
import Yams

/// Reads and writes backup files as YAML 1.2 (core schema), like the Android app's
/// SnakeYAML Engine setup, so files move between the two apps:
///  - plain scalars are typed by the core schema only: "20:09" and "2026-11-08" stay text
///    (YAML 1.1 readers would turn them into a number and a date);
///  - text that would otherwise read back as something else ("true", "12", "") is quoted;
///  - only maps, lists, text, numbers and booleans come out: a file can't create objects;
///  - a key can only appear once in a map.
public enum YAMLCodec {
    /// Parses [text]. Throws BackupError with a readable message if it isn't YAML.
    public static func load(_ text: String) throws -> JSON {
        let node: Node?
        do {
            node = try Yams.compose(yaml: text)
        } catch {
            let first = String(describing: error).split(separator: "\n").first.map(String.init) ?? ""
            throw BackupError("This isn't a Gist backup file (not valid YAML): \(first)")
        }
        guard let node else { return .null }
        return try json(node)
    }

    /// [value] as YAML, block style, with [header] as comment lines on top.
    public static func dump(_ value: JSON, header: String = "") throws -> String {
        let body = try Yams.serialize(node: node(value), width: -1, allowUnicode: true)
        if header.isEmpty { return body }
        let comments = header.split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> String in
                let c = "# " + line
                return c.hasSuffix(" ") ? String(c.dropLast()) : c
            }
            .joined(separator: "\n")
        return comments + "\n" + body
    }

    // MARK: - Reading

    static func json(_ node: Node) throws -> JSON {
        switch node {
        case .scalar(let s):
            return scalar(s.string, plain: s.style == .plain)
        case .mapping(let m):
            var o = JSONObject()
            for (k, v) in m {
                let key = k.string ?? ""
                if o.has(key) {
                    throw BackupError("This isn't a Gist backup file (not valid YAML): duplicate key \"\(key)\"")
                }
                o[key] = try json(v)
            }
            return .object(o)
        case .sequence(let seq):
            return .array(try seq.map { try json($0) })
        default:
            throw BackupError("This isn't a Gist backup file (not valid YAML): unsupported YAML (aliases)")
        }
    }

    /// The YAML 1.2 core schema: what a plain (unquoted) scalar means.
    static func scalar(_ s: String, plain: Bool) -> JSON {
        guard plain else { return .string(s) }
        switch s {
        case "", "~", "null", "Null", "NULL": return .null
        case "true", "True", "TRUE": return .bool(true)
        case "false", "False", "FALSE": return .bool(false)
        case ".inf", ".Inf", ".INF", "+.inf", "+.Inf", "+.INF": return .double(.infinity)
        case "-.inf", "-.Inf", "-.INF": return .double(-.infinity)
        case ".nan", ".NaN", ".NAN": return .double(.nan)
        default: break
        }
        if matches(s, "^[-+]?[0-9]+$") {
            let digits = s.hasPrefix("+") ? String(s.dropFirst()) : s
            if let i = Int(digits) { return .int(i) }
            return .double(Double(digits) ?? 0)
        }
        if matches(s, "^0o[0-7]+$"), let i = Int(s.dropFirst(2), radix: 8) { return .int(i) }
        if matches(s, "^0x[0-9a-fA-F]+$"), let i = Int(s.dropFirst(2), radix: 16) { return .int(i) }
        if matches(s, "^[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?$"), let d = Double(s) {
            return .double(d)
        }
        return .string(s)
    }

    private static func matches(_ s: String, _ pattern: String) -> Bool {
        s.range(of: pattern, options: .regularExpression) != nil
    }

    // MARK: - Writing

    static func node(_ value: JSON) -> Node {
        switch value {
        case .null: return Node("null")
        case .bool(let b): return Node(b ? "true" : "false")
        case .int(let i): return Node(String(i))
        case .double(let d): return Node(JSON.text(d))
        case .string(let s): return text(s)
        case .array(let a): return Node(a.map { node($0) })
        case .object(let o): return Node(o.pairs.map { (text($0.0), node($0.1)) })
        }
    }

    /// Text, quoted when it would otherwise read back as something else.
    static func text(_ s: String) -> Node {
        if case .string = scalar(s, plain: true), !s.isEmpty, s.trimmingCharacters(in: .whitespaces) == s {
            return Node(s, Tag(.implicit), .any)
        }
        return Node(s, Tag(.implicit), .singleQuoted)
    }
}

/// A problem with a backup file, with a message to show the person.
public struct BackupError: Error, Equatable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}
