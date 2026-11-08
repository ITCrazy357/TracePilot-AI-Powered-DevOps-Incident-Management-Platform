---
name: TracePilot Clean Precision
colors:
  surface: '#f8f9ff'
  surface-dim: '#c8dbf9'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d2e4ff'
  on-surface: '#071c32'
  on-surface-variant: '#3f4850'
  inverse-surface: '#1e3148'
  inverse-on-surface: '#eaf1ff'
  outline: '#6f7882'
  outline-variant: '#bec7d2'
  surface-tint: '#006495'
  primary: '#006191'
  on-primary: '#ffffff'
  primary-container: '#007bb6'
  on-primary-container: '#fcfcff'
  inverse-primary: '#8fcdff'
  secondary: '#b21277'
  on-secondary: '#ffffff'
  secondary-container: '#fe59b4'
  on-secondary-container: '#630040'
  tertiary: '#02628f'
  on-tertiary: '#ffffff'
  tertiary-container: '#307ba9'
  on-tertiary-container: '#fcfcff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#cbe6ff'
  primary-fixed-dim: '#8fcdff'
  on-primary-fixed: '#001e30'
  on-primary-fixed-variant: '#004b71'
  secondary-fixed: '#ffd8e7'
  secondary-fixed-dim: '#ffafd2'
  on-secondary-fixed: '#3d0025'
  on-secondary-fixed-variant: '#8b005b'
  tertiary-fixed: '#cae6ff'
  tertiary-fixed-dim: '#8ccdff'
  on-tertiary-fixed: '#001e2f'
  on-tertiary-fixed-variant: '#004b70'
  background: '#f8f9ff'
  on-background: '#071c32'
  surface-variant: '#d2e4ff'
  canvas-workspace: '#F5FAFE'
  surface-card: '#FFFFFF'
  border-subtle: '#DDE8F0'
  text-primary: '#10243A'
  text-secondary: '#475569'
  contrast-blue-dark: '#00618E'
  contrast-pink-dark: '#9D2368'
  status-success: '#10B981'
  status-warning: '#F59E0B'
  gutter-error-pink: '#FDF2F8'
typography:
  headline-xl:
    fontFamily: Be Vietnam Pro
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-xl-mobile:
    fontFamily: Be Vietnam Pro
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 34px
    letterSpacing: -0.015em
  headline-lg:
    fontFamily: Be Vietnam Pro
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Be Vietnam Pro
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Be Vietnam Pro
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Be Vietnam Pro
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Be Vietnam Pro
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Be Vietnam Pro
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  code-lg:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
  label-md:
    fontFamily: Be Vietnam Pro
    fontSize: 13px
    fontWeight: '600'
    lineHeight: 16px
  label-sm:
    fontFamily: Be Vietnam Pro
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.04em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1.5rem
  gutter-compact: 1rem
  margin: 2rem
  margin-mobile: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system delivers an analytical, high-clarity environment designed for software and DevOps engineers troubleshooting GitHub Actions pipeline failures. The visual stance is disciplined, quiet, and evidence-first. Under incident pressure, engineers require low cognitive friction; decorative embellishments, flashy gradients, and exaggerated glassmorphism are explicitly excluded.

The overarching aesthetic blends **Clean Corporate Modern** with **Technical Precision Minimalist**. The UI relies on crisp 1px delineation lines, an intentional 85-10-5 visual weight distribution (85% clean white surfaces, 10% structural sky-blue navigation and controls, and 5% surgical pink investigation indicators), and a dedicated monospace layer for logs and commit identifiers.

Localization utilizes natural Vietnamese phrasing for workflow context ("Tổng quan", "Cần điều tra", "Dấu vết gần đây", "Bằng chứng"), while preserving raw international DevOps vernacular (e.g., `GitHub Actions`, `jobs`, `SHAs`, `logs`, `YAML`, `INC-014`).

## Colors

The color distribution adheres strictly to an analytical 85-10-5 discipline:
- **85% Canvas & Surface (`#F5FAFE` / `#FFFFFF`):** Workspaces use a pale ice-canvas `#F5FAFE` with elevated inspectable modules grounded on pure white `#FFFFFF` cards.
- **10% Navigation & Control (`#0297DE` / `#00618E`):** Sky blue acts as the primary pathing mechanism. To preserve WCAG AA legibility for textual links, IDs, and subtle chips, the darker variant `#00618E` is enforced whenever blue typography appears over light backgrounds.
- **5% Investigation & Evidence (`#D83995` / `#9D2368`):** Sparing, high-priority magenta marks breakpoint anomalies, investigation bookmarks, failure root causes, and visual evidence trails. Small textual callouts and inline crash citations enforce `#9D2368`.
- **Text & Structure (`#10243A`, `#475569`, `#DDE8F0`):** Deep navy `#10243A` drives crisp reading contrast, slate `#475569` handles supporting metadata, and architectural boundaries are formed strictly by `#DDE8F0`. Status greens and ambers are functional, restricted to inline operational badges, and are never used alone without an accompanying label.

## Typography

The type ecosystem establishes two coordinated layers:
1. **Human Interface Layer (`Be Vietnam Pro`):** Applied to all interface headers, natural prose, narrative incident write-ups, and form controls. Provides balanced diacritics rendering for Vietnamese without vertical line-height shifting.
2. **Machine Telemetry Layer (`JetBrains Mono`):** Applied strictly to computational entities, stack traces, terminal dumps, Git commit SHAs, incident tags (`INC-014`), and line numbers.

Typography sizing relies on comfortable density: code traces default to 13px on an 18px line height to pack diagnostic logs cleanly without horizontal truncation.

