struct GetLinkedClientsResponse: Codable {
    let success: Bool
    let data: LinkedClientsData?
    let error: String?
}

struct LinkedClientsData: Codable {
    let count: Int
    let clients: [ClientInfo]
}

struct ClientInfo: Codable {
    let clientId: String
    let clientName: String?
    let clientType: String?
    
    // Additional properties that might be in response
    let lastSeen: String?
    let deviceName: String?
}


struct UnlinkDeviceRequest: Codable {
    let clientIdA: String
    let clientIdB: String
}

struct UnlinkDeviceResponse: Codable {
    let success: Bool
    let error: String?
}

struct CategoryMappingRequest: Codable {
    let domains: [String]
    let packages: [String]
}

struct CategoryMappingResponse: Codable {
    let mappings: [String: String] // domain/package -> category
}

struct SubmitCategorySummaryRequest: Codable {
    let timestamp: String
    let categorySummary: [String: Int] // category -> seconds
    let userId: String
}

struct SubmitCategorySummaryResponse: Codable {
    let status: String
    let error: String?
}

struct GetSummaryHistoryResponse: Codable {
    let timestamp: String?
    let userId: String?
    let summary: [String: Int]?
}

import SwiftUI

struct AppUsageData {
    let bundleId: String
    let name: String
    let totalTime: TimeInterval
    let category: String?
}

struct DomainUsageData {
    let domain: String
    let totalTime: TimeInterval
    let category: String?
}
