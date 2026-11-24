plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(libs.kotlinx.serialization.json)
    compileOnly(libs.room.common)
    testImplementation(libs.room.common)
    testImplementation(libs.junit)
}
tasks.test {
    // الاختبار يولّد عينات التقارير في samples/: لا يُؤخذ من الذاكرة المؤقتة حتى تُعاد كتابتها دائماً
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // samples are written here by ReportSamplesTest
    systemProperty("rasidi.samples.dir", rootProject.layout.projectDirectory.dir("samples").asFile.absolutePath)
    environment("LC_ALL", "C.UTF-8"); environment("LANG", "C.UTF-8")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
