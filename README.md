Nebula Dependency Recommender
=============================

A Gradle plugin that allows you to leave off version numbers in your dependencies section and have versions recommended by several possible sources.  The most familiar recommendation provider that is supported is the Maven BOM (i.e. Maven dependency management metadata).  The plugin will control the versions of any dependencies that do not have a version specified.

Usage
------

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

Dependency recommender configuration
------------------------------------

Dependency recommenders are the source of versions.  If more than one recommender defines a recommended version for a module, the first recommender specified will win.

```groovy
dependencyRecommendations {
        mavenBom(name: ‘netflix.platform.{stability}’, module: ‘netflix:platform:latest.{stability}@pom’)
        propertiesFile(name: ‘netflix.extlib’, uri: ‘http://somewhere/extlib.properties’)
}
```
