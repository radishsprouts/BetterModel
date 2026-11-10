plugins {
    alias(libs.plugins.convention.standard)
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val minecraft = property("minecraft_version").toString()

// 開発者が使うタスクはこの組にまとめる（.\gradlew.bat tasks で先頭付近に出る）
val taskGroup = "bettermodel"

runPaper {
    disablePluginJarDetection()
}

val bettermodel get() = project(":platform:bettermodel-paper").tasks.named<Jar>("shadowJar").flatMap {
    it.archiveFile
}
val bettermodelTest get() = project(":test-plugin").tasks.jar.flatMap {
    it.archiveFile
}

// Folia のサーバーを起動し、BetterModel と test-plugin の jar を入れる
runPaper.folia.registerTask {
    pluginJars(bettermodel, bettermodelTest)
    minecraftVersion(minecraft)
}

tasks {
    jar {
        enabled = false
    }
    named("runFolia") {
        group = taskGroup
        description = "Folia のサーバー（Minecraft $minecraft）を起動し、BetterModel と test-plugin を入れる。run/ フォルダを使う"
    }

    // shadowJar は build/libs/ に出る。test-plugin の jar も同じ場所へ集める
    val copyTestPlugin = register<Copy>("copyTestPlugin") {
        from(bettermodelTest)
        into(layout.buildDirectory.dir("libs"))
    }

    register("buildPlugin") {
        group = taskGroup
        description = "Paper（Folia 兼用）用の BetterModel の jar と test-plugin の jar を作り、build/libs/ に置く"
        dependsOn(bettermodel, copyTestPlugin)
        val paperJar = bettermodel
        val testJar = layout.buildDirectory.file(bettermodelTest.map { "libs/${it.asFile.name}" })
        doLast {
            logger.lifecycle("BetterModel の jar: ${paperJar.get().asFile}")
            logger.lifecycle("test-plugin の jar: ${testJar.get().asFile}")
        }
    }

    register("checkAll") {
        group = taskGroup
        description = "全モジュールのビルド（コンパイル・ライセンスヘッダの確認・jar の作成）を行う。各モジュールの build タスクの別名"
        dependsOn(provider {
            allprojects.filter { it.tasks.names.contains("build") }.map { it.tasks.named("build") }
        })
    }

    register("publishApiToMavenLocal") {
        group = taskGroup
        description = "api と api:bukkit-api を、ローカルの Maven（~/.m2）へ出す。ArenaPvP から使うため。外部への公開はしない"
        dependsOn(
            ":bettermodel-api:publishToMavenLocal",
            ":bettermodel-api:bettermodel-bukkit-api:publishToMavenLocal"
        )
    }
}
