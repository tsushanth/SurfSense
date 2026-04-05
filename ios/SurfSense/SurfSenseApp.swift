//
//  SurfSenseApp.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 5/29/25.
//

import SwiftUI

@main
struct SurfSenseApp: App {
    @StateObject private var screenTimeService = ScreenTimeService.shared

    init() {
        PeriodicSubmission.registerBackgroundTask()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(screenTimeService)
                .task {
                    guard !ProcessInfo.processInfo.arguments.contains("FASTLANE_SNAPSHOT") else { return }
                    // Register device and obtain API key before any authenticated calls
                    await RegistrationService.shared.registerIfNeeded()
                    PeriodicSubmission.scheduleBackgroundSync()
                }
        }
    }
}
