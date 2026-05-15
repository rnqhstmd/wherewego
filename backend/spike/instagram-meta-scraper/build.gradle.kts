dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
}

tasks.test { enabled = false }
tasks.named<Jar>("jar") { enabled = false }

tasks.register<JavaExec>("runSpike") {
    mainClass.set("com.wherewego.spike.instagram.InstagramMetaSpikeRunner")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("src/main/resources/sample-urls.txt", "result.md")
}
