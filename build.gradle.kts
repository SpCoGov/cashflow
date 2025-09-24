plugins {
    application
    id("java")
    id("org.beryx.jlink") version "2.26.0"
}

group = "top.spco"
version = "0.1.0-alpha"

repositories {
    mavenCentral()
}

val javafxVersion = "21"
val platform = "win"
val javafxModules = listOf("base", "controls", "graphics", "fxml")
val appVersionNumeric: String = Regex("""\d+(?:\.\d+){0,3}""")
    .find(project.version.toString())
    ?.value
    ?: "1.0.0"

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // GUI
    javafxModules.forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$platform")
    }

    // YAML
    implementation("org.yaml:snakeyaml:2.5")
    // XLSX
    implementation("org.apache.poi:poi-ooxml:5.4.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaExec>().configureEach {
    // 仅用于 gradle run 的模块路径配置（与 jlink/jpackage 无冲突）
    val javafxLibs = configurations.runtimeClasspath.get()
        .filter { it.name.contains("javafx") }

    jvmArgs = listOf(
        "--module-path", javafxLibs.joinToString(File.pathSeparator),
        "--add-modules", "javafx.controls,javafx.graphics,javafx.base,javafx.fxml"
    )
}

application {
    mainClass.set("top.spco.cashflow.CashflowApplication")
    mainModule.set("top.spco.cashflow")
}

tasks.test {
    useJUnitPlatform()
}

jlink {
    addOptions("--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "2")
    addExtraDependencies("javafx")

    launcher {
        name = "Cashflow"
    }

    jpackage {
        imageName = "Cashflow"
        installerName = "Cashflow-${project.version}"  // 可以带 -alpha
        appVersion = appVersionNumeric              // 必须是数字版本
        vendor = "SpCo"
        installerType = "msi"
        imageOptions = listOf("--icon", file("src/main/resources/icon/app.ico").absolutePath)

        // 注册 .cflg 关联
        installerOptions.addAll(
            listOf(
                "--file-associations", file("src/packaging/cflg-assoc.properties").absolutePath
            )
        )

        // 应用图标
        imageOptions = listOf(
            "--icon", file("packaging/icon.ico").absolutePath
        )

        // 安装器选项 + 文件关联（注意：用赋值，不要用 +=）
        installerOptions = listOf(
            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser",
            "--win-per-user-install",
            "--file-associations", file("packaging/cflg-assoc.properties").absolutePath
            // 需要控制台再加： "--win-console"
        )
    }
}

tasks.register<Zip>("distWinZip") {
    group = "distribution"
    description = "Zip the Windows app-image (includes custom runtime)"
    dependsOn(tasks.named("jpackageImage")) // 先生成 app-image

    val imageDir = layout.buildDirectory.dir("jpackage/Cashflow") // 对应上面的 imageName
    from(imageDir)

    // zip 根目录名更友好一些
    into("Cashflow-${project.version}-win")
    archiveFileName.set("Cashflow-${project.version}-win.zip")
}