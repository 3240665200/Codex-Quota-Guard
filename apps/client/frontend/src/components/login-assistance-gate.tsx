import { useEffect, useRef, useState } from "react";
import {
  api,
  assistanceAccessRevokedEvent,
  getApiErrorMessage,
  type LoginAssistanceContext,
} from "@/lib/api";
import { useAssistanceRefresh } from "@/lib/use-assistance-refresh";
import { AuthenticatorAssistance } from "./authenticator-assistance";
import { MailAssistance } from "./login-assistance";
import { Button } from "./ui/button";

/** No sensitive child mounts until the server confirms this device owns the exclusive login grant. */
export function LoginAssistance({ userId }: { userId: string }) {
  const [state, setState] = useState<{
    owner: string;
    context: LoginAssistanceContext;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const deadline = useRef(0);
  const lifetime = useRef({ owner: userId, active: true, generation: 0 });
  const context = state?.owner === userId ? state.context : null;
  useEffect(() => {
    const scope = { owner: userId, active: true, generation: 0 };
    lifetime.current = scope;
    setState(null);
    setError(null);
    deadline.current = 0;
    return () => {
      scope.active = false;
      scope.generation++;
    };
  }, [userId]);
  const refresh = useAssistanceRefresh(userId, async () => {
    const scope = lifetime.current,
      generation = scope.generation;
    const started = performance.now();
    try {
      const value = await api.loginAssistanceContext();
      if (
        !scope.active ||
        scope.owner !== userId ||
        generation !== scope.generation
      )
        return;
      const remaining = value.expiresAt
        ? Date.parse(value.expiresAt) -
          Date.parse(value.serverTime) -
          (performance.now() - started)
        : 0;
      deadline.current = value.allowed
        ? performance.now() + Math.max(0, remaining)
        : 0;
      setState({
        owner: userId,
        context:
          value.allowed && remaining <= 0
            ? {
                ...value,
                allowed: false,
                reasonCode: "BINDING_GRANT_REQUIRED",
                bindingAttemptId: null,
                expiresAt: null,
              }
            : value,
      });
      setError(null);
    } catch (failure) {
      if (
        !scope.active ||
        scope.owner !== userId ||
        generation !== scope.generation
      )
        return;
      deadline.current = 0;
      setState(null);
      setError(getApiErrorMessage(failure));
    }
  });
  useEffect(() => {
    const invalidate = () => {
      lifetime.current.generation++;
      deadline.current = 0;
      setState(null);
      refresh();
    };
    const expiry = window.setInterval(() => {
      if (deadline.current && deadline.current <= performance.now())
        invalidate();
    }, 250);
    const fallback = window.setInterval(() => {
      if (!document.hidden) refresh();
    }, 2000);
    window.addEventListener(assistanceAccessRevokedEvent, invalidate);
    window.addEventListener("focus", invalidate);
    window.addEventListener("quota-guard:assistance-clear", invalidate);
    return () => {
      window.clearInterval(expiry);
      window.clearInterval(fallback);
      window.removeEventListener(assistanceAccessRevokedEvent, invalidate);
      window.removeEventListener("focus", invalidate);
      window.removeEventListener("quota-guard:assistance-clear", invalidate);
    };
  }, [refresh]);
  if (context && !context.mailEnabled && !context.authenticatorEnabled)
    return null;
  if (!context?.allowed)
    return (
      <div className="text-sm text-muted-foreground" role="status">
        {error ? (
          <>
            {error}{" "}
            <Button variant="outline" size="sm" onClick={refresh}>
              重新核对登录辅助
            </Button>
          </>
        ) : context?.reasonCode === "ALREADY_BOUND" ? (
          "会话已绑定，登录辅助已关闭。解除绑定后重新申请名额才能使用。"
        ) : context ? (
          "请先点击“开始绑定会话”；取得独占登录名额后，才会开放后台已开启的登录辅助。"
        ) : (
          "正在核对登录辅助权限…"
        )}
      </div>
    );
  const scope = `${userId}:${context.bindingAttemptId}`;
  return (
    <div className="space-y-6">
      {context.authenticatorEnabled ? (
        <AuthenticatorAssistance key={`totp:${scope}`} userId={userId} />
      ) : null}
      {context.mailEnabled ? (
        <MailAssistance key={`mail:${scope}`} userId={userId} />
      ) : null}
    </div>
  );
}
