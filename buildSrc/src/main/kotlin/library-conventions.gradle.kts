// ライブラリ用の共通設定。外部への公開先は持たず、publishToMavenLocal（ローカルの Maven）にだけ出せる。
plugins {
    id("standard-conventions")
    `maven-publish`
}

dependencies {
    api(libs.bundles.library) {
        exclude(module = "jspecify")
    }

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            artifactId = project.name
        }
    }
}
