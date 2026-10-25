import type { TestingLibraryMatchers } from '@testing-library/jest-dom/matchers';
import 'vitest';

// Bind the matcher types to this app's Vitest, even when the workspace has another Vitest version.
declare module 'vitest' {
  interface Assertion<T = any> extends TestingLibraryMatchers<any, T> { }
  interface AsymmetricMatchersContaining extends TestingLibraryMatchers<any, any> { }
}
