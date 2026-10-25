import * as domMatchers from '@testing-library/jest-dom/matchers';
import { cleanup } from '@testing-library/react';
import { afterEach, expect } from 'vitest';

expect.extend(domMatchers);

if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string): MediaQueryList => ({
      addEventListener: () => undefined,
      addListener: () => undefined,
      dispatchEvent: () => false,
      matches: false,
      media: query,
      onchange: null,
      removeEventListener: () => undefined,
      removeListener: () => undefined,
    }),
  });
}

if (!window.ResizeObserver) {
  class ResizeObserverStub implements ResizeObserver {
    disconnect() {
      // jsdom has no layout engine; observing is intentionally a no-op.
    }

    observe() {
      // jsdom has no layout engine; observing is intentionally a no-op.
    }

    unobserve() {
      // jsdom has no layout engine; observing is intentionally a no-op.
    }
  }
  Object.defineProperty(window, 'ResizeObserver', {
    configurable: true,
    value: ResizeObserverStub,
  });
}

// rc-table asks for pseudo-element styles when measuring scrollbars; jsdom does not
// implement the optional pseudo-element argument, so ignore it in tests.
const getComputedStyleWithoutPseudo = window.getComputedStyle.bind(window);
Object.defineProperty(window, 'getComputedStyle', {
  configurable: true,
  value: (element: Element) => getComputedStyleWithoutPseudo(element),
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});
