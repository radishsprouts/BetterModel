plugins {
    alias(libs.plugins.convention.library)
    alias(libs.plugins.convention.bukkit)
}

dependencies {
    api(project(":bettermodel-api"))
}
