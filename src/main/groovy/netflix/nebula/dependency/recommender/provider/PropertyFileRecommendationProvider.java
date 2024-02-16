package netflix.nebula.dependency.recommender.provider;

import org.gradle.api.Project;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collection;
import java.util.Properties;

public class PropertyFileRecommendationProvider extends FileBasedRecommendationProvider {
    private Properties recommendations;

    private FuzzyVersionResolver fuzzyResolver = new FuzzyVersionResolver() {
        @Override
        protected Collection<String> propertyNames() {
            return recommendations.stringPropertyNames();
        }

        @Override
        protected String propertyValue(String name) {
            String property = recommendations.getProperty(name);
            if (property == null) {
                return null;
            }
            return property.split(" ")[0].split("#")[0];
        }
    };

    public PropertyFileRecommendationProvider(Project project) {
        super(project);
    }

    @Override
    public String getVersion(String org, String name) throws Exception {
        if(recommendations == null) {
            recommendations = new Properties();
            try (InputStream inputStream = inputProvider.getInputStream()) {
                recommendations.load(new ColonFilteringReader(new InputStreamReader(inputStream)));
            }
        }
        return fuzzyResolver.versionOf(org + "/" + name);
    }

    /**
     * Because unfortunately Properties.load treats colons as an assignment operator
     */
    private class ColonFilteringReader extends Reader {
        Reader reader;

        public ColonFilteringReader(Reader reader) {
            this.reader = reader;
        }

        @SuppressWarnings("NullableProblems")
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
