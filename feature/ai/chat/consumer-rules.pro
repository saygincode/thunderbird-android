# AiChatActivity.resetChatContext() resets the chat by reflecting into the
# llama-android library's private guard field. R8 would otherwise rename the
# class and field, breaking Class.forName()/getDeclaredField() in release builds.
-keep class com.arm.aichat.internal.InferenceEngineImpl { *; }
-keepclassmembers class com.arm.aichat.internal.InferenceEngineImpl {
    private boolean _readyForSystemPrompt;
}
