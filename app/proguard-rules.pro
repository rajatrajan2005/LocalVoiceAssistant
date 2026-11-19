# Native llama_jni calls Kotlin token/progress callbacks by name.
-keep class com.localvoice.assistant.llm.LlamaEngine { *; }
-keep class com.localvoice.assistant.llm.LlamaEngine$* { *; }
# sherpa-onnx AAR loads its Java/Kotlin classes via JNI FindClass.
-keep class com.k2fsa.sherpa.onnx.** { *; }