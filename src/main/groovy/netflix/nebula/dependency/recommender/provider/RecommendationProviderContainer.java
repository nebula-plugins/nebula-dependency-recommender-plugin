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

import groovy.lang.Closure;
import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin;
import netflix.nebula.dependency.recommender.RecommendationStrategies;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.internal.ConfigureByMapAction;
import org.gradle.api.model.ObjectFactory;
import org.gradle.util.ConfigureUtil;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin.CORE_BOM_SUPPORT_ENABLED;

public class RecommendationProviderContainer {

    private Project project;
    private NamedDomainObjectList<RecommendationProvider> providers;
    private RecommendationStrategies strategy = RecommendationStrategies.ConflictResolved;
    private MavenBomRecommendationProvider mavenBomProvider;
    private Boolean strictMode = false;
    private Set<String> excludedConfigurations = new HashSet<>();
    private Set<String> excludedConfigurationPrefixes = new HashSet<>();
    private Set<String> reasons = new HashSet<>();
    private Boolean eagerlyResolve = true;
    
    // Make strategies available without import
    public static final RecommendationStrategies OverrideTransitives = RecommendationStrategies.OverrideTransitives;
    public static final RecommendationStrategies ConflictResolved = RecommendationStrategies.ConflictResolved;

    public RecommendationProviderContainer(Project project) {
        createList(project);
        this.project = project;
        this.mavenBomProvider = getMavenBomRecommendationProvider();
        providers.add(this.mavenBomProvider);
    }

    private void createList(Project project) {
        ObjectFactory objects = project.getObjects();
        try {
            Method factoryMethod = objects.getClass().getDeclaredMethod("namedDomainObjectList", Class.class);
            providers = (NamedDomainObjectList<RecommendationProvider>) factoryMethod.invoke(objects, RecommendationProvider.class);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("NamedDomainObjectList couldn't be created", e);
        }

    }

    private static class RecommendationProviderNamer implements Namer<RecommendationProvider> {
        public String determineName(RecommendationProvider r) {
            return r.getName();
        }
    }

    private MavenBomRecommendationProvider getMavenBomRecommendationProvider() {
        MavenBomRecommendationProvider mavenBomRecommendationProvider;
        if(DependencyRecommendationsPlugin.CORE_BOM_SUPPORT_ENABLED) {
            mavenBomRecommendationProvider = new CoreBomSupportProvider(this.project, DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, this.reasons);
        } else {
            mavenBomRecommendationProvider = new MavenBomRecommendationProvider(this.project, DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, this.reasons);
        }
        return mavenBomRecommendationProvider;
    }

    public <T extends RecommendationProvider> T addProvider(T provider, Action<? super T> configureAction) {
        configureAction.execute(provider);
        providers.add(provider);
        return provider;
    }

    public <T extends RecommendationProvider> T addFirst(T provider) {
        providers.remove(provider);
        providers.add(0, provider);
        return provider;
    }

    public RecommendationProvider getByName(String name) {
        return providers.getByName(name);
    }

    public PropertyFileRecommendationProvider propertiesFile(Map<String, ?> args) {
        ensureCoreBomSupportNotEnabled("propertiesFile");
        String message = "nebula.dependency-recommender uses a properties file: " + args.get("file");
        reasons.add(message);
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return addProvider(new PropertyFileRecommendationProvider(project), new ConfigureByMapAction<PropertyFileRecommendationProvider>(modifiedArgs));
    }

    public PropertyFileRecommendationProvider propertiesFile(Closure closure) {
        ensureCoreBomSupportNotEnabled("propertiesFile");
        String message = "nebula.dependency-recommender uses a properties file";
        reasons.add(message);
        return addProvider(new PropertyFileRecommendationProvider(project), ConfigureUtil.<PropertyFileRecommendationProvider>configureUsing(closure));
    }

    public MavenBomRecommendationProvider mavenBom(Map<String, ?> args) {
        Object dependencyNotation = args.get("module");
        Object isEnforced = args.get("enforced");
        if(dependencyNotation == null) {
            throw new IllegalArgumentException("Module may not be null");
        }

        if (!CORE_BOM_SUPPORT_ENABLED) {
            if (Map.class.isAssignableFrom(dependencyNotation.getClass())) {
                ((Map) dependencyNotation).put("ext", "pom");
            } else if (!dependencyNotation.toString().endsWith("@pom")) {
                dependencyNotation = dependencyNotation.toString() + "@pom";
            }
            project.getDependencies().add(DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, dependencyNotation);
        } else {
            Dependency platform;
            if (isEnforced != null && Boolean.valueOf(isEnforced.toString())) {
                platform = project.getDependencies().enforcedPlatform(dependencyNotation);
            } else
                platform = project.getDependencies().platform(dependencyNotation);
            project.getDependencies().add(DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, platform);
        }

        return mavenBomProvider;
    }

