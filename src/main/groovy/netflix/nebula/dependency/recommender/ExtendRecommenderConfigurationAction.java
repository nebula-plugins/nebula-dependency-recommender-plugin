package netflix.nebula.dependency.recommender;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

public class ExtendRecommenderConfigurationAction implements Action<Configuration> {
    private final Configuration bom;
    private final Project project;
    private final RecommendationProviderContainer container;

    public ExtendRecommenderConfigurationAction(Configuration bom, Project project, RecommendationProviderContainer container) {
        this.bom = bom;
        this.project = project;
        this.container = container;
    }

    @Override
    public void execute(Configuration configuration) {
        if (container.getExcludedConfigurations().contains(configuration.getName())) {
            return;
        }
        Configuration toExtend = bom;
        if (!project.getRootProject().equals(project)) {
            toExtend = bom.copy();
            toExtend.setVisible(false);
            project.getConfigurations().add(toExtend);
        }
        configuration.extendsFrom(toExtend);
    }
}
