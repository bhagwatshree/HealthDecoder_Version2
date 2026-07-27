import SwiftUI
import AVFoundation

struct ScanScreen: View {
    var body: some View {
        VStack(spacing: 20) {
            Button(action: {
                // Launch Camera
            }) {
                Label("Camera Scan", systemImage: "camera.fill")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }
            
            Button(action: {
                // Launch File Picker
            }) {
                Label("From Device (PDF/Image)", systemImage: "folder.fill")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.green)
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }
            
            Button(action: {
                // Launch QR Scanner (ML Kit)
            }) {
                Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.orange)
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }
        }
        .padding()
        .navigationTitle("Scan Report")
    }
}
