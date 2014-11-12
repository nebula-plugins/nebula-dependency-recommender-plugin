package netflix.nebula.dependency.recommendations.provider;

public interface RecommendationProvider {
    String getVersion(String org, String name);
    String getName();
    void setName(String name);
}
