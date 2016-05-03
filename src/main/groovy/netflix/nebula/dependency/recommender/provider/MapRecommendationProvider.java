package netflix.nebula.dependency.recommender.provider;

import org.gradle.api.InvalidUserDataException;

import java.util.Collection;
import java.util.Map;

public class MapRecommendationProvider extends AbstractRecommendationProvider {
    private Map<String, String> recommendations;

    private FuzzyVersionResolver fuzzyResolver = new FuzzyVersionResolver() {
        @Override
        protected Collection<String> propertyNames() {
            return recommendations.keySet();
        }

        @Override
        protected String propertyValue(String projectName, String name) {
            return recommendations.get(name);
        }
    };

    @Override
    public String getVersion(String org, String name) {
        if(recommendations == null)
            throw new InvalidUserDataException("No recommender of dependencies to versions was provided");
        return fuzzyResolver.versionOf("", org + ":" + name);
    }

    public void setRecommendations(Map<String, String> recommendations) {
        this.recommendations = recommendations;
    }
}
