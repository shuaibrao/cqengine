---
name: mermaid-diagrams
description: Generate readable Mermaid architecture, sequence, flow, class, and state diagrams. Use when the user requests a diagram, visualization, architecture overview, flow chart, lifecycle, or sequence.
---

# Mermaid diagrams

- Let the renderer theme choose colors. Do not use `style`, `classDef`, `:::`, `fill`, `color`, or `stroke` directives.
- Use identifiers without spaces and avoid reserved identifiers such as `end`, `subgraph`, `graph`, and `flowchart`.
- Quote labels containing punctuation: `A["Process (main)"]` and `A -->|"O(1) lookup"| B`.
- Give subgraphs explicit IDs: `subgraph parser ["Parser module"]`.
- Prefer top-to-bottom architecture and left-to-right pipelines.
- Keep a diagram below roughly 30 nodes; split larger views by concern.
- Do not use click handlers or HTML entities.

Select `graph` for relationships, `sequenceDiagram` for interactions, `flowchart` for decisions, `classDiagram` for types, and `stateDiagram-v2` for lifecycles.
