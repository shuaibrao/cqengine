---
description: Java editing conventions for a compatibility-sensitive inherited library.
globs: "**/*.java"
alwaysApply: false
---

# Java style

- Preserve surrounding upstream style and the formatting ratchet; avoid whole-file reformatting.
- Keep overloads together and use explicit types where inference would obscure compatibility or allocation behavior.
- Prefer supported JDK APIs over deep reflection, `Unsafe`, finalization or module opens.
- Do not change public/protected signatures, generic attributes, exception hierarchies or serialization identity as incidental cleanup.
- Use deterministic close and failure suppression for owned resources.
- Add comments only under `code-comments.md`.
