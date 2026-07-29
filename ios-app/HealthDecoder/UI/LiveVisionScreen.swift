import SwiftUI
import AVFoundation

/// "Smart Health Lens" — live camera feed with an Identify button that captures a frame and
/// asks Gemini vision to name the medicine in view, then opens `MedicineInfoSheet`. Mirrors
/// `ui/LiveVisionScreen.kt`.
struct LiveVisionScreen: View {
    @StateObject private var camera = CameraController()
    @State private var permissionGranted = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    @State private var isIdentifying = false
    @State private var statusMessage: String?
    @State private var identifiedName: String?

    var body: some View {
        ZStack {
            if permissionGranted {
                CameraPreviewView(session: camera.session)
                    .ignoresSafeArea()
            } else {
                Color(red: 0.12, green: 0.16, blue: 0.22).ignoresSafeArea()
                VStack(spacing: 16) {
                    Text(tr("Camera permission is needed to identify medicines."))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Button(tr("Grant Permission")) { requestPermission() }
                        .buttonStyle(.borderedProminent)
                }
            }

            VStack {
                Spacer()
                VStack(alignment: .leading, spacing: 16) {
                    Text(statusMessage ?? "Point camera at a medicine strip to instantly identify it, see usage instructions, and check for interactions.")
                        .foregroundColor(statusMessage != nil ? Color.statusLow : .white)
                        .italic()
                        .font(.subheadline)

                    Button {
                        identify()
                    } label: {
                        if isIdentifying {
                            ProgressView().tint(.white).frame(maxWidth: .infinity)
                        } else {
                            Text(tr("📸 Identify")).frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isIdentifying || !permissionGranted)
                }
                .padding(20)
                .background(Color.black.opacity(0.6))
            }
        }
                .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("Smart Health Lens")) }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if permissionGranted {
                camera.configure()
                camera.start()
            }
        }
        .onDisappear { camera.stop() }
        .sheet(isPresented: Binding(
            get: { identifiedName != nil },
            set: { if !$0 { identifiedName = nil } }
        )) {
            if let name = identifiedName {
                MedicineInfoSheet(medicineName: name)
            }
        }
    }

    private func requestPermission() {
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                permissionGranted = granted
                if granted {
                    camera.configure()
                    camera.start()
                }
            }
        }
    }

    private func identify() {
        guard !isIdentifying else { return }
        isIdentifying = true
        statusMessage = nil
        camera.capturePhoto { data in
            Task {
                guard let data else {
                    await MainActor.run {
                        isIdentifying = false
                        statusMessage = "Capture failed. Please try again."
                    }
                    return
                }
                let name = await MedicalEngine.identifyMedicine(imageData: data, mimeType: "image/jpeg")
                await MainActor.run {
                    isIdentifying = false
                    if name.isEmpty {
                        statusMessage = "Couldn't read a medicine name — hold steady and a bit closer, then try again."
                    } else {
                        identifiedName = name
                    }
                }
            }
        }
    }
}

/// Owns the `AVCaptureSession` for the live preview + still-photo capture. All session mutation
/// happens on a dedicated serial queue, per Apple's guidance (never on the main thread).
final class CameraController: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let sessionQueue = DispatchQueue(label: "com.example.medicalscanner.camera")
    private var captureCompletion: ((Data?) -> Void)?

    func configure() {
        sessionQueue.async { [session, photoOutput] in
            guard session.inputs.isEmpty else { return }
            session.beginConfiguration()
            session.sessionPreset = .photo
            defer { session.commitConfiguration() }
            guard
                let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: device),
                session.canAddInput(input)
            else { return }
            session.addInput(input)
            if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        }
    }

    func start() {
        sessionQueue.async { [session] in
            guard !session.isRunning else { return }
            session.startRunning()
        }
    }

    func stop() {
        sessionQueue.async { [session] in
            guard session.isRunning else { return }
            session.stopRunning()
        }
    }

    func capturePhoto(completion: @escaping (Data?) -> Void) {
        sessionQueue.async { [photoOutput] in
            self.captureCompletion = completion
            let settings = AVCapturePhotoSettings()
            photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        captureCompletion?(data)
        captureCompletion = nil
    }
}

private struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}

    final class PreviewUIView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
