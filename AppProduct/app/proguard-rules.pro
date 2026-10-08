# sherpa-onnx (M2): its AAR ships empty consumer rules and JNI reads fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime (Main-Core MT): its JNI creates OnnxTensor/OrtSession objects and reads their
# fields by name, like sherpa-onnx above.
-keep class ai.onnxruntime.** { *; }

# sherpa-onnx TTS calls our callback through `invoke([F)Ljava/lang/Integer;` by name
# (Speaker.synthesizeAndPlay). R8 would keep only the generic bridge and the native
# side aborts with NoSuchMethodError on the first sentence (release builds only).
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    java.lang.Integer invoke(float[]);
}

# kotlinx.serialization: keep generated serializers of app classes.
-keepclassmembers class vn.viettechtrans.app.** {
    *** Companion;
}
-keepclasseswithmembers class vn.viettechtrans.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
