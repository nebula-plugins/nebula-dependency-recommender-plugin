package netflix.nebula.dependency.recommendations.provider;

import org.gradle.api.InvalidUserDataException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

public class PropertyFileRecommendationProvider extends AbstractRecommendationProvider {
    private Properties recommendations;
    private Map<Pattern, String> wildcards;

    @Override
    public String getVersion(String org, String name) {
        if(recommendations == null)
            throw new InvalidUserDataException("No properties file was provided");
        return resolveVersion(versionOf(org + "/" + name));
    }

    private String resolveVersion(String value) {
        if(value == null) return null;
        if(!value.startsWith("$")) return value;
        return resolveVersion(versionOf(value.substring(1)));
    }

    private String versionOf(String key) {
        String version = recommendations.getProperty(key);
        if(version != null) return version;

        if(wildcards == null) {
            wildcards = new HashMap<>();
            for (String name : recommendations.stringPropertyNames()) {
                if(name.contains("*")) {
                    wildcards.put(Pattern.compile(name.replaceAll("\\*", ".*?")),
                            (String) recommendations.get(name));
                }
            }
        }

        for (Map.Entry<Pattern, String> wildcard: wildcards.entrySet()) {
            if(wildcard.getKey().matcher(key).matches())
                return wildcard.getValue();
        }

        return null;
    }

    public void setFile(File file) {
        recommendations = new Properties();
        try {
            recommendations.load(new ColonFilteringReader(new FileReader(file)));
        } catch (IOException e) {
            throw new InvalidUserDataException(String.format("Properties file %s does not exist", file.getAbsolutePath()));
        }
    }

    /**
     * Because unfortunately Properties.load treats colons as an assignment operator
     */
    private class ColonFilteringReader extends Reader {
        Reader reader;

        public ColonFilteringReader(Reader reader) {
            this.reader = reader;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int pos = reader.read(cbuf, off, len);
            for(int i = 0; i < cbuf.length; i++)
                if(cbuf[i] == ':')
                    cbuf[i] = '/';
            return pos;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
