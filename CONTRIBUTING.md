# Contributing to Nebula

If you would like to contribute code you can do so through GitHub by forking the repository and sending a pull request. When submitting code, please make every effort to follow existing conventions and style in order to keep the code as readable as possible.

## Experimental Isolated Projects resolution

Gate experimental Isolated Projects resolution changes with
`IsolatedProjectsSupport.isEnabled(project)`, which reads the build-wide Gradle property
`nebula.dependency-recommender.isolatedProjectsSupport` and defaults to `false`. Capture the
decision during configuration; do not use a static flag or inspect another project's state.
Keep the legacy path when the flag is unset or false. Do not enable it automatically when
Gradle's Isolated Projects mode is active or change the default as part of an experimental slice.

Verify actual resolved artifacts, versions, and reasons on both paths. Check opt-in, opt-out,
and rollback with the same daemon, no cache, strict configuration-cache storage/reuse, and
strict Isolated Projects for the supported scope. Keep remaining isolation failures explicit;
expected-failure characterizations do not establish compatibility. Existing fixes for ordinary
builds, such as BOM publication configuration-cache support, do not need this rollout gate.

## License

By contributing your code, you agree to license your contribution under the terms of the [Apache License v2.0](http://www.apache.org/licenses/LICENSE-2.0). Your contributions should also include the following header:

```
/**
 * Copyright 2015 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 ```
