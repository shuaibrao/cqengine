---
description: Compatibility policy for upstream API, serialization, JPMS, OSGi and published artifact shapes.
alwaysApply: false
---

# Public compatibility

- Preserve `com.googlecode.cqengine.*`, automatic module name `cqengine` and the canonical thin OSGi bundle identity.
- Compare public/protected source and binary API against upstream 3.6.0 with complete classpaths.
- Preserve inherited serialization identities unless a deliberate versioned break is documented and tested.
- Run Bnd exported-package baselines for public API changes and select a semantically valid CQEngine version.
- Keep thin and `all` consumers isolated. Never put both artifact forms or the shaded artifact plus its embedded dependencies in one graph.
- Treat relocation of dependency-exposed public descriptors as an artifact boundary; consumers compile and run against the same selected form.
- Additive modules may reduce dependency surface, but the `cqengine` compatibility artifact must retain the established API and behavior.
