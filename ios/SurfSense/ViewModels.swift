import SwiftUI

struct LinkedDeviceViewModel: Identifiable {
    let id: String
    let name: String
    let type: DeviceType
    let lastSeen: String
    var usageData: DeviceUsageData

    init(from clientInfo: ClientInfo) {
        self.id = clientInfo.clientId

        if let name = clientInfo.clientName, !name.isEmpty {
            self.name = name
        } else if let deviceName = clientInfo.deviceName, !deviceName.isEmpty {
            self.name = deviceName
        } else {
            let deviceType = DeviceType.from(clientInfo.clientType)
            self.name = LinkedDeviceViewModel.generateDeviceName(for: deviceType)
        }

        self.type = DeviceType.from(clientInfo.clientType)

        if let lastSeen = clientInfo.lastSeen, !lastSeen.isEmpty {
            self.lastSeen = lastSeen
        } else {
            self.lastSeen = "Unknown"
        }

        self.usageData = DeviceUsageData(
            totalTime: "0m",
            categories: [],
            mostUsedCategory: CategoryUsage(category: "None", totalTime: 0, color: "gray")
        )
    }

    // MARK: - Helper Methods

    private static func generateDeviceName(for deviceType: DeviceType) -> String {
        switch deviceType {
        case .iOS:
            return "iPhone"
        case .android:
            return "Android Phone"
        case .desktop:
            return "Computer"
        case .web:
            return "Browser"
        case .unknown:
            return "Device"
        }
    }
}
