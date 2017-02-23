package netflix.nebula.dependency.recommender.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public abstract class FuzzyVersionResolver {
    private List<Glob> globs;

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
            globs = new ArrayList<>();
            for (String name : propertyNames()) {
                if(name.contains("*")) {
                    globs.add(Glob.compile(name, propertyValue(name)));
                }
            }
            Collections.sort(globs);
        }

        for (Glob glob : globs) {
            if (glob.matches(key)) {
                return resolveVersion(glob.version);
            }
        }

        return null;
    }

    private static class Glob implements Comparable<Glob> {
        private final Pattern pattern;
        private final String version;
        private final int weight;

        private Glob(Pattern pattern, String version, int weight) {
            this.pattern = pattern;
            this.version = version;
            this.weight = weight;
        }

        private static Glob compile(String glob, String version) {
            StringBuilder patternBuilder = new StringBuilder();
            boolean first = true;
            int weight = 0;

            for (String token : glob.split("\\*", -1)) {
                if (first) {
                    first = false;
                } else {
                    patternBuilder.append(".*?");
                }

                weight += token.length();
                patternBuilder.append(Pattern.quote(token));
            }

            Pattern pattern = Pattern.compile(patternBuilder.toString());

            return new Glob(pattern, version, weight);
        }

        private boolean matches(String key) {
            return pattern.matcher(key).matches();
        }

        @Override
        public int compareTo(Glob o) {
            return Integer.compare(o.weight, weight);
        }
    }
}
