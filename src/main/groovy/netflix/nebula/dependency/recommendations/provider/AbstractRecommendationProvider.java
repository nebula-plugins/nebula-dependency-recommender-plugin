package netflix.nebula.dependency.recommendations.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class AbstractRecommendationProvider implements RecommendationProvider {
    protected String name;

    @Override
    public String getName() { return name; }

    @Override
    public void setName(String name) { this.name = name; }
}
