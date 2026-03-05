import SwiftUI
import Foundation

struct DeviceUsageData {
    let totalTime: String
    let categories: [CategoryUsage]
    let mostUsedCategory: CategoryUsage
}


struct CategoryUsage: Identifiable, Codable {
    let id = UUID()
    let category: String
    let totalTime: TimeInterval
    let color: String
    
    var formattedTime: String {
        let hours = Int(totalTime) / 3600
        let minutes = Int(totalTime) % 3600 / 60
        if hours > 0 {
            return "\(hours)h \(minutes)m"
        } else {
            return "\(minutes)m"
        }
    }
}

struct LinkedDevice: Identifiable {
    let id: Int
    let name: String
    let type: DeviceType
    let lastSeen: String
    let usageData: DeviceUsageData
    
    enum DeviceType {
        case phone, tablet, laptop
        
        var icon: String {
            switch self {
            case .phone: return "iphone"
            case .tablet: return "ipad"
            case .laptop: return "laptopcomputer"
            }
        }
    }
}

struct InitiateLinkingRequest: Codable {
    let clientId: String
}

struct InitiateLinkingResponse: Codable {
    let success: Bool
    let code: String?
    let error: String?
}

struct CompleteLinkingRequest: Codable {
    let clientId: String?
    let mobileClientId: String?
    let code: String
    
    init(clientId: String, code: String) {
        self.clientId = clientId
        self.mobileClientId = clientId  // Send same ID as both for compatibility
        self.code = code
    }
}

struct CompleteLinkingResponse: Codable {
    let success: Bool
    let linkedWith: String?
    let error: String?
}
