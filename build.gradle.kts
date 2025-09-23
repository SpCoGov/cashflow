plugins {
    application
    id("java")
}

group = "top.spco"
version = "0.1.0-alpha"

repositories {
    mavenCentral()
}

val javafxVersion = "21"
val platform = "win"
val javafxModules = listOf("base", "controls", "graphics", "fxml")

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // GUI
    javafxModules.forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$platform")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaExec>().configureEach {
    val javafxLibs = configurations.runtimeClasspath.get()
        .filter { it.name.contains("javafx") }

    jvmArgs = listOf(
        "--module-path", javafxLibs.joinToString(File.pathSeparator),
        "--add-modules", "javafx.controls,javafx.graphics,javafx.base,javafx.fxml"
    )
}

application {
    mainClass.set("top.spco.cashflow.CashflowApplication")
}

tasks.test {
    useJUnitPlatform()
}