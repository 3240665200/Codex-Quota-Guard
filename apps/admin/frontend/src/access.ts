import type { InitialState } from './app';

/** Official Ant Design Pro v6 access.ts equivalent. */
export default function access(initialState?: InitialState) {
  return {
    canAdmin: initialState?.currentUser?.role === 'ADMIN',
  };
}
