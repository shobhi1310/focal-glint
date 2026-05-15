# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# LiteRT-LM's native library looks up these callback methods by exact Java name
# through JNI GetMethodID. The 0.11.0 AAR does not ship consumer keep rules for
# them, so release R8 can rename/remove the methods and crash in
# nativeSendMessageAsync with NoSuchMethodError.
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniMessageCallback {
    void onMessage(java.lang.String);
    void onDone();
    void onError(int, java.lang.String);
}
-keep class com.google.ai.edge.litertlm.Conversation$JniMessageCallbackImpl {
    void onMessage(java.lang.String);
    void onDone();
    void onError(int, java.lang.String);
}

# Kotlinx Serialization
-keepattributes Signature,InnerClasses,EnclosingMethod,MethodParameters,*Annotation*
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.focal.**$$serializer { *; }
-keepclassmembers class com.focal.** {
    *** Companion;
}
-keepclasseswithmembers class com.focal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# LiteRT-LM discovers app tools through reflection. If R8 strips or renames
# these methods/classes, the LLM sees an invalid tool schema and release builds
# silently produce zero classification/extraction/tool results.
-keep class com.focal.intelligence.ClassifyNotificationTool { *; }
-keep class com.focal.intelligence.BatchClassifyNotificationTool { *; }
-keep class com.focal.intelligence.GenerateTopicCardTool { *; }
-keep class com.focal.intelligence.Extract*Tool { *; }
-keep class com.focal.intelligence.NoExtractionTool { *; }
-keepclassmembers class * implements com.google.ai.edge.litertlm.ToolSet {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
