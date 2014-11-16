package netflix.nebula.dependency.recommender.provider;

import groovy.lang.Closure;

public class CustomRecommendationProvider extends AbstractRecommendationProvider {
    private Closure versionFunction;

    public CustomRecommendationProvider(Closure versionFunction) {
        this.versionFunction = versionFunction;
    }

    @Override
    public String getVersion(String org, String name) throws Exception {
        return (String) versionFunction.call(org, name);
    }
}
