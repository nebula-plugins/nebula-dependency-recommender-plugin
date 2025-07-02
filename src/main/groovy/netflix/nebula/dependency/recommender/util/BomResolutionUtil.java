/*
 * Copyright 2025 Netflix, Inc.
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
package netflix.nebula.dependency.recommender.util;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import netflix.nebula.dependency.recommender.service.BomResolverService;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;

/**
 * Utility class for handling BOM (Bill of Materials) resolution operations.
 * 
 * <p>This utility provides methods for eagerly resolving BOM configurations during the 
 * configuration phase to prevent configuration resolution lock conflicts in parallel builds 
 * with Gradle 9+.</p>
 * 
 * <p>The eager resolution approach is particularly important for:</p>
 * <ul>
 *   <li>Parallel builds where configuration resolution locks can cause deadlocks</li>
 *   <li>Build services that need cached BOM data during dependency resolution</li>
 *   <li>External plugins that want to control when BOM resolution occurs</li>
 * </ul>
 * 
 * @since 12.7.0
 */
public final class BomResolutionUtil {
    private static final Logger logger = Logging.getLogger(BomResolutionUtil.class);

    private BomResolutionUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Eagerly resolves BOM configurations for the given project during the configuration phase.
     * 
     * <p>This method should be called during {@code afterEvaluate} when exclusive locks are
     * available. It instructs the {@link BomResolverService} to resolve all BOM
     * configurations and cache the results for later use during dependency resolution.</p>
     * 
     * <p>The eager resolution prevents the need to resolve configurations during the
     * dependency resolution phase, which would cause {@code IllegalResolutionException}
     * in parallel builds with Gradle 9+.</p>
     * 
     * <p><strong>Usage by External Plugins:</strong></p>
     * <pre>{@code
     * // Disable automatic eager resolution
     * container.setEagerlyResolve(false);
     * 
     * // Modify BOMs as needed
     * container.mavenBom(module: 'com.example:updated-bom:1.0.0');
     * 
     * // Manually trigger eager resolution
     * BomResolutionUtil.eagerlyResolveBoms(project, container, "nebulaRecommenderBom");
     * }</pre>
     * 
     * @param project the Gradle project whose BOM configurations should be resolved
     * @param container the recommendation provider container to check for additional BOM providers
     * @param bomConfigurationName the name of the BOM configuration to resolve
     * @throws IllegalArgumentException if any parameter is null
     * @since 12.7.0
     */
    public static void eagerlyResolveBoms(Project project, RecommendationProviderContainer container, String bomConfigurationName) {
        if (project == null) {
            throw new IllegalArgumentException("Project cannot be null");
        }
        if (container == null) {
            throw new IllegalArgumentException("RecommendationProviderContainer cannot be null");
        }
        if (bomConfigurationName == null || bomConfigurationName.trim().isEmpty()) {
            throw new IllegalArgumentException("BOM configuration name cannot be null or empty");
        }

        try {
            // Get the build service
            Provider<BomResolverService> bomResolverService =
                project.getGradle().getSharedServices().registerIfAbsent(
                    "bomResolver", BomResolverService.class, spec -> {}
                );
            
            // Resolve BOMs from the specified configuration
            bomResolverService.get().eagerlyResolveAndCacheBoms(project, bomConfigurationName);
            
            // Also trigger resolution for maven BOM provider if it exists
            // This handles mavenBom providers configured in the extension
            netflix.nebula.dependency.recommender.provider.MavenBomRecommendationProvider mavenBomProvider = container.getMavenBomProvider();
            if (mavenBomProvider != null) {
                try {
                    mavenBomProvider.getVersion("dummy", "dummy");  // Trigger lazy initialization
                } catch (Exception e) {
                    // Expected - just needed to trigger BOM resolution
                    logger.debug("Triggered BOM resolution for maven BOM provider", e);
                }
            }
            
            logger.debug("Successfully resolved BOMs for project {} using configuration {}", 
                project.getPath(), bomConfigurationName);
                
        } catch (Exception e) {
            logger.warn("Failed to eagerly resolve BOMs for project {} using configuration {}: {}", 
                project.getPath(), bomConfigurationName, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("BOM resolution failure details", e);
            }
        }
    }

    /**
     * Checks if the given project should use eager BOM resolution.
     * 
     * <p>This method evaluates both the container's eager resolution setting and
     * any project-specific overrides to determine if BOMs should be resolved eagerly.</p>
     * 
     * @param project the Gradle project to check
     * @param container the recommendation provider container with eager resolution settings
     * @return true if BOMs should be resolved eagerly, false otherwise
     * @throws IllegalArgumentException if any parameter is null
     * @since 12.7.0
     */
    public static boolean shouldEagerlyResolveBoms(Project project, RecommendationProviderContainer container) {
        if (project == null) {
            throw new IllegalArgumentException("Project cannot be null");
        }
        if (container == null) {
            throw new IllegalArgumentException("RecommendationProviderContainer cannot be null");
        }

        // Check container setting first
        if (!container.shouldEagerlyResolve()) {
            logger.debug("Eager BOM resolution disabled for project {} via container setting", project.getPath());
            return false;
        }

        // Could add additional project-specific checks here in the future
        // For example, checking project properties or environment variables
        
        logger.debug("Eager BOM resolution enabled for project {}", project.getPath());
        return true;
    }
}