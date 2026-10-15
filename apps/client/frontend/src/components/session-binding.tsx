import { useEffect, useRef, useState } from "react";
import { Link2, LoaderCircle } from "lucide-react";
import {
  api,
  getApiErrorMessage,
  type SessionBinding as Binding,
  type SessionBindingAttempt,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

const activeStates = new Set([
  "QUEUED",
  "PREPARING",
  "READY_FOR_LOGIN",
  "DETECTING",
  "AWAITING_CONFIRMATION",
]);
function active(attempt: SessionBindingAttempt | null) {
  return !!attempt && activeStates.has(attempt.status);
}
function message(attempt: SessionBindingAttempt | null) {
  if (!attempt) return "首次使用请将本设备与本次官方登录会话绑定。";
  switch (attempt.status) {
    case "QUEUED":
      return `正在排队，当前排位 ${Math.max(1, attempt.queuePosition)}。请等待登录名额，不要提前登录。`;
    case "PREPARING":
      return "正在取得本次登录前的官方会话快照，请稍候。";
    case "READY_FOR_LOGIN":
      return "已取得本次独占登录名额。请打开官方 Codex 应用，使用应用内的正常登录入口完成登录，然后点击下方识别按钮。";
    case "DETECTING":
      return "正在读取本次登录后的官方会话，识别唯一新增候选…";
    case "AWAITING_CONFIRMATION":
      return "已找到一个新增官方会话。请核对以下信息，再确认是否属于本次登录。";
    case "BOUND":
      return "本设备官方会话已绑定。";
    case "CANCELLED":
      return "本次绑定已取消。若已取得登录名额，管理员需核对官方会话后恢复排队。";
    case "EXPIRED":
      return "本次绑定已超时，不能继续确认。请联系管理员核对本次官方登录后重新开始。";
    default:
      switch (attempt.errorCode) {
        case "SESSION_BINDING_NO_NEW_SESSION":
          return "没有发现本次新增的官方会话。已有官方登录不能直接推断为本设备，请联系管理员处理；需要重新登录时，请由你在官方应用中明确操作。";
        case "SESSION_BINDING_AMBIGUOUS":
          return "本次出现多个新增官方会话，无法唯一识别。请联系管理员核对并恢复队列。";
        case "SESSION_BINDING_DEVICE_CONFLICT":
          return "本设备已有其他系统账户的有效绑定，请由管理员处理，不能直接接管。";
        case "SESSION_BINDING_SNAPSHOT_UNAVAILABLE":
          return "无法取得新鲜的官方会话快照，请联系管理员检查官方连接。";
        default:
          return "本次绑定未完成。请联系管理员检查官方会话与队列状态后重试。";
      }
  }
}
interface Lifetime {
  active: boolean;
  generation: number;
  busy: boolean;
  attempt: SessionBindingAttempt | null;
}

/** Central state restores across app restarts; sensitive task state never enters persistent renderer storage. */
export function SessionBinding({ userId }: { userId: string }) {
  const [attempt, setAttempt] = useState<SessionBindingAttempt | null>(null);
  const [binding, setBinding] = useState<Binding | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const lifetimeRef = useRef<Lifetime | null>(null);

  useEffect(() => {
    const lifetime: Lifetime = {
      active: true,
      generation: 0,
      busy: false,
      attempt: null,
    };
    lifetimeRef.current = lifetime;
    setAttempt(null);
    setBinding(null);
    setError(null);
    setPending(false);
    setLoading(true);
    void api
      .sessionBindingCurrent()
      .then(
        (current) => {
          if (!lifetime.active) return;
          lifetime.attempt = current.attempt;
          setAttempt(current.attempt);
          setBinding(current.binding);
        },
        (failure: unknown) => {
          if (lifetime.active) setError(getApiErrorMessage(failure));
        },
      )
      .finally(() => {
        if (lifetime.active) setLoading(false);
      });
    // A prepared official login may still finish after navigation. Keep the central lease;
    // only an explicit cancel or its server deadline ends it and quarantines late logins.
    return () => {
      lifetime.active = false;
      lifetime.generation += 1;
    };
  }, [userId, reload]);

  useEffect(() => {
    if (!active(attempt) || pending) return;
    const lifetime = lifetimeRef.current;
    if (!lifetime) return;
    const generation = lifetime.generation;
    const id = attempt!.id;
    let disposed = false;
    let timer: number | undefined;
    const current = () =>
      !disposed &&
      lifetime.active &&
      lifetime.generation === generation &&
      lifetime.attempt?.id === id;
    const poll = async () => {
      if (!current() || lifetime.busy) return;
      try {
        const result = await api.sessionBindingAction(
          id,
          lifetime.attempt?.status === "QUEUED" ? "prepare" : "status",
        );
        if (!current()) return;
        lifetime.attempt = result;
        setAttempt(result);
        if (result.binding) setBinding(result.binding);
        setError(null);
        if (!active(result)) return;
      } catch (failure) {
        if (!current()) return;
        const text = getApiErrorMessage(failure);
        setError(text);
        if (text === "请先登录系统账户。") {
          lifetime.attempt = null;
          setAttempt(null);
          setBinding(null);
          return;
        }
      }
      if (current()) timer = window.setTimeout(() => void poll(), 2_000);
    };
    timer = window.setTimeout(() => void poll(), 2_000);
    return () => {
      disposed = true;
      window.clearTimeout(timer);
    };
  }, [attempt?.id, attempt?.status, pending]);

  useEffect(() => {
    if (!active(attempt)) return;
    const lifetime = lifetimeRef.current;
    const id = attempt!.id;
    const timer = window.setTimeout(
      () => {
        if (
          !lifetime?.active ||
          lifetime.attempt?.id !== id ||
          !active(lifetime.attempt)
        )
          return;
        lifetime.generation += 1;
        lifetime.busy = false;
        const expired: SessionBindingAttempt = {
          ...lifetime.attempt!,
          status: "EXPIRED",
          loginAllowed: false,
          candidateSessionId: null,
          candidateDisplayName: null,
          candidatePlatform: null,
        };
        lifetime.attempt = expired;
        setAttempt(expired);
        setPending(false);
        setError(null);
      },
      Math.max(0, Date.parse(attempt!.expiresAt) - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [attempt?.id, attempt?.expiresAt, attempt?.status]);

  const run = async (action: "start" | "detect" | "confirm" | "cancel") => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy) return;
    const existing = lifetime.attempt;
    if (action !== "start" && !existing) return;
    lifetime.busy = true;
    const generation = ++lifetime.generation;
    setPending(true);
    setError(null);
    const current = () => lifetime.active && lifetime.generation === generation;
    try {
      let result =
        action === "start"
          ? await api.sessionBindingStart()
          : await api.sessionBindingAction(existing!.id, action);
      if (!current()) return;
      lifetime.attempt = result;
      setAttempt(result);
      if (action === "start" && result.status === "QUEUED") {
        result = await api.sessionBindingAction(result.id, "prepare");
        if (!current()) return;
        lifetime.attempt = result;
        setAttempt(result);
      }
      if (result.binding) setBinding(result.binding);
    } catch (failure) {
      if (current()) {
        const text = getApiErrorMessage(failure);
        setError(text);
        if (text === "请先登录系统账户。") {
          lifetime.attempt = null;
          setAttempt(null);
          setBinding(null);
        }
      }
    } finally {
      if (current()) {
        lifetime.busy = false;
        setPending(false);
      }
    }
  };

  const bound = binding?.status === "ACTIVE" || attempt?.status === "BOUND";
  return (
    <section className="cqg-surface p-5 sm:p-6" aria-label="本设备官方会话绑定">
      <div className="flex items-start gap-3">
        <Link2
          aria-hidden="true"
          className="mt-0.5 size-5 shrink-0 text-primary"
        />
        <div>
          <h2 className="text-base font-semibold">本设备官方会话绑定</h2>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            {bound
              ? "已保存本设备与系统账户的官方会话映射，重新打开客户端可继续查看。"
              : "排队取得名额 → 在官方 Codex 登录 → 识别新增会话 → 你确认后保存绑定。"}
          </p>
        </div>
      </div>
      {loading ? (
        <p role="status" className="mt-4 text-sm">
          正在读取本设备绑定状态…
        </p>
      ) : (
        <>
          <p role="status" className="mt-4 text-sm leading-relaxed">
            {bound ? "本设备官方会话已绑定。" : message(attempt)}
          </p>
          {bound && binding ? (
            <p className="mt-2 break-all text-xs text-muted-foreground">
              绑定账户：{binding.userDisplayName} · 会话标识：
              {binding.sessionId}
            </p>
          ) : null}
          {error ? (
            <Alert variant="destructive" className="mt-4">
              <AlertTitle>绑定提示</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}
          {attempt?.status === "AWAITING_CONFIRMATION" &&
          attempt.candidateSessionId ? (
            <dl className="mt-4 grid gap-3 rounded-md border bg-muted/30 p-4 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-xs text-muted-foreground">
                  官方会话显示名称
                </dt>
                <dd className="mt-1 break-words">
                  {attempt.candidateDisplayName ?? "官方未提供名称"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">平台</dt>
                <dd className="mt-1">
                  {attempt.candidatePlatform ?? "官方未提供平台"}
                </dd>
              </div>
              <div className="sm:col-span-2">
                <dt className="text-xs text-muted-foreground">会话标识</dt>
                <dd className="mt-1 break-all font-mono text-xs">
                  {attempt.candidateSessionId}
                </dd>
              </div>
            </dl>
          ) : null}
          <div className="mt-4 flex flex-wrap gap-3">
            {!bound && !active(attempt) && !attempt?.quarantined && !error ? (
              <Button disabled={pending} onClick={() => void run("start")}>
                {pending ? (
                  <LoaderCircle aria-hidden="true" className="animate-spin" />
                ) : null}
                开始绑定本设备
              </Button>
            ) : null}
            {attempt?.status === "READY_FOR_LOGIN" && attempt.loginAllowed ? (
              <Button disabled={pending} onClick={() => void run("detect")}>
                已完成登录，识别本设备
              </Button>
            ) : null}
            {attempt?.status === "AWAITING_CONFIRMATION" &&
            attempt.candidateSessionId ? (
              <Button disabled={pending} onClick={() => void run("confirm")}>
                确认绑定此会话
              </Button>
            ) : null}
            {active(attempt) ? (
              <Button
                variant="outline"
                disabled={pending}
                onClick={() => void run("cancel")}
              >
                取消本次绑定
              </Button>
            ) : null}
            {error || attempt?.quarantined || attempt?.status === "EXPIRED" ? (
              <Button
                variant="outline"
                disabled={pending}
                onClick={() => setReload((value) => value + 1)}
              >
                重新读取绑定状态
              </Button>
            ) : null}
          </div>
          {!bound ? (
            <p className="mt-4 text-xs leading-relaxed text-muted-foreground">
              需要验证码时可在“登录辅助”页面监听邮件。收到验证码仅用于官方登录，仍需完成识别与确认。本流程根据排他时段内的新增会话推断归属，不能证明物理设备身份。设备标识为本次安装持久保存的随机
              UUID，升级不变。
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}
