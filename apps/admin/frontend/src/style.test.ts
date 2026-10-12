import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';

const stylesheet = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'style.css'), 'utf8');
const styles: HTMLStyleElement[] = [];

function addStyle(css: string) {
  const style = document.createElement('style');
  style.textContent = css;
  document.head.append(style);
  styles.push(style);
  return style;
}

function applyReducedMotionStyles() {
  const source = addStyle(stylesheet);
  // jsdom has no media-query/layout engine. Activate the real reduced-motion
  // declarations here; popup coordinates are additionally checked in a browser.
  const reducedMotion = Array.from(source.sheet!.cssRules)
    .filter((rule): rule is CSSMediaRule =>
      rule.type === CSSRule.MEDIA_RULE &&
      (rule as CSSMediaRule).conditionText === '(prefers-reduced-motion: reduce)',
    )
    .flatMap((rule) => Array.from(rule.cssRules, (child) => child.cssText))
    .join('\n');
  expect(reducedMotion).not.toBe('');
  addStyle(reducedMotion);
}

afterEach(() => {
  styles.splice(0).forEach((style) => style.remove());
  document.body.replaceChildren();
});

describe('reduced-motion popup positioning', () => {
  it.each(['ant-dropdown', 'ant-select-dropdown', 'ant-picker-dropdown', 'ant-tooltip'])(
    '%s does not animate coordinates used by synchronous popup measurement',
    (className) => {
      applyReducedMotionStyles();
      const popup = document.createElement('div');
      popup.className = className;
      popup.style.position = 'absolute';
      popup.style.top = '-9999px';
      popup.style.left = '-9999px';
      document.body.append(popup);

      // A tiny positive duration still enables the default transition-property:
      // all. rc-trigger temporarily changes top/left to measure its origin, so
      // even a 0.01ms transition can make that synchronous measurement stale.
      const durations = getComputedStyle(popup).transitionDuration.split(',');
      expect(durations.every((duration) => !Number.parseFloat(duration))).toBe(true);
    },
  );

  it('also removes existing decorative transitions when reduced motion is requested', () => {
    applyReducedMotionStyles();
    const button = document.createElement('button');
    button.className = 'cqg-logout-button';
    document.body.append(button);
    expect(Number.parseFloat(getComputedStyle(button).transitionDuration)).toBe(0);
  });
});
