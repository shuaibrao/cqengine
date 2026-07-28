---
description: Compatibility policy for upstream API, serialization, JPMS, OSGi and the published artifact.
alwaysApply: false
---

# Public compatibility

- Preserve `com.googlecode.cqengine.*`, automatic module name `cqengine` and the canonical OSGi bundle identity.
- Compare public/protected source and binary API against upstream 3.6.0 with complete classpaths.
- Preserve inherited serialization identities unless a deliberate versioned break is documented and tested.
- Run Bnd exported-package baselines for public API changes and select a semantically valid CQEngine version.
- Keep each consumer graph isolated and resolved only from the staged repository.
- Additive modules may reduce dependency surface, but the `cqengine` artifact must retain the established API and behavior.
