# Ritimcraw release R8 rules (E5, release-readiness branch).
#
# The default file (proguard-android-optimize.txt in app/build.gradle.kts)
# already carries the AndroidX/Compose/library consumer rules; this file
# adds ONLY what is specific to this app. Three families:
#
#   1. JNI — the native bridge resolves symbols by NAME
#      (Java_com_ritim_ritimcraw_engine_NativeSource_*) and constructs
#      Kotlin objects through FindClass + <init> (jni_bridge.cpp), so the
#      class/member names it touches must survive shrinking+renaming.
#   2. Wire enums — every persisted JSON format writes and reads enum
#      VALUES BY NAME (.name / valueOf / entries.firstOrNull { it.name }).
#      R8's -obfuscation would rename the constants and SILENTLY break
#      every old project/export/codec-measure file (an unknown name
#      becomes a parse refusal or a default, never a crash — the worst
#      kind of breakage: data that reads as something else).
#   3. One reflective lookup in a debug-only harness.
#
# Everything else (Compose, Kotlin metadata, coroutines) is covered by the
# consumer rules that ship inside the AARs (AGP 8.13 embeds them).

# ----------------------------------------------------------------------------
# 1. JNI
# ----------------------------------------------------------------------------

# NativeSource: 73 `external fun` declarations resolved by their JNI symbol
# names, plus the companion's System.loadLibrary init. Keep the class name
# (the symbol prefix Java_com_ritim_ritimcraw_engine_NativeSource_) and the
# native method names. Keeping the whole class is the honest rule here: the
# bridge also touches its static state through the symbols.
-keep class com.ritim.ritimcraw.engine.NativeSource { *; }
-keepclasseswithmembernames class com.ritim.ritimcraw.** {
    native <methods>;
}

# Objects the C++ bridge constructs via FindClass + GetMethodID(<init>):
#   jni_bridge.cpp:2300  com/ritim/ritimcraw/engine/FrameInfoK
#   jni_bridge.cpp:3175  com/ritim/ritimcraw/engine/ContainerIdentityK
# A renamed class or a renamed/removed synthetic constructor makes
# nativeFrameInfo/nativeContainerIdentity return null (silent failure of
# clip open), so both keep their names AND their declared constructors
# with the exact parameter lists the bridge looks up.
-keep class com.ritim.ritimcraw.engine.FrameInfoK {
    public <init>(long, int, int, int, int, android.graphics.Rect, int);
}
-keep class com.ritim.ritimcraw.engine.ContainerIdentityK {
    public <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, double, double);
}

# nativePreviewSessionSetDrtReadyListener stores a Kotlin Runnable and the
# bridge calls its run() through GetMethodID (jni_bridge.cpp:5458). The
# listener is a lambda { } captured at ClipEditorScreen.kt:2430; R8 must
# not rename/remove run() on any class that can land in that parameter.
-keepclassmembers class * implements java.lang.Runnable {
    public void run();
}

# Manifest-referenced entry points (export foreground service + app class):
# aapt keep rules already cover these, but the service is startService'd
# through an explicit Intent (ExportForegroundService.kt:391) — its class
# name must survive so the intent resolves.
-keep class com.ritim.ritimcraw.export.ExportForegroundService { *; }
-keep class com.ritim.ritimcraw.export.ExportApp { *; }

