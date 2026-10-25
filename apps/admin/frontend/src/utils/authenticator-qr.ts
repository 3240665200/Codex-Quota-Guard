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
    const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight));
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) throw new Error('当前浏览器无法读取二维码图片。');
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    pixels = context.getImageData(0, 0, canvas.width, canvas.height);
    const { default: jsQR } = await import('jsqr');
    const result = jsQR(pixels.data, pixels.width, pixels.height, { inversionAttempts: 'attemptBoth' });
    if (!result) throw new Error('没有识别到二维码，请选择正对镜头、完整且清晰的二维码图片。');
    const value = result.data;
    result.binaryData.fill(0);
    result.data = '';
    return value;
  } finally {
    pixels?.data.fill(0);
    canvas.width = canvas.height = 0;
    image.onload = image.onerror = null;
    image.src = '';
    URL.revokeObjectURL(url);
  }
}
