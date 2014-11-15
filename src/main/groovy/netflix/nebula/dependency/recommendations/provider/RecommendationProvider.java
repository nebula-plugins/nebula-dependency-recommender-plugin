package netflix.nebula.dependency.recommendations.provider;

public interface RecommendationProvider {
    String getVersion(String org, String name) throws Exception;
    String getName();
    void setName(String name);
}
