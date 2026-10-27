/** Decode images in this browser; the image and QR payload never leave it. */
export async function readAuthenticatorQr(file: File): Promise<string> {
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || !file.size || file.size > 5 * 1024 * 1024) {
    throw new Error('请选择不超过 5 MiB 的 PNG、JPEG 或 WebP 二维码图片。');
  }
  const url = URL.createObjectURL(file);
  const image = new Image();
  const canvas = document.createElement('canvas');
  let pixels: ImageData | undefined;
  try {
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error('无法读取二维码图片，请重新选择清晰图片。'));
      image.src = url;
    });
    if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > 32_000_000) {
      throw new Error('二维码图片尺寸过大，请裁剪到二维码区域后重新选择。');
    }
    const { default: jsQR } = await import('jsqr');
    const width = image.naturalWidth, height = image.naturalHeight, side = Math.min(width, height);
    // Camera photos can alias finder patterns at one resampling ratio. Try bounded
    // independent scales, then square regions to exclude surrounding phone UI.
    const attempts = [1600, 1200, 2000, 960].map((longest) => ({ x: 0, y: 0, width, height, longest }));
    for (const offset of [0.5, 0, 1]) attempts.push({
      x: Math.round((width - side) * offset), y: Math.round((height - side) * offset),
      width: side, height: side, longest: 1200,
    });
    const tried = new Set<string>();
    for (const attempt of attempts) {
      const scale = Math.min(1, attempt.longest / Math.max(attempt.width, attempt.height));
      const targetWidth = Math.max(1, Math.round(attempt.width * scale));
      const targetHeight = Math.max(1, Math.round(attempt.height * scale));
      const key = [attempt.x, attempt.y, attempt.width, attempt.height, targetWidth, targetHeight].join(':');
      if (tried.has(key)) continue;
      tried.add(key);
      canvas.width = targetWidth; canvas.height = targetHeight;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      if (!context) throw new Error('当前浏览器无法读取二维码图片。');
      context.imageSmoothingEnabled = true; context.imageSmoothingQuality = 'high';
      context.drawImage(image, attempt.x, attempt.y, attempt.width, attempt.height, 0, 0, targetWidth, targetHeight);
      pixels = context.getImageData(0, 0, targetWidth, targetHeight);
      try {
        const result = jsQR(pixels.data, pixels.width, pixels.height, { inversionAttempts: 'attemptBoth' });
        if (result) {
          const value = result.data;
          result.binaryData.fill(0); result.data = '';
          return value;
        }
      } finally { pixels.data.fill(0); pixels = undefined; }
      // Let the loading state and close button paint between CPU-bound attempts.
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
    throw new Error('没有识别到二维码，请裁剪到完整二维码区域后重新导入，或更换清晰图片。');
  } finally {
    pixels?.data.fill(0);
    canvas.width = canvas.height = 0;
    image.onload = image.onerror = null;
    image.src = '';
    URL.revokeObjectURL(url);
  }
}
