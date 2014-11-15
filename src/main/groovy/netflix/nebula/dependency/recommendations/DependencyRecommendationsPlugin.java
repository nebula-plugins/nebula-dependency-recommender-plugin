package netflix.nebula.dependency.recommendations;

import netflix.nebula.dependency.recommendations.provider.RecommendationProvider;
import netflix.nebula.dependency.recommendations.provider.RecommendationProviderContainer;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.plugins.JavaPlugin;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getPlugins().withType(JavaPlugin.class, new Action<JavaPlugin>() {
            @Override
            public void execute(JavaPlugin javaPlugin) {
                project.getExtensions().create("recommendationProvider", RecommendationProviderContainer.class, project);
                applyRecommendations();
            }

            public void applyRecommendations() {
                project.getConfigurations().all(new Action<Configuration>() {
                    @Override
                    public void execute(Configuration conf) {
                        conf.getResolutionStrategy().eachDependency(new Action<DependencyResolveDetails>() {
                            @Override
                            public void execute(DependencyResolveDetails details) {
                                if (StringUtils.isBlank(details.getRequested().getVersion())) {
                                    for (RecommendationProvider provider : project.getExtensions().getByType(RecommendationProviderContainer.class)) {
                                        String group = details.getRequested().getGroup();
                                        String name = details.getRequested().getName();
                                        try {
                                            String version = provider.getVersion(group, name);
                                            if (version != null) {
                                                details.useVersion(version);
                                                break;
                                            }
                                        } catch (Exception e) {
                                            project.getLogger().error("Unable to provide a recommended version for " + group + ":" + name, e);
                                        }
                                    }
                                }
                            }
                        });
                    }
                });
            }
        });
    }
}
