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
                project.getExtensions().create("recommendationProvider", RecommendationProviderContainer.class);
                applyRecommendations();
            }

            public void applyRecommendations() {
                project.getConfigurations().all(new Action<Configuration>() {
                    @Override
                    public void execute(Configuration conf) {
                        conf.getResolutionStrategy().eachDependency(new Action<DependencyResolveDetails>() {
                            @Override
                            public void execute(DependencyResolveDetails details) {
                                RecommendationProviderContainer providerContainer = (RecommendationProviderContainer) project.getExtensions().getByName("recommendationProvider");

                                if (StringUtils.isBlank(details.getRequested().getVersion())) {
                                    for (RecommendationProvider provider : providerContainer) {
                                        String version = provider.getVersion(details.getRequested().getGroup(),
                                                details.getRequested().getName());
                                        if (version != null) {
                                            details.useVersion(version);
                                            break;
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
