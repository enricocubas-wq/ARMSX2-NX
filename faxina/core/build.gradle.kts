plugins {
    id("org.jetbrains.kotlin.jvm")
}

// O módulo :core é Kotlin puro (sem Android) para poder ser testado em qualquer JVM.
// Ele só usa APIs que existem no Android 8+ (java.nio.file, java.util.concurrent).
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Roda as verificações do núcleo (varredura, duplicados, IMAP)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.faxina.core.SelfTestKt")
}

tasks.named("check") { dependsOn(selfTest) }
