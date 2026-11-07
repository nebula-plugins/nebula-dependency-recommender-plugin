/*
 * Copyright 2016-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender.publisher

import groovy.transform.CompileDynamic
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.publish.maven.MavenPublication

/**
 * Adds Maven dependency-management entries through the {@code nebulaDependencyManagement} DSL
 * inside a Maven publication. Repeated calls append to one dependency-management section and
 * retain the registration order of the publication's {@code pom.withXml} actions.
 *
 * <p>The built-in DSL evaluates the supplied closures during publication configuration and
 * registers XML actions that retain only coordinate providers or notation strings. This avoids
 * retaining configurations and Groovy script delegates when Gradle caches POM generation.
 * Custom XML actions and subclass callbacks must independently meet configuration-cache requirements.
 */
@CompileDynamic
class MavenBomXmlGenerator {

    /**
     * Adds the selected module versions from one or more resolvable configurations, including
     * transitive dependencies. Repeated module versions are emitted once per configuration;
     * duplicates between configurations or separate calls are preserved.
     *
     * <p>The closure runs immediately to select configurations, but their resolution remains lazy
     * through Gradle's resolution-root providers until the publication is needed. The deferred XML
     * action retains neither the closure nor the configurations. Resolution failures fail POM
     * generation rather than publishing a partial dependency-management section.
     *
     * @param configurationsClosure a publication-scoped closure returning a single
     *     {@link Configuration} or an {@code Iterable<Configuration>}
     */
    void fromConfigurations(Closure configurationsClosure) {
        MavenPublication pub = getMavenPublication(configurationsClosure)

        Iterable<Configuration> configurations

        def configurationsRet = configurationsClosure()
        if(configurationsRet instanceof Configuration)
            configurations = [(Configuration) configurationsRet]
        else if(Iterable.class.isAssignableFrom(configurationsRet.class))
            configurations = configurationsRet as Iterable<Configuration>

        // ResolutionResult providers are tracked by Gradle and are only queried for a
        // selected publication. Do not retain Configuration or this DSL's delegates.
        pub.pom.withXml(new DependencyManagementXmlAction(configurations.collect {
            it.incoming.resolutionResult.rootComponent.map(new DependencyManagementXmlAction.ResolvedCoordinates())
        }))
    }

    /**
     * Adds explicit module notations to dependency management without resolving transitive
     * dependencies. Entries retain their supplied order and duplicates.
     *
     * <p>The closure runs during publication configuration, and its returned strings are copied
     * into the XML action. Notation parsing and validation occur later, when the POM is generated.
     * Only group, artifact, and version are written to the POM.
     *
     * @param dependenciesClosure a publication-scoped closure returning a single module notation
     *     string, such as {@code 'org.example:library:1.0'}, or an {@code Iterable<String>}
     */
    void withDependencies(Closure dependenciesClosure) {
        MavenPublication pub = getMavenPublication(dependenciesClosure)

        Iterable<String> dependencies = null

        def dependenciesRet = dependenciesClosure()
        if(dependenciesRet instanceof String)
            dependencies = [(String) dependenciesRet]
        else if(Iterable.class.isAssignableFrom(dependenciesRet.class))
            dependencies = dependenciesRet as Iterable<String>

        pub.pom.withXml(new DependencyManagementXmlAction.ExplicitDependencies(dependencies.collect { String notation -> notation }))
    }

    /**
     * Registers a deferred dependency-management contribution using the legacy closure API.
     * Kept for subclass compatibility; the built-in DSL uses dedicated XML actions instead.
     *
     * <p>The closure is invoked during POM generation and retained by the publication's XML
     * action. Callers must ensure that its captured state meets Gradle's configuration-cache
     * requirements, including avoiding project, configuration, and script-delegate captures.
     *
     * @param pub the publication to receive the dependency-management entries
     * @param deps a deferred supplier of the module versions to append
     */
    @CompileDynamic
    protected static generateDependencyManagementXml(MavenPublication pub, Closure<Iterable<ModuleVersionIdentifier>> deps) {
        pub.pom.withXml {
            Node root = it.asNode()
            def dependencyManagement = root.getByName("dependencyManagement")

            // when merging two or more sources of dependencies, we want to only create one dependencyManagement section
            Node dependencies
            if(dependencyManagement.isEmpty())
                dependencies = root.appendNode("dependencyManagement").appendNode("dependencies")
            else
                dependencies = dependencyManagement[0].getByName("dependencies")[0]

            deps.call().each { mvid ->
                Node dep = dependencies.appendNode("dependency")
                dep.appendNode("groupId").value = mvid.group
                dep.appendNode("artifactId").value = mvid.name
                dep.appendNode("version").value = mvid.version
            }
        }
    }

    @CompileDynamic
    private MavenPublication getMavenPublication(Closure configurationsClosure) {
        return configurationsClosure.delegate.delegate
    }

    /**
     * Resolves a configuration immediately through the legacy resolved-configuration API and
     * collects its direct and transitive module versions. Kept for subclass compatibility;
     * {@link #fromConfigurations(Closure)} uses lazy resolution-result providers instead.
     *
     * @param configuration the resolvable configuration to inspect
     * @return the distinct module versions selected by Gradle
     */
    protected static Set<ModuleVersionIdentifier> getManagedDependencies(Configuration configuration) {
        getManagedDependenciesRecursive(configuration.resolvedConfiguration.firstLevelModuleDependencies,
                new HashSet<ModuleVersionIdentifier>())
    }

    /**
     * Collects module versions from a legacy resolved-dependency graph, using the accumulating
     * set to stop revisiting module versions and terminate cycles.
     *
     * @param deps the dependencies to visit at this level
     * @param all the mutable set of module versions already collected
     * @return the supplied set, updated with reachable module versions
     */
    protected static Set<ModuleVersionIdentifier> getManagedDependenciesRecursive(Set<ResolvedDependency> deps, Set<ModuleVersionIdentifier> all) {
        deps.each { dep ->
            if (all.add(dep.module.id)) {
                getManagedDependenciesRecursive(dep.children, all)
            }
        }
        return all
    }
}
