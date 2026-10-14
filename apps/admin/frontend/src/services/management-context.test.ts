import { describe, expect, it, vi } from 'vitest';
import { isSecureManagementContext } from './api';

describe('官方授权管理的页面传输要求', () => {
  it.each([
    ['https:', 'admin.example.com', true],
    ['https:', 'localhost', true],
    ['http:', 'localhost', true],
    ['http:', '127.0.0.1', true],
    ['http:', '[::1]', true],
    ['http:', 'admin.example.com', false],
    ['http:', 'localhost.example.com', false],
    ['http:', 'sub.localhost', false],
    ['http:', '127.0.0.1.example.com', false],
    ['http:', '127.0.0.2', false],
    ['http:', '0.0.0.0', false],
    ['http:', '192.168.1.2', false],
    ['file:', 'localhost', false],
  ])('%s//%s permits sensitive controls: %s', (protocol, hostname, allowed) => {
    vi.stubGlobal('window', { location: { protocol, hostname } });
    try {
      expect(isSecureManagementContext()).toBe(allowed);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('does not permit a non-browser context', () => {
    vi.stubGlobal('window', undefined);
    try {
      expect(isSecureManagementContext()).toBe(false);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
