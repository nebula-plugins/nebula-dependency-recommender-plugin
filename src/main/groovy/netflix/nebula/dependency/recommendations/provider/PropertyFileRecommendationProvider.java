package netflix.nebula.dependency.recommendations.provider;

import org.gradle.api.InvalidUserDataException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.Properties;

public class PropertyFileRecommendationProvider extends AbstractRecommendationProvider {
    private Properties recommendations;

    @Override
    public String getVersion(String org, String name) {
        if(recommendations == null)
            throw new InvalidUserDataException("No properties file was provided");
        return resolveVersion(versionOf(org + "/" + name));
    }

    public void setFile(File file) {
        recommendations = new Properties();
        try {
            recommendations.load(new ColonFilteringReader(new FileReader(file)));
        } catch (IOException e) {
            throw new InvalidUserDataException(String.format("Properties file %s does not exist", file.getAbsolutePath()));
        }
    }

    @Override
    protected Collection<String> propertyNames() {
        return recommendations.stringPropertyNames();
    }

    @Override
    protected String propertyValue(String name) {
        return recommendations.getProperty(name);
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
