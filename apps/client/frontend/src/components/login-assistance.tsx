import { useEffect, useRef, useState } from "react";
import { Check, Copy, LoaderCircle, Mail } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AuthenticatorAssistance } from "./authenticator-assistance";
import { useAssistanceRefresh } from "@/lib/use-assistance-refresh";
import {
  api,
  getApiErrorMessage,
  type LoginAssistanceInfo,
  type LoginAssistanceListener,
} from "@/lib/api";

function isActive(listener: LoginAssistanceListener | null) {
  return listener?.state === "CONNECTING" || listener?.state === "LISTENING";
}

function unavailableMessage(reason: string | null) {
  switch (reason) {
    case "TEAM_UNAVAILABLE":
      return "所属团队暂不可用，请联系管理员检查团队分配。";
    case "ACCOUNT_UNAVAILABLE":
      return "团队官方账户尚未配置或不可用，请联系管理员。";
    case "MAILBOX_UNCONFIGURED":
    case "MAILBOX_CREDENTIALS_MISSING":
      return "团队登录邮箱尚未配置完整，请联系管理员在后台设置邮箱。";
    case "MAILBOX_DISABLED":
      return "团队登录邮箱已停用，请联系管理员检查配置。";
    case "ENCRYPTION_UNAVAILABLE":
      return "邮箱服务暂不可用，请联系管理员检查后台配置。";
    default:
      return "登录辅助暂不可用，请联系管理员检查团队与邮箱配置。";
  }
}

function failedMessage(errorCode: string | null) {
  switch (errorCode) {
    case "MAIL_OAUTH_FAILED":
      return "邮箱认证失败，请联系管理员检查邮箱配置。";
    case "MAIL_DNS_FAILED":
      return "无法解析邮箱服务器，请联系管理员检查邮箱主机与 DNS。";
    case "MAIL_HOST_BLOCKED":
      return "邮箱服务器地址被安全策略阻止，请联系管理员核对主机配置。";
    case "MAIL_AUTH_FAILED":
      return "邮箱认证失败，请联系管理员检查邮箱地址、IMAP 开关与授权码。";
    case "MAIL_TLS_FAILED":
      return "邮箱加密连接失败，请联系管理员检查邮箱服务与网络设置。";
    case "MAIL_CONNECTION_TIMEOUT":
      return "连接邮箱超时，请稍后重新监听；如持续失败，请联系管理员检查网络。";
    case "MAIL_FOLDER_FAILED":
      return "无法打开邮箱文件夹，请联系管理员检查 INBOX 及访问权限。";
    case "MAIL_CONNECTION_FAILED":
      return "暂时无法连接邮箱，请稍后重新监听。";
    case "MAIL_CREDENTIALS_UNAVAILABLE":
    case "MAIL_ENCRYPTION_UNAVAILABLE":
      return "邮箱服务尚未配置完整，请联系管理员检查后台配置。";
    case "MAIL_ACCESS_REVOKED":
    case "MAIL_CONFIG_CHANGED":
      return "团队或邮箱配置已变更，请联系管理员后重新监听。";
    case "MAIL_CURSOR_CHANGED":
    case "MAIL_CURSOR_UNAVAILABLE":
      return "邮箱状态已变化，无法确认本次新邮件，请重新监听。";
    case "MAIL_LISTENER_TIMEOUT":
      return "监听已超时，请重新监听后再发送验证码";
    case "MAIL_LISTENER_STOPPED":
      return "本次监听已停止，请重新监听。";
    case "CODE_EXPIRED":
      return "验证码已过期，请重新监听后再发送验证码";
    case "MAIL_QUEUE_FULL":
      return "监听服务当前繁忙，请稍后重试。";
    default:
      return "本次监听失败，请稍后重试；如持续失败，请联系管理员。";
  }
}