# ----------------------------------------------------------------------------
# 2. Wire enums — persisted BY NAME
# ----------------------------------------------------------------------------
# Verified name-keyed reads (each file: the render writes .name, the parse
# matches it back):
#   export/ExportSettings.kt   ExportSettingsWire: ExportCodec,
#     ExportDynamicRange, ExportRateMode, BitrateLevel, KeyframeInterval
#   export/ExportStore.kt      ExportJob rows: Target, State,
#     RenderBackend, AudioLinkState (enumOrNullOr = enumValueOf)
#   data/metadata/MetadataStore.kt  MetadataRecord: SourceKind, Diagnosis,
#     LogDiagnosis, Capability (enumValueOf)
#   data/timeline/TimelineStore.kt  TimelineTrack: TrackKind
#     (java.lang.Enum.valueOf)
#   data/SourceRepository.kt   SourceKind, Access (valueOf)
#   data/audio/AudioStore.kt   AudioLinkState (.name)
#   data/ThemeStore.kt         ThemeMode (fromWire)
#   data/RenderBackendStore.kt RenderBackend (.name/fromWire)
#   data/PreviewQualityStore.kt PreviewQuality (.name/fromWire)
#   proxy/ProxySignature.kt    ProxySourceKind — inside the proxy file-name
#     signature hash: a renamed constant makes every existing proxy stale
#     (re-encode, wasted GBs), the most expensive silent break of all.
#   engine/EncodedColorSpec.kt SourceMatrix/SourceTransfer/SourcePrimaries
#     in describe() — diagnostic lines only, but stable lines are the
#     owner's A/B format; kept with the same rule.
#
# R8 renames enum CONSTANTS under -obfuscation; the values()/valueOf()
# machinery needs no help (the compiler emits the methods), only the
# constant FIELD names are the wire. Keep names, allow removal of unused
# constants (they cannot be removed while valueOf references them, and
# these are all referenced).

-keepclassmembers enum com.ritim.ritimcraw.export.ExportCodec { *; }
-keepclassmembers enum com.ritim.ritimcraw.export.ExportDynamicRange { *; }
-keepclassmembers enum com.ritim.ritimcraw.export.ExportRateMode { *; }
-keepclassmembers enum com.ritim.ritimcraw.export.BitrateLevel { *; }
-keepclassmembers enum com.ritim.ritimcraw.export.KeyframeInterval { *; }
-keepclassmembers enum com.ritim.ritimcraw.export.Target { *; }
-keepclassmembers enum com.ritim.ritimcraw.export.State { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.audio.AudioLinkState { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.SourceKind { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.Access { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.ThemeMode { *; }
-keepclassmembers enum com.ritim.ritimcraw.engine.RenderBackend { *; }
-keepclassmembers enum com.ritim.ritimcraw.engine.PreviewQuality { *; }
-keepclassmembers enum com.ritim.ritimcraw.proxy.ProxySourceKind { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.metadata.Diagnosis { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.metadata.LogDiagnosis { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.metadata.Capability { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.timeline.TrackKind { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.color.SourceMatrix { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.color.SourceTransfer { *; }
-keepclassmembers enum com.ritim.ritimcraw.data.color.SourcePrimaries { *; }

# The generic belt for the rest: any enum under our package keeps its
# constant names and its values()/valueOf() plumbing. Cheaper than
# auditing every future enum; the wire-format rule is a project-wide
# convention ("Enums travel by NAME", ExportSettingsWire).
-keepclassmembers enum com.ritim.ritimcraw.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Wire enum-ADJACENT classes: the render/parse paths themselves are plain
# code (no reflection) and need no keep; the JsonParser is a hand-rolled
# parser (no reflection). Nothing to do there.

# ----------------------------------------------------------------------------
# 3. Reflection (debug-only paths, but the code ships in release)
# ----------------------------------------------------------------------------

# GradeParitySweep.dumpCropDiag (line 626) resolves the Application through
# ActivityThread.currentApplication when it writes crop-diag PNGs. The
# sweep is BuildConfig.DEBUG-gated at runtime, but the class reference is
# compiled in; -keep the target so R8 cannot warn/rename it away and a
# debug-enabled future build keeps working. Harmless in release (android
# framework classes are not renamed by app R8 anyway, but the rule also
# silences the missing-class note).
-keep class android.app.ActivityThread {
    public static android.app.Application currentApplication();
}

# ----------------------------------------------------------------------------
# Crash reports / stack traces
# ----------------------------------------------------------------------------
# Release stack traces stay readable: keep line numbers, rename the
# source file to the conventional "SourceFile".
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
