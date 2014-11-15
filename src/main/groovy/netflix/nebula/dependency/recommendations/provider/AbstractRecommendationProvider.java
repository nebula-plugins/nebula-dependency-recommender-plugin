package netflix.nebula.dependency.recommendations.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class AbstractRecommendationProvider implements RecommendationProvider {
    protected String name;
    protected Map<Pattern, String> globs;

    @Override
    public String getName() { return name; }

    @Override
    public void setName(String name) { this.name = name; }

    abstract protected Collection<String> propertyNames();
    abstract protected String propertyValue(String name);

    protected String resolveVersion(String value) {
        if(value == null) return null;
        if(!value.startsWith("$")) return value;
        return resolveVersion(versionOf(value.substring(1)));
    }

    protected String versionOf(String key) {
        String version = propertyValue(key);
        if(version != null) return version;

        if(globs == null) {
            // initialize glob cache
            globs = new HashMap<>();
            for (String name : propertyNames()) {
                if(name.contains("*")) {
                    globs.put(Pattern.compile(name.replaceAll("\\*", ".*?")), propertyValue(name));
                }
            }
        }

        for (Map.Entry<Pattern, String> glob: globs.entrySet())
            if(glob.getKey().matcher(key).matches())
                return glob.getValue();

        return null;
    }
}
