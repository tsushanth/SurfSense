import SwiftUI

class DeviceTrackingService: ObservableObject {
    @Published var isGeneratingCode = false
    @Published var generatedCode: String?
    @Published var errorMessage: String?

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = Config.requestTimeout
        config.timeoutIntervalForResource = Config.resourceTimeout
        self.session = URLSession(configuration: config)
    }

    func initiateLinking(clientId: String) async {
        await MainActor.run {
            isGeneratingCode = true
            errorMessage = nil
        }

        do {
            guard let url = URL(string: "\(Config.apiBaseURL)/initiate-linking") else {
                throw URLError(.badURL)
            }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            SecureStorage.shared.addAuthHeader(to: &request)

            let requestBody = InitiateLinkingRequest(clientId: clientId)
            request.httpBody = try JSONEncoder().encode(requestBody)

            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw URLError(.badServerResponse)
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                throw NetworkError.httpError(statusCode: httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(InitiateLinkingResponse.self, from: data)

            await MainActor.run {
                if apiResponse.success, let code = apiResponse.code {
                    self.generatedCode = code
                } else {
                    self.errorMessage = apiResponse.error ?? "Failed to generate code"
                }
                self.isGeneratingCode = false
            }
        } catch {
            await MainActor.run {
                self.errorMessage = Self.friendlyErrorMessage(for: error)
                self.isGeneratingCode = false
            }
        }
    }

    func resetCode() {
        generatedCode = nil
        errorMessage = nil
    }

    private static func friendlyErrorMessage(for error: Error) -> String {
        if let networkError = error as? NetworkError {
            return networkError.localizedDescription
        }

        switch error {
        case let urlError as URLError:
            switch urlError.code {
            case .timedOut:
                return "Request timed out. Please try again."
            case .notConnectedToInternet:
                return "No internet connection. Please check your network."
            case .networkConnectionLost:
                return "Connection lost. Please try again."
            default:
                return "Network error. Please try again."
            }
        default:
            return error.localizedDescription
        }
    }
}

enum NetworkError: LocalizedError {
    case httpError(statusCode: Int)
    case invalidResponse
    case decodingError

    var errorDescription: String? {
        switch self {
        case .httpError(let statusCode):
            switch statusCode {
            case 400: return "Invalid request. Please try again."
            case 401: return "Unauthorized. Please re-link your device."
            case 404: return "Service not found. Please update the app."
            case 500...599: return "Server error. Please try again later."
            default: return "Request failed (Error \(statusCode))."
            }
        case .invalidResponse:
            return "Invalid server response."
        case .decodingError:
            return "Failed to process server response."
        }
    }
}
