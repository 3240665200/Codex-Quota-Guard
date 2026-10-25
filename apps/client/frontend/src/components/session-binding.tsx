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
import { useAssistanceRefresh } from "@/lib/use-assistance-refresh";

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
      return "已取得本次独占登录名额。请打开官方 Codex 应用，使用应用内的正常登录入口完成登录，后台会自动检测并完成绑定。";
    case "DETECTING":
      return "后台正在检查官方登录结果并识别新增会话，请稍候…";
    case "AWAITING_CONFIRMATION":
      return "已找到一个新增官方会话，后台正在复核，完成后会自动保存绑定。";
    case "BOUND":
      return "本设备官方会话绑定记录已保存。";
    case "CANCELLED":
      return "本次绑定已取消，可以重新申请排队。新申请不会继续上次任务。";
    case "EXPIRED":
      return "本次绑定已超时，可以重新申请排队。";
    default:
      switch (attempt.errorCode) {
        case "SESSION_BINDING_NO_NEW_SESSION":
          return "没有发现本次新增的官方会话，后台会自动整理队列。已有官方登录不能直接推断为本设备；需要重新登录时，请重新申请名额并由你在官方应用中明确操作。";
        case "SESSION_BINDING_AMBIGUOUS":
          return "本次出现多个新增官方会话，无法唯一识别，未保存绑定。后台会自动整理队列；请勿将其他人的登录认作本设备。";
        case "SESSION_BINDING_INVALID_CANDIDATE":
          return "新增会话无法确认用于 Codex，未保存绑定。后台会自动整理队列，可以重新申请排队。";
        case "SESSION_BINDING_DEVICE_CONFLICT":
          return "本设备已有其他系统账户的有效绑定，请由管理员处理，不能直接接管。";
        case "SESSION_BINDING_SNAPSHOT_UNAVAILABLE":
          return "无法取得新鲜的官方会话快照，后台会自动重试；持续失败请联系管理员检查官方连接。";
        default:
          return "本次绑定未完成，后台会自动整理队列。可以重新申请排队；持续失败请联系管理员检查官方连接。";
      }
  }
}
interface Lifetime {
  active: boolean;
  generation: number;
  busy: boolean;
  refreshQueued?: boolean;
  attempt: SessionBindingAttempt | null;
}

