import GistCore
import SwiftUI
import UIKit

/// Gist's colors (the same as the Android app's), light and dark.
enum Theme {
    static let primaryColor = dynamic(0x1E7D5B, 0x6FD3A8)
    static let primaryContainer = dynamic(0xD3F0E3, 0x1D4D3C)
    static let surface = dynamic(0xFFFFFF, 0x18201C)
    static let surfaceVariant = dynamic(0xEAEFEC, 0x232D28)
    static let muted = dynamic(0x5D6B64, 0x9AA9A1)
    static let outline = dynamic(0xD9E1DC, 0x2E3934)

    static let goodLight = Score.RGB(0x23, 0x95, 0x5E)
    static let goodDark = Score.RGB(0x5D, 0xCB, 0x8F)
    static let badLight = Score.RGB(0xD2, 0x42, 0x3F)
    static let badDark = Score.RGB(0xFF, 0x8A, 0x84)

    static let good = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? ui(goodDark) : ui(goodLight) })
    static let bad = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? ui(badDark) : ui(badLight) })

    /// The color for an on-track score: red at 0 %, through amber, to green at 100 %.
    static func scoreColor(_ score: Double) -> Color {
        Color(uiColor: UIColor { traits in
            let dark = traits.userInterfaceStyle == .dark
            return ui(Score.color(score, bad: dark ? badDark : badLight, good: dark ? goodDark : goodLight))
        })
    }

    static func dynamic(_ light: Int, _ dark: Int) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? ui(hex: dark) : ui(hex: light) })
    }

    static func ui(hex: Int) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }

    static func ui(_ c: Score.RGB) -> UIColor {
        UIColor(red: CGFloat(c.r) / 255, green: CGFloat(c.g) / 255, blue: CGFloat(c.b) / 255, alpha: 1)
    }
}

/// A rounded card, outlined (in a score's color once there is one).
struct CardBackground: ViewModifier {
    var score: Double?

    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(score.map { Theme.scoreColor($0) } ?? Theme.outline, lineWidth: score == nil ? 1 : 2)
            )
    }
}

extension View {
    func card(score: Double? = nil) -> some View { modifier(CardBackground(score: score)) }
}

/// "9:30 PM" in the phone's format, from "21:30".
enum TimeText {
    static func of(_ t: AlarmTime) -> String {
        var c = DateComponents()
        c.hour = t.hour
        c.minute = t.minute
        let date = Calendar.current.date(from: c) ?? Date()
        return date.formatted(date: .omitted, time: .shortened)
    }

    static func of(_ hhmm: String) -> String {
        AlarmTime.parse(hhmm).map { of($0) } ?? hhmm
    }

    /// "today 9:30 PM", "tomorrow 7:00 AM", "Wed 7:00 AM", "Dec 1 7:00 AM".
    static func at(_ date: Date) -> String {
        let cal = Calendar.current
        let time = date.formatted(date: .omitted, time: .shortened)
        if cal.isDateInToday(date) { return "today \(time)" }
        if cal.isDateInTomorrow(date) { return "tomorrow \(time)" }
        if let days = cal.dateComponents([.day], from: cal.startOfDay(for: Date()), to: cal.startOfDay(for: date)).day, days < 7 {
            return date.formatted(.dateTime.weekday(.abbreviated)) + " \(time)"
        }
        return date.formatted(.dateTime.month(.abbreviated).day()) + " \(time)"
    }

    /// "Monday, Nov 9" from "2026-11-09".
    static func day(_ day: String) -> String {
        guard let d = Days.date(day) else { return day }
        return d.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }
}