    public IvyRecommendationProvider ivyXml(Map<String, ?> args) {
        ensureCoreBomSupportNotEnabled("ivyXml");
        String message = "nebula.dependency-recommender uses a ivyXml: " + args.get("module");
        reasons.add(message);
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return addProvider(new IvyRecommendationProvider(project), new ConfigureByMapAction<IvyRecommendationProvider>(modifiedArgs));
    }

    public IvyRecommendationProvider ivyXml(Closure closure) {
        ensureCoreBomSupportNotEnabled("ivyXml");
        String message = "nebula.dependency-recommender uses a ivyXml";
        reasons.add(message);
        return addProvider(new IvyRecommendationProvider(project), ConfigureUtil.<IvyRecommendationProvider>configureUsing(closure));
    }

    public DependencyLockProvider dependencyLock(Map<String, ?> args) {
        ensureCoreBomSupportNotEnabled("dependencyLock");
        String message = "nebula.dependency-recommender uses a dependency lock: " + args.get("module");
        reasons.add(message);
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return addProvider(new DependencyLockProvider(project), new ConfigureByMapAction<DependencyLockProvider>(modifiedArgs));
    }

    public DependencyLockProvider dependencyLock(Closure closure) {
        ensureCoreBomSupportNotEnabled("dependencyLock");
        String message = "nebula.dependency-recommender uses a dependency lock for recommendations";
        reasons.add(message);
        return addProvider(new DependencyLockProvider(project), ConfigureUtil.<DependencyLockProvider>configureUsing(closure));
    }

    public MapRecommendationProvider map(Map<String, ?> args) {
        ensureCoreBomSupportNotEnabled("map");
        String message = "nebula.dependency-recommender uses a provided map for recommendations";
        reasons.add(message);
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return addProvider(new MapRecommendationProvider(), new ConfigureByMapAction<MapRecommendationProvider>(modifiedArgs));
    }

    public MapRecommendationProvider map(Closure closure) {
        ensureCoreBomSupportNotEnabled("map");
        String message = "nebula.dependency-recommender uses a provided map for recommendations";
        reasons.add(message);
        return addProvider(new MapRecommendationProvider(), ConfigureUtil.<MapRecommendationProvider>configureUsing(closure));
    }

    public CustomRecommendationProvider addProvider(Closure closure) {
        ensureCoreBomSupportNotEnabled("addProvider");
        String message = "nebula.dependency-recommender uses a CustomRecommendationProvider";
        reasons.add(message);
        return addProvider(new CustomRecommendationProvider(closure), new Action<CustomRecommendationProvider>() {
            @Override
            public void execute(CustomRecommendationProvider customRecommendationProvider) {
            }
        });
    }

    public MavenBomRecommendationProvider getMavenBomProvider() {
        return mavenBomProvider;
    }

