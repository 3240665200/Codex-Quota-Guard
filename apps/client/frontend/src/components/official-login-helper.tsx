import { useEffect, useRef, useState } from "react";
import {
  api,
  getApiErrorMessage,
  type OfficialLoginHelperJob,
  type OfficialLoginHelperStatus,
} from "@/lib/api";
import { Button } from "@/components/ui/button";

function failureMessage(code: string | null) {
  if (code?.includes("PROXY"))
    return "该账户的固定代理暂不可用，请检查后台代理配置后重试。";
  if (code?.includes("BROWSER"))
    return "无法打开所选浏览器，请确认已安装 Microsoft Edge 或 Google Chrome。";
  if (code?.includes("EXPIRED") || code?.includes("TIMEOUT"))
    return "授权任务已超时，请在管理后台重新发起连接。";
  if (code?.includes("CHALLENGE") || code?.includes("LOGIN_REQUIRED"))
    return "官方账户仍需验证，请完成浏览器中的验证后重试。";
  return "官方登录未能保存，请检查管理后台任务状态后重试。";
}
const activeStates = new Set(["STARTING", "WAITING_FOR_LOGIN", "SAVING"]);
const stateLabels: Record<OfficialLoginHelperStatus["state"], string> = {
  IDLE: "选择一个待授权任务开始。",
  STARTING: "正在打开独立浏览器窗口…",
  WAITING_FOR_LOGIN:
    "请在浏览器中完成登录与验证码；如登录后仍等待，请在官方页面进入设置 → 活动会话。",
  SAVING: "正在将授权结果交给后台验证…",
  COMPLETED: "官方账户连接已保存。",
  FAILED: "官方登录未完成。",
  CANCELLED: "本次官方登录已取消。",
};
export function OfficialLoginHelper({ userId }: { userId: string }) {
  const [jobs, setJobs] = useState<OfficialLoginHelperJob[]>([]);
  const [status, setStatus] = useState<OfficialLoginHelperStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const lifetimeRef = useRef<{
    active: boolean;
    busy: boolean;
    generation: number;
  } | null>(null);
  useEffect(() => {
    const lifetime = { active: true, busy: false, generation: 0 };
    lifetimeRef.current = lifetime;
    setJobs([]);
    setStatus(null);
    setPending(false);
    setError(null);
    let timer: number | undefined;
    const poll = async () => {
      if (!lifetime.active) return;
      const generation = lifetime.generation;
      try {
        const [nextJobs, nextStatus] = await Promise.all([
          api.officialLoginHelperJobs(),
          api.officialLoginHelperStatus(),
        ]);
        if (
          lifetime.active &&
          generation === lifetime.generation &&
          !lifetime.busy
        ) {
          setJobs(nextJobs);
          setStatus(nextStatus);
          setError(null);
        }
      } catch (failure) {
        if (lifetime.active && generation === lifetime.generation) {
          setError(getApiErrorMessage(failure));
          setJobs([]);
          setStatus(null);
        }
      }
      if (lifetime.active) timer = window.setTimeout(() => void poll(), 3_000);
    };
    void poll();
    return () => {
      lifetime.active = false;
      lifetime.generation += 1;
      window.clearTimeout(timer);
    };
  }, [userId, refresh]);

  const run = async (
    job?: OfficialLoginHelperJob,
    browser: "EDGE" | "CHROME" = "EDGE",
  ) => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy) return;
    lifetime.busy = true;
    const generation = ++lifetime.generation;
    setPending(true);
    setError(null);
    try {
      const next = job
        ? await api.officialLoginHelperStart(job.id, browser)
        : await api.officialLoginHelperCancel();
      if (lifetime.active && generation === lifetime.generation)
        setStatus(next);
    } catch (failure) {
      if (lifetime.active && generation === lifetime.generation)
        setError(getApiErrorMessage(failure));
    } finally {
      if (lifetime.active && generation === lifetime.generation) {
        lifetime.busy = false;
        setPending(false);
      }
    }
  };
  const busy = pending || (!!status && activeStates.has(status.state));
  return (
    <section className="cqg-surface p-5 sm:p-6" aria-label="官方登录助手">
      <h2 className="text-base font-semibold">官方登录助手</h2>
      <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
        仅管理员可用。选择管理后台发起的待授权任务后，会打开独立浏览器窗口，请你自己完成官方登录与验证码。
      </p>
      <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
        浏览器登录使用本机系统网络及系统代理；后台保存和读取官方账户时继续使用该任务指定的服务器代理。代理故障会保留错误供处理。
      </p>
      {error ? (
        <p role="alert" className="mt-4 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <p role="status" className="mt-4 text-sm">
        {status?.state === "FAILED"
          ? failureMessage(status.errorCode)
          : status
            ? stateLabels[status.state]
            : "正在读取待授权任务…"}
      </p>
      {status?.accountName && busy ? (
        <p className="mt-2 text-sm">当前账户：{status.accountName}</p>
      ) : null}
      <div className="mt-4 space-y-3">
        {jobs.map((job) => (
          <div
            key={job.id}
            className="flex flex-wrap items-center gap-3 rounded-md border p-4"
          >
            <span className="min-w-0 flex-1 break-words text-sm font-medium">
              {job.accountName}
            </span>
            <Button
              disabled={busy || Date.parse(job.expiresAt) <= Date.now()}
              onClick={() => void run(job, "EDGE")}
            >
              使用 Edge 登录
            </Button>
            <Button
              variant="outline"
              disabled={busy || Date.parse(job.expiresAt) <= Date.now()}
              onClick={() => void run(job, "CHROME")}
            >
              使用 Chrome 登录
            </Button>
          </div>
        ))}
      </div>
      {status && !jobs.length && !busy ? (
        <p className="mt-4 text-sm text-muted-foreground">
          当前没有待授权任务，请先在管理后台的官方账户中发起连接。
        </p>
      ) : null}
      <div className="mt-4 flex gap-3">
        {status && activeStates.has(status.state) ? (
          <Button
            variant="outline"
            disabled={pending}
            onClick={() => void run()}
          >
            取消官方登录
          </Button>
        ) : null}
        <Button
          variant="outline"
          disabled={pending}
          onClick={() => setRefresh((value) => value + 1)}
        >
          刷新待授权任务
        </Button>
      </div>
    </section>
  );
}