## Layout & Spacing

The system implements an 8px base rhythm (`0.5rem` = 8px) prioritizing desktop diagnostics at 1440px with responsive adaptation down to 390px mobile viewports:

- **Desktop Layout Shell:**
  - Fixed persistent left navigation bar at `224px`.
  - Main operational area utilizing fluid distribution over `#F5FAFE`.
  - Secondary slide-out investigation inspector and notification rail at `380px` fixed width with hardware-accelerated transition and focus trapping.
- **Investigation Column Grids:**
  - Standard Dashboard uses an asymmetric `2:1` split (Primary work stream: "Cần điều tra", Secondary context: "Dấu vết gần đây").
  - Incident Detail view splits into a centralized interactive trace timeline + log analyzer and a pinned right-rail for evidence and metadata.
- **Mobile Reflow:**
  - Converts columns into single-stack vertical layouts with bottom sheets replacing the 380px inspector rail.
  - Horizontal scrolling is strictly restricted to terminal log viewers and wide metric tables.

## Elevation & Depth

Depth is defined through **structural surface layering and crisp outlines** rather than diffuse shadows.

- **Level 0 (Canvas Base):** Pervasive background at `#F5FAFE`. Zero shadow, flat foundation.
- **Level 1 (Card & Content Blocks):** Pure white `#FFFFFF` bounded by a strict `1px solid #DDE8F0` border. No drop shadow is applied during idle states.
- **Level 2 (Interactive Floating / Active Inspecting):** Hovered cards or active evidence nodes adopt an ultra-diffused micro shadow: `0 2px 8px rgba(16, 36, 58, 0.04)`, reinforced by an active border change to `#0297DE`.
- **Level 3 (Overlay Drawers & Modals):** The `380px` investigation slide-out and notification panel introduces crisp boundary containment: `0 8px 30px rgba(16, 36, 58, 0.08)` backed by a subtle semi-transparent curtain overlay `rgba(16, 36, 58, 0.25)`.
- **Atmosphere Prohibition:** Skeuomorphic bevels, glow spreads, multi-layered colorful gradients, and glassmorphism blurs are strictly forbidden.

## Shapes

The design system standardizes on a roundedness level of `2` (`0.5rem` / `8px` baseline), paired with an explicit `12px` (`0.75rem`) radius reserved for major workspace cards and modal containers:

- **Cards, Panels, and Drawers:** Defined strictly with `12px` corner radii (`rounded-xl` token) and a uniform 1px structural stroke.
- **Interactive Buttons & Input Controls:** Styled with `8px` corner radii (`rounded-lg`).
- **Telemetry Chips & Status Badges:** Styled with `6px` or full pill curves depending on context: status indicators use `6px` (`rounded-md`) to signal non-clickable states; interactive filter chips use full pill shapes.
- **The "Đường dấu vết" (Evidence Trace) Signature Motif:** Thin 1px vector rails (`#DDE8F0` or `#0297DE`) connecting circular nodes (6px to 10px circular dots). Operational pipeline steps use `#0297DE` discs, while failure branch points terminate in `#D83995` nodes.

## Components

### Buttons & Interactive Controls
- **Primary Action:** Solid `#0297DE` background, `#FFFFFF` text, 8px border radius, 40px height (`space-md` horizontal padding). Active state shifts to `#00618E`.
- **Investigation / Action:** Solid `#D83995` background with white text, reserved for "Đánh dấu bằng chứng" (Bookmark Evidence) and incident triggers.
- **Secondary / Ghost:** White surface `#FFFFFF`, 1px border `#DDE8F0`, `#10243A` text. Hover prompts `#F5FAFE` surface fill with text tinting to `#00618E`.
- **Focus Rings:** Accessible 2px offset ring in `#0297DE` under keyboard navigation.

### Chips & Badges
- **Status Indicators:** Structured paired pairs—always an outline icon alongside localized text (never color alone):
  - *Đang điều tra (Investigating):* Surface `#FDF2F8`, text `#9D2368`, outline warning dot.
  - *Đã xử lý (Resolved):* Surface `#ECFDF5`, text `#065F46`, outline checkmark.
  - *Mở (Open):* Surface `#EFF6FF`, text `#00618E`, outline circle.
- **Evidence Reference Markers:** Compact inline badges such as `[1]`, `[2]` set in `JetBrains Mono` with `#9D2368` text and `#FDF2F8` backgrounds that trigger deep links into log coordinates.

### Inputs & Search Bars
- **Style:** Height 40px, surface `#FFFFFF`, border `1px solid #DDE8F0`, 8px radius. Secondary text `#475569` for placeholder labels.
- **Active State:** Focus states transition the border to `#0297DE` with zero shadow blowout.
- **Monospace Input Variant:** Run ID and Git commit filters are explicitly set in `JetBrains Mono` 13px.

### Cards & Panels
- **Structure:** Clean white `#FFFFFF` surface with `12px` border radius and `1px solid #DDE8F0`. Padding follows `1.5rem` (`space-lg`).
- **Header Separator:** Subtle `1px solid #DDE8F0` rule dividing title bars from data representations.

### Log Viewer & Gutter Component
- **Surface:** Monospace terminal area utilizing `#10243A` dark mode surface for logs, or clean `#F5FAFE` in light inspector mode.
- **Error Callout Gutter:** When a failure line occurs, the line gutter highlights in pale pink `#FDF2F8` accompanied by a left `#D83995` 3px border line and dark pink `#9D2368` inline exception text.
- **Sanitization Tag:** Masked tokens render a monospace badge labeled `[REDACTED]` in bold slate styling.