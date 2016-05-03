package netflix.nebula.dependency.recommender.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class FuzzyVersionResolver {
    public static final String PROJECT_DELIM = "@";
    private Map<Pattern, String> globs;

    abstract protected Collection<String> propertyNames();
    abstract protected String propertyValue(String projectName, String name);

    private String resolveVersion(String projectName, String value) {
        if(value == null) return null;
        if(!value.startsWith("$")) return value;
        return resolveVersion(projectName, versionOf(projectName, value.substring(1)));
    }

    public String versionOf(String projectName, String key) {
        String version = propertyValue(projectName, key);
        if(version != null) return resolveVersion(projectName, version);

        if(globs == null) {
            // initialize glob cache
            globs = new HashMap<>();
            for (String name : propertyNames()) {
                if(name.contains("*")) {
                    // do not prefix with project, since name already contains project prefix
                    globs.put(Pattern.compile(name.replaceAll("\\*", ".*?")), propertyValue("", name));
                }
            }
        }

        // scan for a project-specific glob first
        for (Map.Entry<Pattern, String> glob: globs.entrySet())
            if(glob.getKey().matcher(projectName + PROJECT_DELIM + key).matches())
                return resolveVersion(projectName, glob.getValue());

        for (Map.Entry<Pattern, String> glob: globs.entrySet())
            if(glob.getKey().matcher(key).matches())
                return resolveVersion(projectName, glob.getValue());

        return null;
    }
}
