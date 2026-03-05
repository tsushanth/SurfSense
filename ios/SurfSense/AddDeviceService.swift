//
//  AddDeviceService.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

class AddDeviceService: ObservableObject {
    @Published var isLinking = false
    @Published var linkingSuccess = false
    @Published var linkedDeviceName: String?
    @Published var errorMessage: String?
    
    private let baseURL = Config.apiBaseURL
    
    func completeDeviceLinking(clientId: String, code: String) async {
        await MainActor.run {
            isLinking = true
            errorMessage = nil
            linkingSuccess = false
            linkedDeviceName = nil
        }
        
        do {
            guard let url = URL(string: "\(baseURL)/complete-linking") else {
                throw URLError(.badURL)
            }
            
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            SecureStorage.shared.addAuthHeader(to: &request)

            let requestBody = CompleteLinkingRequest(clientId: clientId, code: code)
            request.httpBody = try JSONEncoder().encode(requestBody)

            let (data, _) = try await URLSession.shared.data(for: request)
            let apiResponse = try JSONDecoder().decode(CompleteLinkingResponse.self, from: data)

            await MainActor.run {
                if apiResponse.success {
                    self.linkingSuccess = true
                    self.linkedDeviceName = apiResponse.linkedWith
                } else {
                    self.errorMessage = apiResponse.error ?? "Failed to link device"
                }
                self.isLinking = false
            }
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
                self.isLinking = false
            }
        }
    }
    
    func reset() {
        isLinking = false
        linkingSuccess = false
        linkedDeviceName = nil
        errorMessage = nil
    }
}
