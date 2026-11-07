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
import netflix.nebula.dependency.recommender.ConfigureUtil;
import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin;
import netflix.nebula.dependency.recommender.RecommendationStrategies;
import netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.ConfigureByMapAction;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin.CORE_BOM_SUPPORT_ENABLED;

public class RecommendationProviderContainer {

    private Project project;
    private NamedDomainObjectList<RecommendationProvider> providers;
    private final Property<RecommendationStrategies> strategy;
    private MavenBomRecommendationProvider mavenBomProvider;
    private final Property<Boolean> strictMode;
    private final SetProperty<String> excludedConfigurations;
    private final SetProperty<String> excludedConfigurationPrefixes;
    private Set<String> reasons = new HashSet<>(); // Keep as regular Set - it's an output/result collection
    private final Property<Boolean> eagerlyResolve;
    private final String bomConfigurationName;
    private final boolean inheritedLayer;
    private final Map<String, RecommendationProviderContainer> inheritedPolicies = new LinkedHashMap<>();
    
    // Make strategies available without import
    public static final RecommendationStrategies OverrideTransitives = RecommendationStrategies.OverrideTransitives;
    public static final RecommendationStrategies ConflictResolved = RecommendationStrategies.ConflictResolved;

    public RecommendationProviderContainer(Project project) {
        this(project, DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM, null, false);
    }

