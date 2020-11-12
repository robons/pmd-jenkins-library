import com.mkobit.jenkins.pipelines.http.AnonymousAuthentication
import org.gradle.kotlin.dsl.version
import java.io.ByteArrayOutputStream

plugins {
  id("com.gradle.build-scan") version "2.3"
  id("com.mkobit.jenkins.pipelines.shared-library") version "0.10.1"
  id("com.github.ben-manes.versions") version "0.21.0"
}

val commitSha: String by lazy {
  ByteArrayOutputStream().use {
    project.exec {
      commandLine("git", "rev-parse", "HEAD")
      standardOutput = it
    }
    it.toString(Charsets.UTF_8.name()).trim()
  }
}

buildScan {
  setTermsOfServiceAgree("yes")
  setTermsOfServiceUrl("https://gradle.com/terms-of-service")
  link("Git", "https://git.floop.org.uk/git/ONS/pmd-jenkins-library.git")
  value("Revision", commitSha)
}


tasks {
  wrapper {
    gradleVersion = "5.5.1"
  }

  integrationTest {
    doFirst{
      environment("GIT_URL", "https://github.com/GSS-Cogs/family-covid-19.git")
      environment("GIT_COMMIT", "15632b3a9ff2d05cfb82d05323d565ef1e98e108")
    }
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
  val spock = "org.spockframework:spock-core:1.2-groovy-2.4"
  testImplementation(spock)
  testImplementation("org.assertj:assertj-core:3.12.2")
  integrationTestImplementation(spock)
  integrationTestImplementation("com.github.tomakehurst:wiremock:2.25.1")
  integrationTestImplementation("jakarta.xml.bind:jakarta.xml.bind-api:2.3.2")
  integrationTestImplementation("org.glassfish.jaxb:jaxb-runtime:2.3.2")
  integrationTestImplementation("org.apache.jena:jena-arq:3.15.0")
}

jenkinsIntegration {
  baseUrl.set(uri("http://localhost:5050").toURL())
  authentication.set(providers.provider { AnonymousAuthentication })
  downloadDirectory.set(layout.projectDirectory.dir("jenkinsResources"))
}

sharedLibrary {
  // TODO: this will need to be altered when auto-mapping functionality is complete
  coreVersion.set(jenkinsIntegration.downloadDirectory.file("core-version.txt").map { it.asFile.readText().trim() })
  // TODO: retrieve downloaded plugin resource
  pluginDependencies {
    dependency("org.jenkins-ci.plugins", "pipeline-build-step", "2.12")
    dependency("org.6wind.jenkins", "lockable-resources", "2.8")
    val declarativePluginsVersion = "1.7.0"
    dependency("org.jenkinsci.plugins", "pipeline-model-api", declarativePluginsVersion)
    dependency("org.jenkinsci.plugins", "pipeline-model-declarative-agent", "1.1.1")
    dependency("org.jenkinsci.plugins", "pipeline-model-definition", declarativePluginsVersion)
    dependency("org.jenkinsci.plugins", "pipeline-model-extensions", declarativePluginsVersion)
    dependency("org.jenkins-ci.plugins", "config-file-provider", "3.6.3")
    dependency("org.jenkins-ci.plugins", "http_request", "1.8.26")
    dependency("org.jenkins-ci.plugins", "pipeline-utility-steps", "2.6.1")
    dependency("org.jenkins-ci.plugins", "credentials-binding", "1.23")
    dependency("org.jenkins-ci.plugins", "unique-id", "2.2.0")
  }
}

