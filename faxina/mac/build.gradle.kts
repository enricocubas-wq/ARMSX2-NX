import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "app.faxina.mac.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Faxina"
            packageVersion = "1.0.0"
            description = "Limpeza de armazenamento e e-mail"
            // O app fala TLS (e-mail); sem estes módulos o runtime enxuto perde cifras e falha na conexão.
            includeAllModules = true

            macOS {
                bundleID = "app.faxina.mac"
                dockName = "Faxina"
                appCategory = "public.app-category.utilities"
                minimumSystemVersion = "11.0"
                iconFile.set(project.file("icons/Faxina.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSAppleEventsUsageDescription</key>
                        <string>O Faxina usa o Finder para esvaziar a Lixeira.</string>
                        <key>NSDesktopFolderUsageDescription</key>
                        <string>O Faxina precisa ler a Mesa para achar arquivos que ocupam espaço.</string>
                        <key>NSDocumentsFolderUsageDescription</key>
                        <string>O Faxina precisa ler Documentos para achar arquivos que ocupam espaço.</string>
                        <key>NSDownloadsFolderUsageDescription</key>
                        <string>O Faxina precisa ler Downloads para achar instaladores e arquivos antigos.</string>
                        <key>NSRemovableVolumesUsageDescription</key>
                        <string>O Faxina precisa ler discos externos que você escolher.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// Prévias das telas (renderização fora da tela, sem abrir janela): ./gradlew :mac:previewShots -PshotsDir=...
tasks.register<JavaExec>("previewShots") {
    group = "verification"
    description = "Gera PNGs das telas com dados de mentira."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.faxina.mac.PreviewKt")
    args(providers.gradleProperty("shotsDir").orElse(layout.buildDirectory.dir("shots").map { it.asFile.path }).get())
    jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Xmx2g")
}
