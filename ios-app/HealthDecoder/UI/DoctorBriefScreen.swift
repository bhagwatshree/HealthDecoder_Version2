import SwiftUI
import AVFoundation

/// Generates and reads aloud a doctor-visit summary tied to the next appointment — mirrors
/// `ui/DoctorBriefScreen.kt`. The brief text here is a lighter-weight version than Android's
/// (which pulls from `DashboardEngine`'s full trend/narrative engine, a later phase): this one
/// summarizes the most recent report's abnormal parameters and current medications directly
/// from `LocalRepository`, without cross-report trend analysis yet.
struct DoctorBriefScreen: View {
    let patientName: String

    @State private var appointment: AppointmentSchedule?
    @State private var briefText = "Preparing brief…"
    @State private var isLoading = true
    @State private var showShareSheet = false
    @State private var showAddAppointment = false
    @StateObject private var speech = SpeechController()

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                appointmentCard
                readAloudCard
                briefCard
                Button {
                    showShareSheet = true
                } label: {
                    Label(tr("Share with doctor"), systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.whatsappGreen)
                .disabled(isLoading)
            }
            .padding()
        }
        .navigationTitle(tr("Doctor Visit Brief"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showShareSheet = true } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(isLoading)
            }
        }
        .onAppear(perform: load)
        .sheet(isPresented: $showShareSheet) { ShareSheet(items: [briefText]) }
        .sheet(isPresented: $showAddAppointment) {
            AppointmentEditSheet(existing: nil) { load() }
        }
    }

    private var appointmentCard: some View {
        HStack(spacing: 16) {
            Text("👨‍⚕️")
                .font(.system(size: 24))
                .frame(width: 56, height: 56)
                .background(Circle().fill(Color.medicalTeal))
            VStack(alignment: .leading, spacing: 2) {
                if let appointment {
                    Text("Dr. \(appointment.doctorName)").font(.headline)
                    Text(appointment.place.isEmpty ? "Appointment" : appointment.place)
                        .foregroundColor(.secondary)
                    Text("\(appointment.date), \(appointment.time)")
                        .foregroundColor(.accentColor)
                        .fontWeight(.semibold)
                } else {
                    Text(tr("No upcoming appointment")).font(.headline)
                    Button(tr("Add Appointment")) { showAddAppointment = true }
                        .font(.footnote)
                }
            }
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(14)
    }

    private var readAloudCard: some View {
        HStack(spacing: 12) {
            Button {
                speech.toggle(text: spokenText)
            } label: {
                Image(systemName: speech.isPlaying ? "stop.fill" : "play.fill")
                    .foregroundColor(.white)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(Color.brandBlue))
            }
            .disabled(isLoading)
            VStack(alignment: .leading) {
                Text(tr("Read the brief aloud")).font(.subheadline.bold())
                Text(speech.isPlaying ? "Playing…" : "Tap play to hear this summary")
                    .font(.caption)
                    .foregroundColor(Color.brandBlue)
            }
            Spacer()
        }
        .padding(16)
        .background(Color.brandBlueBg)
        .cornerRadius(14)
    }

    private var briefCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tr("Summary for the doctor"))
                .font(.subheadline.bold())
                .foregroundColor(Color.brandCardText)
            if isLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(tr("Preparing brief…")).foregroundColor(Color.brandCardText)
                }
            } else {
                Text(briefText).foregroundColor(Color.brandCardText)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandCard)
        .cornerRadius(14)
    }

    private var spokenText: String {
        briefText
            .replacingOccurrences(of: "[🩺⚠💊•↗↘↑↓→]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\n", with: ". ")
    }

    private func load() {
        isLoading = true
        let (appt, text) = Self.buildBrief(patientName: patientName)
        appointment = appt
        briefText = text
        isLoading = false
    }

    private static func buildBrief(patientName: String) -> (AppointmentSchedule?, String) {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        dateFormatter.timeZone = TimeZone(identifier: "UTC")
        let today = dateFormatter.string(from: Date())

        let appointments = AppointmentStore.loadAll()
        let next = appointments
            .filter { $0.date >= today }
            .sorted { ($0.date, $0.time) < ($1.date, $1.time) }
            .first
            ?? appointments.sorted { $0.date > $1.date }.first

        let target = patientName.trimmingCharacters(in: .whitespaces)
        let reports = LocalRepository.getAllReports()
            .filter { target.isEmpty || ($0.patientName ?? "").caseInsensitiveCompare(target) == .orderedSame }
            .sorted { ($0.reportDate ?? $0.createdAt) > ($1.reportDate ?? $1.createdAt) }

        guard let latest = reports.first else {
            let suffix = target.isEmpty ? "" : " for \(target)"
            return (next, "No records found\(suffix) yet — scan a report first to prepare a doctor brief.")
        }

        let who = target.isEmpty ? (latest.patientName?.isEmpty == false ? latest.patientName! : "Patient") : target
        var lines: [String] = []
        var header = "🩺 Doctor Brief — \(who)"
        if let profile = AppSettings.familyProfiles.first(where: { $0.name.caseInsensitiveCompare(who) == .orderedSame }) {
            var bits: [String] = []
            if let age = age(fromDOB: profile.dateOfBirth) { bits.append("\(age)y") }
            if !profile.sex.isEmpty { bits.append(profile.sex) }
            if !bits.isEmpty { header += " (\(bits.joined(separator: ", ")))" }
        }
        lines.append(header)
        lines.append("Last report: \(latest.reportDate ?? "—") · \(latest.reportType ?? "Report")")
        lines.append("\(reports.count) report(s) on file")

        let abnormal = (latest.testResults?.parameters ?? []).filter {
            let status = ($0.status ?? "").lowercased()
            return !status.isEmpty && status != "normal"
        }
        if !abnormal.isEmpty {
            lines.append("")
            lines.append("⚠ Needs attention (latest report):")
            for parameter in abnormal.prefix(5) {
                lines.append("• \(parameter.name): \(parameter.value) \(parameter.unit) (\(parameter.status ?? ""))")
            }
        }

        var seenMeds = Set<String>()
        let meds = reports.flatMap { $0.medications.map { $0.name } }
            .filter { !$0.isEmpty }
            .filter { seenMeds.insert($0.lowercased()).inserted }
        if !meds.isEmpty {
            lines.append("")
            lines.append("💊 Current medicines:")
            for med in meds.prefix(8) { lines.append("• \(med)") }
        }

        lines.append("")
        lines.append("— Prepared by HealthDecoder")

        return (next, lines.joined(separator: "\n"))
    }

    private static func age(fromDOB dob: String) -> Int? {
        guard !dob.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = TimeZone(identifier: "UTC")
        guard let birthDate = formatter.date(from: dob) else { return nil }
        return Calendar.current.dateComponents([.year], from: birthDate, to: Date()).year
    }
}

/// On-device text-to-speech (mirrors Android's "Phone" TTS engine option — Sarvam/Gemini TTS
/// engines are a later phase).
final class SpeechController: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published var isPlaying = false
    private let synthesizer = AVSpeechSynthesizer()

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func toggle(text: String) {
        if isPlaying {
            stop()
            return
        }
        // Playback has to share the session with dictation's .record category, otherwise
        // speaking right after using the mic stays silent.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
        try? AVAudioSession.sharedInstance().setActive(true)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
        synthesizer.speak(utterance)
        isPlaying = true
    }

    func stop() {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        isPlaying = false
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        isPlaying = false
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        isPlaying = false
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

