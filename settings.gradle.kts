rootProject.name = "shortener"

// The tools are a build of their own (tools/build.gradle.kts), included so that `./gradlew test` tests them as well. It is
// configured only when a task of it is asked for, and `./gradlew -p tools ...` runs it without this one.
includeBuild("tools")
