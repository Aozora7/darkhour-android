plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    testImplementation(libs.junit)
}

// Private manual overlays are evaluation inputs, not unit-test fixtures.
sourceSets.test {
    resources.exclude("circadian/ground_truth/**")
}

val privateGroundTruthResources = files("src/test/resources")

tasks.register<JavaExec>("groundTruthScore") {
    group = "verification"
    description = "Score circadian algorithms against the optional private manual overlays."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + privateGroundTruthResources
    mainClass.set("one.aozora.darkhour.core.circadian.groundtruth.GroundTruthScoringRunner")

    args(
        "--algorithms=${providers.gradleProperty("scoreAlgorithms").getOrElse("all")}",
    )
}

tasks.register<JavaExec>("groundTruthTune") {
    group = "verification"
    description = "Search circadian parameters; use -PtuneUpdateResource=true to update tuned defaults."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + privateGroundTruthResources
    mainClass.set("one.aozora.darkhour.core.circadian.groundtruth.GroundTruthTuner")

    args(
        "--algorithms=${providers.gradleProperty("tuneAlgorithms").getOrElse("csf-v1")}",
        "--samples=${providers.gradleProperty("tuneSamples").getOrElse("256")}",
        "--generations=${providers.gradleProperty("tuneGenerations").getOrElse("8")}",
        "--population=${providers.gradleProperty("tunePopulation").getOrElse("32")}",
        "--threads=${providers.gradleProperty("tuneThreads").getOrElse("2")}",
        "--seed=${providers.gradleProperty("tuneSeed").getOrElse("25072026")}",
        "--window-days=${providers.gradleProperty("tuneWindowDays").getOrElse("42")}",
        "--parameters=${providers.gradleProperty("tuneParameters").getOrElse("")}",
        "--output=${layout.buildDirectory.dir("reports/ground-truth-tuning").get().asFile.absolutePath}",
    )

    val updateResource = providers.gradleProperty("tuneUpdateResource").getOrElse("false")
    require(updateResource == "true" || updateResource == "false") {
        "tuneUpdateResource must be 'true' or 'false'"
    }
    if (updateResource.toBoolean()) {
        args(
            "--update-resource=${layout.projectDirectory.file(
                "src/main/resources/one/aozora/darkhour/core/circadian/circadian-algorithm-parameters.tsv",
            ).asFile.absolutePath}",
        )
    }
}

tasks.register<JavaExec>("groundTruthCausal") {
    group = "verification"
    description = "Compare blocked causal circadian forecasts against the optional private ground-truth fixtures."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + privateGroundTruthResources
    mainClass.set("one.aozora.darkhour.core.circadian.groundtruth.GroundTruthCausalRunner")

    args(
        "--algorithms=${providers.gradleProperty("causalAlgorithms").getOrElse("all")}",
        "--history-days=${providers.gradleProperty("causalHistoryDays").getOrElse("180")}",
        "--horizon-days=${providers.gradleProperty("causalHorizonDays").getOrElse("42")}",
        "--spacing-days=${providers.gradleProperty("causalSpacingDays").getOrElse("42")}",
        "--overrides=${providers.gradleProperty("causalOverrides").getOrElse("")}",
        "--output=${layout.buildDirectory.dir("reports/ground-truth-causal").get().asFile.absolutePath}",
    )
}
