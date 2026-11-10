plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(libs.build.kotlin.jvm)
    implementation(libs.build.shadow)
    implementation(libs.build.resourcefactory)
    implementation(libs.build.paperweight)

    implementation("dev.yumi.gradle.licenser:dev.yumi.gradle.licenser.gradle.plugin:4.0.0")
}
