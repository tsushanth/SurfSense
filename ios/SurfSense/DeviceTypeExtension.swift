import SwiftUI

enum DeviceType: String, CaseIterable, Codable {
    case iOS = "iOS"
    case android = "Android"
    case desktop = "Desktop"
    case web = "Web"
    case unknown = "Unknown"

    var icon: String {
        switch self {
        case .iOS: return "iphone"
        case .android: return "smartphone"
        case .desktop: return "desktopcomputer"
        case .web: return "globe"
        case .unknown: return "questionmark.circle"
        }
    }

    static func from(_ typeString: String?) -> DeviceType {
        guard let typeString = typeString?.lowercased() else { return .unknown }

        // Check for iOS devices
        if typeString.contains("ios") || typeString.contains("iphone") || typeString.contains("ipad") {
            return .iOS
        }

        // Check for Android devices
        if typeString.contains("android") {
            return .android
        }

        // Check for web/browser extensions
        if typeString.contains("extension") || typeString.contains("chrome") ||
           typeString.contains("firefox") || typeString.contains("safari") ||
           typeString.contains("edge") || typeString.contains("web") ||
           typeString.contains("browser") {
            return .web
        }

        // Check for desktop
        if typeString.contains("desktop") || typeString.contains("mac") ||
           typeString.contains("windows") || typeString.contains("computer") ||
           typeString.contains("linux") {
            return .desktop
        }

        return .unknown
    }
}
