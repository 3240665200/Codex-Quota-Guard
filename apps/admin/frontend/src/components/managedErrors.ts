const transportPolicyMessage = '当前请求不符合服务器的传输配置。请使用 HTTPS，或由管理员确认 HTTP 部署的 APP_REQUIRE_HTTPS=false 已生效。';

const messages: Record<string, string> = {
  BROWSER_LOGIN_BUSY: '此账户已有进行中的官方网页登录任务，请先完成或取消该任务。',
  BROWSER_LOGIN_RUNTIME_UNAVAILABLE: '服务器登录浏览器尚未就绪，请检查浏览器服务后重试。',
  BROWSER_LOGIN_FRAME_UNAVAILABLE: '暂时无法读取服务器浏览器画面，请重新检查任务或取消。',
  BROWSER_LOGIN_NOT_READY: '尚未发现可保存的登录授权，请先亲自完成官方登录。',
  BROWSER_LOGIN_SSO_UNSUPPORTED: '当前登录跳转到了暂不支持的外部身份站点，请选择其他官方登录方式或取消任务。',
  BROWSER_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED: '首版暂不支持 Google、Apple 或 Microsoft 等外部登录，请使用官方邮箱登录，或取消本次任务。',
  BROWSER_LOGIN_DESTINATION_BLOCKED: '已阻止访问非允许站点，请取消任务后重新登录。',
  BROWSER_LOGIN_SESSION_CHANGED: '临时网页登录会话已改变，请点击“打开官方会话页”重新读取候选，确认完成后再保存。',
  BROWSER_LOGIN_BROWSER_DISABLED: '服务器登录浏览器尚未启用，请联系管理员配置。',
  BROWSER_LOGIN_BROWSER_NOT_FOUND: '服务器未找到登录浏览器，请检查服务端配置。',
  BROWSER_LOGIN_BROWSER_UNAVAILABLE: '服务器登录浏览器暂不可用，请检查服务后重新开始。',
  WEB_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED: '当前登录跳转到了暂不支持的外部身份站点，请选择官方支持的其他登录方式或取消任务。',
  WEB_LOGIN_DESTINATION_BLOCKED: '当前登录跳转地址不在允许范围内，浏览器已停止继续访问。',
  BROWSER_LOGIN_NOT_FOUND: '登录任务已不存在，请重新开始官方网页登录。',
  BROWSER_LOGIN_NOT_ACTIVE: '此登录任务已结束，请重新检查任务状态或开始新任务。',
  BROWSER_LOGIN_SAVING: '授权正在加密保存，请等待结果后再操作。',
  BROWSER_LOGIN_EXPIRED: '登录任务已过期，请重新开始并在有效期内完成登录。',
  OFFICIAL_CREDENTIALS_INVALID: '授权格式不正确，请检查 Bearer 值和允许的官方会话 Cookie。',
  OFFICIAL_PRESET_NOT_CONFIGURED: '服务端尚未配置预置授权，可从本机参数文件填入或手动导入授权。',
  OFFICIAL_PRESET_INVALID: '服务端预置授权格式不正确，请联系管理员检查；也可从本机参数文件填入。',
  OFFICIAL_PRESET_UNAVAILABLE: '服务端预置授权暂时无法读取，请联系管理员检查；也可从本机参数文件填入。',
  OFFICIAL_PRESET_REVISION_CONFLICT: '预置授权已更新，请关闭窗口后重新打开，再确认使用。',
  OFFICIAL_HTTPS_REQUIRED: transportPolicyMessage,
  OFFICIAL_ENCRYPTION_UNAVAILABLE: '服务端尚未配置官方授权加密密钥。',
  OFFICIAL_AUTH_REQUIRED: '官方授权已失效，请在官方网页重新登录后导入授权。',
  OFFICIAL_CHALLENGE_REQUIRED: '官方返回了 Cloudflare 浏览器验证页面，当前后台 HTTP 连接无法读取账户或会话。这不等于授权填写错误；仅复制 Token/Cookie 不能完成该验证，当前尚未连接成功。',
  OFFICIAL_ACCESS_BLOCKED: '官方站点拒绝了本次访问（403）；请检查授权与代理出口，当前未连接成功。',
  OFFICIAL_RATE_LIMITED: '官方站点暂时限制请求频率，请稍后手动刷新。',
  OFFICIAL_UNAVAILABLE: '暂时无法读取官方账户，请稍后手动重试。',
  OFFICIAL_INVALID_RESPONSE: '官方返回的数据格式无法验证，尚未更新快照。',
  OFFICIAL_RESPONSE_INVALID: '官方返回的数据格式无法验证，尚未更新快照。',
  OFFICIAL_ACCOUNT_MISMATCH: '官方返回的账户与指定账户不一致，请核对官方 Account ID。',
  OFFICIAL_CONNECTION_NOT_CONFIGURED: '请先保存此账户的官方授权。',
  OFFICIAL_SNAPSHOT_UNAVAILABLE: '尚无官方会话快照，请手动刷新官方账户与会话。',
  OFFICIAL_CONNECTION_VERSION_CONFLICT: '配置已被其他管理员更新，请关闭窗口后重新打开。',
  OFFICIAL_IDENTITY_CHANGE_REQUIRES_DISCONNECT: '已验证的官方身份不能直接替换，请先删除保存的授权，再配置新的官方 Account ID。',
  OFFICIAL_PROXY_UNAVAILABLE: '所选代理节点不可用，请检查节点状态后重新配置授权。',
  OFFICIAL_PROXY_REQUIRED: '请为当前官方账户选择一个已启用的代理节点并重新保存授权，所有官方请求均禁止直连。',
  OFFICIAL_REFRESH_BUSY: '此账户正在刷新，请等待当前请求完成后再试。',
  OFFICIAL_REFRESH_RATE_LIMITED: '刷新过于频繁，请稍后手动重试。',
  OFFICIAL_CONNECTION_CHANGED: '授权在刷新期间发生变化，请核对当前配置后重新验证。',
  OFFICIAL_ACCOUNT_NOT_FOUND: '此官方账户登记已不存在，请返回列表刷新。',
  OFFICIAL_VERSION_CONFLICT: '配置已被其他管理员更新，请关闭窗口后重新打开。',
  OFFICIAL_CREDENTIALS_UNAVAILABLE: '保存的授权暂时无法解密，请检查服务端配置。',
  PROXY_NODE_INVALID: '节点信息无效，请检查名称、协议、服务器、端口及用户名和密码。',
  PROXY_NODE_UNAVAILABLE: '此账户保存的代理节点不可用，请检查节点状态或重新选择并保存。',
  PROXY_NODE_DISABLED: '此代理节点已停用，请启用节点后再测试或使用。',
  PROXY_CONNECT_TIMEOUT: '代理连接超时，请检查服务器地址、端口和节点网络后重试。',
  PROXY_TEST_RESPONSE_INVALID: '未能验证节点测试返回的出口信息，请检查节点后重新测试。',
  PROXY_SUBSCRIPTION_FORMAT_UNSUPPORTED: '订阅格式不支持，请使用 URI 列表或 Base64 内容。',
  PROXY_SUBSCRIPTION_FAILED: '订阅获取失败，请检查 HTTPS 地址和订阅服务。',
  PROXY_NODE_NOT_FOUND: '所选代理节点已不存在，请刷新节点列表。',
  PROXY_NODE_IN_USE: '此节点仍被官方账户使用，请先修改对应账户的代理配置。',
  PROXY_NODE_VERSION_CONFLICT: '节点已被其他管理员更新，请刷新列表后重试。',
  PROXY_ENCRYPTION_UNAVAILABLE: '服务端尚未配置代理节点加密密钥。',
  PROXY_CREDENTIALS_UNAVAILABLE: '节点授权暂时无法解密，请检查服务端配置。',
  PROXY_ENDPOINT_BLOCKED: '节点或订阅地址不符合服务端的访问范围要求。',
  PROXY_KERNEL_UNAVAILABLE: '服务端代理内核尚未就绪，暂时无法使用该节点。',
  PROXY_CAPACITY_EXCEEDED: '代理服务当前容量已满，请稍后重试。',
  PROXY_CONNECTION_FAILED: '无法通过此代理建立连接，请检查节点服务。',
  PROXY_HTTPS_REQUIRED: transportPolicyMessage,
  SESSION_BINDING_HTTPS_REQUIRED: transportPolicyMessage,
};

export function managedErrorCode(error: unknown): string | undefined {
  return error && typeof error === 'object' && 'code' in error && typeof error.code === 'string'
    ? error.code : undefined;
}

/** Never render upstream messages: they can contain credentials or subscription URLs. */
export function managedErrorMessage(error: unknown, fallback = '操作失败，请检查配置后重试。'): string {
  const code = managedErrorCode(error);
  if (code && messages[code]) return messages[code];
  if (error && typeof error === 'object' && 'status' in error) {
    if (error.status === 401) return '管理登录已过期，请重新登录。';
    if (error.status === 403) return '当前管理员没有执行此操作的权限。';
    if (error.status === 409) return '配置已发生变化，请刷新后重试。';
  }
  return fallback;
}