function stateMessage(
  listener: LoginAssistanceListener | null,
  starting: boolean,
) {
  if (starting || listener?.state === "CONNECTING") return "正在连接邮箱";
  switch (listener?.state) {
    case "LISTENING":
      return "正在监听";
    case "RECEIVED":
      return "已收到验证码，监听已停止";
    case "CANCELLED":
      return "监听已取消";
    case "TIMED_OUT":
      return "监听已超时，请重新监听后再发送验证码";
    case "FAILED":
      return failedMessage(listener.errorCode);
    default:
      return "点击监听邮件，准备接收本次登录验证码";
  }
}

interface Lifetime {
  active: boolean;
  generation: number;
  busy: boolean;
  refreshQueued?: boolean;
  listener: LoginAssistanceListener | null;
}

// Listener responses (including the code) belong only to this mounted page.
// They are deliberately excluded from React Query and persistent storage.
export function LoginAssistance({ userId }: { userId: string }) {
  const [info, setInfo] = useState<LoginAssistanceInfo | null>(null);
  const [infoLoading, setInfoLoading] = useState(true);
  const [infoAttempt, setInfoAttempt] = useState(0);
  const [listener, setListener] = useState<LoginAssistanceListener | null>(
    null,
  );
  const [pending, setPending] = useState<"start" | "cancel" | null>(null);
  const [attempted, setAttempted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [codeExpired, setCodeExpired] = useState(false);
  const lifetimeRef = useRef<Lifetime | null>(null);

  useEffect(() => {
    // A distinct object also isolates the two StrictMode effect lifetimes.
    const lifetime: Lifetime = {
      active: true,
      generation: 0,
      busy: false,
      listener: null,
    };
    lifetimeRef.current = lifetime;
    setInfo(null);
    setInfoLoading(true);
    setListener(null);
    setPending(null);
    setAttempted(false);
    setCopied(false);
    setCodeExpired(false);
    setError(null);
    const clear = () => {
      lifetime.generation += 1;
      lifetime.busy = false;
      lifetime.listener = null;
      setListener(null);
      setCopied(false);
      setPending(null);
      setCodeExpired(false);
      setError(null);
    };
    window.addEventListener("quota-guard:assistance-clear", clear);
    return () => {
      window.removeEventListener("quota-guard:assistance-clear", clear);
      lifetime.active = false;
      lifetime.generation += 1;
      lifetime.listener = null;
    };
  }, [userId]);

  useEffect(() => {
    let active = true;
    setInfoLoading(true);
    void api
      .loginAssistanceInfo()
      .then(
        (result) => {
          if (active) setInfo(result);
        },
        (failure: unknown) => {
          if (active)
            setError(`登录辅助读取失败：${getApiErrorMessage(failure)}`);
        },
      )
      .finally(() => {
        if (active) setInfoLoading(false);
      });
    return () => {
      active = false;
    };
  }, [userId, infoAttempt]);

  const refresh = useAssistanceRefresh(userId, async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active) return;
    if (lifetime.busy) {
      lifetime.refreshQueued = true;
      return;
    }
    const generation = lifetime.generation;
    const current = () => lifetime.active && lifetime.generation === generation;
    try {
      let { listener: result } = await api.loginAssistanceCurrent();
      if (!current()) return;
      const known = lifetime.listener;
      if (!result && known) {
        if (isActive(known) || known.state === "RECEIVED") {
          // /current restores only resumable work. Read this page's known task
          // once to retain a safe terminal failure/cancellation/timeout message.
          if (known.code) {
            lifetime.listener = { ...known, code: null };
            setListener(lifetime.listener);
            setCopied(false);
          }
          result = await api.loginAssistanceStatus(known.id);
        } else result = known;
      }
      if (!current()) return;
      const expired =
        result?.state === "RECEIVED" &&
        Date.parse(result.expiresAt) <= Date.now();
      const safe = result && expired ? { ...result, code: null } : result;
      lifetime.listener = safe;
      setListener(safe);
      setCodeExpired(!!expired);
      setCopied(false);
      if (result) setAttempted(true);
      setError(null);
    } catch (failure) {
      if (!current()) return;
      const message = getApiErrorMessage(failure);
      if (message === "请先登录系统账户。") {
        lifetime.listener = null;
        setListener(null);
        setCopied(false);
      }
      setError(`无法读取监听状态：${message} 后台监听不会因离开页面而停止。`);
    }
  });

  useEffect(() => {
    if (!listener || (!isActive(listener) && listener.state !== "RECEIVED"))
      return;
    const lifetime = lifetimeRef.current;
    if (!lifetime) return;
    const { id, expiresAt } = listener;
    const timer = window.setTimeout(
      () => {
        const current = lifetime.listener;
        if (
          !lifetime.active ||
          current?.id !== id ||
          current.expiresAt !== expiresAt
        )
          return;
        setCopied(false);
        if (current.state === "RECEIVED") {
          lifetime.listener = { ...current, code: null };
          setListener(lifetime.listener);
          setCodeExpired(true);
        }
        refresh();
      },
      Math.max(0, Date.parse(expiresAt) - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [listener?.id, listener?.state, listener?.expiresAt, refresh]);

  const start = async () => {
    const lifetime = lifetimeRef.current;
    if (
      !info?.available ||
      !lifetime?.active ||
      lifetime.busy ||
      isActive(lifetime.listener)
    )
      return;
    lifetime.busy = true;
    const generation = ++lifetime.generation;
    lifetime.listener = null;
    setListener(null);
    setCopied(false);
    setCodeExpired(false);
    setError(null);
    setAttempted(true);
    setPending("start");
    const current = () => lifetime.active && lifetime.generation === generation;
    try {
      const result = await api.loginAssistanceStart();
      if (!current()) return;
      lifetime.listener = result;
      setListener(result);
    } catch (failure) {
      if (current()) setError(`无法开始监听：${getApiErrorMessage(failure)}`);
    } finally {
      if (current()) {
        lifetime.busy = false;
        setPending(null);
        if (lifetime.refreshQueued) {
          lifetime.refreshQueued = false;
          refresh();
        }
      }
    }
  };

  const cancel = async () => {
    const lifetime = lifetimeRef.current;
    const active = lifetime?.listener;
    if (!lifetime?.active || lifetime.busy || !active || !isActive(active))
      return;
    lifetime.busy = true;
    const generation = ++lifetime.generation;
    setPending("cancel");
    setError(null);
    const current = () => lifetime.active && lifetime.generation === generation;
    try {
      const result = await api.loginAssistanceCancel(active.id);
      if (!current()) return;
      lifetime.listener = result;
      setListener(result);
      if (isActive(result))
        setError("未能确认取消，监听可能仍在进行，请重试取消或等待超时。");
    } catch {
      if (current())
        setError(
          "未能确认取消，监听可能仍在进行，请检查连接后重试取消或等待超时。",
        );
    } finally {
      if (current()) {
        lifetime.busy = false;
        setPending(null);
        if (lifetime.refreshQueued) {
          lifetime.refreshQueued = false;
          refresh();
        }
      }
    }
  };

  const copy = async () => {
    const lifetime = lifetimeRef.current;
    const code = listener?.state === "RECEIVED" ? listener.code : null;
    if (
      !lifetime?.active ||
      !code ||
      Date.parse(listener!.expiresAt) <= Date.now()
    )
      return;
    const generation = lifetime.generation;
    try {
      await navigator.clipboard.writeText(code);
      if (lifetime.active && lifetime.generation === generation)
        setCopied(true);
    } catch {
      if (lifetime.active && lifetime.generation === generation)
        setError("无法复制验证码，请手动选择本次验证码。");
    }
  };

  const active = isActive(listener);
  return (
    <div className="space-y-6">
      <AuthenticatorAssistance key={userId} userId={userId} />
      <section
        className="cqg-surface min-w-0 p-5 sm:p-6"
        aria-label="登录验证码辅助"
      >
        <div className="flex items-start gap-3">
          <Mail
            aria-hidden="true"
            className="mt-0.5 size-5 shrink-0 text-primary"
          />
          <div className="min-w-0">
            <h2 className="text-base font-semibold">监听登录邮件</h2>
            <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
              只接收本次监听建立后新到的一封 ChatGPT
              登录邮件，收到验证码后自动停止。
            </p>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
              请等待“正在监听”，再到 ChatGPT 请求发送登录验证码。
            </p>
          </div>
        </div>
        {error ? (
          <Alert variant="destructive" className="mt-5">
            <AlertTitle>登录辅助提示</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
        {infoLoading ? (
          <p role="status" className="mt-6 text-sm text-muted-foreground">
            正在读取登录辅助配置…
          </p>
        ) : !info ? (
          <Button
            className="mt-5"
            variant="outline"
            onClick={() => setInfoAttempt((value) => value + 1)}
          >
            重新读取
          </Button>
        ) : !info.available ? (
          <p
            role="status"
            className="mt-6 rounded-md bg-muted/60 p-4 text-sm leading-relaxed"
          >
            {unavailableMessage(info.reasonCode)}
          </p>
        ) : (
          <>
            <dl className="mt-5 grid gap-4 rounded-md border bg-muted/30 p-4 text-sm sm:grid-cols-2">
              <div className="min-w-0">
                <dt className="text-xs text-muted-foreground">
                  所属团队的官方账户
                </dt>
                <dd className="mt-1 break-words font-medium">
                  {info.accountLabel ?? "团队官方账户"}
                </dd>
              </div>
              <div className="min-w-0">
                <dt className="text-xs text-muted-foreground">登录邮箱</dt>
                <dd className="mt-1 break-all font-medium">
                  {info.recipientEmail ?? "已配置邮箱"}
                </dd>
              </div>
            </dl>
            <div className="mt-6 flex flex-wrap items-center gap-3">
              <Button
                onClick={() => void start()}
                disabled={active || pending !== null}
              >
                {pending === "start" ? (
                  <LoaderCircle
                    aria-hidden="true"
                    className="motion-safe:animate-spin"
                  />
                ) : (
                  <Mail aria-hidden="true" />
                )}
                {attempted ? "再次监听" : "监听邮件"}
              </Button>
              {active ? (
                <Button
                  variant="outline"
                  onClick={() => void cancel()}
                  disabled={pending !== null}
                >
                  {pending === "cancel" ? "正在取消…" : "取消监听"}
                </Button>
              ) : null}
            </div>
            <p role="status" className="mt-4 text-sm leading-relaxed">
              {codeExpired
                ? "验证码已过期，请重新监听后再发送验证码"
                : stateMessage(listener, pending === "start")}
            </p>
            {listener?.state === "RECEIVED" &&
            listener.code &&
            Date.parse(listener.expiresAt) > Date.now() ? (
              <div className="mt-5 flex flex-wrap items-center gap-4 rounded-md border bg-primary/5 p-4">
                <output
                  aria-label="本次登录验证码"
                  className="font-mono text-3xl font-semibold tracking-[0.2em] tabular-nums"
                >
                  {listener.code}
                </output>
                <Button
                  variant="outline"
                  onClick={() => void copy()}
                  aria-label="复制验证码"
                >
                  {copied ? (
                    <Check aria-hidden="true" />
                  ) : (
                    <Copy aria-hidden="true" />
                  )}
                  {copied ? "已复制" : "复制验证码"}
                </Button>
              </div>
            ) : null}
            <p className="mt-5 text-xs leading-relaxed text-muted-foreground">
              刷新或离开页面不会停止后台监听，重新打开可恢复；只有明确取消或退出系统才会取消监听。验证码仅保存在页面内存，过期后隐藏。
            </p>
          </>
        )}
        {!infoLoading && info && !active && pending === null ? (
          <Button
            className="mt-5"
            variant="outline"
            onClick={() => setInfoAttempt((value) => value + 1)}
          >
            重新读取邮箱配置
          </Button>
        ) : null}
      </section>
    </div>
  );
}
