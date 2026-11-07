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
 * Experimental project-owned BOM cache, registered only when Isolated Projects support is enabled.
 * Entries are immutable snapshots for each configuration in the current build.
 */
public abstract class ProjectOwnedBomResolverService extends BomResolverService {
    private final Map<Configuration, CacheEntry> cache = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class CacheEntry {
        private volatile BomResult result;
    }

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

    /** Retrieves an immutable snapshot as a mutable defensive copy. */
    @Override
    public Map<String, String> getCachedRecommendationsFromConfiguration(Configuration configuration, Set<String> reasons) {
        CacheEntry entry = cache.get(configuration);
        BomResult cached = entry == null ? null : entry.result;
        if (cached != null) {
            return cached.copyTo(reasons);
        }

        throw new RuntimeException("BOM not cached and no project context available for resolution");
    }

    /** Resolves eagerly, retaining the existing empty-result fallback for this configuration only. */
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

    /** Resolves once per configuration and publishes recommendations and reasons together. */
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
