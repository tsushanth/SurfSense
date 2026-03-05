import SwiftUI

class LinkedDevicesService: ObservableObject {
    @Published var linkedDevices: [LinkedDeviceViewModel] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var isUnlinking = false

    private let baseURL = Config.apiBaseURL
    private let currentClientId = SecureStorage.shared.deviceId
    
    var unifiedUsage: DeviceUsageData {
        guard !linkedDevices.isEmpty else {
            return DeviceUsageData(
                totalTime: "0m",
                categories: [],
                mostUsedCategory: CategoryUsage(category: "None", totalTime: 0, color: "gray")
            )
        }

        // Dynamically aggregate ALL categories from all linked devices
        var totals: [String: TimeInterval] = [:]
        for device in linkedDevices {
            for category in device.usageData.categories {
                totals[category.category, default: 0] += category.totalTime
            }
        }

        let categories = totals
            .sorted { $0.value > $1.value }
            .map { (cat, seconds) in
                CategoryUsage(
                    category: cat,
                    totalTime: seconds,
                    color: Config.categoryColors[cat] ?? "gray"
                )
            }

        let totalSeconds = Int(totals.values.reduce(0, +))
        let totalHours = totalSeconds / 3600
        let totalMinutes = (totalSeconds % 3600) / 60
        let totalTimeString = totalHours > 0 ? "\(totalHours)h \(totalMinutes)m" : "\(totalMinutes)m"

        let mostUsed = categories.first ?? CategoryUsage(category: "None", totalTime: 0, color: "gray")

        return DeviceUsageData(
            totalTime: totalTimeString,
            categories: categories,
            mostUsedCategory: mostUsed
        )
    }
    
