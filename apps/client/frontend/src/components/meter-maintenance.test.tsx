import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { MeterMaintenance } from "./meter-maintenance";

const result = {
  supported: true,
  checkedAtEpochSeconds: 1793000000,
  checks: [
    {
      code: "SERVICE",
      label: "Windows 统计服务",
      state: "WARNING" as const,
      detail: "服务已停止",
      repairable: true,
    },
  ],
};
beforeEach(() => vi.restoreAllMocks());
function view() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MeterMaintenance />
    </QueryClientProvider>,
  );
}
it("only diagnoses and repairs after separate user actions, then shows fresh results", async () => {
  const user = userEvent.setup();
  const diagnose = vi
    .spyOn(api, "meterServiceDiagnose")
    .mockResolvedValue(result);
  const repair = vi
    .spyOn(api, "meterServiceRepair")
    .mockResolvedValue({
      ...result,
      checks: [
        {
          ...result.checks[0],
          state: "PASS",
          repairable: false,
          detail: "正在后台运行",
        },
      ],
    });
  view();
  expect(diagnose).not.toHaveBeenCalled();
  expect(repair).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "修复统计程序" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "开始自检" }));
  expect(await screen.findByText("服务已停止")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "修复统计程序" }));
  expect(repair).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "确认修复" }));
  await waitFor(() => expect(repair).toHaveBeenCalledTimes(1));
  expect(await screen.findByText("正在后台运行")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "修复统计程序" })).toBeDisabled();
});
it("keeps authorization failures visible and permits retry without clearing data", async () => {
  const user = userEvent.setup();
  vi.spyOn(api, "meterServiceDiagnose").mockResolvedValue(result);
  vi.spyOn(api, "meterServiceRepair").mockRejectedValue(
    "已取消管理员授权，统计程序未更改。",
  );
  view();
  await user.click(screen.getByRole("button", { name: "开始自检" }));
  await screen.findByText("服务已停止");
  await user.click(screen.getByRole("button", { name: "修复统计程序" }));
  await user.click(screen.getByRole("button", { name: "确认修复" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "已取消管理员授权",
  );
  expect(screen.getByRole("button", { name: "修复统计程序" })).toBeEnabled();
});