    private RecommendationProviderContainer(Project project, String bomConfigurationName,
                                            Property<Boolean> eagerlyResolve, boolean inheritedLayer) {
        createList(project);
        this.project = project;
        this.bomConfigurationName = bomConfigurationName;
        this.inheritedLayer = inheritedLayer;

        // Initialize properties using ObjectFactory for proper Gradle integration
        ObjectFactory objects = project.getObjects();
        this.strategy = objects.property(RecommendationStrategies.class)
                .convention(RecommendationStrategies.ConflictResolved);
        this.strictMode = objects.property(Boolean.class)
                .convention(false);
        this.excludedConfigurations = objects.setProperty(String.class)
                .convention(new HashSet<>());
        this.excludedConfigurationPrefixes = objects.setProperty(String.class)
                .convention(new HashSet<>());
        this.eagerlyResolve = eagerlyResolve == null ? objects.property(Boolean.class).convention(true) : eagerlyResolve;

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
            mavenBomRecommendationProvider = new CoreBomSupportProvider(this.project, bomConfigurationName, this.reasons);
        } else {
            mavenBomRecommendationProvider = new MavenBomRecommendationProvider(this.project, bomConfigurationName, this.reasons);
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
            project.getDependencies().add(bomConfigurationName, dependencyNotation);
        } else {
            Dependency platform;
            if (isEnforced != null && Boolean.valueOf(isEnforced.toString())) {
                platform = project.getDependencies().enforcedPlatform(dependencyNotation);
            } else
                platform = project.getDependencies().platform(dependencyNotation);
            project.getDependencies().add(bomConfigurationName, platform);
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

    /**
     * Supplies an inherited policy layer using sources configured in the consumer project.
     *
     * <p>This experimental adapter requires
     * {@code nebula.dependency-recommender.isolatedProjectsSupport=true}. A shared local
     * convention should configure equivalent sources for each ancestor, nearest parent
     * first and root last. The path identifies the policy scope; it does not look up or
     * configure that project. Empty layers explicitly represent ancestors without policy.
     * Repeated calls for an existing layer append providers using the normal LIFO order.</p>
     *
     * <p>Each layer owns its provider instances, reasons, and BOM configuration in this
     * consumer. Source declarations retain their usual lazy query behavior. Automatic
     * eager resolution is controlled by the shared top-level {@link #getEagerlyResolve()}
     * property; public manual adapters resolve all declared local layers. Strategy,
     * strictness, and configuration exclusions remain consumer settings and are not
     * inherited from these source containers.</p>
     *
     * <p>Declare equivalent repository and interpolation inputs in the local convention.
     * Arbitrary ancestor build-script callbacks are not imported automatically. Core-BOM
     * distribution is outside this adapter's scope.</p>
     *
     * @param ancestorPath the next ancestor's absolute Gradle path, or an existing layer
     * @param action configures this consumer's local sources for that ancestor
     * @return the local source container, which may be customized before resolution
     * @throws GradleException if the flag is disabled, core mode is enabled, or the path is out of order
     */
    public RecommendationProviderContainer inheritedPolicy(String ancestorPath, Action<? super RecommendationProviderContainer> action) {
        ensureCoreBomSupportNotEnabled("inheritedPolicy");
        if (!IsolatedProjectsSupport.isEnabled(project)) {
            throw new GradleException("inheritedPolicy requires nebula.dependency-recommender.isolatedProjectsSupport=true");
        }
        if (inheritedLayer) {
            throw new GradleException("Declare inheritedPolicy layers on the consumer's dependencyRecommendations container");
        }
        RecommendationProviderContainer layer = inheritedPolicies.get(ancestorPath);
        if (layer == null) {
            String expected = nextAncestorPath();
            if (expected == null || !expected.equals(ancestorPath)) {
                throw new GradleException("Expected inheritedPolicy '" + expected + "' next for " + project.getPath()
                        + "; declare layers nearest parent first, including empty intermediate layers");
            }
            String configurationName = DependencyRecommendationsPlugin.NEBULA_RECOMMENDER_BOM + "Inherited" + inheritedPolicies.size();
            excludeConfigurations(configurationName);
            project.getConfigurations().create(configurationName, configuration -> {
                configuration.setCanBeConsumed(false);
                configuration.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_API));
                configuration.getAttributes().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
                configuration.getAttributes().attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.REGULAR_PLATFORM));
            });
            layer = new RecommendationProviderContainer(project, configurationName, eagerlyResolve, true);
            inheritedPolicies.put(ancestorPath, layer);
        }
        action.execute(layer);
        return layer;
    }

    /**
     * Groovy DSL adapter for {@link #inheritedPolicy(String, Action)}.
     * The closure configures a consumer-owned container and must not capture foreign projects.
     *
     * @param ancestorPath the ancestor scope supplied by the local convention
     * @param closure configures equivalent sources for that scope
     * @return the consumer-owned source container
     */
    public RecommendationProviderContainer inheritedPolicy(String ancestorPath, Closure closure) {
        return inheritedPolicy(ancestorPath, ConfigureUtil.<RecommendationProviderContainer>configureUsing(closure));
    }

    /**
     * Returns the local BOM configuration name used by eager-resolution adapters.
     *
     * @return the main or inherited layer's consumer-owned configuration name
     */
    public String getBomConfigurationName() {
        return bomConfigurationName;
    }

    /**
     * Returns declared local ancestor layers in nearest-parent-to-root order.
     *
     * @return an unmodifiable view of the consumer-owned layer containers
     */
    public Collection<RecommendationProviderContainer> getInheritedPolicies() {
        return Collections.unmodifiableCollection(inheritedPolicies.values());
    }

    /**
     * Queries inherited local layers after the consumer's own providers miss.
     * Each layer retains provider LIFO precedence and lazy initialization.
     *
     * @param group dependency group
     * @param name dependency name
     * @return the nearest layer's recommendation, or null after a complete chain misses
     * @throws GradleException if lookup needs an ancestor not supplied by the convention
     */
    public String getInheritedRecommendedVersion(String group, String name) {
        for (RecommendationProviderContainer layer : inheritedPolicies.values()) {
            String version = layer.getRecommendedVersion(group, name);
            if (version != null) return version;
        }
        requireCompleteInheritedPolicy();
        return null;
    }

    /**
     * Selects the nearest nonempty inherited reason set independently of version selection.
     * This preserves the legacy reason lookup, including reasons from a nearer layer whose
     * providers did not supply the chosen version. It does not poll additional providers.
     *
     * @return the first nonempty layer's reasons, or an empty set after a complete chain
     * @throws GradleException if reason lookup needs an undeclared ancestor layer
     */
    public Set<String> getInheritedReasons() {
        for (RecommendationProviderContainer layer : inheritedPolicies.values()) {
            if (!layer.getReasons().isEmpty()) return layer.getReasons();
        }
        requireCompleteInheritedPolicy();
        return Collections.emptySet();
    }

    private String nextAncestorPath() {
        String path = project.getPath();
        for (String ancestor : inheritedPolicies.keySet()) path = ancestor;
        if (path.equals(":")) return null;
        int separator = path.lastIndexOf(':');
        return separator == 0 ? ":" : path.substring(0, separator);
    }

    private void requireCompleteInheritedPolicy() {
        String missing = nextAncestorPath();
        if (missing != null) {
            throw new GradleException("Inherited recommendation lookup in " + project.getPath() + " requires local inheritedPolicy('"
                    + missing + "') sources. Configure equivalent parent/root layers through a shared local convention; "
                    + "implicit access to another project's recommendations is unavailable with isolatedProjectsSupport=true.");
        }
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

    public Property<RecommendationStrategies> getStrategy() {
        return strategy;
    }

    /**
     * @deprecated Use {@link #getStrategy()}.set() instead
     */
    @Deprecated
    public void setStrategy(RecommendationStrategies value) {
        strategy.set(value);
    }

    public Property<Boolean> getStrictMode() {
        return strictMode;
    }

    /**
     * @deprecated Use {@link #getStrictMode()}.set() instead
     */
    @Deprecated
    public void setStrictMode(Boolean value) {
        strictMode.set(value);
    }

    /**
     * Returns the Property controlling whether BOM configurations should be resolved eagerly.
     *
     * <p>When set to {@code true} (default), BOM configurations will be resolved automatically
     * during the {@code afterEvaluate} phase to prevent configuration resolution lock conflicts
     * in parallel builds with Gradle 9+.</p>
     *
     * <p>When set to {@code false}, external plugins can take control of BOM resolution timing
     * by calling {@link netflix.nebula.dependency.recommender.util.BomResolutionUtil#eagerlyResolveBoms}
     * manually after modifying BOM configurations.</p>
     *
     * @return Property containing the eagerly resolve flag
     * @since 12.7.0
     * @see netflix.nebula.dependency.recommender.util.BomResolutionUtil#eagerlyResolveBoms
     */
    public Property<Boolean> getEagerlyResolve() {
        return eagerlyResolve;
    }

    /**
     * Convenience method to check if BOMs should be resolved eagerly.
     *
     * @return {@code true} if BOMs should be resolved eagerly (default),
     *         {@code false} if resolution should be handled manually
     * @since 12.7.0
     */
    public Boolean shouldEagerlyResolve() {
        return eagerlyResolve.get();
    }

    /**
     * Convenience method to set whether BOMs should be resolved eagerly.
     *
     * @param value {@code true} to enable automatic eager resolution,
     *              {@code false} to disable it and allow manual control
     * @since 12.7.0
     * @deprecated Use {@link #getEagerlyResolve()}.set() instead
     */
    @Deprecated
    public void setEagerlyResolve(Boolean value) {
        eagerlyResolve.set(value);
    }

    public void excludeConfigurations(String ... names) {
        excludedConfigurations.addAll(Arrays.asList(names));
    }

    public void excludeConfigurationPrefixes(String ... names) {
        excludedConfigurationPrefixes.addAll(Arrays.asList(names));
    }

    public SetProperty<String> getExcludedConfigurations() {
        return excludedConfigurations;
    }

    public SetProperty<String> getExcludedConfigurationPrefixes() {
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
            return bomResolverService.get().getCachedRecommendationsFromConfiguration(detachedConfig, reasons);
        }
    }
}
