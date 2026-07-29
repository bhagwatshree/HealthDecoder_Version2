import SwiftUI
import Speech
import AVFoundation

/// AI assistant over the patient's own records — port of `ui/ChatScreen.kt` (WhatsApp-styled
/// bubbles, voice input, read-aloud). `context` scopes the question the way Android's
/// "Asking about: X" per-screen chat does.
struct ChatScreen: View {
    let context: String

    @State private var messages: [ChatMessage] = []
    @State private var input = ""
    @State private var isSending = false
    @StateObject private var speech = SpeechController()
    @StateObject private var dictation = DictationController()

    var body: some View {
        VStack(spacing: 0) {
            if !context.isEmpty {
                Text("Asking about: \(context)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(8)
                    .background(Color.medicalSurfaceVariant)
            }

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        if messages.isEmpty {
                            VStack(spacing: 8) {
                                Text("💬").font(.system(size: 40))
                                Text(tr("Ask me about your reports"))
                                    .font(.headline)
                                Text("e.g. \u{201c}What does my last blood test mean?\u{201d}")
                                    .font(.footnote)
                                    .foregroundColor(.secondary)
                            }
                            .padding(.top, 60)
                        }
                        ForEach(messages) { message in
                            bubble(message).id(message.id)
                        }
                        if isSending {
                            HStack {
                                ProgressView().padding(.leading, 12)
                                Text(tr("Thinking…")).font(.caption).foregroundColor(.secondary)
                                Spacer()
                            }
                        }
                    }
                    .padding(12)
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }

            Divider()

            HStack(spacing: 8) {
                Button {
                    dictation.toggle { transcript in input = transcript }
                } label: {
                    Image(systemName: dictation.isRecording ? "mic.fill" : "mic")
                        .foregroundColor(dictation.isRecording ? .statusHigh : .medicalTeal)
                }
                TextField(tr("Type or tap mic…"), text: $input, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button {
                    send()
                } label: {
                    Image(systemName: "paperplane.fill")
                        .foregroundColor(canSend ? .medicalTeal : .secondary)
                }
                .disabled(!canSend)
            }
            .padding(10)
        }
                .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("AI Health Assistant")) }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { speech.stop(); dictation.stop() }
    }

    private var canSend: Bool {
        !input.trimmingCharacters(in: .whitespaces).isEmpty && !isSending
    }

    private func bubble(_ message: ChatMessage) -> some View {
        let isUser = message.role == "user"
        return HStack {
            if isUser { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 6) {
                Text(message.content)
                    .foregroundColor(isUser ? .white : Color.brandCardText)
                if !isUser {
                    Button {
                        speech.toggle(text: message.content)
                    } label: {
                        Label(
                            speech.isPlaying ? "Stop" : "Read aloud",
                            systemImage: speech.isPlaying ? "stop.fill" : "speaker.wave.2.fill"
                        )
                        .font(.caption2)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.medicalTeal)
                }
            }
            .padding(10)
            .background(isUser ? Color.medicalTeal : Color.brandCard)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            if !isUser { Spacer(minLength: 40) }
        }
    }

    private func send() {
        let question = input.trimmingCharacters(in: .whitespaces)
        guard !question.isEmpty else { return }
        dictation.stop()
        input = ""
        messages.append(ChatMessage(role: "user", content: question))
        isSending = true

        // Scope the reports handed to the model by the active patient, matching how Android's
        // per-screen chat keeps answers to what's on screen.
        var reports = LocalRepository.getAllReports()
        if let active = AppSettings.activePatient, !active.isEmpty {
            reports = reports.filter { ($0.patientName ?? "").caseInsensitiveCompare(active) == .orderedSame }
        }
        let history = messages

        Task {
            let scopedQuestion = context.isEmpty ? question : "[Context: \(context)] \(question)"
            let result = await MedicalEngine.chat(question: scopedQuestion, reports: reports, history: history)
            await MainActor.run {
                messages.append(ChatMessage(role: "assistant", content: result.answer))
                isSending = false
            }
        }
    }
}

/// Microphone dictation via `SFSpeechRecognizer` — the analog of Android's `RecognizerIntent`
/// voice input in ChatScreen.
@MainActor
final class DictationController: NSObject, ObservableObject {
    @Published var isRecording = false

    private let recognizer = SFSpeechRecognizer(locale: Locale.current)
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    func toggle(onTranscript: @escaping (String) -> Void) {
        if isRecording { stop() } else { start(onTranscript: onTranscript) }
    }

    private func start(onTranscript: @escaping (String) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else { return }
            AVAudioApplication.requestRecordPermission { granted in
                guard granted else { return }
                Task { @MainActor in self.beginSession(onTranscript: onTranscript) }
            }
        }
    }

    private func beginSession(onTranscript: @escaping (String) -> Void) {
        guard let recognizer, recognizer.isAvailable else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            self.request = request

            let inputNode = audioEngine.inputNode
            let format = inputNode.outputFormat(forBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            audioEngine.prepare()
            try audioEngine.start()
            isRecording = true

            task = recognizer.recognitionTask(with: request) { result, error in
                if let result {
                    Task { @MainActor in onTranscript(result.bestTranscription.formattedString) }
                }
                if error != nil || result?.isFinal == true {
                    Task { @MainActor in self.stop() }
                }
            }
        } catch {
            stop()
        }
    }

    func stop() {
        guard isRecording || audioEngine.isRunning else { return }
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
