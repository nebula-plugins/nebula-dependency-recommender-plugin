package netflix.nebula.dependency.recommender

import nebula.test.IntegrationSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator

class DependencyRecommendationsMultiprojectPluginSpec extends IntegrationSpec {
    def 'can use recommender across a multiproject'() {
        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule('example:bar:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(depGraph)
        generator.generateTestMavenRepo()

        def a = addSubproject('a', '''\
                dependencies {
                    compile 'example:foo'
                }
            '''.stripIndent())
        writeHelloWorld('a', a)
        def b = addSubproject('b', '''\
                dependencies {
                    compile project(':a')
                }
            '''.stripIndent())
        writeHelloWorld('b', b)
        buildFile << """\
            allprojects {
                apply plugin: 'nebula.dependency-recommender'
                dependencyRecommendations {
                    map recommendations: ['example:foo': '1.0.0']
                }
            }
            subprojects {
                apply plugin: 'java'

                repositories {
                    ${generator.mavenRepositoryBlock}
                }
            }
            """.stripIndent()
        when:
        def results = runTasksSuccessfully(':a:dependencies', ':b:dependencies', 'build')

        then:
        noExceptionThrown()
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo\n' +
                '\\--- project :a\n' +
                '     \\--- example:foo: -> 1.0.0'
    }
}
