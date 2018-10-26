package netflix.nebula.dependency.recommender;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

public class ExtendRecommenderConfigurationAction implements Action<Configuration> {

    private Logger logger = Logging.getLogger(ExtendRecommenderConfigurationAction.class);

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

        if (configuration.getState() == Configuration.State.UNRESOLVED) {
            Configuration toExtend = bom;
            if (!project.getRootProject().equals(project)) {
                toExtend = bom.copy();
                toExtend.setVisible(false);
                project.getConfigurations().add(toExtend);
            }
            configuration.extendsFrom(toExtend);
        } else {
            logger.info("Configuration '" + configuration.getName() + "' has already been resolved and cannot be included for recommendation");
        }
    }
}
