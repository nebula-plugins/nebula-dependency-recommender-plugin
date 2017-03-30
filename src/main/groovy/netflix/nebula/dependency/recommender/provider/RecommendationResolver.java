package netflix.nebula.dependency.recommender.provider;

import netflix.nebula.dependency.recommender.ModuleNotationParser;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleVersionIdentifier;

public class RecommendationResolver {
    Project project;

    public RecommendationResolver(Project project) {
        this.project = project;
    }

    public String recommend(String dependencyNotation, String recommenderName) throws Exception {
        ModuleVersionIdentifier mvid = ModuleNotationParser.parse(dependencyNotation);

        String version = mvid.getVersion() != null ? mvid.getVersion() : project
                .getExtensions()
                .getByType(RecommendationProviderContainer.class)
                .getByName(recommenderName)
                .getVersion(project.getName(), mvid.getGroup(), mvid.getName());

        return mvid.getGroup() + ":" + mvid.getName() + ":" + version;
    }
}
