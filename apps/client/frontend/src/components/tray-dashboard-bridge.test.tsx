import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { useTrayDashboard } from "./tray-dashboard-bridge";
import {
  listenTrayRequests,
  publishTraySnapshot,
  type TraySnapshot,
} from "@/lib/tray-panel";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn().mockResolvedValue(null),
}));
vi.mock("@/lib/tray-panel", () => ({
  isDesktopShell: () => true,
  listenTrayRequests: vi.fn().mockResolvedValue(() => {}),
  publishTraySnapshot: vi.fn().mockResolvedValue(undefined),
}));
const snapshot = { userName: "Member" } as TraySnapshot;
function Bridge({ request }: { request: (action: string) => void }) {
  useTrayDashboard(snapshot, request);
  return (
    <>
      <div>用量面板</div>
      <input aria-label="输入区" />
      <div contentEditable suppressContentEditableWarning aria-label="编辑区">
        编辑内容
      </div>
    </>
  );
}
beforeEach(() => {
  vi.clearAllMocks();
});
afterEach(() => {
  window.getSelection()?.removeAllRanges();
});

describe("main renderer tray bridge", () => {
  it("updates the callback without keeping a previous account's handler", () => {
    const old = vi.fn(),
      current = vi.fn();
    const view = render(<Bridge request={old} />);
    view.rerender(<Bridge request={current} />);
    vi.mocked(listenTrayRequests).mock.calls[0][0]("ledger");
    expect(current).toHaveBeenCalledWith("ledger");
    expect(old).not.toHaveBeenCalled();
  });
  it("opens the quota menu on blank areas and keeps native editing context menus", () => {
    render(<Bridge request={vi.fn()} />);
    fireEvent.contextMenu(screen.getByText("用量面板"));
    expect(invoke).toHaveBeenCalledWith("tray_panel_show");
    vi.mocked(invoke).mockClear();
    fireEvent.contextMenu(screen.getByLabelText("输入区"));
    fireEvent.contextMenu(screen.getByLabelText("编辑区"));
    expect(invoke).not.toHaveBeenCalled();
  });
  it("keeps the normal copy menu when text is selected", () => {
    render(<Bridge request={vi.fn()} />);
    const range = document.createRange();
    range.selectNodeContents(screen.getByText("用量面板"));
    window.getSelection()!.addRange(range);
    fireEvent.contextMenu(screen.getByText("用量面板"));
    expect(invoke).not.toHaveBeenCalled();
  });
  it("clears cached quota information when the dashboard is unmounted", async () => {
    const view = render(<Bridge request={vi.fn()} />);
    expect(publishTraySnapshot).toHaveBeenCalledWith(snapshot);
    view.unmount();
    await waitFor(() =>
      expect(publishTraySnapshot).toHaveBeenLastCalledWith(null),
    );
  });
});
