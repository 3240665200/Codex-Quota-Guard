'use strict';
// Loaded only by start-admin-preview.ps1. Umi's current dev server ignores HOST
// in its listen() call; constrain TCP listeners in this preview process tree.
const net = require('node:net');
const listen = net.Server.prototype.listen;
net.Server.prototype.listen = function (...args) {
  const first = args[0];
  if (first && typeof first === 'object' && 'port' in first) {
    args[0] = { ...first, host: '127.0.0.1' };
  } else if (typeof first === 'number' || (typeof first === 'string' && /^\d+$/.test(first))) {
    if (typeof args[1] === 'string') args[1] = '127.0.0.1';
    else args.splice(1, 0, '127.0.0.1');
  }
  return Reflect.apply(listen, this, args);
};
