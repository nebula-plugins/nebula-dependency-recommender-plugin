package netflix.nebula.dependency.recommender.provider;

public interface RecommendationProvider {
    String getVersion(String org, String name) throws Exception;
    String getName();
    void setName(String name);
}
