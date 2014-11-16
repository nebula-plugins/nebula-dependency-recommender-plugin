package netflix.nebula.dependency.recommendations.provider

import groovy.json.JsonSlurper
import org.gradle.api.Project

class DependencyLockProvider extends FileBasedRecommendationProvider {
    Map<String, String> recommendations

    DependencyLockProvider() {}

    DependencyLockProvider(Project project) {
        super(project)
    }

    @Override
    String getVersion(String org, String name) throws Exception {
        if(!recommendations) {
            recommendations = new JsonSlurper().parse(input)
                .collectEntries { [(it.key) : it.value.locked] }
        }
        recommendations[org + ':' + name]
    }
}
