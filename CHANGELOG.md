3.6.3 / 2016-07-19
==================

* *BUGFIX* handle recommendations across a multiproject where subproject B depends on A which has a dependency it needs to pick up from recommendations

3.2.0 / 2016-01-11
==================

* Handle the gradle-dependency-lock V4 format

3.1.0 / 2015-12-03
==================

* Offer two strategies for how recommendations interact with transitive dependencies:
  - `ConflictResolved` - If there is no first order recommend-able dependency, a transitive will conflict resolve with dependencies in the recommendations listing
  - `OverrideTransitives` - If a recommendation conflicts with a transitive, pick the transitive

3.0.3 / 2015-11-04
==================

* Fixed bug where GString inputs to Maven BOM and Ivy module attribute were not appended with the file type

3.0.2 / 2015-10-15
==================

* Add a dependency on maven-module-builder which Gradle now shades

3.0.1 / 2015-09-15
==================

* Upgrade to Gradle 2.7

