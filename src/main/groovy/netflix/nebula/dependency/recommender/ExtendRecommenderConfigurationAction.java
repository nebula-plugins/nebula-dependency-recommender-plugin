package netflix.nebula.dependency.recommender;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.util.Set;

public class ExtendRecommenderConfigurationAction implements Action<Configuration> {
    private final Configuration bom;
    private final Set<String> excludedConfigurationNames;
    private final Project project;

    public ExtendRecommenderConfigurationAction(Configuration bom, Set<String> excludedConfigurationNames, Project project) {
        this.bom = bom;
        this.excludedConfigurationNames = excludedConfigurationNames;
        this.project = project;
    }

    @Override
    public void execute(Configuration configuration) {
        if (excludedConfigurationNames.contains(configuration.getName())) {
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
