//
//  LinkedDevicesView.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct LinkedDevicesView: View {
    @StateObject private var service = LinkedDevicesService()
    @State private var deviceToUnlink: LinkedDeviceViewModel?
    @State private var showUnlinkConfirmation = false
    
    var body: some View {
        NavigationView {
            ScrollView {
                LazyVStack(spacing: 16) {
                    // Use adaptive layout for different screen sizes
                    if UIDevice.current.userInterfaceIdiom == .pad {
                        iPadLayout
                    } else {
                        iPhoneLayout
                    }
                }
                .padding(.horizontal, horizontalPadding)
                .padding(.vertical, 16)
            }
            .navigationTitle("Linked Devices")
            .background(Color(.systemGroupedBackground))
            .refreshable {
                await service.fetchLinkedDevices()
            }
            .task {
                await service.fetchLinkedDevices()
            }
            .alert("Remove Device", isPresented: $showUnlinkConfirmation) {
                Button("Cancel", role: .cancel) {
                    deviceToUnlink = nil
                }
                Button("Remove", role: .destructive) {
                    if let device = deviceToUnlink {
                        Task {
                            await unlinkDevice(device)
                        }
                    }
                }
            } message: {
                if let device = deviceToUnlink {
                    Text("Are you sure you want to remove \(device.name)? This device will no longer be able to track your usage.")
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle()) // Better for iPad
    }
    
    // MARK: - iPad Layout
    private var iPadLayout: some View {
        VStack(spacing: 24) {
            // Top section with unified usage
            HStack(alignment: .top, spacing: 20) {
                // Unified Usage Card - takes 60% width
                UnifiedUsageCard(usageData: service.unifiedUsage)
                    .frame(maxWidth: .infinity, alignment: .leading)
                
                // Action buttons - takes 40% width
                VStack(spacing: 16) {
                    Text("Quick Actions")
                        .font(.headline)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    NavigationLink(destination: AddDeviceView()) {
                        ActionButton(
                            title: "Add Device",
                            icon: "plus",
                            style: .primary
                        )
                    }
                    
                    NavigationLink(destination: ShareDeviceView()) {
                        ActionButton(
                            title: "Share This Device",
                            icon: "square.and.arrow.up",
                            style: .secondary
                        )
                    }
                    
                    Spacer()
                }
                .frame(maxWidth: 300)
            }
            
            // Devices grid section
            devicesGridSection
        }
    }
    
    // MARK: - iPhone Layout
    private var iPhoneLayout: some View {
        VStack(spacing: 16) {
            // Unified Usage Card
            UnifiedUsageCard(usageData: service.unifiedUsage)
            
            // Loading/Error/Content
            if service.isLoading {
                ProgressView("Loading linked devices...")
                    .frame(maxWidth: .infinity, minHeight: 100)
            } else if let error = service.errorMessage {
                ErrorCard(message: error) {
                    Task {
                        await service.fetchLinkedDevices()
                    }
                }
            } else if service.linkedDevices.isEmpty {
                EmptyStateCard()
            } else {
                VStack(spacing: 12) {
                    ForEach(service.linkedDevices) { device in
                        LinkedDeviceCard(
                            device: device,
                            isUnlinking: service.isUnlinking,
                            onRemove: {
                                deviceToUnlink = device
                                showUnlinkConfirmation = true
                            }
                        )
                    }
                }
            }
            
            // Action Buttons
            VStack(spacing: 12) {
                NavigationLink(destination: AddDeviceView()) {
                    ActionButton(
                        title: "Add Device",
                        icon: "plus",
                        style: .primary
                    )
                }
                
                NavigationLink(destination: ShareDeviceView()) {
                    ActionButton(
                        title: "Share This Device",
                        icon: "square.and.arrow.up",
                        style: .secondary
                    )
                }
            }
        }
    }
    
    // MARK: - Content Section
    private var contentSection: some View {
        Group {
            if service.isLoading {
                ProgressView("Loading linked devices...")
                    .frame(maxWidth: .infinity, minHeight: 100)
            } else if let error = service.errorMessage {
                ErrorCard(message: error) {
                    Task {
                        await service.fetchLinkedDevices()
                    }
                }
            } else if service.linkedDevices.isEmpty {
                EmptyStateCard()
            } else {
                devicesListSection
            }
        }
    }
    
    // MARK: - Devices Grid Section (iPad)
    private var devicesGridSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            if service.isLoading {
                ProgressView("Loading linked devices...")
                    .frame(maxWidth: .infinity, minHeight: 100)
            } else if let error = service.errorMessage {
                ErrorCard(message: error) {
                    Task {
                        await service.fetchLinkedDevices()
                    }
                }
            } else if service.linkedDevices.isEmpty {
                EmptyStateCard()
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Linked Devices (\(service.linkedDevices.count))")
                        .font(.headline)
                        .fontWeight(.semibold)
                    
                    LazyVGrid(columns: gridColumns, spacing: 16) {
                        ForEach(service.linkedDevices) { device in
                            LinkedDeviceCard(
                                device: device,
                                isUnlinking: service.isUnlinking,
                                onRemove: {
                                    deviceToUnlink = device
                                    showUnlinkConfirmation = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // MARK: - Devices List Section (iPhone)
    private var devicesListSection: some View {
        VStack(spacing: 12) {
            ForEach(service.linkedDevices) { device in
                LinkedDeviceCard(
                    device: device,
                    isUnlinking: service.isUnlinking,
                    onRemove: {
                        deviceToUnlink = device
                        showUnlinkConfirmation = true
                    }
                )
            }
        }
    }
    
    // MARK: - Computed Properties
    private var horizontalPadding: CGFloat {
        UIDevice.current.userInterfaceIdiom == .pad ? 32 : 16
    }
    
    private var gridColumns: [GridItem] {
        [
            GridItem(.flexible(), spacing: 16),
            GridItem(.flexible(), spacing: 16)
        ]
    }
    
    private func unlinkDevice(_ device: LinkedDeviceViewModel) async {
        let success = await service.unlinkDevice(deviceId: device.id)
        
        await MainActor.run {
            deviceToUnlink = nil
            
            if success {
                // Show success feedback
                let impactFeedback = UIImpactFeedbackGenerator(style: .light)
                impactFeedback.impactOccurred()
            }
        }
    }
}
