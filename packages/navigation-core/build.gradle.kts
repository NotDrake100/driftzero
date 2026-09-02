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
