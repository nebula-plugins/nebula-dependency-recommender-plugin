package netflix.nebula.dependency.recommender.provider

import org.gradle.api.Project

class IvyRecommendationProvider extends FileBasedRecommendationProvider {
    Map<String, String> versionsByCoord

    IvyRecommendationProvider(Project p) { super(p) }

    @Override
    String getVersion(String org, String name) throws Exception {
        if (versionsByCoord == null) {
            versionsByCoord = [:]
            getInput().withCloseable {
                def ivy = new XmlSlurper().parse(it)
                ivy.dependencies.dependency.each { d ->
                    versionsByCoord.put("${d.@org.text()}:${d.@name.text()}".toString(), "${d.@rev.text()}")
                }
            }
        }
        return versionsByCoord["$org:$name".toString()]
    }

    @SuppressWarnings("unchecked")
    @Override
    public InputStreamProvider setModule(Object dependencyNotation) {
        if (dependencyNotation == null)
            throw new IllegalArgumentException("Module may not be null")

        if (dependencyNotation && Map.class.isAssignableFrom(dependencyNotation.getClass()))
            ((Map) dependencyNotation).put("ext", "ivy")
        else if (!dependencyNotation.toString().endsWith("@ivy"))
            dependencyNotation = "${dependencyNotation}@ivy".toString()
        return super.setModule(dependencyNotation)
    }
}
