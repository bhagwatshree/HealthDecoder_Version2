# Proguard rules for Medical Assist (MA)

# Keep room classes and annotations
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Keep SQLCipher classes
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Keep JavaMail classes
-keep class javax.mail.** { *; }
-dontwarn javax.mail.**
-keep class com.sun.mail.** { *; }
-dontwarn com.sun.mail.**
-keep class java.beans.** { *; }
-dontwarn java.beans.**

# Gson reflects on field names/generics to (de)serialize — without these, R8's release-only
# obfuscation silently renames/strips fields and every API/local-cache JSON round-trip breaks,
# even though debug builds (unminified) never show the bug.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.stream.** { *; }
-keep class com.healthdecoder.app.model.** { *; }
# The scan-extraction DTOs -- ScanExtraction, MultiScanExtraction, FoundDate, RecommendedTest,
# FollowUp -- live in .ai, not .model, and were covered by no keep rule. That single omission
# broke EVERY scan in release builds: keeping only the @SerializedName FIELDS (rule below) still
# lets R8 obfuscate and merge the enclosing CLASS, which loses a nested generic's element type.
# List<FoundDate> then deserialized into LinkedTreeMaps and threw a bare ClassCastException at
# the first element access, far from Gson -- which the scan pipeline reported to users as
# "check your internet connection".
#
# Confirmed on-device by retracing the crash to DateResolver.resolve() reading section.datesFound.
# A conditional `-if class * { @SerializedName <fields>; } -keep class <1>` was tried first and
# looked more elegant, but did NOT keep FoundDate -- verify any replacement against
# build/outputs/mapping/release/mapping.txt, where a kept class maps to ITSELF.
-keep class com.healthdecoder.app.ai.** { *; }
# Same exposure, found the same way (a LinkedTreeMap -> SlotConfig crash in
# MedicineReminderManager.scheduleAll): every package holding a class Gson deserializes into
# must be kept, not just .model. These are the packages with fromJson/TypeToken call sites --
# .reminder (MedicineSchedule, SlotConfig, AppointmentSchedule), .backup (ExportManager.Payload)
# and .local (RemoteHealthTip, FamilyProfile, plus the Room converters' targets).
#
# Kept wholesale by package rather than class-by-class on purpose. Being surgical here failed
# twice, and each failure shipped as a release-only crash that debug builds never reproduce;
# the lost shrinking is a cheap price. If you narrow these, verify against
# build/outputs/mapping/release/mapping.txt -- a kept class maps to ITSELF.
-keep class com.healthdecoder.app.reminder.** { *; }
-keep class com.healthdecoder.app.backup.** { *; }
-keep class com.healthdecoder.app.local.** { *; }
-keep class com.healthdecoder.app.network.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}


# `new TypeToken<List<X>>() {}` anonymous subclasses (used throughout for generic JSON
# collections/maps) need their own generic superclass signature preserved too — R8 can still
# erase that even with -keepattributes Signature above unless the subclasses are kept directly.
# Without this, any TypeToken-based Gson call throws IllegalStateException at runtime in release
# builds only (confirmed crash: opening Server Settings, via RemoteUiTranslations' TypeToken use).
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Retrofit inspects interface methods/annotations via reflection at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# ML Kit text recognition (on-device OCR: OcrEngine.localOcrPages, PiiRedactor, ScanScreen).
# ML Kit resolves its recognizer implementations reflectively through its own component
# registry, so R8 shrinking leaves the option/impl classes half-present: the failure is a bare
# NullPointerException from inside obfuscated ML Kit code the first time a recognizer is built,
# release-only, with nothing in the trace naming ML Kit as the cause. Same defect shape as the
# .ai/.reminder keeps above -- see 75d7330 and d0fca82.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-keep class com.google.android.gms.internal.mlkit_common** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
