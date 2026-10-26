import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { useQueryClient } from "@tanstack/react-query";
import { api, getApiErrorMessage, type MeterDiagnostics } from "@/lib/api";
import { Button } from "@/components/ui/button";

export function MeterMaintenance() {
  const client = useQueryClient();
  const [result, setResult] = useState<MeterDiagnostics>();
  const [busy, setBusy] = useState<"check" | "repair">();
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState("");
  const [repaired, setRepaired] = useState(false);
  async function run(operation: "check" | "repair") {
    setBusy(operation);
    setError("");
    setRepaired(false);
    try {
      setResult(
        await (operation === "check"
          ? api.meterServiceDiagnose()
          : api.meterServiceRepair()),
      );
      setRepaired(operation === "repair");
    } catch (failure) {
      setError(
        typeof failure === "string" && failure.length <= 512
          ? failure
          : getApiErrorMessage(failure),
      );
    } finally {
      setBusy(undefined);
      if (operation === "repair") {
        await Promise.all([
          client.invalidateQueries({ queryKey: ["usage-status"] }),
          client.invalidateQueries({ queryKey: ["meter-service-version"] }),
          client.invalidateQueries({ queryKey: ["meter-runtime-logs"] }),
        ]);
      }
    }
  }
  const canRepair =
    result?.supported && result.checks.some((item) => item.repairable);
  return (
    <div className="mt-4 border-t pt-3 text-xs">
      <h4 className="font-medium">统计程序自检与修复</h4>
      <p className="mt-1 text-muted-foreground">
        检查服务、开机自启、故障恢复、版本和扫描心跳。修复需要 Windows
        管理员授权，保留设备配对、用量队列与原始记录时间。
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={!!busy}
          onClick={() => void run("check")}
        >
          {busy === "check" ? "正在自检…" : "开始自检"}
        </Button>
        <Button
          size="sm"
          disabled={!!busy || !canRepair}
          onClick={() => setConfirm(true)}
        >
          {busy === "repair" ? "正在修复，请完成系统授权…" : "修复统计程序"}
        </Button>
      </div>
      {error ? (
        <p
          role="alert"
          className="mt-2 text-destructive [overflow-wrap:anywhere]"
        >
          {error}
        </p>
      ) : null}
      {repaired ? (
        <p role="status" className="mt-2">
          修复执行完成，以下为重新自检结果；仍有提示的项目请按说明处理。
        </p>
      ) : null}
      {result ? (
        <div className="mt-3" aria-label="统计程序自检结果">
          <p className="text-muted-foreground">
            自检时间：
            {new Date(result.checkedAtEpochSeconds * 1000).toLocaleString()}
          </p>
          {!result.supported ? (
            <p className="mt-2">自修复目前仅支持 Windows。</p>
          ) : (
            <ul className="mt-2 space-y-3">
              {result.checks.map((item) => (
                <li key={item.code}>
                  <p
                    className={
                      item.state === "PASS"
                        ? "font-medium"
                        : "font-medium text-amber-700 dark:text-amber-400"
                    }
                  >
                    {item.label} ·{" "}
                    {item.state === "PASS"
                      ? "正常"
                      : item.repairable
                        ? "可修复"
                        : "需处理"}
                  </p>
                  <p className="mt-1 text-muted-foreground [overflow-wrap:anywhere]">
                    {item.detail}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
      <Dialog.Root open={confirm} onOpenChange={setConfirm}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border bg-background p-5 shadow-lg">
            <Dialog.Title className="text-base font-medium">
              修复统计程序
            </Dialog.Title>
            <Dialog.Description className="mt-3 text-sm text-muted-foreground">
              将使用当前安装包恢复统计程序文件、自动启动和 Windows
              故障恢复策略，并重新启动统计服务。系统会请求管理员授权；不会删除配对、队列或修改用量时间。
            </Dialog.Description>
            <div className="mt-4 flex justify-end gap-2">
              <Dialog.Close asChild>
                <Button variant="outline">取消</Button>
              </Dialog.Close>
              <Button
                onClick={() => {
                  setConfirm(false);
                  void run("repair");
                }}
              >
                确认修复
              </Button>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
