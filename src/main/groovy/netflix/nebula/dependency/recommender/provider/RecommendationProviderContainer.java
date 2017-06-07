/*
 * Copyright 2014-2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender.provider;

import com.netflix.nebula.dependencybase.DependencyManagement;
import groovy.lang.Closure;
import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin;
import netflix.nebula.dependency.recommender.RecommendationStrategies;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Namer;
import org.gradle.api.Project;
import org.gradle.api.internal.ClosureBackedAction;
import org.gradle.api.internal.ConfigureByMapAction;
import org.gradle.api.internal.DefaultNamedDomainObjectList;

import java.util.HashMap;
import java.util.Map;

public class RecommendationProviderContainer extends DefaultNamedDomainObjectList<RecommendationProvider> {

    private Project project;
    private RecommendationStrategies strategy = RecommendationStrategies.ConflictResolved;
    private MavenBomRecommendationProvider mavenBomProvider;
    private DependencyManagement insight;
    private Boolean strictMode = false;
    
    // Make strategies available without import
    public static final RecommendationStrategies OverrideTransitives = RecommendationStrategies.OverrideTransitives;
    public static final RecommendationStrategies ConflictResolved = RecommendationStrategies.ConflictResolved;

    private final Action<? super RecommendationProvider> addLastAction = new Action<RecommendationProvider>() {
        public void execute(RecommendationProvider r) {
            RecommendationProviderContainer.super.add(r);
        }
    };

    public RecommendationProviderContainer(Project project, DependencyManagement dependencyInsight) {
        super(RecommendationProvider.class, null, new RecommendationProviderNamer());
        this.project = project;
        this.insight = dependencyInsight;
        this.mavenBomProvider = new MavenBomRecommendationProvider(this.project, DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, this.insight);
        this.add(this.mavenBomProvider);
    }

    private static class RecommendationProviderNamer implements Namer<RecommendationProvider> {
        public String determineName(RecommendationProvider r) {
            return r.getName();
        }
    }

    public <T extends RecommendationProvider> T add(T provider, Action<? super T> configureAction) {
        configureAction.execute(provider);
        assertCanAdd(provider.getName());
        addLastAction.execute(provider);
        return provider;
    }

    public <T extends RecommendationProvider> T addFirst(T provider) {
        remove(provider);
        super.add(0, provider);
        return provider;
    }

    public PropertyFileRecommendationProvider propertiesFile(Map<String, ?> args) {
        insight.addPluginMessage("nebula.dependency-recommender uses a properties file: " + args.get("file"));
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return add(new PropertyFileRecommendationProvider(project), new ConfigureByMapAction<PropertyFileRecommendationProvider>(modifiedArgs));
    }

    public PropertyFileRecommendationProvider propertiesFile(Closure closure) {
        insight.addPluginMessage("nebula.dependency-recommender uses a properties file");
        return add(new PropertyFileRecommendationProvider(project), new ClosureBackedAction<PropertyFileRecommendationProvider>(closure));
    }

    public MavenBomRecommendationProvider mavenBom(Map<String, ?> args) {
        Object dependencyNotation = args.get("module");
        if(dependencyNotation == null) {
            throw new IllegalArgumentException("Module may not be null");
        }

        if(Map.class.isAssignableFrom(dependencyNotation.getClass())) {
            ((Map) dependencyNotation).put("ext", "pom");
        } else if(!dependencyNotation.toString().endsWith("@pom")) {
            dependencyNotation = dependencyNotation.toString() + "@pom";
        }
        project.getDependencies().add(DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, dependencyNotation);

        return mavenBomProvider;
    }

    public IvyRecommendationProvider ivyXml(Map<String, ?> args) {
        insight.addPluginMessage("nebula.dependency-recommender uses a ivyXml: " + args.get("module"));
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return add(new IvyRecommendationProvider(project), new ConfigureByMapAction<IvyRecommendationProvider>(modifiedArgs));
    }

    public IvyRecommendationProvider ivyXml(Closure closure) {
        insight.addPluginMessage("nebula.dependency-recommender uses a ivyXml");
        return add(new IvyRecommendationProvider(project), new ClosureBackedAction<IvyRecommendationProvider>(closure));
    }

    public DependencyLockProvider dependencyLock(Map<String, ?> args) {
        insight.addPluginMessage("nebula.dependency-recommender uses a dependency lock: " + args.get("module"));
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return add(new DependencyLockProvider(project), new ConfigureByMapAction<DependencyLockProvider>(modifiedArgs));
    }

    public DependencyLockProvider dependencyLock(Closure closure) {
        insight.addPluginMessage("nebula.dependency-recommender uses a dependency lock for recommendations");
        return add(new DependencyLockProvider(project), new ClosureBackedAction<DependencyLockProvider>(closure));
    }

    public MapRecommendationProvider map(Map<String, ?> args) {
        insight.addPluginMessage("nebula.dependency-recommender uses a provided map for recommendations");
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return add(new MapRecommendationProvider(), new ConfigureByMapAction<MapRecommendationProvider>(modifiedArgs));
    }

    public MapRecommendationProvider map(Closure closure) {
        insight.addPluginMessage("nebula.dependency-recommender uses a provided map for recommendations");
        return add(new MapRecommendationProvider(), new ClosureBackedAction<MapRecommendationProvider>(closure));
    }

    public CustomRecommendationProvider add(Closure closure) {
        insight.addPluginMessage("nebula.dependency-recommender uses a CustomRecommendationProvider");
        return add(new CustomRecommendationProvider(closure), new Action<CustomRecommendationProvider>() {
            @Override
            public void execute(CustomRecommendationProvider customRecommendationProvider) {
            }
        });
    }

    public String getRecommendedVersion(String group, String name) {
        // providers are queried in LIFO order
        for (int i = size()-1; i >= 0; i--) {
            try {
                String version = get(i).getVersion(group, name);
                if (version != null) {
                    return version;
                }
            } catch(Exception e) {
                project.getLogger().error("Exception while polling provider " + get(i).getName() + " for version", e);
            }
        }
        return null;
    }

    public RecommendationStrategies getStrategy() {
        return strategy;
    }

    public void setStrategy(RecommendationStrategies strategy) {
        this.strategy = strategy;
    }

    public Boolean isStrictMode() {
        return strictMode;
    }

    public void setStrictMode(Boolean strict) {
        strictMode = strict;
    }
}
