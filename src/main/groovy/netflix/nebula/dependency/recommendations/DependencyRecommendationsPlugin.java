package netflix.nebula.dependency.recommendations;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getPlugins().withType(JavaPlugin.class, new Action<JavaPlugin>() {
            @Override
            public void execute(JavaPlugin javaPlugin) {
                project.getExtensions().create("recommendationProvider", RecommendationProviderExtension.class);
            }
        });
    }
}
