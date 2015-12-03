package netflix.nebula.dependency.recommender.publisher
import netflix.nebula.dependency.recommender.ModuleNotationParser
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.publish.maven.MavenPublication

class MavenBomXmlGenerator {
    Project project

    MavenBomXmlGenerator(Project project) {
        this.project = project
    }

    void fromConfigurations(Closure configurationsClosure) {
        MavenPublication pub = configurationsClosure.delegate.delegate

        Iterable<Configuration> configurations

        def configurationsRet = configurationsClosure()
        if(configurationsRet instanceof Configuration)
            configurations = [(Configuration) configurationsRet]
        else if(Iterable.class.isAssignableFrom(configurationsRet.class))
            configurations = configurationsRet

        generateDependencyManagementXml(pub, configurations.collect { getManagedDependencies(it) }.flatten())
    }

    void withDependencies(Closure dependenciesClosure) {
        MavenPublication pub = dependenciesClosure.delegate.delegate

        Iterable<String> dependencies = null

        def dependenciesRet = dependenciesClosure()
        if(dependenciesRet instanceof String)
            dependencies = [(String) dependenciesRet]
        else if(Iterable.class.isAssignableFrom(dependenciesRet.class))
            dependencies = dependenciesRet

        generateDependencyManagementXml(pub, dependencies.collect { ModuleNotationParser.parse(it) })
    }

    protected static generateDependencyManagementXml(MavenPublication pub, Iterable<ModuleVersionIdentifier> deps) {
        pub.pom.withXml {
            Node root = it.asNode()
            def dependencyManagement = root.getByName("dependencyManagement")

            // when merging two or more sources of dependencies, we want to only create one dependencyManagement section
            Node dependencies
            if(dependencyManagement.isEmpty())
                dependencies = root.appendNode("dependencyManagement").appendNode("dependencies")
            else
                dependencies = dependencyManagement[0].getByName("dependencies")[0]

            deps.each { mvid ->
                Node dep = dependencies.appendNode("dependency")
                dep.appendNode("groupId").value = mvid.group
                dep.appendNode("artifactId").value = mvid.name
                dep.appendNode("version").value = mvid.version
            }
        }
    }

    protected static Set<ModuleVersionIdentifier> getManagedDependencies(Configuration configuration) {
        getManagedDependenciesRecursive(configuration.resolvedConfiguration.firstLevelModuleDependencies,
                new HashSet<ModuleVersionIdentifier>())
    }

    protected static Set<ModuleVersionIdentifier> getManagedDependenciesRecursive(Set<ResolvedDependency> deps, Set<ModuleVersionIdentifier> all) {
        deps.each { dep ->
            all << dep.module.id
            getManagedDependenciesRecursive(dep.children, all)
        }
        return all
    }
}
