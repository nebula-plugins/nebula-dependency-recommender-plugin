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
        if (!isClasspathConfiguration(configuration) || container.getExcludedConfigurations().contains(configuration.getName()) || isCopyOfBomConfiguration(configuration)) {
            return;
        }

        if (configuration.getState() == Configuration.State.UNRESOLVED) {
            Configuration toExtend = bom;
            if (!project.getRootProject().equals(project)) {
                toExtend = bom.copy();
                toExtend.setVisible(false);
                toExtend.setCanBeResolved(false);
                project.getConfigurations().add(toExtend);
            }
            configuration.extendsFrom(toExtend);
        } else {
            logger.info("Configuration '" + configuration.getName() + "' has already been resolved and cannot be included for recommendation");
        }
    }

    //we want to apply recommendation only into final resolvable configurations like `compileClasspath` or `runtimeClasspath` across all source sets.
    private boolean isClasspathConfiguration(Configuration configuration) {
        return configuration.getName().endsWith("Classpath") || configuration.getName().equals("annotationProcessor");
    }

    //this action creates clones of bom configuration from root and gradle will also apply the action to them which would
    //lead to another copy of copy and so on creating infinite loop. We won't apply the action when configuration is copy from bom configuration.
    private boolean isCopyOfBomConfiguration(Configuration configuration) {
        return configuration.getName().startsWith(bom.getName());
    }
}
