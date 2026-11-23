pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // ملف Robolectric الأصلي (~150MB) يُرفض أحياناً من Maven Central بـ 429؛ نجلبه من مرآة Google لـ Maven Central
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { content { includeGroup("org.robolectric") } }
        mavenCentral { content { excludeGroup("org.robolectric") } }
    }
}
rootProject.name = "rasidi"
include(":core", ":app")
