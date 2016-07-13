package netflix.nebula.dependency.recommender.publisher

import nebula.test.ProjectSpec
import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

class MavenBomXmlGeneratorSpec extends ProjectSpec {
    def 'configure a publication with BOM generation'() {
        given:
        project.plugins.apply(MavenPublishPlugin)
        project.plugins.apply(DependencyRecommendationsPlugin)
        project.configurations.create('recommendation')

        project.publishing {
            publications {
                recommender(MavenPublication) {
                    project.nebulaDependencyManagement.fromConfigurations {
                        project.configurations.recommendation
                    }
                }
            }
        }

        when:
        project.publishing

        then:
        noExceptionThrown()
    }
}
