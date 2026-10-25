import { useEffect, useRef, useState } from "react";
import { Check, Copy, KeyRound, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  api,
  getApiErrorMessage,
  type AuthenticatorCode,
  type AuthenticatorInfo,
} from "@/lib/api";

function unavailable(reason: string | null) {
  if (reason === "TEAM_UNAVAILABLE")
    return "所属团队或官方账户不可用，请联系管理员。";
  if (reason === "AUTHENTICATOR_DISABLED")
    return "团队验证器已停用，请联系管理员。";
  if (reason === "ENCRYPTION_UNAVAILABLE")
    return "后台验证器加密服务尚未配置，请联系管理员。";
  return "团队尚未配置验证器。管理员配置对应账户的 TOTP 密钥后即可使用。";
}

// OTPs live only in this mounted page. They never enter a query cache, storage or a native command.
export function AuthenticatorAssistance({ userId }: { userId: string }) {
  const [info, setInfo] = useState<AuthenticatorInfo | null>(null);
  const [code, setCode] = useState<AuthenticatorCode | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const lifetime = useRef<{
    active: boolean;
    generation: number;
    deadline: number;
    abort: AbortController | null;
    busy: boolean;
  } | null>(null);

  useEffect(() => {
    const current = {
      active: true,
      generation: 0,
      deadline: 0,
      abort: null as AbortController | null,
      busy: false,
    };
    lifetime.current = current;
    setCode(null);
    setRemaining(0);
    setCopied(false);
    setInfo(null);
    setError(null);
    setBusy(false);
    const clear = () => {
      current.generation++;
      current.deadline = 0;
      current.busy = false;
      current.abort?.abort();
      setCode(null);
      setRemaining(0);
      setCopied(false);
      setBusy(false);
    };
    const hidden = () => {
      if (document.hidden) clear();
    };
    const revoked = () => {
      clear();
      setInfo(null);
    };
    window.addEventListener("quota-guard:assistance-clear", revoked);
    document.addEventListener("visibilitychange", hidden);
    const timer = window.setInterval(() => {
      if (current.deadline === 0) return;
      const left = Math.max(
        0,
        Math.ceil((current.deadline - performance.now()) / 1000),
      );
      setRemaining(left);
      if (left === 0) clear();
    }, 250);
    return () => {
      current.active = false;
      current.generation++;
      current.abort?.abort();
      current.deadline = 0;
      window.clearInterval(timer);
      window.removeEventListener("quota-guard:assistance-clear", revoked);
      document.removeEventListener("visibilitychange", hidden);
    };
  }, [userId]);

  useEffect(() => {
    let active = true;
    const current = lifetime.current;
    const generation = current?.generation;
    const abort = new AbortController();
    setLoading(true);
    void api
      .authenticatorInfo(abort.signal)
      .then(
        (value) => {
          if (active && current?.active && current.generation === generation) {
            setInfo(value);
            setError(null);
          }
        },
        (failure: unknown) => {
          if (active && current?.active && current.generation === generation)
            setError(`验证器配置读取失败：${getApiErrorMessage(failure)}`);
        },
      )
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
      abort.abort();
    };
  }, [userId, attempt]);

  const requestCode = async () => {
    const current = lifetime.current;
    if (!current?.active || current.busy || !info?.available || document.hidden)
      return;
    current.busy = true;
    setBusy(true);
    setError(null);
    setCode(null);
    setCopied(false);
    current.deadline = 0;
    const generation = ++current.generation;
    const abort = new AbortController();
    current.abort = abort;
    const started = performance.now();
    try {
      const result = await api.authenticatorCode(abort.signal);
      if (
        !current.active ||
        current.generation !== generation ||
        document.hidden
      )
        return;
      if (result.officialAccountId !== info.officialAccountId) {
        setInfo(null);
        setError("团队账户已变化，请重新读取验证器配置。");
        return;
      }
      // Use server time and conservatively subtract the entire round trip, independent of the PC wall clock.
      const millis =
        Date.parse(result.expiresAt) -
        Date.parse(result.serverTime) -
        (performance.now() - started);
      if (millis <= 0) {
        setError("验证码已过期，请重新获取。");
        return;
      }
      current.deadline = performance.now() + millis;
      setCode(result);
      setRemaining(Math.ceil(millis / 1000));
    } catch (failure) {
      if (current.active && current.generation === generation)
        setError(getApiErrorMessage(failure));
    } finally {
      if (current.active && current.generation === generation) {
        current.busy = false;
        setBusy(false);
      }
    }
  };
  const copy = async () => {
    const current = lifetime.current;
    if (
      !current?.active ||
      !code ||
      document.hidden ||
      current.deadline <= performance.now()
    )
      return;
    const generation = current.generation;
    try {
      await navigator.clipboard.writeText(code.code);
      if (current.active && current.generation === generation) setCopied(true);
    } catch {
      if (current.active && current.generation === generation)
        setError("复制失败，请手动选择验证码。");
    }
  };

  return (
    <section
      className="cqg-surface min-w-0 p-5 sm:p-6"
      aria-label="Authenticator 验证码"
    >
      <div className="flex items-start gap-3">
        <KeyRound
          aria-hidden="true"
          className="mt-0.5 size-5 shrink-0 text-primary"
        />
        <div>
          <h2 className="text-base font-semibold">
            Google Authenticator 验证码
          </h2>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            当官方登录要求输入身份验证器的一次性验证码时，在这里获取所属团队账户的当前验证码。
          </p>
        </div>
      </div>
      {loading ? (
        <p role="status" className="mt-5 text-sm text-muted-foreground">
          正在读取验证器配置…
        </p>
      ) : info?.available ? (
        <>
          <p className="mt-5 text-sm">
            官方账户：{info.accountLabel ?? "团队官方账户"}
          </p>
          <Button
            className="mt-4"
            onClick={() => void requestCode()}
            disabled={busy || !!code}
          >
            {busy ? (
              <LoaderCircle
                aria-hidden="true"
                className="motion-safe:animate-spin"
              />
            ) : (
              <KeyRound aria-hidden="true" />
            )}
            {busy ? "正在获取…" : "获取验证器验证码"}
          </Button>
        </>
      ) : info ? (
        <p role="status" className="mt-5 text-sm text-muted-foreground">
          {unavailable(info.reasonCode)}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="mt-4 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {code && remaining > 0 ? (
        <div className="mt-5 rounded-md border bg-primary/5 p-4">
          <div className="flex flex-wrap items-center gap-4">
            <output
              aria-label="当前验证器验证码"
              className="font-mono text-3xl font-semibold tracking-[0.2em] tabular-nums"
            >
              {code.code}
            </output>
            <Button
              variant="outline"
              onClick={() => void copy()}
              aria-label="复制验证器验证码"
            >
              {copied ? (
                <Check aria-hidden="true" />
              ) : (
                <Copy aria-hidden="true" />
              )}
              {copied ? "已复制" : "复制验证码"}
            </Button>
          </div>
          <p role="status" className="mt-3 text-sm text-muted-foreground">
            {remaining} 秒后失效，到期后可获取新验证码
          </p>
        </div>
      ) : null}
      <p className="mt-5 text-xs leading-relaxed text-muted-foreground">
        兼容标准
        TOTP。验证码由后台按时间生成，离开页面、切到后台或退出登录后隐藏；客户端不保存验证器密钥。
      </p>
      {!loading && !code ? (
        <Button
          variant="outline"
          className="mt-4"
          onClick={() => setAttempt((value) => value + 1)}
          disabled={busy}
        >
          重新读取验证器配置
        </Button>
      ) : null}
    </section>
  );
}
