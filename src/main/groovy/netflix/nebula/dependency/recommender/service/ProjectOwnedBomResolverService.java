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

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.io.File;
import java.util.*;

/**
 * Experimental BOM cache registered separately for each project when the
 * dependency-recommender Isolated Projects rollout flag is enabled.
 *
 * <p>Entries use {@link Configuration} identity rather than declared BOM coordinates
 * or configuration names. Matching coordinates can resolve to different POM contents
 * or effective Maven models because repositories, interpolation inputs, and resolution
 * rules belong to the requesting context. Even same-named configuration copies can
 * have different forced BOM versions and must not share their parsed policy.</p>
 *
 * <p>Each entry is a snapshot of the configuration's first resolution in the current
 * build. Recommendations and reasons are published together as an immutable result;
 * callers receive mutable copies of recommendation maps. Synchronization is per entry,
 * allowing independent configurations to parse without a shared parsing lock. This is
 * not persistent memoization across build invocations or support for changing an
 * already-resolved configuration.</p>
 *
 * <p>The snapshot includes dependency management inherited from Maven parent POMs.
 * Each parent's repository resolution and effective interpolation use the same owner
 * as the child BOM. Matching parent coordinates in another project do not create a
 * shared parsed-model entry. Gradle configuration-cache input tracking, rather than
 * this in-memory map, determines whether a later build can reuse configured policy.</p>
 *
 * <p>The provider and manual eager-resolution adapters select this service through
 * {@link netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport#registerBomResolver(Project)}.
 * The legacy {@link BomResolverService} remains the default. Opted-in affected builds
 * can select different versions or reasons when the legacy cache had returned another
 * configuration's policy. BOM parsing and eager-resolution timing remain unchanged.</p>
 */
public abstract class ProjectOwnedBomResolverService extends BomResolverService {
    private final Map<Configuration, CacheEntry> cache = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Publishes the recommendation/reason pair atomically after resolution completes. */
    private static final class CacheEntry {
        private volatile BomResult result;
    }

    /** Owns defensive snapshots so neither the parser nor consumers can mutate cached policy. */
    private static final class BomResult {
        private final Map<String, String> recommendations;
        private final Set<String> reasons;

        private BomResult(Map<String, String> recommendations, Set<String> reasons) {
            this.recommendations = Collections.unmodifiableMap(new HashMap<>(recommendations));
            this.reasons = Collections.unmodifiableSet(new HashSet<>(reasons));
        }

        private Map<String, String> copyTo(Set<String> targetReasons) {
            targetReasons.addAll(reasons);
            // Preserve the mutable return type without allowing callers to change cached policy.
            return new HashMap<>(recommendations);
        }
    }

    /**
     * Returns the snapshot previously cached for this exact configuration instance.
     *
     * <p>This method performs no resolution. It adds cached reasons to the supplied
     * set without clearing existing entries and returns a mutable defensive map copy.
     * Mutating either caller-owned collection does not change the cached snapshot.</p>
     *
     * @param configuration the configuration previously resolved by this service
     * @param reasons the caller's mutable set to which cached reasons are added
     * @return a mutable map from {@code group:artifact} to recommended version
     * @throws RuntimeException if this configuration has no cached result
     */
    @Override
    public Map<String, String> getCachedRecommendationsFromConfiguration(Configuration configuration, Set<String> reasons) {
        CacheEntry entry = cache.get(configuration);
        BomResult cached = entry == null ? null : entry.result;
        if (cached != null) {
            return cached.copyTo(reasons);
        }

        throw new RuntimeException("BOM not cached and no project context available for resolution");
    }

    /**
     * Resolves and caches the owning project's BOM configuration during configuration.
     *
     * <p>Normally called after project evaluation, or explicitly by a manual adapter
     * after late BOM customization. This method does not decide whether automatic eager
     * resolution is enabled. On failure, it retains the legacy empty-result fallback
     * only for the configuration that was located; other configurations remain unaffected.
     * A failed lookup cannot create a cache entry because no configuration was obtained.</p>
     *
     * @param project the project that owns the BOM configuration and its parsing inputs
     * @param configName the name of the configuration containing BOM dependencies
     */
    @Override
    public void eagerlyResolveAndCacheBoms(Project project, String configName) {
        Configuration configuration = null;
        try {
            configuration = project.getConfigurations().getByName(configName);
            Set<String> reasons = new HashSet<>();
            getRecommendationsFromConfiguration(configuration, project, reasons);
        } catch (Exception e) {
            // Preserve the existing empty-result fallback, scoped to the failed configuration.
            if (configuration != null) {
                cache.computeIfAbsent(configuration, ignored -> new CacheEntry()).result =
                        new BomResult(Collections.emptyMap(), Collections.emptySet());
            }
        }
    }

    /**
     * Resolves and parses BOM POMs once for the supplied configuration instance.
     *
     * <p>Use during configuration, when Gradle permits resolution of the owning
     * project's configuration. Maven model building uses the supplied project's
     * repositories and interpolation inputs through the same parser as the legacy
     * service. Concurrent requests for the same entry share one completed snapshot.</p>
     *
     * <p>Successful calls add snapshot reasons to the caller's set and return a mutable
     * defensive map copy. Failed parsing publishes no partial result; the eager adapter
     * separately supplies its documented empty-result fallback.</p>
     *
     * @param configuration the owning project's configuration containing BOM dependencies
     * @param project the owner that supplies repository and interpolation context
     * @param reasons the caller's mutable set to which resolution reasons are added
     * @return a mutable map from {@code group:artifact} to recommended version
     * @throws RuntimeException if resolution or Maven model building fails
     */
    @Override
    public Map<String, String> getRecommendationsFromConfiguration(Configuration configuration, Project project, Set<String> reasons) {
        CacheEntry entry = cache.computeIfAbsent(configuration, ignored -> new CacheEntry());

        // Check if already resolved
        BomResult cached = entry.result;
        if (cached != null) {
            return cached.copyTo(reasons);
        }

        // Synchronize resolution to prevent concurrent access
        synchronized (entry) {
            // Double-check pattern to avoid duplicate resolution
            cached = entry.result;
            if (cached != null) {
                return cached.copyTo(reasons);
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

                BomResult result = new BomResult(recommendations, currentReasons);
                entry.result = result;
                return result.copyTo(reasons);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

}
