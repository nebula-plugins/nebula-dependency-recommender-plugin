package netflix.nebula.dependency.recommendations.provider;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.gradle.mvn3.org.apache.maven.model.Dependency;
import org.gradle.mvn3.org.apache.maven.model.Model;
import org.gradle.mvn3.org.apache.maven.model.building.*;
import org.gradle.mvn3.org.apache.maven.model.interpolation.StringSearchModelInterpolator;
import org.gradle.mvn3.org.apache.maven.model.path.DefaultPathTranslator;
import org.gradle.mvn3.org.apache.maven.model.path.DefaultUrlNormalizer;
import org.gradle.mvn3.org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.gradle.mvn3.org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.gradle.mvn3.org.codehaus.plexus.interpolation.ValueSource;

import java.io.File;
import java.util.*;

public class MavenBomRecommendationProvider extends FileBasedRecommendationProvider {
    private Map<String, String> recommendations;

    public MavenBomRecommendationProvider(Project project) {
        super(project);
    }

//    def request = new DefaultModelBuildingRequest()
//    request.setModelSource(new FileModelSource(file))
//    request.modelResolver = new StandardModelResolver()
//    def result = modelBuilder.build(request)
//    result.effectiveModel.dependencyManagement.dependencies.each { dependency ->
//            versions["$dependency.groupId:$dependency.artifactId" as String] = dependency.version
//    }

    @Override
    public String getVersion(String org, String name) throws Exception {
        if(recommendations == null) {
            recommendations = new HashMap<>();
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setModelSource(new StringModelSource(IOUtils.toString(getInput(), "UTF-8")));

            DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
            modelBuilder.setModelInterpolator(new ProjectPropertiesModelInterpolator(project));

            ModelBuildingResult result = modelBuilder.build(request);
            for (Dependency d : result.getEffectiveModel().getDependencyManagement().getDependencies()) {
                recommendations.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
            }
        }
        return recommendations.get(org + ":" + name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public InputStreamProvider setModule(Object dependencyNotation) {
        if(dependencyNotation instanceof String && !((String) dependencyNotation).endsWith("@pom"))
            dependencyNotation = dependencyNotation + "@pom";
        if(dependencyNotation != null && Map.class.isAssignableFrom(dependencyNotation.getClass()))
            ((Map) dependencyNotation).put("ext", "pom");
        return super.setModule(dependencyNotation);
    }

    @Override
    protected Collection<String> propertyNames() {
        return null;
    }

    @Override
    protected String propertyValue(String name) {
        return null;
    }

    private static class ProjectPropertiesModelInterpolator extends StringSearchModelInterpolator {
        private final Project project;

        ProjectPropertiesModelInterpolator(Project project) {
            this.project = project;
            setUrlNormalizer(new DefaultUrlNormalizer());
            setPathTranslator(new DefaultPathTranslator());
        }

        public List<ValueSource> createValueSources(Model model, File projectDir, ModelBuildingRequest request, ModelProblemCollector collector) {
            List<ValueSource> sources = new ArrayList<>();
            sources.add(new MapBasedValueSource(project.getProperties()));
            sources.add(new PropertiesBasedValueSource(System.getProperties()));
            sources.addAll(super.createValueSources(model, projectDir, request, collector));
            return sources;
        }
    }
}
