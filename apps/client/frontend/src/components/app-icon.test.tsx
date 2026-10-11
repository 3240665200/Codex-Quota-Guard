import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppIcon } from "@/components/app-icon";

describe("AppIcon", () => {
  it("uses the application artwork with a fixed aspect ratio", () => {
    render(<AppIcon className="size-8" />);
    const icon = screen.getByRole("img", { name: "Codex Quota Guard 图标" });
    expect(icon.getAttribute("src")).toContain("app-icon.svg");
    expect(icon).toHaveAttribute("width", "36");
    expect(icon).toHaveAttribute("height", "36");
    expect(icon).toHaveAttribute("draggable", "false");
    expect(icon).toHaveClass("size-8", "object-contain");
  });
});
