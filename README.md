# Nebula Dependency Recommender

A Gradle plugin that allows you to leave off version numbers in your dependencies section and have versions recommended by several possible sources.  The most familiar recommendation provider that is supported is the Maven BOM (i.e. Maven dependency management metadata).  The plugin will control the versions of any dependencies that do not have a version specified.

## Usage

**NOTE:** This plugin has not yet been released!

Apply the nebula-dependency-recommender plugin:

```groovy
buildscript {
    repositories { jcenter() }

    dependencies {
        classpath 'com.netflix.nebula:nebula-recommender:2.0.+'
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

## Conflict resolution and transitive dependencies

* [Resolving differences between recommendation providers](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Resolving-Differences-Between-Recommendation-Providers)
* [Transitive dependencies](https://github.com/nebula-plugins/nebula-dependency-recommender/wiki/Transitive-Dependencies)

## Accessing recommended versions directly

The `dependencyRecommendations` container can be queried directly for a recommended version:

```groovy
dependencyRecommendations.getRecommendedVersion('commons-logging', 'commons-logging')
```

The `getRecommendedVersion` method returns `null` if no recommendation is found.
