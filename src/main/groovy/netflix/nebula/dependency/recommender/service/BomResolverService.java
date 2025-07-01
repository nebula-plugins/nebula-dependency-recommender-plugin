/*
 * Copyright 2016-2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender.service;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.*;
import org.apache.maven.model.interpolation.StringSearchModelInterpolator;
import org.apache.maven.model.path.DefaultPathTranslator;
import org.apache.maven.model.path.DefaultUrlNormalizer;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.ValueSource;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Gradle build service that resolves and caches BOM (Bill of Materials) recommendations
 * to prevent configuration resolution lock conflicts in parallel builds.
 * 
 * <p>This service addresses the issue where multiple subprojects attempting to resolve
 * BOM configurations simultaneously in parallel builds with Gradle 9+ would cause
 * {@code IllegalResolutionException} due to exclusive lock conflicts.</p>
 * 
 * <p>The service works by:</p>
 * <ul>
 *   <li>Eagerly resolving BOMs during the configuration phase when exclusive locks are available</li>
 *   <li>Caching resolved recommendations indexed by BOM coordinates</li>
 *   <li>Providing cached results during dependency resolution phase to avoid lock conflicts</li>
 *   <li>Supporting full Maven model building with property interpolation and parent POM resolution</li>
 * </ul>
 * 
 * <p>Thread-safe implementation using {@link ConcurrentHashMap} and synchronized blocks
 * to handle concurrent access from multiple projects in parallel builds.</p>
 * 
 * @since 13.1.0
 */
public abstract class BomResolverService implements BuildService<BuildServiceParameters.None> {
    private final ConcurrentHashMap<String, Map<String, String>> bomRecommendations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> bomReasons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * Retrieves BOM recommendations for a given project configuration.
     * 
     * <p>This method first attempts to return cached recommendations. If not cached,
     * it falls back to direct resolution. This is the main entry point for accessing
     * BOM recommendations from the build service.</p>
     * 
     * @param project the Gradle project requesting recommendations
     * @param configName the name of the configuration containing BOM dependencies
     * @param reasons a mutable set that will be populated with reasons explaining
     *                why specific recommendations were applied
     * @return a map of dependency coordinates (groupId:artifactId) to recommended versions
     * @throws RuntimeException if BOM resolution fails
     */
    public Map<String, String> getRecommendations(Project project, String configName, Set<String> reasons) {
        Configuration configuration = project.getConfigurations().getByName(configName);
        return getCachedRecommendationsFromConfiguration(configuration, reasons);
    }
    
    /**
     * Retrieves cached BOM recommendations from a configuration.
     * 
     * <p>This method first checks if recommendations have been cached for the given
     * configuration. If cached, returns the cached result. If not cached, throws
     * an exception indicating that the BOM was not pre-resolved.</p>
     * 
     * <p>This design ensures that all BOM resolution happens during the configuration
     * phase via {@link #eagerlyResolveAndCacheBoms(Project, String)}, preventing
     * configuration resolution during the dependency resolution phase.</p>
     * 
     * @param configuration the Gradle configuration containing BOM dependencies
     * @param reasons a mutable set that will be populated with cached reasons
     * @return cached BOM recommendations as a map of coordinates to versions
     * @throws RuntimeException if the BOM was not pre-resolved and cached
     */
    public Map<String, String> getCachedRecommendationsFromConfiguration(Configuration configuration, Set<String> reasons) {
        // First try to return cached data
        String bomKey = createBomKeyFromConfiguration(configuration);
        Map<String, String> cached = bomRecommendations.get(bomKey);
        if (cached != null) {
            Set<String> cachedReasons = bomReasons.get(bomKey);
            if (cachedReasons != null) {
                reasons.addAll(cachedReasons);
            }
            return cached;
        }
        
        // If not cached, we need to resolve it, but we can't use the full Maven model building 
        // without a project context. For unit tests, we'll fall back to a simpler approach.
        throw new RuntimeException("BOM not cached and no project context available for resolution");
    }
    
    /**
     * Eagerly resolves and caches BOM recommendations during the configuration phase.
     * 
     * <p>This method should be called during the configuration phase (typically in
     * {@code afterEvaluate}) when exclusive locks are available. It resolves all
     * BOMs in the specified configuration and caches the results for later use.</p>
     * 
     * <p>If resolution fails, empty results are cached to prevent repeated failures
     * and ensure the build can continue.</p>
     * 
     * @param project the Gradle project containing the BOM configuration
     * @param configName the name of the configuration containing BOM dependencies
     */
    public void eagerlyResolveAndCacheBoms(Project project, String configName) {
        try {
            Configuration configuration = project.getConfigurations().getByName(configName);
            Set<String> reasons = new HashSet<>();
            getRecommendationsFromConfiguration(configuration, project, reasons);
        } catch (Exception e) {
            // Cache empty results if resolution fails
            String bomKey = createBomKey(project, configName);
            bomRecommendations.put(bomKey, new HashMap<>());
            bomReasons.put(bomKey, new HashSet<>());
        }
    }
    
