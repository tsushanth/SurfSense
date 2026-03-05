import Foundation
import Security
import UIKit

/// Keychain-based secure storage for sensitive data (API keys, device IDs)
class SecureStorage {
    static let shared = SecureStorage()

    private init() {}

    // MARK: - API Key

    var apiKey: String? {
        get { getString(forKey: Config.KeychainKeys.apiKey) }
        set {
            if let value = newValue {
                setString(value, forKey: Config.KeychainKeys.apiKey)
            } else {
                deleteItem(forKey: Config.KeychainKeys.apiKey)
            }
        }
    }

    // MARK: - Device ID

    var deviceId: String {
        if let stored = getString(forKey: Config.KeychainKeys.deviceId) {
            return stored
        }
        let newId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        setString(newId, forKey: Config.KeychainKeys.deviceId)
        return newId
    }

    // MARK: - Auth Header Helper

    func addAuthHeader(to request: inout URLRequest) {
        if let key = apiKey {
            request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        }
    }

    // MARK: - Keychain Operations

    private func setString(_ value: String, forKey key: String) {
        guard let data = value.data(using: .utf8) else { return }

        // Delete existing item first
        deleteItem(forKey: key)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        SecItemAdd(query as CFDictionary, nil)
    }

    private func getString(forKey key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess,
              let data = result as? Data,
              let string = String(data: data, encoding: .utf8) else {
            return nil
        }

        return string
    }

    private func deleteItem(forKey key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }

    func clearAll() {
        deleteItem(forKey: Config.KeychainKeys.apiKey)
        deleteItem(forKey: Config.KeychainKeys.deviceId)
    }
}
