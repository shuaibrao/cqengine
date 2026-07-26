---
description: Documentation organization and style for user guides, maintenance contracts, results and historical evidence.
alwaysApply: false
---

# Documentation style

- `documentation/README.md` is the canonical index.
- Maintain one continuous CQEngine documentation set organized by topic. Do not create a separate fork,
  maintenance or migration-documentation silo.
- Keep phases, checkpoints, remediation IDs, research dispositions and pending-work tracking outside the publishable
  tree; they are working material, not durable project documentation.
- Continue release history in `documentation/ReleaseNotes.md`. Do not create a second changelog or change ledger.
- Explain design decisions, invariants, limitations and operational consequences. Link to source instead of pasting large Java, Gradle or generated-code blocks.
- State whether evidence is current, historical, machine-specific, generated or retained in a release bundle.
- Do not call an item pending when it is complete; distinguish deployment-dependent adoption gates from library defects.
- Public documentation describes what changed and why. Temporary implementation sequencing is private planning
  material, not durable release documentation.
- Build outputs are ignored. Commit a curated result summary when readers need it after a clean checkout.
