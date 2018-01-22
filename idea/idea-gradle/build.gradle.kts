
apply { plugin("kotlin") }

val androidSdk by configurations.creating

dependencies {
    testRuntime(intellijCoreDep()) { includeJars("intellij-core") }
    testRuntime(intellijDep())

    compileOnly(project(":idea"))
    compileOnly(project(":idea:idea-jvm"))
    compile(project(":idea:kotlin-gradle-tooling"))

    compile(project(":compiler:frontend"))
    compile(project(":compiler:frontend.java"))
    compile(project(":compiler:frontend.script"))

    compile(project(":js:js.frontend"))

    compileOnly(intellijDep()) { includeJars("openapi", "idea", "platform-api", "platform-impl", "java-api", "java-impl", "external-system-rt", "forms_rt", "extensions", "jdom", "util") }
    compileOnly(intellijPluginDep("gradle")) { includeJars("gradle", "gradle-api", rootProject = rootProject) }
    compileOnly(intellijPluginDep("Groovy")) { includeJars("Groovy") }
    compileOnly(intellijPluginDep("junit")) { includeJars("idea-junit") }

    testCompile(projectTests(":idea"))
    testCompile(project(":idea:idea-test-framework"))

    testCompile(intellijPluginDep("gradle")) { includeJars("gradle-wrapper", "gradle-api", "gradle-tooling-extension-impl", "gradle", rootProject = rootProject) }
    testCompileOnly(intellijPluginDep("Groovy")) { includeJars("Groovy") }
    testCompileOnly(intellijDep()) { includeJars("groovy-all-2.4.12", "idea_rt") }

    testRuntime(projectDist(":kotlin-reflect"))
    testRuntime(project(":idea:idea-jvm"))
    testRuntime(project(":idea:idea-android"))
    testRuntime(project(":plugins:kapt3-idea"))
    testRuntime(project(":plugins:android-extensions-ide"))
    testRuntime(project(":plugins:lint"))
    testRuntime(project(":sam-with-receiver-ide-plugin"))
    testRuntime(project(":allopen-ide-plugin"))
    testRuntime(project(":noarg-ide-plugin"))
    // TODO: the order of the plugins matters here, consider avoiding order-dependency
    testRuntime(intellijPluginDep("junit"))
    testRuntime(intellijPluginDep("testng")) { includeJars("jcommander", "resources_en") }
    testRuntime(intellijPluginDep("properties")) { includeJars("resources_en") }
    testRuntime(intellijPluginDep("gradle"))
    testRuntime(intellijPluginDep("Groovy"))
    testRuntime(intellijPluginDep("coverage")) { includeJars("jacocoant") }
    testRuntime(intellijPluginDep("maven"))
    testRuntime(intellijPluginDep("android"))

    androidSdk(project(":custom-dependencies:android-sdk", configuration = "androidSdk"))
}

sourceSets {
    "main" {
        projectDefault()
        resources.srcDir("res").apply { include("**") }
    }
    "test" { projectDefault() }
}

testsJar()

projectTest {
    workingDir = rootDir
    dependsOn(androidSdk)
    doFirst {
        systemProperty("android.sdk", androidSdk.singleFile.canonicalPath)
        systemProperty("idea.home.path", intellijRootDir().canonicalPath)
    }
}

configureInstrumentation()
