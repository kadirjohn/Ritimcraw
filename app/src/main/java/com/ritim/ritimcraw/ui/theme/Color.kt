package com.ritim.ritimcraw.ui.theme

import androidx.compose.ui.graphics.Color

// Fallback browse-screen tokens (fallback-* in STEPS/DESIGN.md), used below
// API 31 when dynamic color is unavailable. minSdk is 34, so the dynamic path
// always wins in practice; these are kept for completeness per the plan.
//
// The dark set below is the M3 dark baseline the plan shipped. The light set
// (owner request 2026-11-14: the theme override made the LIGHT fallback
// reachable in principle for the first time — the app previously followed
// the system, and the pre-existing light scheme turned out to be built from
// these DARK tokens) is the M3 light baseline: the same hue family with
// light-container surfaces and dark on-colors, so a light theme reads as a
// light theme, not a dark one with a few inverted roles.

// --- dark ---

val FallbackPrimary = Color(0xFFd0bcff)
val FallbackOnPrimary = Color(0xFF381e72)
val FallbackPrimaryContainer = Color(0xFF4f378b)
val FallbackOnPrimaryContainer = Color(0xFFeaddff)
val FallbackSecondaryContainer = Color(0xFF4a4458)
val FallbackOnSecondaryContainer = Color(0xFFe8def8)
val FallbackSurface = Color(0xFF141218)
val FallbackSurfaceContainer = Color(0xFF211f26)
val FallbackOnSurface = Color(0xFFe6e0e9)
val FallbackOnSurfaceVariant = Color(0xFFcac4d0)
val FallbackOutline = Color(0xFF938f99)
val FallbackOutlineVariant = Color(0xFF49454f)
val FallbackError = Color(0xFFf2b8b5)
val FallbackErrorContainer = Color(0xFF8c1d18)
val FallbackOnErrorContainer = Color(0xFFf9dedc)

// --- light ---

val FallbackLightPrimary = Color(0xFF6750a4)
val FallbackLightOnPrimary = Color(0xFFffffff)
val FallbackLightPrimaryContainer = Color(0xFFeaddff)
val FallbackLightOnPrimaryContainer = Color(0xFF21005d)
val FallbackLightSecondaryContainer = Color(0xFFe8def8)
val FallbackLightOnSecondaryContainer = Color(0xFF1d192b)
val FallbackLightSurface = Color(0xFFfef7ff)
val FallbackLightSurfaceContainer = Color(0xFFf3edf7)
val FallbackLightOnSurface = Color(0xFF1d1b20)
val FallbackLightOnSurfaceVariant = Color(0xFF49454f)
val FallbackLightOutline = Color(0xFF79747e)
val FallbackLightOutlineVariant = Color(0xFFe7e0ec)
val FallbackLightError = Color(0xFFb3261e)
val FallbackLightErrorContainer = Color(0xFFf9dedc)
val FallbackLightOnErrorContainer = Color(0xFF410e0b)