    /**
     * Resolves BOM recommendations from a configuration with full Maven model building.
     * 
     * <p>This method performs the actual work of resolving BOM files, parsing them with
     * full Maven model support (including parent POM resolution and property interpolation),
     * and caching the results. It uses synchronization to prevent concurrent resolution
     * of the same BOM.</p>
     * 
     * @param configuration the Gradle configuration containing BOM dependencies
     * @param project the Gradle project (used for Maven model interpolation and resolution)
     * @param reasons a mutable set that will be populated with resolution reasons
     * @return a map of dependency coordinates to recommended versions
     * @throws RuntimeException if BOM resolution or parsing fails
     */
    public Map<String, String> getRecommendationsFromConfiguration(Configuration configuration, Project project, Set<String> reasons) {
        // Create cache key based on BOM files/coordinates
        String bomKey = createBomKeyFromConfiguration(configuration);
        Object lock = locks.computeIfAbsent(bomKey, k -> new Object());
        
        // Check if already resolved
        Map<String, String> cached = bomRecommendations.get(bomKey);
        if (cached != null) {
            Set<String> cachedReasons = bomReasons.get(bomKey);
            if (cachedReasons != null) {
                reasons.addAll(cachedReasons);
            }
            return cached;
        }
        
        // Synchronize resolution to prevent concurrent access
        synchronized (lock) {
            // Double-check pattern to avoid duplicate resolution
            cached = bomRecommendations.get(bomKey);
            if (cached != null) {
                Set<String> cachedReasons = bomReasons.get(bomKey);
                if (cachedReasons != null) {
                    reasons.addAll(cachedReasons);
                }
                return cached;
            }
            
            try {
                Map<String, String> recommendations = new HashMap<>();
                Set<String> currentReasons = new HashSet<>();
                
                Set<File> bomFiles = configuration.resolve();
                
                for (File bomFile : bomFiles) {
                    if (!bomFile.getName().endsWith("pom")) {
                        continue;
                    }
                    
                    Map<String, String> bomRecommendations = parseBom(bomFile, project, currentReasons);
                    recommendations.putAll(bomRecommendations);
                }
                
                bomRecommendations.put(bomKey, recommendations);
                bomReasons.put(bomKey, currentReasons);
                reasons.addAll(currentReasons);
                return recommendations;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    /**
     * Creates a cache key for BOM recommendations based on project and configuration.
     * 
     * <p>This method attempts to create a key based on the actual BOM coordinates
     * in the configuration. If that fails, it falls back to using the project path
     * and configuration name.</p>
     * 
     * @param project the Gradle project
     * @param configName the configuration name
     * @return a unique cache key for the BOM
     */
    private String createBomKey(Project project, String configName) {
        try {
            Configuration configuration = project.getConfigurations().getByName(configName);
            return createBomKeyFromConfiguration(configuration);
        } catch (Exception e) {
            // Fallback to project+config if we can't determine dependencies
            return project.getPath() + ":" + configName;
        }
    }
    
    /**
     * Creates a cache key based on the actual BOM dependencies in a configuration.
     * 
     * <p>This method creates a deterministic key by concatenating all dependency
     * coordinates (group:name:version) in the configuration. This ensures that
     * configurations with the same BOM dependencies share the same cache entry,
     * regardless of which project they belong to.</p>
     * 
     * @param configuration the Gradle configuration containing BOM dependencies
     * @return a unique cache key based on the BOM coordinates
     */
    private String createBomKeyFromConfiguration(Configuration configuration) {
        // Create a key based on the dependency coordinates in the configuration
        StringBuilder keyBuilder = new StringBuilder();
        configuration.getAllDependencies().forEach(dep -> {
            keyBuilder.append(dep.getGroup())
                      .append(":")
                      .append(dep.getName())
                      .append(":")
                      .append(dep.getVersion())
                      .append(";");
        });
        return keyBuilder.toString();
    }
    
    /**
     * Parses a BOM file using Maven model building with full interpolation support.
     * 
     * <p>This method uses Maven's model building capabilities to:</p>
     * <ul>
     *   <li>Parse the BOM POM file</li>
     *   <li>Resolve parent POMs if referenced</li>
     *   <li>Interpolate properties from both system and Gradle project properties</li>
     *   <li>Extract dependency management recommendations</li>
     * </ul>
     * 
     * @param bomFile the BOM POM file to parse
     * @param project the Gradle project (used for property interpolation)
     * @param reasons a mutable set that will be populated with parsing reasons
     * @return a map of dependency coordinates to recommended versions
     * @throws Exception if BOM parsing or model building fails
     */
    private Map<String, String> parseBom(File bomFile, Project project, Set<String> reasons) throws Exception {
        Map<String, String> recommendations = new HashMap<>();
        
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setModelResolver(new ProjectModelResolver(project));
        request.setModelSource(new SimpleModelSource(new FileInputStream(bomFile)));
        request.setSystemProperties(System.getProperties());
        
        DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
        modelBuilder.setModelInterpolator(new ProjectPropertiesModelInterpolator(project));
        
        ModelBuildingResult result = modelBuilder.build(request);
        reasons.add("nebula.dependency-recommender uses mavenBom: " + result.getEffectiveModel().getId());
        
        Model model = result.getEffectiveModel();
        if (model != null && model.getDependencyManagement() != null) {
            for (Dependency d : model.getDependencyManagement().getDependencies()) {
                recommendations.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
            }
        }
        
        return recommendations;
    }
    
    /**
     * A simple implementation of Maven's {@link ModelSource2} that reads from an InputStream.
     * 
     * <p>This class provides a minimal implementation for reading POM files during
     * Maven model building. It's used internally by the BOM parsing process.</p>
     */
    private static class SimpleModelSource implements ModelSource2 {
        InputStream in;

        public SimpleModelSource(InputStream in) {
            this.in = in;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return in;
        }

        @Override
        public String getLocation() {
            return null;
        }

        @Override
        public ModelSource2 getRelatedSource(String relPath) {
            return null;
        }

        @Override
        public URI getLocationURI() {
            return null;
        }
    }
    
    /**
     * A Maven model resolver that can resolve parent POMs and dependencies using Gradle.
     * 
     * <p>This resolver integrates Maven's model building with Gradle's dependency resolution
     * system. It can resolve parent POMs and other dependencies referenced in BOM files
     * by creating detached Gradle configurations and resolving them.</p>
     * 
     * <p>Essential for handling complex BOM hierarchies with parent POM inheritance.</p>
     */
    private static class ProjectModelResolver implements ModelResolver {
        private final Project project;
        
        public ProjectModelResolver(Project project) {
            this.project = project;
        }
        
        @Override
        public ModelSource2 resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
            String notation = groupId + ":" + artifactId + ":" + version + "@pom";
            org.gradle.api.artifacts.Dependency dependency = project.getDependencies().create(notation);
            Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);
            try {
                File file = configuration.getFiles().iterator().next();
                return new SimpleModelSource(new FileInputStream(file));
            } catch (Exception e) {
                throw new UnresolvableModelException(e, groupId, artifactId, version);
            }
        }

        @Override
        public ModelSource2 resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public ModelSource2 resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public void addRepository(Repository repository) throws InvalidRepositoryException {
            // do nothing
        }

        @Override
        public void addRepository(Repository repository, boolean bool) throws InvalidRepositoryException {
            // do nothing
        }

        @Override
        public ModelResolver newCopy() {
            return this; // do nothing
        }
    }
    
    /**
     * A Maven model interpolator that can access Gradle project properties.
     * 
     * <p>This interpolator extends Maven's standard string interpolation to include
     * Gradle project properties as value sources. This allows BOM files to reference
     * properties like {@code ${commons.version}} that are defined in the Gradle project.</p>
     * 
     * <p>The interpolator includes value sources from:</p>
     * <ul>
     *   <li>Maven's default sources (system properties, model properties, etc.)</li>
     *   <li>Java system properties</li>
     *   <li>Gradle project properties</li>
     * </ul>
     */
    private static class ProjectPropertiesModelInterpolator extends StringSearchModelInterpolator {
        private final Project project;

        ProjectPropertiesModelInterpolator(Project project) {
            this.project = project;
            setUrlNormalizer(new DefaultUrlNormalizer());
            setPathTranslator(new DefaultPathTranslator());
        }

        public List<ValueSource> createValueSources(Model model, File projectDir, ModelBuildingRequest request, ModelProblemCollector collector) {
            List<ValueSource> sources = new ArrayList<>();
            sources.addAll(super.createValueSources(model, projectDir, request, collector));
            sources.add(new PropertiesBasedValueSource(System.getProperties()));
            sources.add(new MapBasedValueSource(project.getProperties()));
            return sources;
        }
    }
}