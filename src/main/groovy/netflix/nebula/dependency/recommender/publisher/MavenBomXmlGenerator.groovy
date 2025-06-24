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
import netflix.nebula.dependency.recommender.ModuleNotationParser
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.publish.maven.MavenPublication

@CompileDynamic
class MavenBomXmlGenerator {
    Project project

    MavenBomXmlGenerator(Project project) {
        this.project = project
    }

    void fromConfigurations(Closure configurationsClosure) {
        MavenPublication pub = getMavenPublication(configurationsClosure)

        Iterable<Configuration> configurations

        def configurationsRet = configurationsClosure()
        if(configurationsRet instanceof Configuration)
            configurations = [(Configuration) configurationsRet]
        else if(Iterable.class.isAssignableFrom(configurationsRet.class))
            configurations = configurationsRet as Iterable<Configuration>

        generateDependencyManagementXml(pub, { configurations.collect { getManagedDependencies(it) }.flatten() })
    }

    void withDependencies(Closure dependenciesClosure) {
        MavenPublication pub = getMavenPublication(dependenciesClosure)

        Iterable<String> dependencies = null

        def dependenciesRet = dependenciesClosure()
        if(dependenciesRet instanceof String)
            dependencies = [(String) dependenciesRet]
        else if(Iterable.class.isAssignableFrom(dependenciesRet.class))
            dependencies = dependenciesRet as Iterable<String>

        generateDependencyManagementXml(pub, { dependencies.collect { ModuleNotationParser.parse(it) } })
    }

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

    protected static Set<ModuleVersionIdentifier> getManagedDependencies(Configuration configuration) {
        getManagedDependenciesRecursive(configuration.resolvedConfiguration.firstLevelModuleDependencies,
                new HashSet<ModuleVersionIdentifier>())
    }

    protected static Set<ModuleVersionIdentifier> getManagedDependenciesRecursive(Set<ResolvedDependency> deps, Set<ModuleVersionIdentifier> all) {
        deps.each { dep ->
            if (all.add(dep.module.id)) {
                getManagedDependenciesRecursive(dep.children, all)
            }
        }
        return all
    }
}
