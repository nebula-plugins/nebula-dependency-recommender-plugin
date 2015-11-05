package netflix.nebula.dependency.recommender.provider;

import java.io.InputStream;

public interface InputStreamProvider {
    InputStream getInputStream() throws Exception;
}