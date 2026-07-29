import SwiftUI

/// Shared building blocks that give every screen the same look as the Android app —
/// ports of `ui/Watermark.kt` and the card/empty-state composables in
/// `ui/DashboardComponents.kt`. Screens use these instead of plain `List`/`Form`, because
/// iOS's default list chrome looks nothing like Android's card-on-tinted-background design.

// MARK: - Screen chrome

extension View {
    /// Faint, centred logo behind a screen's content — the analog of `Modifier.appWatermark()`.
    /// Android blends at 6% alpha; the logo JPG has an opaque white background there, which
    /// `BlendMode.Multiply` neutralises. Here the same effect comes from low opacity over the
    /// app background, which reads identically without needing a blend mode.
    func appWatermark(opacity: Double = 0.06) -> some View {
        background(
            GeometryReader { geo in
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: min(geo.size.width, geo.size.height) * 0.85)
                    .opacity(opacity)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            }
        )
    }

    /// The standard screen background + watermark every Android screen sits on.
    func medicalScreenBackground() -> some View {
        background(Color.medicalBackground.ignoresSafeArea())
            .appWatermark()
    }
}

/// The small logo badge shown next to the title in every screen's top bar — port of
/// `TopBarLogo()`. Sits in a white rounded badge so the opaque-background logo doesn't
/// read as a stray white box in dark mode, exactly as on Android.
struct TopBarLogo: View {
    var size: CGFloat = 26

    var body: some View {
        Image("Logo")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(Color(.separator), lineWidth: 1)
            )
    }
}

/// Logo + bold title, used as the `principal` toolbar item so every screen's header matches
/// Android's `TopBarLogo() + Text(title)` row.
struct TopBarTitle: View {
    let title: String

    var body: some View {
        HStack(spacing: 8) {
            TopBarLogo()
            Text(title).font(.headline)
        }
    }
}

// MARK: - Cards & states

/// Centred icon + title + description for an empty screen — port of `EmptyStateView`.
struct EmptyStateView: View {
    let icon: String
    let title: String
    let description: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 56))
                .foregroundColor(.secondary.opacity(0.4))
            Text(title)
                .fontWeight(.bold)
                .foregroundColor(.secondary)
            Text(description)
                .font(.subheadline)
                .foregroundColor(.secondary.opacity(0.75))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(32)
    }
}

/// The white rounded card everything sits in — Android's `Card(shape = 12.dp, elevation = 2.dp,
/// containerColor = surface)`.
struct MedicalCard<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.medicalSurface)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.10), radius: 2, y: 1)
    }
}

/// Small coloured pill — the report-type badge and medication chips both use this shape
/// (`RoundedCornerShape(6.dp)` with 8×4 padding on Android).
struct BadgePill: View {
    let text: String
    var background: Color
    var foreground: Color
    var bold: Bool = false
    var size: CGFloat = 11

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: bold ? .bold : .regular))
            .foregroundColor(foreground)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

/// One report in the Records list — port of `ReportItemCard`: type badge, patient, date,
/// medication chips, then a truncated comments line.
struct ReportItemCard: View {
    let report: MedicalReport

    /// Type → (container, content) colour pair, matching the Android original exactly.
    private var typeColors: (background: Color, foreground: Color) {
        switch report.reportType?.lowercased() {
        case "prescription": return (.reportPrescriptionBg, .reportPrescription)
        case "lab report": return (.reportLabBg, .reportLab)
        default: return (Color(hex: 0xECEFF1), Color(hex: 0x455A64))
        }
    }

    var body: some View {
        MedicalCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    BadgePill(
                        text: (report.reportType ?? tr("Report Details")).uppercased(),
                        background: typeColors.background,
                        foreground: typeColors.foreground,
                        bold: true
                    )
                    if !report.analyzed {
                        BadgePill(
                            text: tr("PROCESSING").uppercased(),
                            background: Color.statusLow.opacity(0.18),
                            foreground: .statusLow,
                            bold: true
                        )
                    }
                    Spacer()
                }

                Text("\(tr("Patient")): \(report.patientName?.isEmpty == false ? report.patientName! : tr("Unknown"))")
                    .font(.headline)

                Text(report.reportDate ?? tr("No Date"))
                    .font(.caption)
                    .foregroundColor(.secondary)

                if !report.medications.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(Array(report.medications.enumerated()), id: \.offset) { _, medication in
                            BadgePill(
                                text: medication.dosage.isEmpty
                                    ? medication.name
                                    : "\(medication.name) \(medication.dosage)",
                                background: Color.medicalSurfaceVariant,
                                foreground: .secondary,
                                size: 12
                            )
                        }
                    }
                }

                if let comments = report.comments, !comments.isEmpty {
                    Divider().padding(.vertical, 2)
                    Text(comments)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
            }
        }
    }
}

// MARK: - Controls

/// Rounded search field matching Android's `OutlinedTextField(shape = 12.dp)` with a leading
/// magnifier and a clear button.
struct MedicalSearchField: View {
    @Binding var text: String
    var placeholder: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundColor(.secondary)
            TextField(placeholder, text: $text)
                .autocorrectionDisabled()
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.medicalSurface)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12).stroke(Color(.separator), lineWidth: 1)
        )
    }
}

/// Pill-shaped selectable chip — Android's `FilterChip(shape = 20.dp)`, filled with the
/// primary colour when selected.
struct MedicalFilterChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if isSelected {
                    Image(systemName: "checkmark").font(.system(size: 11, weight: .bold))
                }
                Text(label).font(.system(size: 12))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(isSelected ? Color.medicalTeal : Color.clear)
            .foregroundColor(isSelected ? .white : .primary)
            .clipShape(Capsule())
            .overlay(
                Capsule().stroke(isSelected ? Color.clear : Color(.separator), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// Circular primary-coloured floating action button, matching Android's `FloatingActionButton`.
struct MedicalFAB<Label: View>: View {
    @ViewBuilder var label: Label

    var body: some View {
        label
            .font(.title2)
            .foregroundColor(.white)
            .frame(width: 56, height: 56)
            .background(Color.medicalTeal)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.25), radius: 6, y: 3)
    }
}

/// Wraps children onto multiple lines — the analog of Compose's `FlowRow`, used for the
/// medication chips on a report card.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth, rowWidth > 0 {
                totalHeight += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth, height: totalHeight + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
