# sherpa-onnx (M2): its AAR ships empty consumer rules and JNI reads fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# kotlinx.serialization: keep generated serializers of app classes.
-keepclassmembers class vn.viettechtrans.app.** {
    *** Companion;
}
-keepclasseswithmembers class vn.viettechtrans.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
