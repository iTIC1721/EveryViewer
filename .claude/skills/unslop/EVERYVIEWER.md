# unslop in EveryViewer

This app is an Android viewer written in Jetpack Compose, not a web page. The passes of [SKILL.md](SKILL.md) still apply, with these mappings. The result of the first run (2026-11, decision 40 in docs/기획서.md) was "light slop", so later runs normally stay in polish mode: fix what the audit finds, keep the layout.

## Where things live

- Tokens: `app/src/main/java/com/tabpdf/ui/theme/` (Color, Type, Shape, Motion, Theme extensions like `desk`, `card`, `floating`). Change a token before changing a screen. Screens must not write colours or radii directly (CLAUDE.md), except group colours, pen colours, search highlights and document colours.
- Rules in prose: docs/기획서.md 4.4 (visual design). Keep it in sync with the tokens.
- Words on screen: the `ui-copy-ko` skill, not the copy pass of SKILL.md (that one is written for English).

## Direction (keep unless the user changes it)

- Product: a viewer that keeps many documents open as tabs, swiped through, grouped and written on.
- Character: calm, exact, like paper.
- World: paper on a desk, ink.
- Moments: the document pages and the page thumbnails in the tab switcher. The chrome stays quiet.
- Signature: the tab count badge and the dots in the group's colour.
- Accent: ink violet from the app icon (the real brand). Only for state: pen on, current tab, selection, a switch or radio that is on, the scroll handle being dragged.

## Mapping the passes

- Spacing: dp on the 4 dp grid (2 dp steps inside small controls are fine). Touch targets stay at least 48 dp.
- Colour: neutral grey ramp for surfaces, text and outlines in both themes (`LightColors`, `DarkColors`). Material 3 roles: `onSurface` primary text, `onSurfaceVariant` secondary, `outline` muted. `secondaryContainer` is a grey surface step, not a tint of the accent.
- Icons: Material Icons (filled) are the one set; size 24 dp in bars. Icon buttons have a contentDescription and no visible label (CLAUDE.md).
- Type: Pretendard Std, Medium for titles and labels, Regular for body, no negative tracking under 16 sp, `tabularNumbers()` for changing numbers.
- Details: radii from `AppShapes` (4/8/14/16/20 dp). Buttons use `MaterialTheme.shapes.small`, not the default pill.
- Motion: tokens in `Motion`; one decelerate curve for entering, no bouncing springs, nothing loops.
- Visuals and story video: not used in this app.

## Checking

Run the app on the Android 11 emulator (CLAUDE.md, "에뮬레이터 테스트"), capture the same screens before and after in light and dark theme (`adb exec-out screencap -p`), and compare them side by side.
