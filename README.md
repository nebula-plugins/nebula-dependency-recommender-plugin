# Nebula Dependency Recommender

[![Build Status](https://travis-ci.org/nebula-plugins/nebula-dependency-recommender-plugin.svg?branch=master)](https://travis-ci.org/nebula-plugins/projects/nebula-dependency-recommender-plugin)
[![Coverage Status](https://coveralls.io/repos/nebula-plugins/nebula-dependency-recommender-plugin/badge.svg?branch=masterservice=github)](https://coveralls.io/github/nebula-plugins/projects/nebula-dependency-recommender-plugin?branch=master)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/nebula-plugins/nebula-dependency-recommender-plugin?utm_source=badgeutm_medium=badgeutm_campaign=pr-badge)
[![Apache 2.0](https://img.shields.io/github/license/nebula-plugins/nebula-dependency-recommender-plugin.svg)](http://www.apache.org/licenses/LICENSE-2.0)

A Gradle plugin that allows you to leave off version numbers in your dependencies section and have versions recommended by several possible sources.  The most familiar recommendation provider that is supported is the Maven BOM (i.e. Maven dependency management metadata).  The plugin will control the versions of any dependencies that do not have a version specified.

Table of Contents
=================

  * [Nebula Dependency Recommender](#nebula-dependency-recommender)
    * [Usage](#usage)
    * [Dependency recommender configuration](#dependency-recommender-configuration)
    * [Built-in recommendation providers](#built-in-recommendation-providers)
    * [Producing a Maven BOM for use as a dependency recommendation source](#producing-a-maven-bom-for-use-as-a-dependency-recommendation-source)
    * [Version selection rules](#version-selection-rules)
      * [1. Forced dependencies](#1-forced-dependencies)
      * [2. Direct dependencies with a version qualifier](#2-direct-dependencies-with-a-version-qualifier)
      * [3.  Dependency recommendations](#3--dependency-recommendations)
      * [4.  Transitive dependencies](#4--transitive-dependencies)
    * [Conflict resolution and transitive dependencies](#conflict-resolution-and-transitive-dependencies)
    * [Accessing recommended versions directly](#accessing-recommended-versions-directly)

## Usage

**NOTE:** This plugin has not yet been released!

Apply the nebula-dependency-recommender plugin:

```groovy
buildscript {
    repositories { jcenter() }

    dependencies {
        classpath 'com.netflix.nebula:nebula-dependency-recommender:2.2.+'
    }
}

apply plugin: 'nebula-dependency-recommender'
```

## Dependency recommender configuration

Dependency recommenders are the source of versions.  If more than one recommender defines a recommended version for a module, the first recommender specified will win.

```groovy
dependencyRecommendations {
   mavenBom module: 'netflix:platform:latest.release'
   propertiesFile uri: 'http://somewhere/extlib.properties', name: 'myprops'
}

dependencies {
   compile 'com.google.guava:guava' // no version, version is recommended
   compile 'commons-lang:commons-lang:2.6' // I know what I want, don't recommend
   compile project.recommend('commmons-logging:commons-logging', 'myprops') // source the recommendation from the provider named myprops'
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

## Producing a Maven BOM for use as a dependency recommendation source

Suppose you want to produce a BOM that contains a recommended version for commons-configuration.

```groovy
buildscript {
    repositories { jcenter() }
    dependencies { classpath 'com.netflix.nebula:nebula-dependency-recommender:2.2.+' }
}

apply plugin: 'maven-publish'
apply plugin: 'nebula-dependency-recommender'

group = 'netflix'

configurations { compile }
repositories { jcenter() }

dependencies {
   compile 'commons-configuration:commons-configuration:1.6'
}

publishing {
	publications {
	    parent(MavenPublication) {
	        // the transitive closure of this configuration will be flattened and added to the dependency management section
	        dependencyManagement.fromConfigurations { configurations.compile }
	        
	        // alternative syntax when you want to explicitly add a dependency with no transitives
	        dependencyManagement.withDependencies { 'manual:dep:1' }
	
		// the bom will be generated with dependency coordinates of netflix:module-parent:1
	        artifactId = 'module-parent'
	        version = 1
	        
	        // further customization of the POM is allowed if desired
	        pom.withXml { asNode().appendNode('description', 'A demonstration of maven POM customization') }
	    }
	}
	repositories {
	    maven { 
	       url "$buildDir/repo" // point this to your destination repository
	    }
	}
}
```

The resultant BOM would look like this:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>netflix</groupId>
  <artifactId>module-parent</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>commons-digester</groupId>
        <artifactId>commons-digester</artifactId>
        <version>1.8</version>
      </dependency>
      <dependency>
        <groupId>commons-logging</groupId>
        <artifactId>commons-logging</artifactId>
        <version>1.1.1</version>
      </dependency>
      <dependency>
        <groupId>commons-lang</groupId>
        <artifactId>commons-lang</artifactId>
        <version>2.4</version>
      </dependency>
      <dependency>
        <groupId>commons-configuration</groupId>
        <artifactId>commons-configuration</artifactId>
        <version>1.6</version>
      </dependency>
      <dependency>
        <groupId>commons-beanutils</groupId>
        <artifactId>commons-beanutils</artifactId>
        <version>1.7.0</version>
      </dependency>
      <dependency>
        <groupId>commons-collections</groupId>
        <artifactId>commons-collections</artifactId>
        <version>3.2.1</version>
      </dependency>
      <dependency>
        <groupId>commons-beanutils</groupId>
        <artifactId>commons-beanutils-core</artifactId>
        <version>1.8.0</version>
      </dependency>
      <dependency>
        <groupId>manual</groupId>
        <artifactId>dep</artifactId>
        <version>1</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <description>A demonstration of maven POM customization</description>
</project>
```

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

Whenever a recommendation provider can provide a version recommendation for a transitive dependency AND there is a first order dependency on that transitive that has no version specified, the recommendation overrides versions of the module that are provided by transitively.  

Consider the following example with dependencies on `commons-configuration` and `commons-logging`.  `commons-configuration:1.6` depends on `commons-logging:1.1.1`.  Even though `commons-configuration` indicates that it prefers version `1.1.1`, `1.0` is selected because of the recommendation provider.

```groovy
dependencyRecommendations {
   map recommendations: ['commons-logging:commons-logging': '1.0']
}

dependencies {
   compile 'commons-configuration:commons-configuration:1.6'
   compile 'commons-logging:commons-logging'
}
```

Conversely, if no recommendation can be found for a dependency that has no version, but a version is provided by a transitive the version provided by the transitive is applied.  In this scenario, if several transitives provide versions for the module, normal Gradle conflict resolution applies.

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
