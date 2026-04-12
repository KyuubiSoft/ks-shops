plugins {
    java
}

group = project.findProperty("pluginGroup") as String? ?: "com.kyuubisoft"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("${rootProject.projectDir}/libs/HytaleServer.jar"))

    // Optional dependencies (reflection-based bridges)
    compileOnly(project(":mods:core"))
    compileOnly(project(":mods:claims"))

    // Database dependencies (bundled into JAR)
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.mysql:mysql-connector-j:9.2.0")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-nop:2.0.9")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.jar {
    archiveBaseName.set("KyuubiSoftShops")

    manifest {
        attributes(
            "Main-Class" to "com.kyuubisoft.shops.ShopPlugin"
        )
    }

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("org/slf4j/**")
        exclude("META-INF/versions/*/org/slf4j/**")
        exclude("META-INF/maven/**")
        exclude("META-INF/native-image/**")
        exclude("META-INF/services/**")
        exclude("META-INF/proguard/**")
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("sqlite-jdbc.properties")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    filesMatching("manifest.json") {
        expand(
            "group" to project.group,
            "version" to project.version,
            "description" to (project.findProperty("pluginDescription") ?: "Advanced Shop System for Hytale"),
            "serverVersion" to (project.extra["hytaleServerVersion"] as String)
        )
    }
}

tasks.register<Copy>("deployToServer") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into(layout.projectDirectory.dir("../server-files/hytale-server-files/mods"))
}
