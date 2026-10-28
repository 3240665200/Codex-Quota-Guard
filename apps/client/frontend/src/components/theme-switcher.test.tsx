import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ThemeSwitcher } from "@/components/theme-switcher";
import { ClientThemeProvider } from "@/lib/client-theme";

const THEME_STORAGE_KEY = "cqg-client-theme";
const originalMatchMedia = window.matchMedia;

afterEach(() => {
  if (originalMatchMedia) {
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: originalMatchMedia,
    });
  } else {
    Reflect.deleteProperty(window, "matchMedia");
  }
});

describe("ThemeSwitcher", () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove("dark");
    delete document.documentElement.dataset.theme;
    document.documentElement.style.colorScheme = "";
  });

  it("works without a provider and persists the selected theme", () => {
    render(<ThemeSwitcher compact />);

    expect(screen.getByRole("group", { name: "主题模式" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "深色模式" }));

    expect(document.documentElement).toHaveClass("dark");
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
  });

  it("applies a saved theme and updates it through the provider", async () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");

    render(
      <ClientThemeProvider>
        <ThemeSwitcher compact />
      </ClientThemeProvider>,
    );

    await waitFor(() => expect(document.documentElement).toHaveClass("dark"));
    fireEvent.click(screen.getByRole("button", { name: "浅色模式" }));

    await waitFor(() =>
      expect(document.documentElement).not.toHaveClass("dark"),
    );
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
  });

  it("follows system changes and removes its media listener on unmount", async () => {
    let listener: ((event: MediaQueryListEvent) => void) | undefined;
    const removeEventListener = vi.fn();
    const mediaQuery = {
      matches: false,
      addEventListener: (_type: string, callback: EventListener) => {
        listener = callback as unknown as (event: MediaQueryListEvent) => void;
      },
      removeEventListener,
    } as unknown as MediaQueryList;
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: vi.fn(() => mediaQuery),
    });

    const { unmount } = render(
      <ClientThemeProvider>
        <ThemeSwitcher compact />
      </ClientThemeProvider>,
    );

    await waitFor(() => expect(listener).toBeDefined());
    await act(async () => {
      listener?.({ matches: true } as MediaQueryListEvent);
    });
    await waitFor(() => expect(document.documentElement).toHaveClass("dark"));

    unmount();
    expect(removeEventListener).toHaveBeenCalledWith("change", listener);
  });

  it("syncs theme changes from another window without waiting for quota data", async () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "light");
    render(
      <ClientThemeProvider>
        <ThemeSwitcher compact />
      </ClientThemeProvider>,
    );

    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
    fireEvent(
      window,
      new StorageEvent("storage", { key: THEME_STORAGE_KEY, newValue: "dark" }),
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "深色模式" })).toHaveAttribute(
        "aria-pressed",
        "true",
      ),
    );
    expect(document.documentElement).toHaveClass("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(document.documentElement.style.colorScheme).toBe("dark");

    window.localStorage.setItem(THEME_STORAGE_KEY, "light");
    fireEvent(
      window,
      new StorageEvent("storage", {
        key: "unrelated-setting",
        newValue: "light",
      }),
    );
    expect(document.documentElement).toHaveClass("dark");
    fireEvent(
      window,
      new StorageEvent("storage", {
        key: THEME_STORAGE_KEY,
        newValue: "light",
      }),
    );
    await waitFor(() =>
      expect(document.documentElement).not.toHaveClass("dark"),
    );
  });

  it("returns to the system theme when another window clears preferences", async () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: vi.fn(() => ({
        matches: false,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    });
    render(
      <ClientThemeProvider>
        <ThemeSwitcher compact />
      </ClientThemeProvider>,
    );
    await waitFor(() => expect(document.documentElement).toHaveClass("dark"));
    window.localStorage.clear();
    fireEvent(window, new StorageEvent("storage", { key: null }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "跟随系统模式" }),
      ).toHaveAttribute("aria-pressed", "true"),
    );
    expect(document.documentElement).not.toHaveClass("dark");
  });
});
