# Nebula Dependency Recommender

A Gradle plugin that allows you to leave off version numbers in your dependencies section and have versions recommended by several possible sources.  The most familiar recommendation provider that is supported is the Maven BOM (i.e. Maven dependency management metadata).  The plugin will control the versions of any dependencies that do not have a version specified.

## Usage

**NOTE:** This plugin has not yet been released!

Apply the nebula-dependency-recommender plugin:

```groovy
buildscript {
    repositories { jcenter() }

    dependencies {
        classpath 'com.netflix.nebula:nebula-dependency-recommender:2.0.+'
    }
}

apply plugin: 'nebula-dependency-recommender'
```

## Dependency recommender configuration

Dependency recommenders are the source of versions.  If more than one recommender defines a recommended version for a module, the first recommender specified will win.

```groovy
dependencyRecommendations {
   mavenBom module: 'netflix:platform:latest.release'
   propertiesFile uri: 'http://somewhere/extlib.properties'
}

dependencies {
   compile 'com.google.guava:guava' // no version, version is recommended
   compile 'commons-lang:commons-lang:2.6' // I know what I want, don't recommend
}
```

## Built-in recommendation providers

Several recommendation providers pack with the plugin.  The file-based providers all a shared basic configuration that is described separately.

* [File-based providers](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/File-Based-Providers)
	* [Maven BOM](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Maven-BOM-Provider)
	* [Properties file](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Properties-File-Provider)
	* [Nebula dependency lock](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Dependency-Lock-Provider)
* [Map](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Map-Provider)
* [Custom](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Custom-Provider)

## Version selection rules

The hierarchy of preference for versions is:

### 1. Forced dependencies

```groovy
configurations.all {
    resolutionStrategy {
        force 'commons-logging:commons-logging:1.2'
    }
}

dependencyRecommendations {
   map recommendations: ['commons-logging:commons-logging': '1.1']
}

dependencies {
   compile 'commons-logging:commons-logging' // version 1.2 is selected
}
```

### 2. Direct dependencies with a version qualifier

Direct dependencies with a version qualifier trump recommendations, even if the version qualifier refers to an older version.

```groovy
dependencyRecommendations {
   map recommendations: ['commons-logging:commons-logging': '1.2']
}

dependencies {
   compile 'commons-logging:commons-logging:1.0' // version 1.0 is selected
}
```

### 3.  Dependency recommendations

This is the basic case described elsewhere in the documentation;

```groovy
dependencyRecommendations {
   map recommendations: ['commons-logging:commons-logging': '1.0']
}

dependencies {
   compile 'commons-logging:commons-logging' // version 1.0 is selected
}
```

### 4.  Transitive dependencies

Whenever a recommendation provider can provide a version recommendation, that recommendation is applied above versions of the module that are provided by transitive dependencies.  

Consider the following example with dependencies on `commons-configuration` and `commons-logging`.  `commons-configuration:1.6` depends on `commons-logging:1.1.1`.  Even though `commons-configuration` indicates that it prefers version `1.1.1`, `1.0` is selected because of the recommendation provider.

```groovy
dependencyRecommendations {
   map recommendations: ['commons-logging:commons-logging': '1.0']
}

dependencies {
   compile 'commons-configuration:commons-configuration:1.6'
}
```

However, if no recommendation can be found for a dependency that has no version, but a version is provided by a transitive the version provided by the transitive is applied.  In this scenario, if several transitives provide versions for the module, normal Gradle conflict resolution applies.

```groovy
dependencyRecommendations {
   map recommendations: ['some:other-module': '1.1']
}

dependencies {
   compile 'commons-configuration:commons-configuration:1.6'
   compile 'commons-logging:commons-logging' // version 1.1.1 is selected
}
```

## Conflict resolution and transitive dependencies

* [Resolving differences between recommendation providers](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Resolving-Differences-Between-Recommendation-Providers)

## Accessing recommended versions directly

The `dependencyRecommendations` container can be queried directly for a recommended version:

```groovy
dependencyRecommendations.getRecommendedVersion('commons-logging', 'commons-logging')
```

The `getRecommendedVersion` method returns `null` if no recommendation is found.
