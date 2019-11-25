package app.kazy.plugin.task

import app.kazy.plugin.LicenseToolsPluginExtension
import app.kazy.plugin.data.ArtifactId
import app.kazy.plugin.data.LibraryInfo
import app.kazy.plugin.data.LibraryPom
import app.kazy.plugin.extension.licensesUnMatched
import app.kazy.plugin.extension.notListedIn
import app.kazy.plugin.extension.resolvedArtifacts
import app.kazy.plugin.extension.toFormattedText
import app.kazy.plugin.util.YamlUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.internal.impldep.com.google.common.annotations.VisibleForTesting
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import java.io.File


object CheckLicenses {
    fun register(project: Project): Task {
        return project.task("checkLicenses").doLast {
            val ext = project.extensions.getByType(LicenseToolsPluginExtension::class.java)
            // based on license plugin's dependency-license.xml
            val resolvedArtifacts = resolveProjectDependencies(project, ext.ignoredProjects)
            val dependencyLicenses =
                loadDependencyLicenses(project, resolvedArtifacts, ext.ignoredGroups)
            // based on libraries.yml
            val librariesYaml = YamlUtils.loadToLibraryInfo(project.file(ext.licensesYaml))

            val notDocumented = dependencyLicenses.notListedIn(librariesYaml)
            val notInDependencies = librariesYaml.notListedIn(dependencyLicenses)
            val licensesUnMatched = dependencyLicenses.licensesUnMatched(librariesYaml)

            if (
                notDocumented.isEmpty()
                && notInDependencies.isEmpty()
                && licensesUnMatched.isEmpty()
            ) {
                project.logger.info("checkLicenses: ok")
                return@doLast
            }

            if (notDocumented.isNotEmpty()) {
                project.logger.warn("# Libraries not listed in ${ext.licensesYaml}:")
                notDocumented.forEach { libraryInfo ->
                    val text = generateLibraryInfoText(libraryInfo)
                    project.logger.warn(text)
                }
            }

            if (notInDependencies.isNotEmpty()) {
                project.logger.warn("# Libraries listed in ${ext.licensesYaml} but not in dependencies:")
                notInDependencies.forEach { libraryInfo ->
                    project.logger.warn("- artifact: ${libraryInfo.artifactId}\n")
                }
            }
            if (licensesUnMatched.isNotEmpty()) {
                project.logger.warn("# Licenses not matched with pom.xml in dependencies:")
                licensesUnMatched.forEach { libraryInfo ->
                    project.logger.warn("- artifact: ${libraryInfo.artifactId}\n  license: ${libraryInfo.license}")
                }
            }
            throw GradleException("checkLicenses: missing libraries in ${ext.licensesYaml}")
        }.also {
            it.group = "Verification"
            it.description = "Check whether dependency licenses are listed in licenses.yml"
        }
    }

    @VisibleForTesting
    fun generateLibraryInfoText(libraryInfo: LibraryInfo): String {
        val text = StringBuffer()
        text.append("- artifact: ${libraryInfo.artifactId.withWildcardVersion()}\n")
        text.append("  name: ${libraryInfo.name}\n")
        text.append("  copyrightHolder: ${libraryInfo.copyrightHolder}\n")
        text.append("  license: ${libraryInfo.license}\n")
        if (libraryInfo.licenseUrl?.isNotBlank() == true) {
            text.append("  licenseUrl: ${libraryInfo.licenseUrl}\n")
        }
        if (libraryInfo.url?.isNotBlank() == true) {
            text.append("  url: ${libraryInfo.url}\n")
        }
        return text.toString().trim()
    }

    @VisibleForTesting
    fun loadDependencyLicenses(
        project: Project,
        resolvedArtifacts: Set<ResolvedArtifact>,
        ignoredGroups: Set<String>
    ): Set<LibraryInfo> {
        return resolvedArtifacts
            .filterNot { it.moduleVersion.id.version == "unspecified" }
            .filterNot { ignoredGroups.contains(it.moduleVersion.id.group) }
            .mapNotNull { resolvedArtifactToLibraryInfo(it, project) }
            .toSet()
    }

    @VisibleForTesting
    fun targetSubProjects(project: Project, ignoredProjects: Set<String>): List<Project> {
        return project.rootProject.subprojects
            .filter { !ignoredProjects.contains(it.name) }
    }

    @VisibleForTesting
    fun isConfigForDependencies(name: String): Boolean {
        return name.matches(dependencyKeywordPattern)
    }

    @VisibleForTesting
    fun resolvedArtifactToLibraryInfo(artifact: ResolvedArtifact, project: Project): LibraryInfo? {
        val dependencyDesc =
            "${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}:${artifact.moduleVersion.id.version}"
        val artifactId: ArtifactId
        try {
            artifactId = ArtifactId.parse(dependencyDesc)
        } catch (e: IllegalArgumentException) {
            project.logger.info("Unsupport dependency: $dependencyDesc")
            return null
        }
        val pomDependency = project.dependencies.create("$dependencyDesc@pom")
        val pomConfiguration = project.configurations.detachedConfiguration(pomDependency)
        pomConfiguration.resolve().forEach { file ->
            project.logger.info("POM: $file")
        }
        val pomStream: File
        try {
            pomStream = pomConfiguration.resolve().toList().first()
        } catch (e: Exception) {
            project.logger.warn("Unable to retrieve license for $dependencyDesc")
            return null
        }
        val persister: Serializer = Persister()
        val result = persister.read(LibraryPom::class.java, pomStream)
        val licenseName = result.licenses.firstOrNull()?.name
        val licenseUrl = result.licenses.firstOrNull()?.url
        val libraryName = result.name
        val libraryUrl = result.url
        return LibraryInfo(
            artifactId = artifactId,
            name = artifact.name,
            libraryName = libraryName,
            url = libraryUrl,
            fileName = artifact.file.name,
            license = licenseName.toString(),
            licenseUrl = licenseUrl,
            copyrightHolder = null,
            notice = null
        )
    }

    @VisibleForTesting
    fun resolveProjectDependencies(
        project: Project?,
        ignoredProjects: Set<String> = emptySet()
    ): Set<ResolvedArtifact> {
        project ?: return emptySet()
        val subProjects = targetSubProjects(project, ignoredProjects)
        val subProjectIndex = subProjects.groupBy { it.toFormattedText() }
        return subProjects
            .map { it.configurations }
            .flatten()
            .filter { isConfigForDependencies(it.name) }
            .map { it.resolvedArtifacts() }
            .flatten()
            .distinctBy { it.toFormattedText() }
            .flatMap {
                val dependencyDesc = it.toFormattedText()
                val subProject = subProjectIndex[dependencyDesc]?.first()
                setOf(it, *resolveProjectDependencies(subProject).toTypedArray())
            }.toSet()
    }

    private val dependencyKeywordPattern =
        """^(?!releaseUnitTest)(?:release\w*)?([cC]ompile|[cC]ompileOnly|[iI]mplementation|[aA]pi)$""".toRegex()
}