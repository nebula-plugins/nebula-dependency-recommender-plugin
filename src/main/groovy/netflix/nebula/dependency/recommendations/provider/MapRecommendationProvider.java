package netflix.nebula.dependency.recommendations.provider;

import org.gradle.api.InvalidUserDataException;

import java.util.Collection;
import java.util.Map;

public class MapRecommendationProvider extends AbstractRecommendationProvider {
    private Map<String, String> map;

    @Override
    public String getVersion(String org, String name) {
        if(map == null)
            throw new InvalidUserDataException("No map of dependencies to versions was provided");
        return resolveVersion(versionOf(org + ":" + name));
    }

    public void setMap(Map<String, String> map) {
        this.map = map;
    }

    @Override
    protected Collection<String> propertyNames() { return map.keySet(); }

    @Override
    protected String propertyValue(String name) { return map.get(name); }
}
