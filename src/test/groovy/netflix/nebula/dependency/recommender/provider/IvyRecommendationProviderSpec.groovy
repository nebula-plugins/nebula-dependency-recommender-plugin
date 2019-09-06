package netflix.nebula.dependency.recommender.provider

import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

class IvyRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir
    static def version = '1.0'

    @Unroll
    def 'recommendations are loaded from the dependencies section of an ivy file #module'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def repo = projectDir.newFolder('repo')
        def sample = new File(repo, 'sample/recommender/1.0')
        sample.mkdirs()
        def sampleFile = new File(sample, 'recommender-1.0.ivy')
        sampleFile << '''<?xml version="1.0" encoding="UTF-8"?>
            <ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra" xmlns:n="http://netflix.com/build">
              <info organisation="sample" module="recommender" revision="1.0" status="release" publication="20150318224045">
                <n:manifest>
                  Implementation-Vendor: Netflix, Inc.
                  Status-Minimum: candidate
                  X-Compile-Target-JDK: 1.7
                  X-Compile-Source-JDK: 1.7
                </n:manifest>
              </info>
              <configurations>
                <conf name="compile" visibility="public"/>
                <conf name="runtime" visibility="public"/>
                <conf name="default" visibility="public" extends="runtime"/>
                <conf name="dependencyReport" visibility="public"/>
                <conf name="javadoc" visibility="public"/>
              </configurations>
              <publications>
                <artifact name="platform" type="jar" ext="jar" conf="runtime"/>
              </publications>
              <dependencies defaultconfmapping="%-&gt;default">
                <dependency org="netflix" name="platform-ipc" rev="2.1287.0" conf="runtime-&gt;default"/>
              </dependencies>
            </ivy-module>
        '''

        project.repositories { ivy {
            url repo
            patternLayout {
                ivy '[organisation]/[module]/[revision]/[module]-[revision].ivy'
                artifact '[organisation]/[module]/[revision]/[module]-[revision].[ext]'
                m2compatible = true
            }
        } }

        when:
        def recommendations = new IvyRecommendationProvider(project)
        recommendations.setModule(module)

        then:
        recommendations.getVersion('netflix', 'platform-ipc') == '2.1287.0'

        where:
        module << [
            'sample:recommender:1.0',
            "sample:recommender:$version", // verify GString doesn't cause issues
            'sample:recommender:1.0@ivy',
            "sample:recommender:$version@ivy"
        ]
    }
}
