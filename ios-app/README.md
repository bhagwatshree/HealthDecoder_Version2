# HealthDecoder iOS App

This folder contains the Native Swift & SwiftUI implementation of the HealthDecoder application. It is designed to match the capabilities of the Jetpack Compose Android app.

## Prerequisites
- macOS running Xcode 15+
- iOS 17.0+ deployment target

## Getting Started
1. Open Xcode and select "Open a project or file".
2. Select the `HealthDecoder` directory.
3. If dependencies (like Charts or any external packages) need resolving, let Xcode fetch them.
4. Update `APIClient.swift` with your deployed backend API Gateway URL.
5. Build and Run on a Simulator or connected iOS device.

## Features Implemented
- **Home Dashboard**: 6 main action tiles (Records, Reminders, Medications, Pending Tests, Trends, Scan).
- **Core Models**: Full Codable Swift definitions mirroring the Android data schemas.
- **Scanning UI**: Stubs for CameraX/Vision ML Kit equivalents in iOS.
- **Account Settings**: Language toggles and lab unit conversions matching the backend logic.

*Note: As this scaffolding was created on a Windows environment, it may require creating an `.xcodeproj` file or setting it up as a Swift Package when opened on a Mac.*
