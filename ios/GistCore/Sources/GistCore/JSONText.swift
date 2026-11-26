import Foundation

/// JSON as text: parsing (keeping each object's key order) and writing (compact, like
/// org.json's toString on Android). Used by the sync folder's files, which both apps read.
public enum JSONText {
    public struct ParseError: Error, Equatable {
        public let message: String
    }

    public static func parse(_ text: String) throws -> JSON {
        var p = Parser(Array(text.unicodeScalars))
        p.skipSpace()
        let v = try p.value()
        p.skipSpace()
        guard p.atEnd else { throw ParseError(message: "Unexpected text after the value") }
        return v
    }

    public static func parse(_ data: Data) throws -> JSON {
        guard let s = String(data: data, encoding: .utf8) else { throw ParseError(message: "Not UTF-8") }
        return try parse(s)
    }

    /// An object, or nil when [data] isn't a JSON object.
    public static func object(_ data: Data?) -> JSONObject? {
        guard let data else { return nil }
        return (try? parse(data))?.objectValue
    }

    public static func string(_ v: JSON) -> String {
        var out = ""
        write(v, &out)
        return out
    }

    public static func string(_ o: JSONObject) -> String { string(.object(o)) }

    public static func data(_ o: JSONObject) -> Data { Data(string(o).utf8) }

    private static func write(_ v: JSON, _ out: inout String) {
        switch v {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .double(let d):
            if d.isFinite, d == d.rounded(), abs(d) < 1e15 { out += String(Int(d)) } else { out += String(d) }
        case .string(let s): quote(s, &out)
        case .array(let a):
            out += "["
            for (i, x) in a.enumerated() {
                if i > 0 { out += "," }
                write(x, &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (i, (k, x)) in o.pairs.enumerated() {
                if i > 0 { out += "," }
                quote(k, &out)
                out += ":"
                write(x, &out)
            }
            out += "}"
        }
    }

    private static func quote(_ s: String, _ out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if u.value < 0x20 {
                    out += String(format: "\\u%04x", u.value)
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        out += "\""
    }

    private struct Parser {
        let s: [Unicode.Scalar]
        var i = 0

        init(_ s: [Unicode.Scalar]) { self.s = s }

        var atEnd: Bool { i >= s.count }

        mutating func skipSpace() {
            while i < s.count, s[i] == " " || s[i] == "\n" || s[i] == "\r" || s[i] == "\t" { i += 1 }
        }

        func fail(_ m: String) -> ParseError { ParseError(message: "\(m) at \(i)") }

        mutating func value() throws -> JSON {
            guard i < s.count else { throw fail("Unexpected end") }
            switch s[i] {
            case "{": return .object(try object())
            case "[": return .array(try array())
            case "\"": return .string(try string())
            case "t": try word("true"); return .bool(true)
            case "f": try word("false"); return .bool(false)
            case "n": try word("null"); return .null
            default: return try number()
            }
        }

        mutating func word(_ w: String) throws {
            for u in w.unicodeScalars {
                guard i < s.count, s[i] == u else { throw fail("Expected \(w)") }
                i += 1
            }
        }

        mutating func object() throws -> JSONObject {
            var o = JSONObject()
            i += 1
            skipSpace()
            if i < s.count, s[i] == "}" { i += 1; return o }
            while true {
                skipSpace()
                guard i < s.count, s[i] == "\"" else { throw fail("Expected a key") }
                let k = try string()
                skipSpace()
                guard i < s.count, s[i] == ":" else { throw fail("Expected :") }
                i += 1
                skipSpace()
                o[k] = try value()
                skipSpace()
                guard i < s.count else { throw fail("Unexpected end") }
                if s[i] == "," { i += 1; continue }
                if s[i] == "}" { i += 1; return o }
                throw fail("Expected , or }")
            }
        }

        mutating func array() throws -> [JSON] {
            var a: [JSON] = []
            i += 1
            skipSpace()
            if i < s.count, s[i] == "]" { i += 1; return a }
            while true {
                skipSpace()
                a.append(try value())
                skipSpace()
                guard i < s.count else { throw fail("Unexpected end") }
                if s[i] == "," { i += 1; continue }
                if s[i] == "]" { i += 1; return a }
                throw fail("Expected , or ]")
            }
        }

        mutating func hex4() throws -> UInt32 {
            guard i + 4 <= s.count else { throw fail("Bad \\u escape") }
            var v: UInt32 = 0
            for _ in 0..<4 {
                guard let d = Int(String(s[i]), radix: 16) else { throw fail("Bad \\u escape") }
                v = v * 16 + UInt32(d)
                i += 1
            }
            return v
        }

        mutating func string() throws -> String {
            i += 1
            var out = String.UnicodeScalarView()
            while true {
                guard i < s.count else { throw fail("Unterminated string") }
                let c = s[i]
                i += 1
                if c == "\"" { return String(out) }
                if c != "\\" { out.append(c); continue }
                guard i < s.count else { throw fail("Bad escape") }
                let e = s[i]
                i += 1
                switch e {
                case "\"": out.append("\"")
                case "\\": out.append("\\")
                case "/": out.append("/")
                case "b": out.append("\u{08}")
                case "f": out.append("\u{0C}")
                case "n": out.append("\n")
                case "r": out.append("\r")
                case "t": out.append("\t")
                case "u":
                    var v = try hex4()
                    if (0xD800...0xDBFF).contains(v), i + 1 < s.count, s[i] == "\\", s[i + 1] == "u" {
                        i += 2
                        let lo = try hex4()
                        if (0xDC00...0xDFFF).contains(lo) {
                            v = 0x10000 + ((v - 0xD800) << 10) + (lo - 0xDC00)
                        }
                    }
                    out.append(Unicode.Scalar(v) ?? "\u{FFFD}")
                default: throw fail("Bad escape")
                }
            }
        }

        mutating func number() throws -> JSON {
            let start = i
            if i < s.count, s[i] == "-" { i += 1 }
            var fractional = false
            while i < s.count {
                let c = s[i]
                if ("0"..."9").contains(c) { i += 1; continue }
                if c == "." || c == "e" || c == "E" || c == "+" || c == "-" { fractional = true; i += 1; continue }
                break
            }
            let text = String(String.UnicodeScalarView(s[start..<i]))
            guard !text.isEmpty, text != "-" else { throw fail("Expected a value") }
            if !fractional, let n = Int(text) { return .int(n) }
            guard let d = Double(text) else { throw fail("Bad number") }
            return .double(d)
        }
    }
}
