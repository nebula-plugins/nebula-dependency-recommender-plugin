package netflix.nebula.dependency.recommender.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class FuzzyVersionResolver {
    private Map<Pattern, String> globs;

    abstract protected Collection<String> propertyNames();
    abstract protected String propertyValue(String name);

    private String resolveVersion(String value) {
        if(value == null) return null;
        if(!value.startsWith("$")) return value;
        return resolveVersion(versionOf(value.substring(1)));
    }

    public String versionOf(String key) {
        String version = propertyValue(key);
        if(version != null) return resolveVersion(version);

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
                return resolveVersion(glob.getValue());

        return null;
    }
}
