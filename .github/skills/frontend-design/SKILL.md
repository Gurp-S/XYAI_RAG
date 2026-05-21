---
name: frontend-design
description: "Design and implement intentional, bold, production-ready frontend UI in Vue/Vite projects. Use for page redesign, component styling, visual direction, responsive layout, typography, color systems, motion, and UX polish."
argument-hint: "What should be designed or redesigned? Include screen/component, brand mood, constraints, and target devices."
---

# Frontend Designer

## Outcome

Produce a frontend design implementation that is visually intentional and distinctive, technically maintainable, and responsive on desktop and mobile.

## When To Use

- Build or redesign a page or component in a Vue/Vite frontend.
- Improve visual hierarchy, readability, and interaction quality.
- Introduce or refine design tokens, motion, and background atmosphere.
- Avoid generic or boilerplate UI while keeping compatibility with existing project patterns.

## Workflow

1. Understand scope and constraints.
   - Identify target screen(s), audience, brand direction, and required states.
   - Confirm technical constraints: existing component structure, router flow, and store dependencies.
2. Audit existing visual language.
   - Inspect existing CSS variables, spacing/radius rhythm, and component conventions.
   - Detect hard conflicts between scoped component styles and global tokens.
3. Choose a visual direction before coding.
   - Define typography strategy with expressive, purposeful type choices.
   - Define color direction with explicit token variables (avoid accidental purple-on-white defaults).
   - Define atmospheric background treatment (gradient, shapes, pattern, or texture).
4. Design layout and interaction model.
   - Build clear hierarchy for primary actions and supporting content.
   - Plan responsive behavior for narrow and wide viewports.
   - Add a small set of meaningful animations (entry reveal, stagger, or state transition).
5. Implement incrementally.
   - Apply tokens first, then layout, then components, then motion.
   - Keep changes minimal to unrelated files.
   - Preserve existing design system patterns when modifying established screens.
6. Verify behavior and quality.
   - Validate desktop and mobile rendering.
   - Confirm no runtime handler breakage after template/script edits.
   - Check that motion does not degrade perceived performance.

## Decision Points

- Existing system vs new direction:
  - If editing an established screen, preserve existing visual language and structure.
  - If creating a new experience, allow bolder typography, color, and composition choices.
- Scoped CSS vs global tokens:
  - If scoped styles fight the global rhythm, reduce or remove conflicting local overrides.
- Motion depth:
  - If UI feels flat, add one or two purposeful animations.
  - If UI feels heavy or stuttery, reduce animation count and repaint-heavy effects.
- Persistence features:
  - If storing large visual assets in localStorage, guard for quota failures and handle fallback behavior.

## Completion Checks

- Interface does not look generic and has a clear visual direction.
- Typography is intentional and avoids default stacks like Inter/Roboto/Arial/system when unconstrained.
- Color and background create atmosphere beyond a flat single-color canvas.
- Motion is meaningful, limited, and improves comprehension.
- Page loads and behaves correctly on desktop and mobile.
- Updated templates still map to real handlers and state paths.
- No unrelated regressions introduced in adjacent features.

## Prompt Starters

- Redesign the chat main panel to feel editorial and bold, while preserving current router and store behavior.
- Rework the login screen into a high-contrast brand style with responsive mobile layout and subtle staged reveals.
- Improve the sidebar information architecture and visual hierarchy without changing business logic.