    public String getRecommendedVersion(String group, String name) {
        // providers are queried in LIFO order
        for (int i = providers.size()-1; i >= 0; i--) {
            try {
                String version = providers.get(i).getVersion(group, name);
                if (version != null) {
                    return version;
                }
            } catch(Exception e) {
                project.getLogger().error("Exception while polling provider " + providers.get(i).getName() + " for version", e);
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

    @Deprecated
    public Boolean isStrictMode() {
        return getStrictMode();
    }

    public Boolean getStrictMode() {
        return strictMode;
    }

    public void setStrictMode(Boolean strict) {
        strictMode = strict;
    }

    /**
     * Sets whether BOM configurations should be resolved eagerly during the configuration phase.
     * 
     * <p>When set to {@code true} (default), BOM configurations will be resolved automatically 
     * during the {@code afterEvaluate} phase to prevent configuration resolution lock conflicts 
     * in parallel builds with Gradle 9+.</p>
     * 
     * <p>When set to {@code false}, external plugins can take control of BOM resolution timing
     * by calling {@link netflix.nebula.dependency.recommender.util.BomResolutionUtil#eagerlyResolveBoms} 
     * manually after modifying BOM configurations.</p>
     * 
     * <p><strong>Usage by External Plugins:</strong></p>
     * <pre>{@code
     * // Disable automatic eager resolution
     * dependencyRecommendations {
     *     setEagerlyResolve(false)
     *     
     *     // Add initial BOMs
     *     mavenBom module: 'com.example:base-bom:1.0.0'
     * }
     * 
     * project.afterEvaluate { p ->
     *     def container = p.extensions.getByType(RecommendationProviderContainer)
     *     
     *     // Add additional BOMs dynamically
     *     container.mavenBom(module: 'com.example:dynamic-bom:2.0.0')
     *     
     *     // Manually trigger resolution
     *     BomResolutionUtil.eagerlyResolveBoms(p, container, 'nebulaRecommenderBom')
     * }
     * }</pre>
     * 
     * @param eagerlyResolve {@code true} to enable automatic eager resolution, 
     *                       {@code false} to disable it and allow manual control
     * @since 12.7.0
     * @see netflix.nebula.dependency.recommender.util.BomResolutionUtil#eagerlyResolveBoms
     */
    public void setEagerlyResolve(Boolean eagerlyResolve) {
        this.eagerlyResolve = eagerlyResolve;
    }

    /**
     * Returns whether BOM configurations should be resolved eagerly during the configuration phase.
     * 
     * <p>This setting controls whether the dependency recommender plugin automatically resolves
     * BOM configurations during {@code afterEvaluate}, or whether external plugins should
     * handle resolution timing manually.</p>
     * 
     * @return {@code true} if BOMs should be resolved eagerly (default), 
     *         {@code false} if resolution should be handled manually
     * @since 12.7.0
     * @see #setEagerlyResolve(Boolean)
     * @see netflix.nebula.dependency.recommender.util.BomResolutionUtil#shouldEagerlyResolveBoms
     */
    public Boolean shouldEagerlyResolve() {
        return eagerlyResolve;
    }

    public void excludeConfigurations(String ... names) {
        excludedConfigurations.addAll(Arrays.asList(names));
    }

    public void excludeConfigurationPrefixes(String ... names) {
        excludedConfigurationPrefixes.addAll(Arrays.asList(names));
    }

    public Set<String> getExcludedConfigurations() {
        return excludedConfigurations;
    }

    public Set<String> getExcludedConfigurationPrefixes() {
        return excludedConfigurationPrefixes;
    }

    public Set<String> getReasons() {
        return reasons;
    }

    private static void ensureCoreBomSupportNotEnabled(String feature) {
        if(CORE_BOM_SUPPORT_ENABLED) {
            throw new GradleException("dependencyRecommender." + feature + " is not available with 'systemProp.nebula.features.coreBomSupport=true'");
        }
    }

    //ensure special handling for resolution. BOMs are added as platforms which prevent their resolution
    //we add them as regular dependencies so we can resolve them in detached configuration and read the content
    //this is useful for publishing when we copy content from applied BOMs to published BOM
    private static class CoreBomSupportProvider extends MavenBomRecommendationProvider {

        CoreBomSupportProvider(Project project, String configName, Set<String> reasons) {
            super(project, configName, reasons);
        }

        @Override
        protected Map<String, String> getBomRecommendations(Set<String> reasons) {
            // For core BOM support, we need to create detached configuration with regular dependencies
            // instead of using the shared service that works with the main configuration
            List<Dependency> rawPomDependencies = new ArrayList<>();
            for(org.gradle.api.artifacts.Dependency dependency: configuration.getDependencies()) {
                rawPomDependencies.add(project.getDependencies().create(dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion() + "@pom"));
            }
            Configuration detachedConfig = project.getConfigurations().detachedConfiguration(
                    rawPomDependencies.toArray(new org.gradle.api.artifacts.Dependency[0]));
            
            // Use the build service with cached data only (no resolution during dependency resolution)
            if (bomResolverService != null) {
                return bomResolverService.get().getCachedRecommendationsFromConfiguration(detachedConfig, reasons);
            } else {
                // Fallback - this shouldn't happen in normal usage but prevents errors
                throw new RuntimeException("BOM not cached and no build service context available for resolution");
            }
        }
        
        @Override
        Set<File> getFilesOnConfiguration() {
            List<Dependency> rawPomDependencies = new ArrayList<>();
            for(org.gradle.api.artifacts.Dependency dependency: configuration.getDependencies()) {
                rawPomDependencies.add(project.getDependencies().create(dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion() + "@pom"));
            }
            return project.getConfigurations().detachedConfiguration(
                    rawPomDependencies.toArray(new org.gradle.api.artifacts.Dependency[0])).resolve();
        }
    }
}
