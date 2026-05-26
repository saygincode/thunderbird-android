plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.ai.chat"
    resourcePrefix = "ai_chat_"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.core.ui.compose.designsystem)
    implementation(projects.core.ui.compose.theme2.common)
    implementation(projects.core.ui.theme.api)
    implementation(projects.core.common)
    implementation(projects.core.android.account)

    implementation(projects.feature.mail.account.api)
    implementation(projects.feature.search.implLegacy)

    implementation(projects.legacy.core)
    implementation(projects.legacy.mailstore)
    implementation(projects.legacy.message)
    implementation(projects.legacy.ui.base)

    implementation(projects.library.llamaAndroid)
}
