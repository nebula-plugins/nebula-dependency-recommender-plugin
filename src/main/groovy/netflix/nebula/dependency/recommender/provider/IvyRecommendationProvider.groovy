package netflix.nebula.dependency.recommender.provider

import org.gradle.api.Project
import netflix.nebula.dependency.recommender.provider.FileBasedRecommendationProvider.InputStreamProvider

class IvyRecommendationProvider extends FileBasedRecommendationProvider {
    Map<String, String> versionsByCoord

    IvyRecommendationProvider(Project p) { super(p) }

    @Override
    String getVersion(String org, String name) throws Exception {
        if(versionsByCoord == null) {
            versionsByCoord = [:]
            def ivy = new XmlSlurper().parse(getInput())
            ivy.dependencies.dependency.each { d ->
                versionsByCoord.put("${d.@org.text()}:${d.@name.text()}".toString(), "${d.@rev.text()}")
            }
        }
        return versionsByCoord["$org:$name".toString()]
    }

    @SuppressWarnings("unchecked")
    @Override
    public InputStreamProvider setModule(Object dependencyNotation) {
        if(dependencyNotation instanceof String && !((String) dependencyNotation).endsWith("@ivy"))
            dependencyNotation = "${dependencyNotation}@ivy"
        if(dependencyNotation && Map.class.isAssignableFrom(dependencyNotation.getClass()))
            ((Map) dependencyNotation).put("ext", "pom")
        return super.setModule(dependencyNotation)
    }
}