    func fetchLinkedDevices(forceRefresh: Bool = false) async {
        let localStorage = LocalStorage.shared

        // Check cache first unless force refresh
        if !forceRefresh && !localStorage.shouldRefresh() {
            if let cachedDevices = localStorage.getCachedLinkedDevices() {
                await MainActor.run {
                    self.linkedDevices = cachedDevices.map { LinkedDeviceViewModel(from: $0) }
                }
                return
            }
        }

        await MainActor.run {
            isLoading = true
            errorMessage = nil
        }

        do {
            guard let url = URL(string: "\(baseURL)/get-linked-clients?clientId=\(currentClientId)") else {
                throw URLError(.badURL)
            }

            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            SecureStorage.shared.addAuthHeader(to: &request)

            let (data, _) = try await URLSession.shared.data(for: request)

            let apiResponse = try JSONDecoder().decode(GetLinkedClientsResponse.self, from: data)

            if apiResponse.success, let clientsData = apiResponse.data {
                var devices = clientsData.clients.map { LinkedDeviceViewModel(from: $0) }

                // Cache the fetched devices
                localStorage.cacheLinkedDevices(clientsData.clients)

                // Fetch usage data for each linked device in parallel
                await withTaskGroup(of: (Int, DeviceUsageData?).self) { group in
                    for (index, device) in devices.enumerated() {
                        group.addTask {
                            let usage = await self.fetchDeviceUsage(for: device.id)
                            return (index, usage)
                        }
                    }
                    for await (index, usage) in group {
                        if let usage = usage {
                            devices[index].usageData = usage
                        }
                    }
                }

                await MainActor.run {
                    self.linkedDevices = devices
                    self.isLoading = false
                }
            } else {
                await MainActor.run {
                    self.errorMessage = apiResponse.error ?? "Failed to fetch linked devices"
                    self.isLoading = false
                }
            }
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
                self.isLoading = false

                // Try to load from cache on network error
                if let cachedDevices = localStorage.getCachedLinkedDevices() {
                    self.linkedDevices = cachedDevices.map { LinkedDeviceViewModel(from: $0) }
                }
            }
        }
    }
    
    // MARK: - Fetch Usage for a Single Device

    private func fetchDeviceUsage(for deviceId: String) async -> DeviceUsageData? {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        let today = dateFormatter.string(from: Date())

        guard let url = URL(string: "\(baseURL)/get-summary-history?day=\(today)&userId=\(deviceId)") else {
            return nil
        }

        do {
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            SecureStorage.shared.addAuthHeader(to: &request)

            let (data, _) = try await URLSession.shared.data(for: request)
            let history = try JSONDecoder().decode([GetSummaryHistoryResponse].self, from: data)

            guard let latest = history.first, let summary = latest.summary else {
                return nil
            }

            let categories = summary.map { (cat, seconds) in
                CategoryUsage(
                    category: cat,
                    totalTime: TimeInterval(seconds),
                    color: Config.categoryColors[cat] ?? "gray"
                )
            }.sorted { $0.totalTime > $1.totalTime }

            let totalSeconds = summary.values.reduce(0, +)
            let totalHours = totalSeconds / 3600
            let totalMinutes = (totalSeconds % 3600) / 60
            let totalTimeString = totalHours > 0 ? "\(totalHours)h \(totalMinutes)m" : "\(totalMinutes)m"
            let mostUsed = categories.first ?? CategoryUsage(category: "None", totalTime: 0, color: "gray")

            return DeviceUsageData(
                totalTime: totalTimeString,
                categories: categories,
                mostUsedCategory: mostUsed
            )
        } catch {
            return nil
        }
    }

    func unlinkDevice(deviceId: String) async -> Bool {
            await MainActor.run {
                isUnlinking = true
                errorMessage = nil
            }
            
            do {
                guard let url = URL(string: "\(baseURL)/unlink-device") else {
                    throw URLError(.badURL)
                }
                
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                SecureStorage.shared.addAuthHeader(to: &request)

                let requestBody = UnlinkDeviceRequest(
                    clientIdA: currentClientId,
                    clientIdB: deviceId
                )
                let jsonData = try JSONEncoder().encode(requestBody)
                request.httpBody = jsonData
                
                let (data, response) = try await URLSession.shared.data(for: request)
                
                if let httpResponse = response as? HTTPURLResponse,
                   httpResponse.statusCode >= 400 {
                    throw URLError(.badServerResponse)
                }
                
                let apiResponse = try JSONDecoder().decode(UnlinkDeviceResponse.self, from: data)
                
                await MainActor.run {
                    self.isUnlinking = false
                    
                    if apiResponse.success {
                        // Remove the device from local list
                        self.linkedDevices.removeAll { $0.id == deviceId }
                        // Update cache
                        let clientInfoList = self.linkedDevices.map { device in
                            ClientInfo(
                                clientId: device.id,
                                clientName: device.name,
                                clientType: device.type.rawValue,
                                lastSeen: device.lastSeen,
                                deviceName: nil
                            )
                        }
                        LocalStorage.shared.cacheLinkedDevices(clientInfoList)
                    } else {
                        self.errorMessage = apiResponse.error ?? "Failed to unlink device"
                    }
                }
                
                return apiResponse.success
                
            } catch {
                await MainActor.run {
                    self.isUnlinking = false
                    self.errorMessage = "Network error: \(error.localizedDescription)"
                }
                return false
            }
        }

    // MARK: - Usage History Tracking

    func saveCurrentUsageToHistory() {
        let usage = unifiedUsage
        let totalMinutes = usage.categories.reduce(0) { $0 + Int($1.totalTime / 60) }

        var byCategory: [String: Int] = [:]
        for category in usage.categories {
            byCategory[category.category] = Int(category.totalTime / 60)
        }

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        let todayString = dateFormatter.string(from: Date())

        let dailyUsage = LocalStorage.DailyUsage(
            date: todayString,
            totalMinutes: totalMinutes,
            byCategory: byCategory
        )

        LocalStorage.shared.saveUsageHistory(dailyUsage)
    }

    func getUsageHistory(days: Int = 7) -> [LocalStorage.DailyUsage] {
        return LocalStorage.shared.getUsageHistory(days: days)
    }
}
