import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fixture from '../test/fixtures/authenticator-qr.json';
import { importAuthenticator } from './authenticator-import';
import { readAuthenticatorQr } from './authenticator-qr';

const NativeURL = URL;
let pixels: ImageData;
const createUrl = vi.fn(() => 'blob:synthetic-qr');
const revokeUrl = vi.fn();

function qrPixels(): ImageData {
  const size = (fixture.rows.length + 8) * 4;
  const data = new Uint8ClampedArray(size * size * 4).fill(255);
  for (let y = 0; y < size; y += 1) for (let x = 0; x < size; x += 1) {
    const row = fixture.rows[Math.floor(y / 4) - 4];
    if (row?.[Math.floor(x / 4) - 4] === '1') {
      const offset = (y * size + x) * 4;
      data[offset] = data[offset + 1] = data[offset + 2] = 0;
    }
  }
  return { data, width: size, height: size, colorSpace: 'srgb' };
}

describe('browser-local QR image decoding', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    pixels = qrPixels();
    vi.stubGlobal('URL', class extends NativeURL {
      static createObjectURL = createUrl;
      static revokeObjectURL = revokeUrl;
    });
    vi.stubGlobal('Image', class {
      naturalWidth = pixels.width;
      naturalHeight = pixels.height;
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      set src(value: string) { if (value) queueMicrotask(() => this.onload?.()); }
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage: vi.fn(), getImageData: () => pixels,
    } as unknown as CanvasRenderingContext2D);
    vi.stubGlobal('fetch', vi.fn(() => { throw new Error('The QR image must not be uploaded'); }));
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it('decodes an actual synthetic migration QR with jsQR and clears image buffers and URLs', async () => {
    const value = await readAuthenticatorQr(new File(['synthetic image'], 'qr.png', { type: 'image/png' }));
    expect(value).toBe(fixture.uri);
    expect(new NativeURL(importAuthenticator(value).uri).searchParams.get('secret')).toBe('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ');
    expect(pixels.data.every((byte) => byte === 0)).toBe(true);
    expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-qr');
    expect(fetch).not.toHaveBeenCalled();
  });
  it('accepts inverted QR pixels', async () => {
    for (let i = 0; i < pixels.data.length; i += 4) for (let channel = 0; channel < 3; channel += 1) pixels.data[i + channel] = 255 - pixels.data[i + channel];
    expect(await readAuthenticatorQr(new File(['synthetic image'], 'qr.webp', { type: 'image/webp' }))).toBe(fixture.uri);
  });
  it('recovers an image that is unreadable at the first resampling scale', async () => {
    const first = qrPixels(); first.data.fill(255);
    const second = qrPixels();
    vi.stubGlobal('Image', class {
      naturalWidth = 1080; naturalHeight = 2414;
      onload: (() => void) | null = null; onerror: (() => void) | null = null;
      set src(value: string) { if (value) queueMicrotask(() => this.onload?.()); }
    });
    const frames = [first, second];
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage: vi.fn(), getImageData: () => frames.shift()!,
    } as unknown as CanvasRenderingContext2D);
    expect(await readAuthenticatorQr(new File(['synthetic-photo'], 'photo.jpg', { type: 'image/jpeg' }))).toBe(fixture.uri);
    expect(first.data.every((byte) => byte === 0)).toBe(true);
    expect(second.data.every((byte) => byte === 0)).toBe(true);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('clears pixels and releases the URL when no QR is present', async () => {
    pixels.data.fill(255);
    await expect(readAuthenticatorQr(new File(['blank'], 'blank.png', { type: 'image/png' }))).rejects.toThrow('没有识别到二维码');
    expect(pixels.data.every((byte) => byte === 0)).toBe(true);
    expect(revokeUrl).toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });
  it('rejects oversized image dimensions before allocating a canvas buffer', async () => {
    pixels = { ...pixels, width: 8000, height: 8000 };
    await expect(readAuthenticatorQr(new File(['oversized'], 'large.jpg', { type: 'image/jpeg' }))).rejects.toThrow('图片尺寸过大');
    expect(revokeUrl).toHaveBeenCalled();
  });
  it.each(['image/svg+xml', 'text/html', 'application/octet-stream'])('rejects non-raster input (%s) before decoding', async (type) => {
    await expect(readAuthenticatorQr(new File(['synthetic'], 'input', { type }))).rejects.toThrow('PNG、JPEG 或 WebP');
    expect(createUrl).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });
  it('rejects files over 5 MiB', async () => {
    const file = new File(['synthetic'], 'large.png', { type: 'image/png' });
    Object.defineProperty(file, 'size', { value: 6 * 1024 * 1024 });
    await expect(readAuthenticatorQr(file)).rejects.toThrow('不超过 5 MiB');
    expect(createUrl).not.toHaveBeenCalled();
  });
});
