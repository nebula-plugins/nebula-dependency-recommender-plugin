package netflix.nebula.dependency.recommender.provider

import org.gradle.api.Project

class IvyRecommendationProvider extends FileBasedRecommendationProvider {
    volatile Map<String, String> versionsByCoord

    IvyRecommendationProvider(Project p) { super(p) }

    @Override
    String getVersion(String org, String name) throws Exception {
        
        Map<String, String> tmpResult = versionsByCoord
        
        if (tmpResult == null) {
            synchronized (this) {
                tmpResult = versionsByCoord
                if (tmpResult == null) {
                    tmpResult = [:]
                    getInput().withCloseable {
                        def ivy = new XmlSlurper().parse(it)
                        ivy.dependencies.dependency.each { d ->
                            tmpResult.put("${d.@org.text()}:${d.@name.text()}".toString(), "${d.@rev.text()}")
                        }
                    }
                    versionsByCoord = tmpResult
                }
            }
        }
        return tmpResult["$org:$name".toString()]
    }

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
