package netflix.nebula.dependency.recommender

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class RecommendationProviderContainerSpec extends Specification {
    @Rule TemporaryFolder projectDir

    Project project

    def setup() {
        project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        project.repositories { mavenCentral() }
    }

    def 'version recommendations are given in LIFO with respect to the order in which providers are specified'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.2', 'com.google.guava:guava': '18.0']
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging'
            compile 'com.google.guava:guava'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1', '18.0']
    }

    def 'recommendation providers can be named and recommendations provided by name'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1'], name: 'r1'
            map recommendations: ['commons-logging:commons-logging': '1.0'], name: 'r2'
        }

        when:
        project.dependencies {
            compile project.recommend('commons-logging:commons-logging', 'r2')
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.0']
    }

    def 'transitive dependencies of providers are not calculated and therefore have no effect'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
            map recommendations: ['logkit:logkit': '2.0']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging'
            compile 'logkit:logkit'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1', '2.0']
    }

    def 'dependencies that already have versions are not overriden by providers'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging:1.0'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.0']
    }

    def 'recommended versions can be asked of the dependencyRecommendations extension container directly'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging:1.0'
        }

        then:
        project.dependencyRecommendations.getRecommendedVersion('commons-logging', 'commons-logging') == '1.1'
        !project.dependencyRecommendations.getRecommendedVersion('doesnotexist', 'doesnotexist')
    }

    def 'new providers can be inserted before predefined providers'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
            addFirst map(recommendations: ['commons-logging:commons-logging': '1.2', 'com.google.guava:guava': '18.0'])
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging'
            compile 'com.google.guava:guava'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1', '18.0']
    }

    def 'subprojects inherit providers from parent'() {
        setup:
        def subproject = ProjectBuilder.builder()
                .withName('subproject')
                .withParent(project)
                .build()
        project.subprojects.add(subproject)

        subproject.apply plugin: 'java'
        subproject.apply plugin: DependencyRecommendationsPlugin
        subproject.repositories { mavenCentral() }

        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        subproject.dependencies {
            compile 'commons-logging:commons-logging'
        }

        then:
        subproject.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1']
    }

    def 'transitive dependency versions are not overriden by recommendations unless there is a corresponding first order dependency'() {
        setup:
        project.dependencyRecommendations {
            strategy ConflictResolved
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        project.dependencies {
            compile 'commons-configuration:commons-configuration:1.10'
            // no first order dependency on commons-logging, so the recommendation will not be effectual
        }

        def commonsConfig = project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.iterator().next()
        def commonsLang = commonsConfig.children.find { it.moduleName == 'commons-logging' }

        then:
        commonsLang.moduleVersion == '1.1.1'
    }

    def 'transitive dependency versions are overriden by recommendations with OverrideTransitives strategy'() {
        setup:
        project.dependencyRecommendations {
            strategy OverrideTransitives
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        project.dependencies {
            compile 'commons-configuration:commons-configuration:1.10'
            // no first order dependency on commons-logging, but still recommend with OverrideTransitives strategy
        }

        def commonsConfig = project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.iterator().next()
        def commonsLang = commonsConfig.children.find { it.moduleName == 'commons-logging' }

        then:
        commonsLang.moduleVersion == '1.1'
    }

    def 'transitive dependencies are used as a source of recommendations when no explicit recommendation is provided for a module'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['some:other-module': '1.2']
        }

        when:
        project.dependencies {
            compile 'commons-configuration:commons-configuration:1.10'
            compile 'commons-logging:commons-logging'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies
                .collect { it.moduleVersion } == ['1.10', '1.1.1']
    }

    def 'forces always win over recommendations'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.2']
        }

        when:
        project.configurations.all {
            resolutionStrategy {
                force 'commons-logging:commons-logging:1.1'
            }
        }

        project.dependencies {
            compile 'commons-logging:commons-logging'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1']
    }

    def 'excludes configurations'() {
        setup:
        project.configurations.create("excluded")
        
        project.dependencyRecommendations {
            strategy OverrideTransitives
            excludeConfigurations 'excluded'
            map recommendations: ['commons-logging:commons-logging': '1.1']
            
        }

        when:
        project.dependencies {
            compile 'commons-configuration:commons-configuration:1.10'
            // no first order dependency on commons-logging, but still recommend with OverrideTransitives strategy
            excluded 'commons-configuration:commons-configuration:1.10'
            // this one will be excluded from recommendations
        }

        def commonsConfigCompile = project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.iterator().next()
        def commonsLangCompile = commonsConfigCompile.children.find { it.moduleName == 'commons-logging' }

        def commonsConfigExcluded = project.configurations.excluded.resolvedConfiguration.firstLevelModuleDependencies.iterator().next()
        def commonsLangExcluded = commonsConfigExcluded.children.find { it.moduleName == 'commons-logging' }

        then:
        commonsLangCompile.moduleVersion == '1.1'
        commonsLangExcluded.moduleVersion == '1.1.1'
    }

    def 'excludes configurations prefixes'() {
        setup:
        project.configurations.create("incrementalExcluded")

        project.dependencyRecommendations {
            strategy OverrideTransitives
            excludeConfigurationPrefixes 'incremental'
            map recommendations: ['commons-logging:commons-logging': '1.1']

        }

        when:
        project.dependencies {
            compile 'commons-configuration:commons-configuration:1.10'
            // no first order dependency on commons-logging, but still recommend with OverrideTransitives strategy
            incrementalExcluded 'commons-configuration:commons-configuration:1.10'
            // this one will be excluded from recommendations
        }

        def commonsConfigCompile = project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.iterator().next()
        def commonsLangCompile = commonsConfigCompile.children.find { it.moduleName == 'commons-logging' }

        def commonsConfigExcluded = project.configurations.incrementalExcluded.resolvedConfiguration.firstLevelModuleDependencies.iterator().next()
        def commonsLangExcluded = commonsConfigExcluded.children.find { it.moduleName == 'commons-logging' }

        then:
        commonsLangCompile.moduleVersion == '1.1'
        commonsLangExcluded.moduleVersion == '1.1.1'
    }
}
