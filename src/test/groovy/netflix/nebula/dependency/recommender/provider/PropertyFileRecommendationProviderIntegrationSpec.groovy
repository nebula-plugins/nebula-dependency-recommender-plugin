package netflix.nebula.dependency.recommender.provider

import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult
import org.gradle.api.logging.LogLevel

class PropertyFileRecommendationProviderIntegrationSpec extends IntegrationSpec {

    def 'inter-project dependency integration test'() {
        setup:
        def propFile = createFile('recommender.properties')
        propFile << '''
            com.sun.jersey:* = 1.23
            projectA@com.sun.jersey:* = 4.56
            projectB@com.sun.jersey:jersey-core = 9.99
        '''.stripIndent()

        buildFile << '''
            buildscript {
                repositories { jcenter() }

                dependencies {
                    classpath 'com.netflix.nebula:nebula-dependency-recommender:3.1.0'
                }
            }

            subprojects {
                apply plugin: 'java'
                // must be applied to sub-projects to get per-project dependencies
                apply plugin: 'nebula.dependency-recommender'
            }

            // applied at root to set the propertiesFile for all projects
            apply plugin: 'nebula.dependency-recommender'

            dependencyRecommendations {
                propertiesFile file: file('recommender.properties')
            }
        '''.stripIndent()

        addSubproject('projectA', '''
            def myVersion = dependencyRecommendations.getRecommendedVersion('com.sun.jersey', 'jersey-other')
            System.out.println "projectA Version: $myVersion"
        '''.stripIndent())

        addSubproject('projectB', '''
            def myVersion = dependencyRecommendations.getRecommendedVersion('com.sun.jersey', 'jersey-core')
            System.out.println "projectB Version: $myVersion"
        '''.stripIndent())

        addSubproject('projectC', '''
            def myVersion = dependencyRecommendations.getRecommendedVersion('com.sun.jersey', 'jersey-core')
            System.out.println "projectC Version: $myVersion"
        '''.stripIndent())

        when:
        ExecutionResult result = runTasksSuccessfully('compileJava')

        then:
        result.success

        result.standardOutput =~ "projectA Version: 4.56"
        result.standardOutput =~ "projectB Version: 9.99"
        result.standardOutput =~ "projectC Version: 1.23"
    }
}
