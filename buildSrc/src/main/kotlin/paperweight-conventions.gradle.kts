plugins {
    id("standard-conventions")
    id("io.papermc.paperweight.userdev")
}

dependencies {
    compileOnly(project(":bettermodel-api"))
    compileOnly(project(":bettermodel-api:bettermodel-bukkit-api"))
    // 版に依存しない共通部分（パケットの組み立て・プレイヤーの状態など）。nms/* が core を使い、core は nms/* を知らない。
    compileOnly(project(":bettermodel-core"))
    compileOnly(project(":bettermodel-core:bettermodel-bukkit-core"))
}