/** Central state restores across app restarts; sensitive task state never enters persistent renderer storage. */
export function SessionBinding({
  userId,
  onBound,
}: {
  userId: string;
  onBound?: (bindingId: string | null) => void;
}) {
  const [attempt, setAttempt] = useState<SessionBindingAttempt | null>(null);
  const [binding, setBinding] = useState<Binding | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [needsServerCheck, setNeedsServerCheck] = useState(false);
  const [stateVerified, setStateVerified] = useState(false);
  const [now, setNow] = useState(Date.now);
  const lifetimeRef = useRef<Lifetime | null>(null);
  const onBoundRef = useRef(onBound);
  onBoundRef.current = onBound;

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
    setNeedsServerCheck(false);
    setStateVerified(false);
    // A prepared official login may still finish after navigation. Keep the central lease;
    // only an explicit cancel or its server deadline ends it; the server settles the queue.
    return () => {
      lifetime.active = false;
      lifetime.generation += 1;
    };
  }, [userId]);

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
      const result = await api.sessionBindingCurrent();
      if (!current()) return;
      const previouslyBound = lifetime.attempt?.status === "BOUND";
      lifetime.attempt = result.attempt;
      setAttempt(result.attempt);
      setBinding(result.binding);
      setStateVerified(true);
      setNeedsServerCheck(
        !!result.attempt &&
          active(result.attempt) &&
          Date.parse(result.attempt.expiresAt) <= Date.now(),
      );
      setNow(Date.now());
      setError(null);
      if (!previouslyBound && result.attempt?.status === "BOUND")
        onBoundRef.current?.(
          result.binding?.id ?? result.attempt.binding?.id ?? null,
        );
    } catch (failure) {
      if (!current()) return;
      const text = getApiErrorMessage(failure);
      setError(text);
      if (text === "请先登录系统账户。") {
        lifetime.attempt = null;
        setAttempt(null);
        setBinding(null);
        setStateVerified(false);
      }
    } finally {
      if (current()) setLoading(false);
    }
  });

  useEffect(() => {
    if (!active(attempt)) return;
    const lifetime = lifetimeRef.current;
    const id = attempt!.id;
    const ticker = window.setInterval(() => setNow(Date.now()), 1_000);
    const timer = window.setTimeout(
      () => {
        if (
          !lifetime?.active ||
          lifetime.attempt?.id !== id ||
          !active(lifetime.attempt)
        )
          return;
        setNow(Date.now());
        setNeedsServerCheck(true);
        refresh();
      },
      Math.max(0, Date.parse(attempt!.expiresAt) - Date.now()),
    );
    return () => {
      window.clearTimeout(timer);
      window.clearInterval(ticker);
    };
  }, [attempt?.id, attempt?.expiresAt, attempt?.status, refresh]);

  const run = async (action: "start" | "cancel") => {
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
      const result =
        action === "start"
          ? await api.sessionBindingStart()
          : await api.sessionBindingAction(existing!.id, action);
      if (!current()) return;
      lifetime.attempt = result;
      setAttempt(result);
      setNeedsServerCheck(false);
      setNow(Date.now());
      if (result.binding) setBinding(result.binding);
      if (result.status === "BOUND")
        onBoundRef.current?.(result.binding?.id ?? null);
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
        if (lifetime.refreshQueued) {
          lifetime.refreshQueued = false;
          refresh();
        }
      }
    }
  };

  const bound = binding?.status === "ACTIVE" || attempt?.status === "BOUND";
  const remaining = attempt
    ? Math.max(0, Math.ceil((Date.parse(attempt.expiresAt) - now) / 1000))
    : 0;
  const deadlineReached =
    active(attempt) && (needsServerCheck || remaining === 0);
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
              ? "已保存本设备与系统账户的官方会话映射；其他菜单仍以实时权限核验结果为准。"
              : "申请登录名额 → 在官方 Codex 登录 → 后台自动识别并保存绑定。"}
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
            {bound
              ? "本设备官方会话绑定记录已保存。"
              : deadlineReached
                ? "倒计时已结束，正在核对服务器到期状态，请等待后台返回结果。"
                : message(attempt)}
          </p>
          {active(attempt) ? (
            <p
              className="mt-2 text-sm text-muted-foreground"
              aria-label="绑定剩余时间"
            >
              {attempt?.status === "QUEUED" || attempt?.status === "PREPARING"
                ? "排队"
                : "登录名额"}
              剩余 {String(Math.floor(remaining / 60)).padStart(2, "0")}:
              {String(remaining % 60).padStart(2, "0")}
            </p>
          ) : null}
          {bound && binding ? (
            <p className="mt-2 break-all text-xs text-muted-foreground">
              绑定账户：{binding.userDisplayName} · 会话标识：
              {binding.sessionId}
            </p>
          ) : null}
          {error ? (
            <Alert variant="destructive" className="mt-4">
              <AlertTitle>绑定提示</AlertTitle>
              <AlertDescription>
                {error}
                {error ===
                "本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。" ? (
                  <p className="mt-2">
                    请先重新运行安装器准备统计服务，再登录完成设备登记。
                  </p>
                ) : null}
              </AlertDescription>
            </Alert>
          ) : null}
          {!bound &&
          attempt &&
          ["SETTLING", "RETRYING"].includes(attempt.queueState) ? (
            <Alert className="mt-4">
              <AlertTitle>
                {attempt.queueState === "RETRYING"
                  ? "正在自动重试"
                  : "正在整理绑定队列"}
              </AlertTitle>
              <AlertDescription>
                后台正在自动整理并获取新鲜的官方会话快照，无需手动恢复队列。请等待登录名额，不要提前登录。
                {attempt.retryAt ? (
                  <p className="mt-1">
                    下次检查：
                    {new Date(attempt.retryAt).toLocaleTimeString("zh-CN", {
                      hour12: false,
                    })}
                  </p>
                ) : null}
              </AlertDescription>
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
          {attempt &&
          ["READY_FOR_LOGIN", "DETECTING", "AWAITING_CONFIRMATION"].includes(
            attempt.status,
          ) ? (
            <p className="mt-4 rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-sm leading-relaxed">
              已取得本次官方登录名额。取消后后台会自动整理队列；不要继续本次官方登录，以免迟到会话影响下一次识别。
            </p>
          ) : null}
          <div className="mt-4 flex flex-wrap gap-3">
            {!bound &&
            !active(attempt) &&
            !needsServerCheck &&
            stateVerified &&
            error !== "请先登录系统账户。" ? (
              <Button disabled={pending} onClick={() => void run("start")}>
                {pending ? (
                  <LoaderCircle aria-hidden="true" className="animate-spin" />
                ) : null}
                {error
                  ? "重试申请绑定"
                  : attempt
                    ? "重新申请绑定"
                    : "开始绑定本设备"}
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
            {error ||
            needsServerCheck ||
            (attempt && !active(attempt) && !bound) ? (
              <Button variant="outline" disabled={pending} onClick={refresh}>
                重新读取绑定状态
              </Button>
            ) : null}
          </div>
          {!bound ? (
            <p className="mt-4 text-xs leading-relaxed text-muted-foreground">
              需要验证码时可在“登录辅助”页面监听邮件。完成官方登录后，后台会自动检查绑定结果。本流程根据排他时段内的新增会话推断归属，不能证明物理设备身份；外部迟到登录仍可能影响归属判断。设备标识为本次安装持久保存的随机
              UUID，升级不变。
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}
