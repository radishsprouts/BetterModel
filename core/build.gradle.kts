plugins {
    alias(libs.plugins.convention.library)
}

dependencies {
    api(project(":bettermodel-api"))

    compileOnly(libs.bundles.minecraft)
    // パケットのバイト列を書く共通処理（nms/* で共有）が使う。実行時はサーバー本体のものを使う。
    compileOnly(libs.netty.buffer)
    compileOnly("com.mojang:authlib:7.0.61")

    compileOnly(libs.bundles.core)
    compileOnly(libs.cloud.core)
    // 実行時は bukkit-core が shade して relocate する
    compileOnly(libs.commons.codec)
    testImplementation(libs.commons.codec)
    testImplementation(libs.cloud.core)

    testImplementation(libs.bundles.minecraft)
    testImplementation(libs.netty.buffer)
}
