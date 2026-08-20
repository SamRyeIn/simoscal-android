# R8 rules for the release build.
#
# Read this before relaxing anything here. The failure mode R8 introduces to
# this app is not a build error — it is a *runtime* one, on a tool that writes
# ECU calibration bins. A rule removed here does not break the build; it breaks
# the engine on the device, after the APK ships.

# --------------------------------------------------------------------------- #
# Chaquopy
# --------------------------------------------------------------------------- #
# Chaquopy ships as a plain JAR (chaquopy_java-17.0.0.jar), NOT an AAR, so it
# contributes no consumer ProGuard rules of its own — nothing keeps it but this
# block. Its native layer resolves these classes and their members from C over
# JNI, and PyInvocationHandler / DynamicProxy / StaticProxy / Reflector /
# MethodCache exist purely to proxy calls by reflection. R8 sees no Java caller
# for most of it and would strip or rename it, after which Python.start() fails
# on the device with an UnsatisfiedLinkError or a missing-method error that the
# bridge reports only as "The embedded calibration engine could not start."
#
# Keep the whole package. It is ~61 KB of classes in a ~66 MB APK, so there is
# nothing to win by trimming it and a silent engine failure to lose.
-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.internal.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }

# Classes Python may instantiate or subclass through Chaquopy's proxy machinery.
-keep @com.chaquo.python.PyObject class * { *; }
-keepnames class * implements com.chaquo.python.PyProxy

# --------------------------------------------------------------------------- #
# JNI entry points
# --------------------------------------------------------------------------- #
# Any native method's owning class must keep its name and signature; the C side
# looks them up as strings.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --------------------------------------------------------------------------- #
# org.json
# --------------------------------------------------------------------------- #
# No rule needed, and this is the note explaining why rather than an omission.
# BridgeProtocol falls back to `this::class.java.simpleName` when a JSONException
# carries no message, which looks like something obfuscation could reduce to "a".
# It cannot: org.json is part of the Android framework, so it is never packaged
# into the APK and never passes through R8 — confirmed by its total absence from
# mapping.txt. (The org.json:json artifact in the build is testImplementation
# only, for the JVM tests, and does not reach a release build.)

# --------------------------------------------------------------------------- #
# Readable crash reports
# --------------------------------------------------------------------------- #
# Keep line numbers so a stack trace off a user's device still points at a line,
# and rename the source file attribute so it does not leak original file names.
# Upload the mapping.txt from build/outputs/mapping/release/ to Play so traces
# de-obfuscate in the console.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
