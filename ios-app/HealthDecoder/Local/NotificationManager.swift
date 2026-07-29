import Foundation
import UserNotifications

/// iOS replacement for Android's `MedicineReminderManager` / `AppointmentReminderManager`
/// (`AlarmManager` + `BootReceiver`).
///
/// Key behavioural differences from Android, by platform design:
/// * Android sets exact alarms and re-arms them in a `BroadcastReceiver` after each fire and on
///   reboot. iOS schedules `UNCalendarNotificationTrigger`s that repeat on their own and survive
///   reboot without any equivalent of `BootReceiver`, so that whole layer is unnecessary here.
/// * Android can launch a full-screen lock-screen alarm Activity (`ReminderAlarmActivity`).
///   iOS has no equivalent — a background app cannot present UI. The closest is a
///   time-sensitive notification, which is what's used below; the large-text alarm view is
///   shown when the user taps into the app.
/// * iOS caps an app at 64 pending notification requests. Medicine reminders are therefore
///   keyed by time-of-day (like Android's `hour*60+minute` alarm keying) rather than per
///   medicine, so 4 daily slots cost 4 requests no matter how many medicines are due.
enum NotificationManager {
    private static let medicinePrefix = "medicine-slot-"
    private static let appointmentPrefix = "appointment-"

    /// Asks for notification permission. Safe to call repeatedly — iOS only prompts once.
    static func requestAuthorization() async -> Bool {
        do {
            return try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            return false
        }
    }

    static func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    // MARK: - Medicine reminders

    /// Rebuilds every medicine reminder from the current schedule store. Called after any
    /// schedule edit — mirrors Android's `MedicineReminderManager.scheduleAll`.
    static func rescheduleMedicines() {
        Task { await rescheduleMedicinesAsync() }
    }

    static func rescheduleMedicinesAsync() async {
        let center = UNUserNotificationCenter.current()
        guard await authorizationStatus() == .authorized else { return }

        // Clear existing medicine slots, then re-add — simpler and less error-prone than
        // diffing, and cheap since there are at most a handful of distinct times.
        let pending = await center.pendingNotificationRequests()
        let staleIDs = pending.map(\.identifier).filter { $0.hasPrefix(medicinePrefix) }
        center.removePendingNotificationRequests(withIdentifiers: staleIDs)

        // Group every enabled slot by its time-of-day, so medicines sharing a time share one
        // notification (exactly how Android keys its alarms).
        var byTime: [Int: [MedicineSchedule]] = [:]
        for schedule in MedicineScheduleStore.loadAll() {
            for (_, config) in schedule.slots where config.enabled {
                byTime[config.hour * 60 + config.minute, default: []].append(schedule)
            }
        }

        for (minutesOfDay, schedules) in byTime {
            let hour = minutesOfDay / 60
            let minute = minutesOfDay % 60
            let names = schedules.map { schedule -> String in
                let dose = schedule.dosage.isEmpty ? "" : " (\(schedule.dosage))"
                return "• \(schedule.medicineName)\(dose) — \(schedule.patientName)"
            }

            let content = UNMutableNotificationContent()
            content.title = "💊 Time for your medicine"
            content.body = names.joined(separator: "\n")
            content.sound = .default
            content.interruptionLevel = .timeSensitive
            content.userInfo = ["type": "medicine", "minutesOfDay": minutesOfDay]

            var dateComponents = DateComponents()
            dateComponents.hour = hour
            dateComponents.minute = minute
            let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: true)

            let request = UNNotificationRequest(
                identifier: "\(medicinePrefix)\(minutesOfDay)",
                content: content,
                trigger: trigger
            )
            try? await center.add(request)
        }
    }

    // MARK: - Appointment reminders

    static func rescheduleAppointments() {
        Task { await rescheduleAppointmentsAsync() }
    }

    static func rescheduleAppointmentsAsync() async {
        let center = UNUserNotificationCenter.current()
        guard await authorizationStatus() == .authorized else { return }

        let pending = await center.pendingNotificationRequests()
        let staleIDs = pending.map(\.identifier).filter { $0.hasPrefix(appointmentPrefix) }
        center.removePendingNotificationRequests(withIdentifiers: staleIDs)

        for appointment in AppointmentStore.loadAll() {
            guard let fireDate = nextFireDate(for: appointment) else { continue }

            let content = UNMutableNotificationContent()
            content.title = "🩺 Appointment reminder"
            let place = appointment.place.isEmpty ? "" : " at \(appointment.place)"
            content.body = "Dr. \(appointment.doctorName)\(place) — \(appointment.time)"
            content.sound = .default
            content.interruptionLevel = .timeSensitive
            content.userInfo = ["type": "appointment", "id": appointment.id]

            let components = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute], from: fireDate
            )
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            let request = UNNotificationRequest(
                identifier: "\(appointmentPrefix)\(appointment.id)",
                content: content,
                trigger: trigger
            )
            try? await center.add(request)
        }
    }

    /// Next occurrence at/after now, rolling a recurring appointment forward — mirrors
    /// `AppointmentReminderManager`'s calendar-advance loop.
    private static func nextFireDate(for appointment: AppointmentSchedule) -> Date? {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let baseDay = formatter.date(from: appointment.date) else { return nil }

        var components = Calendar.current.dateComponents([.year, .month, .day], from: baseDay)
        components.hour = appointment.hour
        components.minute = appointment.minute
        guard var fire = Calendar.current.date(from: components) else { return nil }

        let now = Date()
        if fire > now { return fire }

        // Past appointment: advance by the recurrence period until it's in the future.
        let step: DateComponents?
        switch appointment.recurrence {
        case "Daily": step = DateComponents(day: 1)
        case "Weekly": step = DateComponents(day: 7)
        case "Monthly": step = DateComponents(month: 1)
        case "3 Months": step = DateComponents(month: 3)
        case "6 Months": step = DateComponents(month: 6)
        case "1 Year": step = DateComponents(year: 1)
        default: step = nil
        }
        guard let step else { return nil } // non-recurring and already past — nothing to schedule

        var guardCounter = 0
        while fire <= now && guardCounter < 500 {
            guard let next = Calendar.current.date(byAdding: step, to: fire) else { return nil }
            fire = next
            guardCounter += 1
        }
        return fire > now ? fire : nil
    }

    /// Re-arms everything — call on app launch so edits made while notifications were denied
    /// (then later granted) take effect.
    static func rescheduleAll() {
        Task {
            await rescheduleMedicinesAsync()
            await rescheduleAppointmentsAsync()
        }
    }
}
