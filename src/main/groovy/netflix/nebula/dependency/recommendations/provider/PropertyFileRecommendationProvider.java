package netflix.nebula.dependency.recommendations.provider;

import java.io.File;

public class PropertyFileRecommendationProvider extends AbstractRecommendationProvider {
    private File file;

    @Override
    public String getVersion(String org, String name) {
        return null;
    }

    public void setFile(File file) {
        this.file = file;
    }
}
