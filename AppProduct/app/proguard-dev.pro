# Dev flavor only: ML Kit Translate. Its internal protobuf-lite messages and component
# registrars are read by reflection; without these rules R8 strips them and
# downloadModelIfNeeded() fails with an NPE inside obfuscated code (release builds only).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.firebase.components.** { *; }
