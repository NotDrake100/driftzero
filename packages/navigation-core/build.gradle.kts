plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("in.driftzero.core.Replay")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

tasks.register<JavaExec>("replay") {
    group = "application"
    description = "Replay SensorFrame JSONL through DeadReckoningFilter and write NavigationState JSONL"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("in.driftzero.core.Replay")
}

tasks.register<JavaExec>("writeGraphBin") {
    group = "application"
    description = "Convert OSM highway XML/PBF to compact graph.bin for the live matcher"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("in.driftzero.core.WriteGraphBin")
    workingDir = rootProject.projectDir
}
