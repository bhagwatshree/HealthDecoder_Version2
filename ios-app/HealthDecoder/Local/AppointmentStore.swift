import Foundation

/// Port of `reminder/AppointmentStore.kt`'s `AppointmentSchedule`.
struct AppointmentSchedule: Codable, Identifiable, Equatable {
    var id: String = UUID().uuidString
    var doctorName: String
    var date: String // "YYYY-MM-DD"
    var time: String // "HH:mm"
    var place: String
    var recurrence: String = "None" // None | Daily | Weekly | Monthly | 3 Months | 6 Months | 1 Year
    var hour: Int
    var minute: Int

    static let recurrenceOptions = ["None", "Daily", "Weekly", "Monthly", "3 Months", "6 Months", "1 Year"]
}

enum AppointmentStore {
    private static let key = "appointment_schedules_v1"

    static func loadAll() -> [AppointmentSchedule] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([AppointmentSchedule].self, from: data)) ?? []
    }

    static func saveAll(_ list: [AppointmentSchedule]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    static func upsert(_ appointment: AppointmentSchedule) {
        var list = loadAll()
        if let index = list.firstIndex(where: { $0.id == appointment.id }) {
            list[index] = appointment
        } else {
            list.append(appointment)
        }
        saveAll(list)
        NotificationManager.rescheduleAppointments()
    }

    static func delete(id: String) {
        saveAll(loadAll().filter { $0.id != id })
        NotificationManager.rescheduleAppointments()
    }

    static func clearAll() {
        UserDefaults.standard.removeObject(forKey: key)
        NotificationManager.rescheduleAppointments()
    }
}
