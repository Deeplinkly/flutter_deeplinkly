allprojects {
    repositories {
        google()
        mavenCentral()
        // Development seam for building this example against a locally
        // published native SDK:
        //
        //     cd ../../android_deeplinkly && ./gradlew publishToMavenLocal
        //
        // It has to be here rather than in the plugin's own build.gradle: the
        // app resolves the plugin's transitive dependencies with *its* list of
        // repositories, so adding it only on the plugin side leaves
        // :app:debugRuntimeClasspath unable to find the SDK.
        //
        // The SDK is on Maven Central now, so this is off by default (see
        // gradle.properties) and the example resolves it exactly as a consumer
        // does. Only affects this example app, never a consumer's.
        if (providers.gradleProperty("deeplinkly.useMavenLocal").orNull == "true") {
            mavenLocal()
        }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
