import Foundation

/// Port of `reminder/MedicineScheduleStore.kt`.
struct SlotConfig: Codable, Equatable {
    var enabled: Bool = false
    var hour: Int = 8
    var minute: Int = 0
}

struct MedicineSchedule: Codable, Identifiable, Equatable {
    var medicineName: String
    var patientName: String
    var dosage: String
    var frequency: String
    /// Keyed by slot name — "Morning" | "Afternoon" | "Evening" | "Night".
    var slots: [String: SlotConfig]

    /// Composite identity: a medicine is unique per patient (matches Android's `matches()`).
    var id: String { "\(patientName.lowercased())|\(medicineName.lowercased())" }

    func matches(_ name: String, _ patient: String) -> Bool {
        medicineName.caseInsensitiveCompare(name) == .orderedSame &&
        patientName.caseInsensitiveCompare(patient) == .orderedSame
    }
}

enum MedicineScheduleStore {
    private static let key = "medicine_schedules_v1"

    /// Slot order matters for display — Swift dictionaries are unordered, so UI iterates this.
    static let slotOrder = ["Morning", "Afternoon", "Evening", "Night"]
    static let defaultSlotTimes: [String: (hour: Int, minute: Int)] = [
        "Morning": (8, 0),
        "Afternoon": (13, 0),
        "Evening": (18, 0),
        "Night": (22, 0)
    ]

    static func loadAll() -> [MedicineSchedule] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([MedicineSchedule].self, from: data)) ?? []
    }

    static func saveAll(_ schedules: [MedicineSchedule]) {
        guard let data = try? JSONEncoder().encode(schedules) else { return }
        UserDefaults.standard.set(data, forKey: key)
        NotificationManager.rescheduleMedicines()
    }

    static func upsert(_ schedule: MedicineSchedule) {
        var list = loadAll()
        if let index = list.firstIndex(where: { $0.matches(schedule.medicineName, schedule.patientName) }) {
            list[index] = schedule
        } else {
            list.append(schedule)
        }
        saveAll(list)
    }

    static func delete(medicineName: String, patientName: String) {
        saveAll(loadAll().filter { !$0.matches(medicineName, patientName) })
    }

    static func clearAll() {
        UserDefaults.standard.removeObject(forKey: key)
        NotificationManager.rescheduleMedicines()
    }

    /// Creates a default schedule for a medicine that has none yet, enabling whichever slots the
    /// prescription implies. No-op if one already exists.
    static func autoSeedIfAbsent(
        medicineName: String, patientName: String, dosage: String,
        frequency: String, activeSlots: [String]
    ) {
        guard !loadAll().contains(where: { $0.matches(medicineName, patientName) }) else { return }
        var slots: [String: SlotConfig] = [:]
        for (slot, time) in defaultSlotTimes {
            slots[slot] = SlotConfig(enabled: activeSlots.contains(slot), hour: time.hour, minute: time.minute)
        }
        upsert(MedicineSchedule(
            medicineName: medicineName, patientName: patientName,
            dosage: dosage, frequency: frequency, slots: slots
        ))
    }

    /// Renames a medicine's schedule so the reminder keeps firing under the corrected name.
    /// If a schedule already exists under `newName`, the old one is dropped in its favour.
    static func rename(
        patientName: String, oldName: String, newName: String,
        dosage: String? = nil, frequency: String? = nil
    ) {
        var list = loadAll()
        guard !newName.isEmpty, oldName.caseInsensitiveCompare(newName) != .orderedSame else {
            // Same name — just refresh dosage/frequency if given.
            if dosage != nil || frequency != nil,
               let index = list.firstIndex(where: { $0.matches(oldName, patientName) }) {
                list[index].dosage = dosage ?? list[index].dosage
                list[index].frequency = frequency ?? list[index].frequency
                saveAll(list)
            }
            return
        }
        guard let oldIndex = list.firstIndex(where: { $0.matches(oldName, patientName) }) else { return }
        var renamed = list[oldIndex]
        renamed.medicineName = newName
        renamed.dosage = dosage ?? renamed.dosage
        renamed.frequency = frequency ?? renamed.frequency
        list.removeAll { $0.matches(newName, patientName) }
        if let index = list.firstIndex(where: { $0.matches(oldName, patientName) }) {
            list[index] = renamed
        } else {
            list.append(renamed)
        }
        saveAll(list)
    }

    /// Re-keys schedules when two mis-scanned patient-name variants are merged.
    static func renamePatient(from oldName: String, to newName: String) {
        guard oldName.caseInsensitiveCompare(newName) != .orderedSame else { return }
        var result: [MedicineSchedule] = []
        for schedule in loadAll() {
            guard schedule.patientName.caseInsensitiveCompare(oldName) == .orderedSame else {
                result.append(schedule)
                continue
            }
            var moved = schedule
            moved.patientName = newName
            // Drop any existing schedule for the same medicine already under the new name.
            result.removeAll { $0.matches(moved.medicineName, newName) }
            result.append(moved)
        }
        saveAll(result)
    }
}
