package com.healthdecoder.app.theme

import androidx.compose.ui.graphics.Color

// ── Light theme tokens ──
val MedicalTeal = Color(0xFF1565C0)        // Primary — deep clinical blue
val MedicalNavy = Color(0xFF0D47A1)        // Secondary — trustworthy navy blue
val MedicalAmber = Color(0xFFE8A838)       // Tertiary — warm accent for actions
val MedicalBackground = Color(0xFFF8F9FA)  // Background — warm off-white
val MedicalSurface = Color(0xFFFFFFFF)     // Surface — clean white
val MedicalSurfaceVariant = Color(0xFFE3F2FD) // Surface variant — light blue tint
val MedicalOnPrimary = Color(0xFFFFFFFF)
val MedicalOnSecondary = Color(0xFFFFFFFF)
val MedicalOnBackground = Color(0xFF1A1C1E)
val MedicalOnSurface = Color(0xFF1A1C1E)
val MedicalOnSurfaceVariant = Color(0xFF44474E)
val MedicalOutline = Color(0xFF74777F)
val MedicalOutlineVariant = Color(0xFFC4C7CF)

// ── Dark theme tokens ──
val MedicalTealLight = Color(0xFF64B5F6)   // Primary (dark) — lighter blue
val MedicalNavyLight = Color(0xFF7FB3D3)   // Secondary (dark) — soft blue
val MedicalAmberLight = Color(0xFFFFD180)  // Tertiary (dark) — warm amber
val MedicalDarkBackground = Color(0xFF111418)
val MedicalDarkSurface = Color(0xFF1A1D21)
val MedicalDarkSurfaceVariant = Color(0xFF252A2F)

// ── Semantic medical status colors (used across all screens) ──
val StatusNormal = Color(0xFF2E7D32)       // Green — normal/healthy/improved
val StatusHigh = Color(0xFFC62828)          // Red — high/critical/worsened
val StatusLow = Color(0xFFE65100)           // Orange — low/warning/changed
val StatusNeutral = Color(0xFF607D8B)       // Blue-grey — neutral/stable

// ── Status container backgrounds ──
val StatusNormalBg = Color(0xFFE8F5E9)
val StatusHighBg = Color(0xFFFFEBEE)
val StatusLowBg = Color(0xFFFFF3E0)

// ── Report type colors ──
val ReportPrescription = Color(0xFF1565C0)
val ReportPrescriptionBg = Color(0xFFE3F2FD)
val ReportLab = Color(0xFF2E7D32)
val ReportLabBg = Color(0xFFE8F5E9)
val ReportGeneric = Color(0xFF455A64)
val ReportGenericBg = Color(0xFFECEFF1)

// ── Time-of-day slot accents ──
val SlotMorningBg = Color(0xFFFFF8E1)
val SlotMorningAccent = Color(0xFFF57F17)
val SlotAfternoonBg = Color(0xFFE3F2FD)
val SlotAfternoonAccent = Color(0xFF1565C0)
val SlotEveningBg = Color(0xFFE8F5E9)
val SlotEveningAccent = Color(0xFF2E7D32)
val SlotNightBg = Color(0xFFF3E5F5)
val SlotNightAccent = Color(0xFF6A1B9A)

// ── AI / Analysis accent ──
val AiAccent = Color(0xFF3F51B5)
val AiAccentBg = Color(0xFFE8EAF6)
val AiAccentDark = Color(0xFF1A237E)
